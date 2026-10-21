package ir.solina.fm.dualpane

import android.os.Environment
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.documentfile.provider.DocumentFile
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.view.DragEvent
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ir.solina.fm.FileOperationsManager
import ir.solina.fm.nativecore.RustFileEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedOutputStream

/**
 * Persistable SAF tree paths encoded as: saf|<tree-uri>|<encoded/relative/path>.
 * This keeps tree permissions attached to every item path without treating content URIs as File paths.
 */
private object SafDocumentAccess {
    fun isSafPath(path: String): Boolean = path.startsWith("saf|")

    fun rootPath(treeUri: Uri): String = "saf|$treeUri|"

    private fun parts(path: String): List<String> {
        val values = path.split("|", limit = 3)
        require(values.size == 3 && values[0] == "saf") { "مسیر SAF نامعتبر است" }
        return values
    }

    fun childPath(parentPath: String, name: String): String {
        val p = parts(parentPath)
        val suffix = p[2].trimEnd('/')
        val child = Uri.encode(name)
        return "saf|" + p[1] + "|" + (if (suffix.isEmpty()) child else "$suffix/$child")
    }

    fun parentPath(path: String): String? {
        val p = parts(path)
        if (p[2].isEmpty()) return null
        val parent = p[2].substringBeforeLast('/', missingDelimiterValue = "")
        return "saf|" + p[1] + "|" + parent
    }

    fun resolve(context: Context, path: String): DocumentFile? {
        val p = parts(path)
        var document = DocumentFile.fromTreeUri(context, Uri.parse(p[1])) ?: return null
        if (p[2].isNotEmpty()) {
            for (encodedName in p[2].split('/')) {
                val name = Uri.decode(encodedName)
                document = document.findFile(name) ?: return null
            }
        }
        return document
    }
}
/** Independent identity for the two explorer panes. */
enum class Pane { LEFT, RIGHT }

/** Controls when the second pane is displayed. */
enum class DualPaneMode { ALWAYS, NEVER, LANDSCAPE_ONLY }

enum class ExplorerSortMode { NAME, SIZE, MODIFIED }

data class ExplorerFileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long
)

data class PaneState(
    val currentPath: String = Environment.getExternalStorageDirectory().absolutePath,
    val items: List<ExplorerFileItem> = emptyList(),
    val selectedItems: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val history: List<String> = emptyList(),
    val errorMessage: String? = null,
    val searchQuery: String = "",
    val sortMode: ExplorerSortMode = ExplorerSortMode.NAME
)

data class ExplorerState(
    val activePane: Pane = Pane.LEFT,
    val dualPaneMode: DualPaneMode = DualPaneMode.LANDSCAPE_ONLY,
    val leftPane: PaneState = PaneState(),
    val rightPane: PaneState = PaneState(),
    val showSelectionMenu: Boolean = false,
    val bookmarkedPaths: Set<String> = emptySet()
) {
    fun getPaneState(pane: Pane): PaneState =
        if (pane == Pane.LEFT) leftPane else rightPane

    fun updatePane(pane: Pane, transform: (PaneState) -> PaneState): ExplorerState =
        if (pane == Pane.LEFT) copy(leftPane = transform(leftPane))
        else copy(rightPane = transform(rightPane))
}

/**
 * State holder for the Strata-style dual-pane explorer.
 * Each pane owns its path, selection, loading state and back history.
 */
class ExplorerViewModel(application: Application) : AndroidViewModel(application) {
    private val initialPath = Environment.getExternalStorageDirectory().absolutePath
    private val _state = MutableStateFlow(
        ExplorerState(
            leftPane = PaneState(currentPath = initialPath),
            rightPane = PaneState(currentPath = initialPath)
        )
    )
    val state: StateFlow<ExplorerState> = _state.asStateFlow()

    init {
        refresh(Pane.LEFT)
        refresh(Pane.RIGHT)
    }

    fun setSearchQuery(pane: Pane, query: String) {
        _state.update { current -> current.updatePane(pane) { it.copy(searchQuery = query) } }
    }

    fun cycleSortMode(pane: Pane) {
        _state.update { current -> current.updatePane(pane) { paneState ->
            val next = when (paneState.sortMode) {
                ExplorerSortMode.NAME -> ExplorerSortMode.SIZE
                ExplorerSortMode.SIZE -> ExplorerSortMode.MODIFIED
                ExplorerSortMode.MODIFIED -> ExplorerSortMode.NAME
            }
            paneState.copy(sortMode = next)
        } }
    }

    fun setActivePane(pane: Pane) {
        _state.update { it.copy(activePane = pane) }
    }

    fun setDualPaneMode(mode: DualPaneMode) {
        _state.update { it.copy(dualPaneMode = mode) }
    }

    fun setSelectionMenuVisible(visible: Boolean) {
        _state.update { it.copy(showSelectionMenu = visible) }
    }

    fun toggleBookmark(path: String) {
        _state.update { current ->
            val next = current.bookmarkedPaths.toMutableSet()
            if (!next.add(path)) next.remove(path)
            current.copy(bookmarkedPaths = next)
        }
    }

    fun setBookmarkedPaths(paths: Set<String>) {
        _state.update { it.copy(bookmarkedPaths = paths.toSet()) }
    }

    fun renameSelected(newName: String, onFinished: (String?) -> Unit = {}) {
        val snapshot = _state.value
        val pane = snapshot.activePane
        val selected = snapshot.getPaneState(pane).selectedItems.singleOrNull()
        if (selected == null || newName.isBlank() || newName == "." || newName == ".." || newName.contains(File.separator)) {
            onFinished("برای تغییر نام، یک مورد و نام معتبر انتخاب کنید")
            return
        }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val source = File(selected)
                    require(source.exists()) { "فایل یا پوشه پیدا نشد" }
                    val target = File(source.parentFile ?: error("مسیر والد پیدا نشد"), newName.trim())
                    require(!target.exists()) { "فایلی با این نام از قبل وجود دارد" }
                    val native = RustFileEngine.renamePath(source.absolutePath, target.absolutePath)
                    val success = if (native != null) native.success else source.renameTo(target)
                    if (!success) native?.error ?: "تغییر نام انجام نشد" else null
                }.getOrElse { it.localizedMessage ?: "تغییر نام انجام نشد" }
            }
            if (error == null) {
                clearSelection(pane)
                refresh(pane)
            }
            onFinished(error)
        }
    }

    fun compressSelected(onFinished: (String?) -> Unit = {}) {
        val snapshot = _state.value
        val pane = snapshot.activePane
        val selected = snapshot.getPaneState(pane).items.filter { it.path in snapshot.getPaneState(pane).selectedItems }.map { File(it.path) }
        if (selected.isEmpty()) {
            onFinished("ابتدا فایل یا پوشه‌ای انتخاب کنید")
            return
        }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    selected.forEach { source ->
                        require(source.exists()) { "فایل پیدا نشد: ${source.name}" }
                        val output = File(source.parentFile, "${source.name}.zip")
                        require(!output.exists()) { "فایل فشرده از قبل وجود دارد: ${output.name}" }
                        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
                            fun addFile(file: File, entryName: String) {
                                if (file.isDirectory) {
                                    val children = file.listFiles() ?: emptyArray()
                                    if (children.isEmpty()) zip.putNextEntry(ZipEntry("$entryName/")).also { zip.closeEntry() }
                                    children.forEach { child ->
                                        addFile(child, if (entryName.isEmpty()) child.name else "$entryName/${child.name}")
                                    }
                                } else {
                                    zip.putNextEntry(ZipEntry(entryName))
                                    FileInputStream(file).use { input -> input.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                            addFile(source, source.name)
                        }
                    }
                }.exceptionOrNull()?.localizedMessage
            }
            refresh(pane)
            onFinished(error)
        }
    }

    fun deleteSelected(onFinished: (FileOperationsManager.OperationResult) -> Unit = {}) {
        val snapshot = _state.value
        val pane = snapshot.activePane
        val selected = snapshot.getPaneState(pane).selectedItems.toList()
        if (selected.isEmpty()) {
            _state.update { it.updatePane(pane) { p -> p.copy(errorMessage = "ابتدا فایل یا پوشه‌ای انتخاب کنید") } }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var successes = 0
                var failures = 0
                val errors = mutableListOf<String>()
                selected.forEach { path ->
                    val file = File(path)
                    val native = RustFileEngine.deletePath(path)
                    val ok = if (native != null) native.success else runCatching { file.deleteRecursively() }.getOrDefault(false)
                    if (ok) successes++ else {
                        failures++
                        errors += "${file.name}: حذف انجام نشد"
                    }
                }
                FileOperationsManager.OperationResult(successes, failures, errors)
            }
            _state.update { current ->
                current.updatePane(pane) { p -> p.copy(selectedItems = emptySet(), errorMessage = result.errors.firstOrNull()) }
                    .copy(showSelectionMenu = false)
            }
            refresh(pane)
            onFinished(result)
        }
    }

    fun navigateToSafRoot(pane: Pane, treeUri: Uri) {
        val path = SafDocumentAccess.rootPath(treeUri)
        _state.update { current -> current.updatePane(pane) { p ->
            p.copy(currentPath = path, history = p.history + p.currentPath, selectedItems = emptySet(), errorMessage = null)
        } }
        refresh(pane)
    }

    fun refresh(pane: Pane) {
        val path = _state.value.getPaneState(pane).currentPath
        _state.update { it.updatePane(pane) { it.copy(isLoading = true, errorMessage = null) } }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (SafDocumentAccess.isSafPath(path)) {
                        val directory = SafDocumentAccess.resolve(getApplication(), path)
                            ?: error("دسترسی به پوشهٔ حافظهٔ جانبی منقضی شده است؛ دوباره آن را انتخاب کنید")
                        require(directory.isDirectory) { "مسیر انتخاب‌شده پوشه نیست" }
                        directory.listFiles().map { child ->
                            ExplorerFileItem(
                                path = SafDocumentAccess.childPath(path, child.name ?: ""),
                                name = child.name ?: "بدون نام",
                                isDirectory = child.isDirectory,
                                sizeBytes = child.length(),
                                modifiedAt = child.lastModified()
                            )
                        }.sortedWith(compareBy<ExplorerFileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    } else {
                    val directory = File(path)
                    require(directory.isDirectory && directory.canRead()) {
                        "پوشه قابل خواندن نیست: $path"
                    }
                    val nativeListing = RustFileEngine.listDirectory(path)
                    if (nativeListing != null) {
                        nativeListing.map { entry ->
                            ExplorerFileItem(
                                path = entry.path,
                                name = entry.name,
                                isDirectory = entry.isDirectory,
                                sizeBytes = entry.sizeBytes,
                                modifiedAt = entry.modifiedAt
                            )
                        }
                    } else {
                        directory.listFiles().orEmpty()
                            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                            .map { ExplorerFileItem(it.absolutePath, it.name, it.isDirectory, it.length(), it.lastModified()) }
                    }
                    }
                }
            }
            _state.update { current ->
                current.updatePane(pane) {
                    it.copy(
                        items = result.getOrDefault(emptyList()),
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.localizedMessage
                    )
                }
            }
        }
    }

    fun navigateTo(pane: Pane, path: String) {
        if (SafDocumentAccess.isSafPath(path)) {
            val target = SafDocumentAccess.resolve(getApplication(), path)
            if (target == null || !target.isDirectory) {
                _state.update { it.updatePane(pane) { p -> p.copy(errorMessage = "دسترسی به پوشهٔ حافظهٔ جانبی ممکن نیست") } }
                return
            }
            _state.update { current -> current.updatePane(pane) { p ->
                p.copy(currentPath = path, history = p.history + p.currentPath, selectedItems = emptySet(), errorMessage = null)
            } }
            refresh(pane)
            return
        }
        val target = File(path)
        if (!target.isDirectory || !target.canRead()) {
            _state.update { it.updatePane(pane) { p -> p.copy(errorMessage = "دسترسی به پوشه ممکن نیست") } }
            return
        }
        _state.update { current ->
            current.updatePane(pane) { p ->
                p.copy(
                    currentPath = target.absolutePath,
                    history = p.history + p.currentPath,
                    selectedItems = emptySet(),
                    errorMessage = null
                )
            }
        }
        refresh(pane)
    }

    fun navigateUp(pane: Pane) {
        val current = _state.value.getPaneState(pane)
        if (current.history.isNotEmpty()) {
            val previous = current.history.last()
            _state.update { s ->
                s.updatePane(pane) { p ->
                    p.copy(currentPath = previous, history = p.history.dropLast(1), selectedItems = emptySet(), errorMessage = null)
                }
            }
            refresh(pane)
        } else if (SafDocumentAccess.isSafPath(current.currentPath)) {
            val parent = SafDocumentAccess.parentPath(current.currentPath) ?: return
            navigateTo(pane, parent)
        } else {
            val parent = File(current.currentPath).parentFile ?: return
            navigateTo(pane, parent.absolutePath)
        }
    }

    fun openItem(pane: Pane, item: ExplorerFileItem) {
        if (item.isDirectory) navigateTo(pane, item.path)
    }

    fun toggleSelection(pane: Pane, item: ExplorerFileItem) {
        _state.update { current ->
            current.updatePane(pane) { p ->
                val next = p.selectedItems.toMutableSet()
                if (!next.add(item.path)) next.remove(item.path)
                p.copy(selectedItems = next)
            }
        }
    }

    fun clearSelection(pane: Pane) {
        _state.update { it.updatePane(pane) { p -> p.copy(selectedItems = emptySet()) } }
    }

    fun swapPanes() {
        _state.update { current ->
            current.copy(
                leftPane = current.rightPane,
                rightPane = current.leftPane,
                activePane = if (current.activePane == Pane.LEFT) Pane.RIGHT else Pane.LEFT
            )
        }
    }

    fun dropItemOnPane(path: String, destinationPane: Pane, move: Boolean = false) {
        val snapshot = _state.value
        val source = File(path)
        val destinationState = snapshot.getPaneState(destinationPane)
        val destination = File(destinationState.currentPath)
        if (!source.exists() || destinationState.isLoading || !destination.isDirectory || !destination.canWrite()) {
            _state.update { it.updatePane(destinationPane) { p -> p.copy(errorMessage = "مبدأ یا مقصد عملیات در دسترس نیست") } }
            return
        }
        if (source.canonicalPath == destination.canonicalPath ||
            (source.isDirectory && destination.canonicalPath.startsWith(source.canonicalPath + File.separator))) {
            _state.update { it.updatePane(destinationPane) { p -> p.copy(errorMessage = "این پوشه مقصد مجاز برای خودش نیست") } }
            return
        }
        val sourcePane = Pane.entries.firstOrNull { pane ->
            snapshot.getPaneState(pane).items.any { it.path == path }
        }
        viewModelScope.launch {
            val result = FileOperationsManager.executeTransfer(
                sources = listOf(source),
                destinationDir = destination,
                isMove = move,
                onProgress = {}
            )
            _state.update { current ->
                current.updatePane(destinationPane) { p ->
                    p.copy(errorMessage = result.errors.firstOrNull())
                }
            }
            if (result.failureCount == 0 && sourcePane != null) clearSelection(sourcePane)
            refresh(destinationPane)
            if (move && result.failureCount == 0 && sourcePane != null) refresh(sourcePane)
        }
    }

    fun copyToOtherPane(move: Boolean, onFinished: (FileOperationsManager.OperationResult) -> Unit = {}) {
        val snapshot = _state.value
        val sourcePane = snapshot.activePane
        val destinationPane = if (sourcePane == Pane.LEFT) Pane.RIGHT else Pane.LEFT
        val sourceState = snapshot.getPaneState(sourcePane)
        val destinationState = snapshot.getPaneState(destinationPane)
        val selectedPaths = sourceState.items.asSequence()
            .filter { it.path in sourceState.selectedItems }
            .map { File(it.path) }
            .toList()
        if (selectedPaths.isEmpty() || sourceState.isLoading || destinationState.isLoading) {
            _state.update { it.updatePane(sourcePane) { p -> p.copy(errorMessage = "ابتدا فایل یا پوشه‌ای انتخاب کنید و صبر کنید بارگذاری تمام شود") } }
            return
        }
        val destination = File(destinationState.currentPath)
        if (!destination.isDirectory || !destination.canWrite()) {
            _state.update { it.updatePane(destinationPane) { p -> p.copy(errorMessage = "پوشه مقصد قابل نوشتن نیست") } }
            return
        }
        viewModelScope.launch {
            val result = FileOperationsManager.executeTransfer(
                sources = selectedPaths,
                destinationDir = destination,
                isMove = move,
                onProgress = {}
            )
            if (result.failureCount == 0) clearSelection(sourcePane)
            refresh(destinationPane)
            if (move && result.failureCount == 0) refresh(sourcePane)
            onFinished(result)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen(
    viewModel: ExplorerViewModel,
    modifier: Modifier = Modifier,
    onDualPaneModeChanged: (DualPaneMode) -> Unit = {},
    onBookmarksChanged: (Set<String>) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.bookmarkedPaths) {
        onBookmarksChanged(state.bookmarkedPaths)
    }
    val context = LocalContext.current
    var safPickerPane by remember { mutableStateOf(Pane.LEFT) }
    val safTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            viewModel.navigateToSafRoot(safPickerPane, uri)
        }
    }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var propertiesTarget by remember { mutableStateOf<String?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showDual = when (state.dualPaneMode) {
        DualPaneMode.ALWAYS -> true
        DualPaneMode.NEVER -> false
        DualPaneMode.LANDSCAPE_ONLY -> isLandscape && configuration.screenWidthDp >= 600
    }
    val panes = if (showDual) listOf(Pane.LEFT, Pane.RIGHT) else listOf(state.activePane)
    // Keep list positions by pane and directory path, restoring them when returning to a folder.
    val savedScroll = remember { mutableStateMapOf<String, Pair<Int, Int>>() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            panes.forEach { pane ->
                val paneState = state.getPaneState(pane)
                val displayedItems = paneState.items
                    .filter { it.name.contains(paneState.searchQuery.trim(), ignoreCase = true) }
                    .let { items -> when (paneState.sortMode) {
                        ExplorerSortMode.NAME -> items.sortedWith(compareBy<ExplorerFileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
                        ExplorerSortMode.SIZE -> items.sortedWith(compareBy<ExplorerFileItem> { !it.isDirectory }.thenByDescending { it.sizeBytes }.thenBy { it.name.lowercase() })
                        ExplorerSortMode.MODIFIED -> items.sortedWith(compareBy<ExplorerFileItem> { !it.isDirectory }.thenByDescending { it.modifiedAt }.thenBy { it.name.lowercase() })
                    } }
                val scrollKey = "${pane.name}:${paneState.currentPath}"
                val savedPosition = savedScroll[scrollKey] ?: (0 to 0)
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = savedPosition.first,
                    initialFirstVisibleItemScrollOffset = savedPosition.second
                )
                DisposableEffect(scrollKey, listState) {
                    onDispose { savedScroll[scrollKey] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                }
                val paneDropTarget = remember(pane) {
                    object : DragAndDropTarget {
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            val clip = event.toAndroidDragEvent().clipData ?: return false
                            val path = clip.getItemAt(0).text?.toString() ?: return false
                            viewModel.setActivePane(pane)
                            viewModel.dropItemOnPane(path, pane, move = false)
                            return true
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { event ->
                                event.toAndroidDragEvent().action == DragEvent.ACTION_DRAG_STARTED &&
                                    event.toAndroidDragEvent().clipDescription?.hasMimeType("text/plain") == true
                            },
                            target = paneDropTarget
                        )
                        .weight(1f)
                        .fillMaxSize()
                        .background(if (state.activePane == pane) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .then(
                            if (state.activePane == pane) Modifier.border(
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            ) else Modifier
                        )
                        .combinedClickable(onClick = { viewModel.setActivePane(pane) }, onLongClick = { viewModel.setActivePane(pane) })
                        .padding(6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (pane == Pane.LEFT) "چپ" else "راست", style = MaterialTheme.typography.labelLarge)
                        Text(paneState.currentPath, modifier = Modifier.weight(1f), maxLines = 2)
                        Button(onClick = { viewModel.navigateUp(pane) }) { Text("بالا") }
                        Button(onClick = { safPickerPane = pane; safTreePicker.launch(null) }) { Text("SD/USB") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = paneState.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(pane, it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("جست‌وجو در پوشه") }
                        )
                        Button(onClick = { viewModel.cycleSortMode(pane) }) {
                            Text(when (paneState.sortMode) {
                                ExplorerSortMode.NAME -> "نام"
                                ExplorerSortMode.SIZE -> "حجم"
                                ExplorerSortMode.MODIFIED -> "تاریخ"
                            })
                        }
                    }
                    if (paneState.isLoading) Text("در حال بارگذاری…")
                    paneState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    AnimatedContent(
                        targetState = paneState.currentPath,
                        transitionSpec = {
                            slideInHorizontally { it / 3 } + fadeIn() togetherWith slideOutHorizontally { -it / 3 } + fadeOut()
                        },
                        label = "ExplorerFolderTransition"
                    ) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(displayedItems, key = { it.path }) { item ->
                                val selected = item.path in paneState.selectedItems
                                Surface(
                                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                        .dragAndDropSource { _ ->
                                            DragAndDropTransferData(
                                                clipData = ClipData.newPlainText("Solina file path", item.path)
                                            )
                                        }
                                        .combinedClickable(
                                        onClick = {
                                            viewModel.setActivePane(pane)
                                            if (paneState.selectedItems.isNotEmpty()) viewModel.toggleSelection(pane, item)
                                            else viewModel.openItem(pane, item)
                                        },
                                        onLongClick = {
                                            viewModel.setActivePane(pane)
                                            viewModel.toggleSelection(pane, item)
                                            viewModel.setSelectionMenuVisible(true)
                                        }
                                    )
                                ) {
                                    Text(
                                        text = (if (item.isDirectory) "📁 " else "📄 ") + item.name,
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { viewModel.setSelectionMenuVisible(true) }) { Text("منوی عملیات") }
            Button(onClick = { viewModel.swapPanes() }) { Text("جابه‌جایی پنل‌ها") }
            Button(onClick = { showBookmarksDialog = true }) { Text("نشانک‌ها (${state.bookmarkedPaths.size})") }
        }

        if (showBookmarksDialog) {
            AlertDialog(
                onDismissRequest = { showBookmarksDialog = false },
                title = { Text("نشانک‌ها") },
                text = {
                    if (state.bookmarkedPaths.isEmpty()) {
                        Text("هنوز مسیری به نشانک‌ها اضافه نشده است.")
                    } else {
                        Column {
                            state.bookmarkedPaths.sorted().forEach { path ->
                                TextButton(
                                    onClick = {
                                        showBookmarksDialog = false
                                        viewModel.navigateTo(state.activePane, path)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(File(path).name.ifBlank { path }, maxLines = 1)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBookmarksDialog = false }) { Text("بستن") }
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("نمایش پنل‌ها:", modifier = Modifier.padding(top = 10.dp))
            Button(onClick = { viewModel.setDualPaneMode(DualPaneMode.ALWAYS); onDualPaneModeChanged(DualPaneMode.ALWAYS) }) { Text(if (state.dualPaneMode == DualPaneMode.ALWAYS) "✓ همیشه" else "همیشه") }
            Button(onClick = { viewModel.setDualPaneMode(DualPaneMode.NEVER); onDualPaneModeChanged(DualPaneMode.NEVER) }) { Text(if (state.dualPaneMode == DualPaneMode.NEVER) "✓ هرگز" else "هرگز") }
            Button(onClick = { viewModel.setDualPaneMode(DualPaneMode.LANDSCAPE_ONLY); onDualPaneModeChanged(DualPaneMode.LANDSCAPE_ONLY) }) { Text(if (state.dualPaneMode == DualPaneMode.LANDSCAPE_ONLY) "✓ افقی" else "فقط افقی") }
        }

        if (state.showSelectionMenu) {
            val activeState = state.getPaneState(state.activePane)
            val selectedPaths = activeState.selectedItems.toList()
            val selectedCount = selectedPaths.size
            AlertDialog(
                onDismissRequest = { viewModel.setSelectionMenuVisible(false) },
                title = { Text("عملیات روی فایل‌ها") },
                text = {
                    Column {
                        Text(if (selectedCount == 0) "فایلی انتخاب نشده است." else "$selectedCount مورد انتخاب شده")
                        TextButton(enabled = selectedCount > 0, onClick = { viewModel.setSelectionMenuVisible(false); viewModel.copyToOtherPane(move = false) }) { Text("کپی به پنل دیگر") }
                        TextButton(enabled = selectedCount > 0, onClick = { viewModel.setSelectionMenuVisible(false); viewModel.copyToOtherPane(move = true) }) { Text("انتقال به پنل دیگر") }
                        TextButton(enabled = selectedCount > 0, onClick = { viewModel.setSelectionMenuVisible(false); viewModel.deleteSelected { result -> operationMessage = if (result.failureCount == 0) "حذف ${result.successCount} مورد انجام شد" else result.errors.joinToString("\n") } }) { Text("حذف") }
                        TextButton(enabled = selectedCount == 1, onClick = {
                            renameTarget = selectedPaths.firstOrNull()
                            renameText = selectedPaths.firstOrNull()?.let { File(it).name }.orEmpty()
                            viewModel.setSelectionMenuVisible(false)
                        }) { Text("تغییر نام") }
                        TextButton(enabled = selectedCount > 0, onClick = {
                            viewModel.setSelectionMenuVisible(false)
                            viewModel.compressSelected { error -> operationMessage = error ?: "فشرده‌سازی با موفقیت انجام شد" }
                        }) { Text("فشرده‌سازی ZIP") }
                        TextButton(enabled = selectedCount == 1, onClick = { propertiesTarget = selectedPaths.firstOrNull(); viewModel.setSelectionMenuVisible(false) }) { Text("مشخصات") }
                        TextButton(enabled = selectedCount == 1, onClick = {
                            selectedPaths.firstOrNull()?.let { path ->
                                viewModel.toggleBookmark(path)
                                operationMessage = if (path in state.bookmarkedPaths) "نشانک حذف شد" else "به نشانک‌ها اضافه شد"
                            }
                            viewModel.setSelectionMenuVisible(false)
                        }) { Text(if (selectedPaths.any { it in state.bookmarkedPaths }) "حذف از نشانک‌ها" else "افزودن نشانک") }
                        TextButton(enabled = selectedCount == 1, onClick = {
                            val file = selectedPaths.firstOrNull()?.let(::File)
                            if (file != null && file.exists()) runCatching {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "اشتراک‌گذاری فایل"))
                            }.onFailure { operationMessage = it.localizedMessage ?: "اشتراک‌گذاری ممکن نشد" }
                            viewModel.setSelectionMenuVisible(false)
                        }) { Text("اشتراک‌گذاری") }
                        TextButton(enabled = selectedCount == 1, onClick = {
                            val file = selectedPaths.firstOrNull()?.let(::File)
                            if (file != null && file.exists()) runCatching {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "باز کردن با"))
                            }.onFailure { operationMessage = it.localizedMessage ?: "برنامه‌ای برای باز کردن فایل پیدا نشد" }
                            viewModel.setSelectionMenuVisible(false)
                        }) { Text("باز کردن با…") }
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.setSelectionMenuVisible(false) }) { Text("بستن") }
                }
            )
        }

        renameTarget?.let { path ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("تغییر نام") },
                text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("نام جدید") }) },
                confirmButton = { TextButton(onClick = { viewModel.renameSelected(renameText) { error -> operationMessage = error ?: "تغییر نام انجام شد" }; renameTarget = null }) { Text("ذخیره") } },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("لغو") } }
            )
        }

        propertiesTarget?.let { path ->
            val file = File(path)
            AlertDialog(
                onDismissRequest = { propertiesTarget = null },
                title = { Text("مشخصات فایل") },
                text = {
                    Column {
                        Text("نام: ${file.name}")
                        Text("مسیر: ${file.absolutePath}")
                        Text("نوع: ${if (file.isDirectory) "پوشه" else "فایل"}")
                        Text("اندازه: ${if (file.isDirectory) "محاسبه نشده" else android.text.format.Formatter.formatShortFileSize(context, file.length())}")
                        Text("آخرین تغییر: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))}")
                    }
                },
                confirmButton = { TextButton(onClick = { propertiesTarget = null }) { Text("بستن") } }
            )
        }

        operationMessage?.let { message ->
            AlertDialog(onDismissRequest = { operationMessage = null }, title = { Text("نتیجه عملیات") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { operationMessage = null }) { Text("باشه") } })
        }
    }
}

package ir.solina.fm.dualpane

import android.os.Environment
import android.content.ClipData
import android.view.DragEvent
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.foundation.draganddrop.DragAndDropTarget
import androidx.compose.foundation.draganddrop.DragAndDropTransferData
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

/** Independent identity for the two explorer panes. */
enum class Pane { LEFT, RIGHT }

/** Controls when the second pane is displayed. */
enum class DualPaneMode { ALWAYS, NEVER, LANDSCAPE_ONLY }

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
    val errorMessage: String? = null
)

data class ExplorerState(
    val activePane: Pane = Pane.LEFT,
    val dualPaneMode: DualPaneMode = DualPaneMode.LANDSCAPE_ONLY,
    val leftPane: PaneState = PaneState(),
    val rightPane: PaneState = PaneState()
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
class ExplorerViewModel : ViewModel() {
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

    fun setActivePane(pane: Pane) {
        _state.update { it.copy(activePane = pane) }
    }

    fun setDualPaneMode(mode: DualPaneMode) {
        _state.update { it.copy(dualPaneMode = mode) }
    }

    fun refresh(pane: Pane) {
        val path = _state.value.getPaneState(pane).currentPath
        _state.update { it.updatePane(pane) { it.copy(isLoading = true, errorMessage = null) } }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
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
    onDualPaneModeChanged: (DualPaneMode) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
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
                            items(paneState.items, key = { it.path }) { item ->
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
                                        }
                                    )
                                ) {
                                    Text(
                                        text = (if (selected) "✓ " else "") + (if (item.isDirectory) "📁 " else "📄 ") + item.name,
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
            Button(onClick = { viewModel.copyToOtherPane(move = false) }) { Text("کپی به پنل دیگر") }
            Button(onClick = { viewModel.copyToOtherPane(move = true) }) { Text("انتقال") }
            Button(onClick = { viewModel.swapPanes() }) { Text("جابه‌جایی پنل‌ها") }
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
    }
}

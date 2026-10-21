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
    fun supportsTransfer(sourcePath: String, destinationPath: String): Boolean =
        isSafPath(sourcePath) || isSafPath(destinationPath)

    /** Copies through ContentResolver/DocumentFile when either endpoint is SAF-backed. */
    fun transfer(context: Context, sourcePath: String, destinationPath: String, move: Boolean): String? {
        return runCatching {
            val sourceDoc = if (isSafPath(sourcePath)) resolve(context, sourcePath) else null
            val sourceFile = if (isSafPath(sourcePath)) null else File(sourcePath)
            require(sourceDoc?.exists() == true || sourceFile?.exists() == true) { "فایل مبدأ پیدا نشد" }
            val sourceName = sourceDoc?.name ?: sourceFile!!.name
            if (isSafPath(sourcePath) && isSafPath(destinationPath)) {
                val sourceParts = parts(sourcePath)
                val destinationParts = parts(destinationPath)
                if (sourceParts[1] == destinationParts[1]) {
                    val sourceRelative = sourceParts[2].trimEnd('/')
                    val destinationRelative = destinationParts[2].trimEnd('/')
                    require(destinationRelative != sourceRelative &&
                        !destinationRelative.startsWith("$sourceRelative/")) {
                        "پوشه را نمی‌توان به خودش یا یکی از زیرپوشه‌هایش کپی یا منتقل کرد"
                    }
                }
            }
            if (isSafPath(destinationPath)) {
                val destination = resolve(context, destinationPath) ?: error("دسترسی نوشتن به مقصد وجود ندارد")
                require(destination.isDirectory) { "مقصد پوشه نیست" }
                val targetName = uniqueDocumentName(destination, sourceName)
                copyNodeToDocument(context, sourcePath, destination, targetName)
            } else {
                val destination = File(destinationPath)
                require(destination.isDirectory && destination.canWrite()) { "مقصد قابل نوشتن نیست" }
                if (sourceFile?.isDirectory == true) {
                    val sourceCanonical = sourceFile.canonicalFile
                    val destinationCanonical = destination.canonicalFile
                    require(destinationCanonical != sourceCanonical &&
                        !destinationCanonical.toPath().startsWith(sourceCanonical.toPath())) {
                        "پوشه را نمی‌توان به خودش یا یکی از زیرپوشه‌هایش کپی یا منتقل کرد"
                    }
                }
                val target = uniqueFile(destination, sourceName)
                copyNodeToFile(context, sourcePath, target)
            }
            if (move) {
                val deleted = if (sourceDoc != null) sourceDoc.delete() else if (sourceFile!!.isDirectory) sourceFile.deleteRecursively() else sourceFile.delete()
                require(deleted) { "کپی انجام شد اما حذف مبدأ ممکن نشد" }
            }
            null
        }.getOrElse { it.localizedMessage ?: "انتقال فایل انجام نشد" }
    }

    /** Extracts a ZIP beside the archive for both local paths and SAF-backed SD/USB storage. */
    fun extract(context: Context, archivePath: String): String? = runCatching {
        val archiveDoc = if (isSafPath(archivePath)) resolve(context, archivePath) else null
        val archiveFile = if (isSafPath(archivePath)) null else File(archivePath)
        require(archiveDoc?.isFile == true || archiveFile?.isFile == true) { "فایل ZIP پیدا نشد" }
        val archiveName = archiveDoc?.name ?: archiveFile!!.name
        require(archiveName.endsWith(".zip", ignoreCase = true)) { "فقط فایل ZIP قابل استخراج است" }
        val folderName = archiveName.dropLast(4).ifBlank { "فایل‌های استخراج‌شده" }
        val destinationPath = if (archiveDoc != null) {
            val parentPath = parentPath(archivePath) ?: error("پوشه والد آرشیو پیدا نشد")
            val parent = resolve(context, parentPath) ?: error("پوشه والد آرشیو پیدا نشد")
            require(parent.findFile(folderName) == null) { "پوشه مقصد از قبل وجود دارد: $folderName" }
            val folder = parent.createDirectory(folderName) ?: error("ساخت پوشه مقصد ناموفق بود")
            childPath(parentPath, folder.name ?: folderName)
        } else {
            val destination = File(archiveFile!!.parentFile ?: error("پوشه والد آرشیو پیدا نشد"), folderName)
            require(!destination.exists()) { "پوشه مقصد از قبل وجود دارد: $folderName" }
            require(destination.mkdir()) { "ساخت پوشه مقصد ناموفق بود" }
            destination.absolutePath
        }
        try {
            val input = if (archiveDoc != null) context.contentResolver.openInputStream(archiveDoc.uri)
                else FileInputStream(archiveFile!!)
            requireNotNull(input) { "خواندن فایل ZIP ممکن نشد" }.use { raw ->
                java.util.zip.ZipInputStream(raw.buffered()).use { zip ->
                    var entryCount = 0
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val normalized = entry.name.replace('\\', '/')
                        require(!normalized.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
                            "مسیر ناامن در ZIP شناسایی شد"
                        }
                        val segments = normalized.split('/').filter { it.isNotEmpty() }
                        require(segments.isNotEmpty() && segments.none { it == "." || it == ".." || it.contains(':') }) {
                            "نام مسیر نامعتبر در ZIP شناسایی شد"
                        }
                        if (archiveDoc != null) {
                            var parent = resolve(context, destinationPath) ?: error("پوشه مقصد در دسترس نیست")
                            segments.forEachIndexed { index, segment ->
                                val isLast = index == segments.lastIndex
                                val directory = !isLast || entry.isDirectory
                                val existing = parent.findFile(segment)
                                parent = if (directory) {
                                    existing?.takeIf { it.isDirectory }
                                        ?: run {
                                            require(existing == null) { "تداخل نام هنگام استخراج: $segment" }
                                            parent.createDirectory(segment) ?: error("ساخت پوشه ناموفق بود: $segment")
                                        }
                                } else {
                                    require(existing == null) { "فایل مقصد از قبل وجود دارد: $segment" }
                                    val target = parent.createFile("application/octet-stream", segment)
                                        ?: error("ساخت فایل ناموفق بود: $segment")
                                    context.contentResolver.openOutputStream(target.uri, "w")?.use { out ->
                                        zip.copyTo(out)
                                    } ?: error("نوشتن فایل ناموفق بود: $segment")
                                    target
                                }
                            }
                        } else {
                            val root = File(destinationPath).canonicalFile
                            val target = File(root, segments.joinToString(File.separator)).canonicalFile
                            require(target.toPath().startsWith(root.toPath())) { "مسیر ناامن در ZIP شناسایی شد" }
                            if (entry.isDirectory) {
                                require(!target.exists() || target.isDirectory) { "تداخل نام هنگام استخراج: " + entry.name }
                                require(target.mkdirs() || target.isDirectory) { "ساخت پوشه ناموفق بود: " + entry.name }
                            } else {
                                require(!target.exists()) { "فایل مقصد از قبل وجود دارد: " + entry.name }
                                require(target.parentFile?.mkdirs() != false) { "ساخت پوشه والد ناموفق بود" }
                                FileOutputStream(target).use { out -> zip.copyTo(out) }
                            }
                        }
                        entryCount++
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    require(entryCount > 0) { "فایل ZIP خالی یا نامعتبر است" }
                }
            }
        } catch (error: Throwable) {
            if (isSafPath(destinationPath)) resolve(context, destinationPath)?.delete()
            else File(destinationPath).deleteRecursively()
            throw error
        }
        null
    }.getOrElse { it.localizedMessage ?: "استخراج ZIP انجام نشد" }

    /** Creates a ZIP beside the source, supporting both filesystem paths and SAF documents. */
    fun compress(context: Context, sourcePath: String): String? = runCatching {
        val sourceDoc = if (isSafPath(sourcePath)) resolve(context, sourcePath) else null
        val sourceFile = if (isSafPath(sourcePath)) null else File(sourcePath)
        require(sourceDoc?.exists() == true || sourceFile?.exists() == true) { "فایل یا پوشه پیدا نشد" }
        val sourceName = sourceDoc?.name ?: sourceFile!!.name
        val zipName = "$sourceName.zip"
        val output = if (sourceDoc != null) {
            val parentPath = parentPath(sourcePath) ?: error("برای ریشه حافظه نمی‌توان فایل ZIP ساخت")
            val parent = resolve(context, parentPath) ?: error("پوشه والد پیدا نشد")
            require(parent.findFile(zipName) == null) { "فایل فشرده از قبل وجود دارد: $zipName" }
            parent.createFile("application/zip", zipName) ?: error("ساخت فایل ZIP در حافظه خارجی ناموفق بود")
        } else {
            val target = File(sourceFile!!.parentFile ?: error("پوشه والد پیدا نشد"), zipName)
            require(!target.exists()) { "فایل فشرده از قبل وجود دارد: $zipName" }
            target
        }
        val outputStream = if (output is DocumentFile) {
            context.contentResolver.openOutputStream(output.uri, "w") ?: error("نوشتن فایل ZIP ممکن نشد")
        } else {
            FileOutputStream(output as File)
        }
        try {
            ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
                fun addNode(path: String, entryName: String) {
                    val doc = if (isSafPath(path)) resolve(context, path) else null
                    val file = if (isSafPath(path)) null else File(path)
                    require(doc?.exists() == true || file?.exists() == true) { "فایل پیدا نشد: $entryName" }
                    val directory = doc?.isDirectory ?: (file?.isDirectory == true)
                    if (directory) {
                        val children = doc?.listFiles()?.map { child ->
                            childPath(path, child.name ?: error("نام فایل نامعتبر است"))
                        } ?: file!!.listFiles()?.map { it.absolutePath }.orEmpty()
                        if (children.isEmpty()) {
                            zip.putNextEntry(ZipEntry("$entryName/"))
                            zip.closeEntry()
                        } else {
                            children.forEach { child ->
                                val childName = if (isSafPath(child)) resolve(context, child)?.name else File(child).name
                                require(!childName.isNullOrBlank()) { "نام فایل نامعتبر است" }
                                addNode(child, "$entryName/$childName")
                            }
                        }
                    } else {
                        zip.putNextEntry(ZipEntry(entryName))
                        val input = if (doc != null) context.contentResolver.openInputStream(doc.uri) else FileInputStream(file!!)
                        requireNotNull(input) { "خواندن فایل مبدأ ممکن نشد" }.use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                addNode(sourcePath, sourceName)
            }
        } catch (error: Throwable) {
            if (output is DocumentFile) output.delete() else (output as File).delete()
            throw error
        }
        null
    }.getOrElse { it.localizedMessage ?: "فشرده‌سازی ZIP انجام نشد" }

    private fun uniqueDocumentName(parent: DocumentFile, name: String): String {
        if (parent.findFile(name) == null) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var i = 1
        while (parent.findFile("$base ($i)$ext") != null) i++
        return "$base ($i)$ext"
    }

    private fun uniqueFile(parent: File, name: String): File {
        var target = File(parent, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var i = 1
        while (File(parent, "$base ($i)$ext").exists()) i++
        target = File(parent, "$base ($i)$ext")
        return target
    }

    private fun copyNodeToDocument(context: Context, sourcePath: String, destination: DocumentFile, targetName: String) {
        val sourceDoc = if (isSafPath(sourcePath)) resolve(context, sourcePath) else null
        val sourceFile = if (isSafPath(sourcePath)) null else File(sourcePath)
        val isDirectory = sourceDoc?.isDirectory ?: sourceFile!!.isDirectory
        if (isDirectory) {
            val targetDir = destination.createDirectory(targetName) ?: error("ساخت پوشه در مقصد ناموفق بود")
            val children = sourceDoc?.listFiles()?.map { child -> childPath(sourcePath, child.name ?: "") }
                ?: sourceFile!!.listFiles()?.map { it.absolutePath }.orEmpty()
            children.forEach { childPath ->
                val childName = if (isSafPath(childPath)) resolve(context, childPath)?.name.orEmpty() else File(childPath).name
                require(childName.isNotBlank()) { "نام فایل نامعتبر است" }
                copyNodeToDocument(context, childPath, targetDir, uniqueDocumentName(targetDir, childName))
            }
        } else {
            val name = sourceDoc?.name ?: sourceFile!!.name
            val mime = context.contentResolver.getType(sourceDoc?.uri ?: Uri.fromFile(sourceFile!!)) ?: "application/octet-stream"
            val outputDoc = destination.createFile(mime, targetName) ?: error("ساخت فایل در مقصد ناموفق بود")
            val input = if (sourceDoc != null) context.contentResolver.openInputStream(sourceDoc.uri) else FileInputStream(sourceFile!!)
            requireNotNull(input) { "خواندن فایل مبدأ ممکن نشد" }.use { stream ->
                val output = context.contentResolver.openOutputStream(outputDoc.uri, "w") ?: error("نوشتن فایل مقصد ممکن نشد")
                output.use { stream.copyTo(it) }
            }
        }
    }

    private fun copyNodeToFile(context: Context, sourcePath: String, target: File) {
        val sourceDoc = if (isSafPath(sourcePath)) resolve(context, sourcePath) else null
        val sourceFile = if (isSafPath(sourcePath)) null else File(sourcePath)
        val isDirectory = sourceDoc?.isDirectory ?: sourceFile!!.isDirectory
        if (isDirectory) {
            require(target.mkdirs() || target.isDirectory) { "ساخت پوشه در مقصد ناموفق بود" }
            val children = sourceDoc?.listFiles()?.map { child -> childPath(sourcePath, child.name ?: "") }
                ?: sourceFile!!.listFiles()?.map { it.absolutePath }.orEmpty()
            children.forEach { childPath ->
                val name = if (isSafPath(childPath)) resolve(context, childPath)?.name.orEmpty() else File(childPath).name
                require(name.isNotBlank()) { "نام فایل نامعتبر است" }
                copyNodeToFile(context, childPath, File(target, name))
            }
        } else {
            val input = if (sourceDoc != null) context.contentResolver.openInputStream(sourceDoc.uri) else FileInputStream(sourceFile!!)
            requireNotNull(input) { "خواندن فایل مبدأ ممکن نشد" }.use { stream ->
                FileOutputStream(target).use { output -> stream.copyTo(output) }
            }
        }
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
                    if (SafDocumentAccess.isSafPath(selected)) {
                        val source = SafDocumentAccess.resolve(getApplication(), selected)
                            ?: error("فایل یا پوشه پیدا نشد")
                        require(source.parentFile?.findFile(newName.trim()) == null) { "فایلی با این نام از قبل وجود دارد" }
                        if (!source.renameTo(newName.trim())) "تغییر نام انجام نشد" else null
                    } else {
                        val source = File(selected)
                        require(source.exists()) { "فایل یا پوشه پیدا نشد" }
                        val target = File(source.parentFile ?: error("مسیر والد پیدا نشد"), newName.trim())
                        require(!target.exists()) { "فایلی با این نام از قبل وجود دارد" }
                        val native = RustFileEngine.renamePath(source.absolutePath, target.absolutePath)
                        val success = if (native != null) native.success else source.renameTo(target)
                        if (!success) native?.error ?: "تغییر نام انجام نشد" else null
                    }
                }.getOrElse { it.localizedMessage ?: "تغییر نام انجام نشد" }
            }
            if (error == null) {
                clearSelection(pane)
                refresh(pane)
            }
            onFinished(error)
        }
    }

    fun extractSelected(onFinished: (String?) -> Unit = {}) {
        val snapshot = _state.value
        val pane = snapshot.activePane
        val selected = snapshot.getPaneState(pane).selectedItems.toList()
        if (selected.isEmpty()) {
            onFinished("ابتدا یک فایل ZIP انتخاب کنید")
            return
        }
        viewModelScope.launch {
            val errors = withContext(Dispatchers.IO) {
                selected.mapNotNull { path ->
                    SafDocumentAccess.extract(getApplication(), path)?.let { error ->
                        (if (SafDocumentAccess.isSafPath(path)) SafDocumentAccess.resolve(getApplication(), path)?.name else File(path).name) + ": " + error
                    }
                }
            }
            refresh(pane)
            onFinished(errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
        }
    }

    fun compressSelected(onFinished: (String?) -> Unit = {}) {
        val snapshot = _state.value
        val pane = snapshot.activePane
        val selected = snapshot.getPaneState(pane).selectedItems.toList()
        if (selected.isEmpty()) {
            onFinished("ابتدا فایل یا پوشه‌ای انتخاب کنید")
            return
        }
        viewModelScope.launch {
            val errors = withContext(Dispatchers.IO) {
                selected.mapNotNull { path ->
                    SafDocumentAccess.compress(getApplication(), path)?.let { error ->
                        "${if (SafDocumentAccess.isSafPath(path)) SafDocumentAccess.resolve(getApplication(), path)?.name else File(path).name}: $error"
                    }
                }
            }
            refresh(pane)
            onFinished(errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
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
                    val fileName = if (SafDocumentAccess.isSafPath(path)) {
                        SafDocumentAccess.resolve(getApplication(), path)?.name ?: path.substringAfterLast('/')
                    } else File(path).name
                    val ok = if (SafDocumentAccess.isSafPath(path)) {
                        SafDocumentAccess.resolve(getApplication(), path)?.delete() == true
                    } else {
                        val native = RustFileEngine.deletePath(path)
                        if (native != null) native.success else runCatching { File(path).deleteRecursively() }.getOrDefault(false)
                    }
                    if (ok) successes++ else {
                        failures++
                        errors += "$fileName: حذف انجام نشد"
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
        val destinationState = snapshot.getPaneState(destinationPane)
        val destinationPath = destinationState.currentPath
        if (destinationState.isLoading) {
            _state.update { it.updatePane(destinationPane) { p -> p.copy(errorMessage = "مقصد هنوز در حال بارگذاری است") } }
            return
        }
        val destinationValid = if (SafDocumentAccess.isSafPath(destinationPath)) {
            SafDocumentAccess.resolve(getApplication(), destinationPath)?.isDirectory == true
        } else {
            File(destinationPath).let { it.isDirectory && it.canWrite() }
        }
        if (!destinationValid) {
            _state.update { it.updatePane(destinationPane) { p -> p.copy(errorMessage = "مبدأ یا مقصد عملیات در دسترس نیست") } }
            return
        }
        val sourcePane = Pane.entries.firstOrNull { pane ->
            snapshot.getPaneState(pane).items.any { it.path == path }
        }
        viewModelScope.launch {
            val result = if (SafDocumentAccess.supportsTransfer(path, destinationPath)) {
                val error = withContext(Dispatchers.IO) {
                    SafDocumentAccess.transfer(getApplication(), path, destinationPath, move)
                }
                FileOperationsManager.OperationResult(
                    successCount = if (error == null) 1 else 0,
                    failureCount = if (error == null) 0 else 1,
                    errors = error?.let { listOf(it) } ?: emptyList()
                )
            } else {
                val source = File(path)
                val destination = File(destinationPath)
                val invalidTarget = runCatching {
                    val src = source.canonicalFile
                    val dst = destination.canonicalFile
                    src == dst || (source.isDirectory && dst.toPath().startsWith(src.toPath()))
                }.getOrDefault(true)
                if (!source.exists() || invalidTarget) {
                    FileOperationsManager.OperationResult(0, 1, listOf("مبدأ یا مقصد عملیات معتبر نیست"))
                } else {
                    FileOperationsManager.executeTransfer(
                        sources = listOf(source),
                        destinationDir = destination,
                        isMove = move,
                        onProgress = {}
                    )
                }
            }
            _state.update { current ->
                current.updatePane(destinationPane) { p -> p.copy(errorMessage = result.errors.firstOrNull()) }
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
            .map { it.path }
            .toList()
        if (selectedPaths.isEmpty() || sourceState.isLoading || destinationState.isLoading) {
            _state.update { it.updatePane(sourcePane) { p -> p.copy(errorMessage = "ابتدا فایل یا پوشه‌ای انتخاب کنید و صبر کنید بارگذاری تمام شود") } }
            return
        }
        val destinationPath = destinationState.currentPath
        val destinationIsValid = if (SafDocumentAccess.isSafPath(destinationPath)) {
            SafDocumentAccess.resolve(getApplication(), destinationPath)?.isDirectory == true
        } else {
            File(destinationPath).let { it.isDirectory && it.canWrite() }
        }
        if (!destinationIsValid) {
            _state.update { it.updatePane(destinationPane) { p -> p.copy(errorMessage = "پوشه مقصد قابل نوشتن نیست") } }
            return
        }
        viewModelScope.launch {
            val usesSaf = selectedPaths.any { SafDocumentAccess.supportsTransfer(it, destinationPath) }
            val result = if (usesSaf) {
                withContext(Dispatchers.IO) {
                    var success = 0
                    var failure = 0
                    val errors = mutableListOf<String>()
                    selectedPaths.forEach { sourcePath ->
                        val error = SafDocumentAccess.transfer(getApplication(), sourcePath, destinationPath, move)
                        if (error == null) success++ else {
                            failure++
                            errors += "${sourcePath.substringAfterLast('/').ifBlank { sourcePath }}: $error"
                        }
                    }
                    FileOperationsManager.OperationResult(success, failure, errors)
                }
            } else {
                FileOperationsManager.executeTransfer(
                    sources = selectedPaths.map(::File),
                    destinationDir = File(destinationPath),
                    isMove = move,
                    onProgress = {}
                )
            }
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
                                    Text((if (SafDocumentAccess.isSafPath(path)) SafDocumentAccess.resolve(context, path)?.name else File(path).name).orEmpty().ifBlank { path }, maxLines = 1)
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
                            renameText = selectedPaths.firstOrNull()?.let { path -> if (SafDocumentAccess.isSafPath(path)) SafDocumentAccess.resolve(context, path)?.name.orEmpty() else File(path).name }.orEmpty()
                            viewModel.setSelectionMenuVisible(false)
                        }) { Text("تغییر نام") }
                        TextButton(enabled = selectedCount > 0, onClick = {
                            viewModel.setSelectionMenuVisible(false)
                            viewModel.compressSelected { error -> operationMessage = error ?: "فشرده‌سازی با موفقیت انجام شد" }
                        }) { Text("فشرده‌سازی ZIP") }
                        TextButton(enabled = selectedCount > 0, onClick = {
                            viewModel.setSelectionMenuVisible(false)
                            viewModel.extractSelected { error -> operationMessage = error ?: "استخراج ZIP با موفقیت انجام شد" }
                        }) { Text("استخراج ZIP") }
                        TextButton(enabled = selectedCount == 1, onClick = { propertiesTarget = selectedPaths.firstOrNull(); viewModel.setSelectionMenuVisible(false) }) { Text("مشخصات") }
                        TextButton(enabled = selectedCount == 1, onClick = {
                            selectedPaths.firstOrNull()?.let { path ->
                                viewModel.toggleBookmark(path)
                                operationMessage = if (path in state.bookmarkedPaths) "نشانک حذف شد" else "به نشانک‌ها اضافه شد"
                            }
                            viewModel.setSelectionMenuVisible(false)
                        }) { Text(if (selectedPaths.any { it in state.bookmarkedPaths }) "حذف از نشانک‌ها" else "افزودن نشانک") }
                        TextButton(enabled = selectedCount == 1, onClick = {
                            val path = selectedPaths.firstOrNull()
                            if (path != null) runCatching {
                                val saf = SafDocumentAccess.isSafPath(path)
                                val file = if (saf) null else File(path)
                                val document = if (saf) SafDocumentAccess.resolve(context, path) else null
                                require(document?.exists() == true || file?.exists() == true) { "فایل پیدا نشد" }
                                val uri = document?.uri ?: FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file!!)
                                val mime = document?.type ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file?.extension?.lowercase().orEmpty()) ?: "*/*"
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "اشتراک‌گذاری فایل"))
                            }.onFailure { operationMessage = it.localizedMessage ?: "اشتراک‌گذاری ممکن نشد" }
                            viewModel.setSelectionMenuVisible(false)
                        }) { Text("اشتراک‌گذاری") }
                        TextButton(enabled = selectedCount == 1, onClick = {
                            val path = selectedPaths.firstOrNull()
                            if (path != null) runCatching {
                                val saf = SafDocumentAccess.isSafPath(path)
                                val file = if (saf) null else File(path)
                                val document = if (saf) SafDocumentAccess.resolve(context, path) else null
                                require(document?.exists() == true || file?.exists() == true) { "فایل پیدا نشد" }
                                val uri = document?.uri ?: FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file!!)
                                val mime = document?.type ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file?.extension?.lowercase().orEmpty()) ?: "*/*"
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
            val saf = SafDocumentAccess.isSafPath(path)
            val document = if (saf) SafDocumentAccess.resolve(context, path) else null
            val file = if (saf) null else File(path)
            val name = document?.name ?: file?.name ?: path
            val isDirectory = document?.isDirectory ?: (file?.isDirectory == true)
            val size = document?.length() ?: file?.length() ?: 0L
            val modified = document?.lastModified() ?: file?.lastModified() ?: 0L
            AlertDialog(
                onDismissRequest = { propertiesTarget = null },
                title = { Text("مشخصات فایل") },
                text = {
                    Column {
                        Text("نام: $name")
                        Text("مسیر: ${if (saf) path.removePrefix("saf|") else file?.absolutePath ?: path}")
                        Text("نوع: ${if (isDirectory) "پوشه" else "فایل"}")
                        Text("اندازه: ${if (isDirectory) "محاسبه نشده" else android.text.format.Formatter.formatShortFileSize(context, size)}")
                        Text("آخرین تغییر: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(modified))}")
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

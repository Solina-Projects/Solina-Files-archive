package ir.solina.fm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for independent navigation and focus in the two file panes.
 * Directory enumeration runs off the main thread.
 */
class DualPaneViewModel : ViewModel() {
    private val _leftPane = MutableStateFlow(
        PaneState(PaneState.LEFT_ID, currentPath = "", isActive = false, isEmpty = true)
    )
    val leftPane: StateFlow<PaneState> = _leftPane.asStateFlow()

    private val _rightPane = MutableStateFlow(
        PaneState(PaneState.RIGHT_ID, currentPath = File.separator, isActive = true)
    )
    val rightPane: StateFlow<PaneState> = _rightPane.asStateFlow()

    private var leftLoadJob: Job? = null
    private var rightLoadJob: Job? = null
    private var initialized = false
    private val fileRepository = FileRepository()

    /** Call once with the path currently open in the main browser and an optional saved left path. */
    fun initialize(rightPath: String, leftPath: String? = null) {
        if (initialized) return
        initialized = true
        val rightDirectory = File(rightPath).takeIf { it.isDirectory } ?: File(File.separator)
        _rightPane.value = PaneState(
            id = PaneState.RIGHT_ID,
            currentPath = rightDirectory.absolutePath,
            isActive = true,
            isLoading = true
        )

        val leftDirectory = leftPath?.let(::File)?.takeIf { it.isDirectory }
        _leftPane.value = if (leftDirectory == null) {
            PaneState(PaneState.LEFT_ID, currentPath = "", isActive = false, isEmpty = true)
        } else {
            PaneState(
                id = PaneState.LEFT_ID,
                currentPath = leftDirectory.absolutePath,
                isActive = false,
                isLoading = true
            )
        }

        loadFiles(PaneState.RIGHT_ID, rightDirectory)
        leftDirectory?.let { loadFiles(PaneState.LEFT_ID, it) }
    }

    fun activatePane(targetId: String) {
        if (targetId != PaneState.LEFT_ID && targetId != PaneState.RIGHT_ID) return
        _leftPane.update { it.copy(isActive = targetId == PaneState.LEFT_ID) }
        _rightPane.update { it.copy(isActive = targetId == PaneState.RIGHT_ID) }
    }

    /** Open an explicitly selected directory in the requested pane. */
    fun openFolder(paneId: String, folder: File) {
        if (paneId != PaneState.LEFT_ID && paneId != PaneState.RIGHT_ID) return
        if (!folder.isDirectory) {
            stateFor(paneId).update { it.copy(errorMessage = "پوشه در دسترس نیست") }
            return
        }

        val directory = folder.absoluteFile
        val current = stateFor(paneId).value
        if (!current.isEmpty && current.currentPath == directory.path) return

        stateFor(paneId).update {
            it.copy(
                currentPath = directory.path,
                files = emptyList(),
                isLoading = true,
                isEmpty = false,
                errorMessage = null
            )
        }
        loadFiles(paneId, directory)
    }

    fun goUp(paneId: String) {
        if (paneId != PaneState.LEFT_ID && paneId != PaneState.RIGHT_ID) return
        val state = stateFor(paneId).value
        if (state.isEmpty) return
        val parent = File(state.currentPath).parentFile ?: return
        openFolder(paneId, parent)
    }

    /**
     * Navigate directly to a breadcrumb path. An empty target represents the filesystem root.
     * Directory enumeration and permission failures are handled by the shared loader.
     */
    fun navigateToPath(paneId: String, targetPath: String) {
        if (paneId != PaneState.LEFT_ID && paneId != PaneState.RIGHT_ID) return
        val destination = File(targetPath.ifBlank { File.separator }).absoluteFile
        openFolder(paneId, destination)
    }

    /** Toggle grid/list presentation independently for the requested pane. */
    fun toggleViewMode(paneId: String) {
        if (paneId != PaneState.LEFT_ID && paneId != PaneState.RIGHT_ID) return
        stateFor(paneId).update { state -> state.copy(isGridView = !state.isGridView) }
    }

    /** Add or remove one absolute file path from the selection in a pane. */
    fun toggleSelection(paneId: String, filePath: String) {
        if (paneId != PaneState.LEFT_ID && paneId != PaneState.RIGHT_ID) return
        val normalizedPath = File(filePath).absolutePath
        stateFor(paneId).update { state ->
            val next = state.selectedIds.toMutableSet()
            if (!next.add(normalizedPath)) next.remove(normalizedPath)
            state.copy(selectedIds = next)
        }
    }

    /** Replace the current selection with the supplied absolute file paths. */
    fun selectFiles(paneId: String, filePaths: Collection<String>) {
        if (paneId != PaneState.LEFT_ID && paneId != PaneState.RIGHT_ID) return
        val normalizedPaths = filePaths.map { File(it).absolutePath }.toSet()
        stateFor(paneId).update { it.copy(selectedIds = normalizedPaths) }
    }

    /** Clear selection without changing navigation or the displayed files. */
    fun clearSelection(paneId: String) {
        if (paneId != PaneState.LEFT_ID && paneId != PaneState.RIGHT_ID) return
        stateFor(paneId).update { it.copy(selectedIds = emptySet()) }
    }

    /** Clear selection in both panes, useful after a completed cross-pane operation. */
    fun clearAllSelections() {
        clearSelection(PaneState.LEFT_ID)
        clearSelection(PaneState.RIGHT_ID)
    }

    data class TransferOutcome(
        val succeeded: Int,
        val failed: Int,
        val message: String
    )

    /**
     * Copy or move the selected entries from one pane into the other pane's current folder.
     * Filesystem work runs on Dispatchers.IO; the optional callback is invoked on the main thread.
     */
    fun transferFiles(
        sourcePaneId: String,
        destinationPaneId: String,
        isMove: Boolean,
        onComplete: ((TransferOutcome) -> Unit)? = null
    ): Job? {
        val validIds = setOf(PaneState.LEFT_ID, PaneState.RIGHT_ID)
        if (sourcePaneId !in validIds || destinationPaneId !in validIds ||
            sourcePaneId == destinationPaneId
        ) return null

        val sourceState = stateFor(sourcePaneId).value
        val destinationState = stateFor(destinationPaneId).value
        val sources = sourceState.selectedIds.map(::File).distinctBy { it.absolutePath }
        val destination = File(destinationState.currentPath)

        if (sources.isEmpty()) {
            onComplete?.invoke(TransferOutcome(0, 0, "ابتدا فایل یا پوشه‌ای را انتخاب کنید"))
            return null
        }
        if (destinationState.isEmpty || !destination.isDirectory) {
            onComplete?.invoke(TransferOutcome(0, sources.size, "پوشهٔ مقصد در دسترس نیست"))
            return null
        }
        if (sources.any { source ->
                runCatching {
                    val sourcePath = source.canonicalFile.toPath()
                    val destinationPath = destination.canonicalFile.toPath()
                    sourcePath == destinationPath || destinationPath.startsWith(sourcePath) && source.isDirectory
                }.getOrDefault(true)
            }
        ) {
            onComplete?.invoke(TransferOutcome(0, sources.size, "انتقال پوشه به داخل خودش مجاز نیست"))
            return null
        }

        stateFor(sourcePaneId).update { it.copy(isLoading = true, errorMessage = null) }
        return viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                var succeeded = 0
                var failed = 0
                sources.forEach { source ->
                    try {
                        if (isMove) {
                            fileRepository.moveFile(source.path, destination.path)
                        } else {
                            fileRepository.copyFile(source.path, destination.path)
                        }
                        succeeded++
                    } catch (_: Exception) {
                        // One failed entry must not abort the remaining selected transfers.
                        failed++
                    }
                }
                val message = when {
                    failed == 0 -> if (isMove) "انتقال با موفقیت انجام شد" else "کپی با موفقیت انجام شد"
                    succeeded == 0 -> "عملیات انجام نشد؛ دسترسی و فضای ذخیره‌سازی را بررسی کنید"
                    else -> "$succeeded مورد انجام شد؛ $failed مورد ناموفق بود"
                }
                TransferOutcome(succeeded, failed, message)
            }

            stateFor(sourcePaneId).update {
                it.copy(selectedIds = emptySet(), isLoading = false, errorMessage = null)
            }
            refreshPane(sourcePaneId)
            refreshPane(destinationPaneId)
            onComplete?.invoke(outcome)
        }
    }

    /** Refresh after file operations or external file-system changes. */
    fun refreshPane(paneId: String) {
        if (paneId != PaneState.LEFT_ID && paneId != PaneState.RIGHT_ID) return
        val state = stateFor(paneId).value
        if (state.isEmpty) return
        val directory = File(state.currentPath)
        stateFor(paneId).update { it.copy(isLoading = true, errorMessage = null) }
        loadFiles(paneId, directory)
    }

    private fun loadFiles(paneId: String, directory: File) {
        val job = viewModelScope.launch {
            stateFor(paneId).update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val entries = withContext(Dispatchers.IO) {
                    directory.listFiles()
                        ?.filter { it.exists() && (it.isFile || it.isDirectory) }
                        ?.sortedWith(
                            compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
                        )
                        .orEmpty()
                }
                stateFor(paneId).update { state ->
                    if (state.currentPath != directory.absolutePath || state.isEmpty) state
                    else state.copy(files = entries, isLoading = false, errorMessage = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                stateFor(paneId).update { state ->
                    if (state.currentPath != directory.absolutePath) state
                    else state.copy(
                        files = emptyList(),
                        isLoading = false,
                        errorMessage = "مجوز دسترسی به پوشه وجود ندارد"
                    )
                }
            } catch (_: Exception) {
                stateFor(paneId).update { state ->
                    if (state.currentPath != directory.absolutePath) state
                    else state.copy(
                        files = emptyList(),
                        isLoading = false,
                        errorMessage = "خواندن پوشه ناموفق بود"
                    )
                }
            }
        }

        if (paneId == PaneState.LEFT_ID) {
            leftLoadJob?.cancel()
            leftLoadJob = job
        } else {
            rightLoadJob?.cancel()
            rightLoadJob = job
        }
    }

    private fun stateFor(paneId: String): MutableStateFlow<PaneState> =
        if (paneId == PaneState.LEFT_ID) _leftPane else _rightPane

    override fun onCleared() {
        leftLoadJob?.cancel()
        rightLoadJob?.cancel()
        super.onCleared()
    }
}

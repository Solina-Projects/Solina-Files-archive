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

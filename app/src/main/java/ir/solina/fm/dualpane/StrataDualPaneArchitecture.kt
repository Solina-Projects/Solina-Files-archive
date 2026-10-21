package ir.solina.fm.dualpane

import android.os.Environment
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ir.solina.fm.FileOperationsManager
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
    val isDualPaneForced: Boolean = false,
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

    fun setDualPaneForced(forced: Boolean) {
        _state.update { it.copy(isDualPaneForced = forced) }
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
                    directory.listFiles().orEmpty()
                        .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                        .map { ExplorerFileItem(it.absolutePath, it.name, it.isDirectory, it.length(), it.lastModified()) }
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
        _state.update { it.copy(leftPane = it.rightPane, rightPane = it.leftPane) }
    }

    fun copyToOtherPane(move: Boolean, onFinished: (FileOperationsManager.OperationResult) -> Unit = {}) {
        val snapshot = _state.value
        val sourcePane = snapshot.activePane
        val destinationPane = if (sourcePane == Pane.LEFT) Pane.RIGHT else Pane.LEFT
        val sourceState = snapshot.getPaneState(sourcePane)
        val destinationState = snapshot.getPaneState(destinationPane)
        val selected = sourceState.selectedItems.map(::File)
        if (selected.isEmpty()) return
        val destination = File(destinationState.currentPath)
        viewModelScope.launch {
            val result = FileOperationsManager.executeTransfer(
                sources = selected,
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
fun ExplorerScreen(viewModel: ExplorerViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val showDual = state.isDualPaneForced || wide
    val panes = if (showDual) listOf(Pane.LEFT, Pane.RIGHT) else listOf(state.activePane)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            panes.forEach { pane ->
                val paneState = state.getPaneState(pane)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(if (state.activePane == pane) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
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
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(paneState.items, key = { it.path }) { item ->
                                val selected = item.path in paneState.selectedItems
                                Surface(
                                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth().combinedClickable(
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
    }
}

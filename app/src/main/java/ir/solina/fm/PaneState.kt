package ir.solina.fm

import java.io.File

/**
 * Independent observable state for one side of the dual-pane file manager.
 * An empty pane has a blank path until the user chooses a destination directory.
 */
data class PaneState(
    val id: String,
    val currentPath: String,
    val files: List<File> = emptyList(),
    val isActive: Boolean = false,
    val isLoading: Boolean = false,
    val isEmpty: Boolean = false,
    val errorMessage: String? = null
) {
    companion object {
        const val LEFT_ID = "PANE_LEFT"
        const val RIGHT_ID = "PANE_RIGHT"

        fun initialRight(path: String): PaneState =
            PaneState(RIGHT_ID, path, isActive = true)

        fun initialLeft(): PaneState =
            PaneState(LEFT_ID, currentPath = "", isActive = false, isEmpty = true)
    }
}

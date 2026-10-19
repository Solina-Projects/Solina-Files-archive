package ir.solina.fm

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Reusable description of a file category, tool, or settings destination.
 *
 * A null accentColor means the current Material theme's primary color is used.
 */
data class CategoryItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val accentColor: Long? = null,
    val onClick: () -> Unit
)

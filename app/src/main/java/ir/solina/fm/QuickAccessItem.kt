package ir.solina.fm

import java.io.File

/**
 * Lightweight model for a home-screen quick-access shortcut.
 *
 * This app currently uses Android Views rather than Jetpack Compose, so the
 * icon is a compact text glyph instead of Compose's ImageVector.
 */
data class QuickAccessItem(
    val id: String,
    val label: String,
    val icon: String,
    val directory: File
)

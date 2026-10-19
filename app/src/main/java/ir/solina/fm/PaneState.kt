package ir.solina.fm

data class PaneState(val id: String, val currentPath: String, val isActive: Boolean = false, val isLoading: Boolean = false, val isEmpty: Boolean = false)

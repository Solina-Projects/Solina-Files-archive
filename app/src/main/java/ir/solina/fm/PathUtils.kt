package ir.solina.fm

import java.io.File

/** Helpers for turning absolute filesystem paths into clickable breadcrumb entries. */
object PathUtils {
    data class Crumb(val name: String, val fullPath: String)

    /**
     * Converts "/a/b/c" to a root crumb and cumulative paths:
     * Home -> /a -> /a/b -> /a/b/c.
     *
     * The root crumb intentionally has an empty fullPath; the UI resolves it to
     * Android's shared internal-storage root rather than the process working directory.
     */
    fun parsePathToCrumbs(path: String): List<Crumb> {
        val normalized = path.trim()
        if (normalized.isEmpty() || normalized == File.separator) {
            return listOf(Crumb("حافظه داخلی", ""))
        }

        val parts = normalized.split(File.separatorChar).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(Crumb("حافظه داخلی", ""))

        val crumbs = mutableListOf(Crumb("⌂", ""))
        var currentPath = if (normalized.startsWith(File.separator)) File.separator else ""
        for (part in parts) {
            currentPath = when {
                currentPath.isEmpty() -> part
                currentPath == File.separator -> currentPath + part
                else -> currentPath + File.separator + part
            }
            crumbs.add(Crumb(part, currentPath))
        }
        return crumbs
    }
}

package ir.solina.fm

import android.content.Context

/**
 * Lightweight persistent list of user-starred file paths.
 *
 * Paths are revalidated by the category scanner, so deleted or moved files
 * are not shown as available starred files.
 */
object StarredFilesStore {
    private const val PREFS = "solina_starred_files"
    private const val KEY_PATHS = "paths"

    fun list(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PATHS, emptySet())
            ?.toSet()
            ?: emptySet()

    fun contains(context: Context, path: String): Boolean = path in list(context)

    fun toggle(context: Context, path: String): Boolean {
        val paths = list(context).toMutableSet()
        val nowStarred = if (paths.remove(path)) {
            false
        } else {
            paths.add(path)
            true
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PATHS, paths)
            .apply()
        return nowStarred
    }
}

package ir.solina.fm

import android.content.Context
import java.io.File

object RecentFilesStore {
    private const val PREFS = "solina_preferences"
    private const val KEY = "recent_files"
    private const val LIMIT = 80

    fun add(context: Context, file: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = prefs.getString(KEY, "").orEmpty().lineSequence()
            .filter { it.isNotBlank() && it != file.absolutePath && File(it).exists() }
            .toMutableList()
        items.add(0, file.absolutePath)
        prefs.edit().putString(KEY, items.take(LIMIT).joinToString("\n")).apply()
    }

    fun list(context: Context): List<File> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().lineSequence()
            .filter { it.isNotBlank() }
            .map { File(it) }
            .filter { it.exists() }
            .distinctBy { it.absolutePath }
            .take(LIMIT)
            .toList()

    fun remove(context: Context, file: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remaining = list(context).filterNot { it.absolutePath == file.absolutePath }
        prefs.edit().putString(KEY, remaining.joinToString("\n") { it.absolutePath }).apply()
    }
}

package ir.solina.fm

import android.content.Context
import android.util.Base64
import java.io.File

/** Same-volume, reversible trash based on hidden sibling renames. */
object TrashStore {
    data class Entry(val original: File, val trashed: File)

    private const val PREFS = "solina_trash"
    private const val KEY = "entries"

    fun entries(context: Context): List<Entry> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNull { decode(it) }
            .filter { it.trashed.exists() }

    @Synchronized
    fun moveToTrash(context: Context, file: File): Boolean {
        if (!file.exists() || file.name.startsWith(".solina-trash-")) return false
        val trashed = File(file.parentFile, ".solina-trash-${System.currentTimeMillis()}-${file.name}")
        if (!file.renameTo(trashed)) return false
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        values.add(encode(file, trashed))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, values).apply()
        return true
    }

    @Synchronized
    fun restore(context: Context, entry: Entry): Boolean {
        if (!entry.trashed.exists() || entry.original.exists()) return false
        val parent = entry.original.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false
        if (!entry.trashed.renameTo(entry.original)) return false
        remove(context, entry)
        return true
    }

    @Synchronized
    fun deletePermanently(context: Context, entry: Entry): Boolean {
        if (entry.trashed.exists() && !entry.trashed.deleteRecursively()) return false
        remove(context, entry)
        return true
    }

    private fun remove(context: Context, entry: Entry) {
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        values.remove(encode(entry.original, entry.trashed))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, values).apply()
    }

    private fun encode(original: File, trashed: File): String =
        "${b64(original.absolutePath)}:${b64(trashed.absolutePath)}"

    private fun decode(value: String): Entry? = try {
        val split = value.indexOf(':')
        if (split <= 0) null else Entry(File(unb64(value.substring(0, split))), File(unb64(value.substring(split + 1))))
    } catch (_: Exception) { null }

    private fun b64(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    private fun unb64(value: String) = String(Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
}

package ir.solina.fm.nativecore

import org.json.JSONArray

/** Result returned by the Rust filesystem engine. */
data class NativeOperationResult(
    val success: Boolean,
    val error: String? = null
)

data class NativeFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long
)

/**
 * JNI bridge to the Rust core. Public methods return null only when native code
 * is unavailable, allowing callers to use the existing Kotlin implementation.
 */
object RustFileEngine {
    private val loaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("solina_core")
            true
        }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeListDirectory(path: String): String

    @JvmStatic
    private external fun nativeCopy(source: String, target: String): String

    @JvmStatic
    private external fun nativeMove(source: String, target: String): String

    @JvmStatic
    private external fun nativeRename(source: String, target: String): String

    @JvmStatic
    private external fun nativeDelete(path: String): String

    @JvmStatic
    private external fun nativeRecursiveSize(path: String): Long

    fun listDirectory(path: String): List<NativeFileEntry>? {
        if (!loaded) return null
        return runCatching {
            val json = nativeListDirectory(path)
            if (json.startsWith("ERR:")) return null
            val array = JSONArray(json)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        NativeFileEntry(
                            path = item.getString("path"),
                            name = item.getString("name"),
                            isDirectory = item.getBoolean("isDirectory"),
                            sizeBytes = item.optLong("sizeBytes", 0L),
                            modifiedAt = item.optLong("modifiedAt", 0L)
                        )
                    )
                }
            }
        }.getOrNull()
    }

    fun copyPath(source: String, target: String): NativeOperationResult? =
        operation { nativeCopy(source, target) }

    fun movePath(source: String, target: String): NativeOperationResult? =
        operation { nativeMove(source, target) }

    fun renamePath(source: String, target: String): NativeOperationResult? =
        operation { nativeRename(source, target) }

    fun deletePath(path: String): NativeOperationResult? =
        operation { nativeDelete(path) }

    fun recursiveSizeBytes(path: String): Long? {
        if (!loaded) return null
        return runCatching { nativeRecursiveSize(path).takeIf { it >= 0L } }.getOrNull()
    }

    private inline fun operation(call: () -> String): NativeOperationResult? {
        if (!loaded) return null
        val response = runCatching(call).getOrNull() ?: return null
        return if (response == "OK") {
            NativeOperationResult(success = true)
        } else {
            NativeOperationResult(
                success = false,
                error = response.removePrefix("ERR:").ifBlank { "عملیات بومی ناموفق بود" }
            )
        }
    }
}

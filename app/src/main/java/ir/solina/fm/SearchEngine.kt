package ir.solina.fm

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Recursive search engine. Filesystem traversal is kept off the main thread and checks
 * coroutine cancellation regularly so a newer query can supersede an older one.
 */
class SearchEngine(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {

    suspend fun search(rootPath: String, criteria: SearchCriteria): List<File> =
        withContext(dispatcher) {
            if (criteria.isEmpty()) return@withContext emptyList()
            val root = File(rootPath)
            if (!root.isDirectory || !root.exists()) return@withContext emptyList()

            val query = criteria.queryText.trim().lowercase(Locale.ROOT)
            val selectedTypes = criteria.fileTypes - FileType.ALL
            val cutoff = criteria.modifiedSinceDays?.let { days ->
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.coerceAtLeast(0).toLong())
            }
            val stack = ArrayDeque<File>()
            val visitedDirectories = HashSet<String>()
            val results = ArrayList<File>()
            stack.addLast(root)

            while (stack.isNotEmpty()) {
                coroutineContext.ensureActive()
                val directory = stack.removeLast()
                val canonicalPath = try {
                    directory.canonicalPath
                } catch (_: Exception) {
                    continue
                }
                if (!visitedDirectories.add(canonicalPath)) continue

                val children = try {
                    directory.listFiles()?.asList().orEmpty()
                } catch (_: SecurityException) {
                    emptyList()
                }
                for (child in children) {
                    coroutineContext.ensureActive()
                    try {
                        if (child.isDirectory) {
                            if (criteria.searchInSubfolders) stack.addLast(child)
                            continue
                        }
                        if (!child.isFile || !child.canRead()) continue
                        if (query.isNotEmpty() && !child.name.lowercase(Locale.ROOT).contains(query)) continue
                        if (selectedTypes.isNotEmpty() && detectFileType(child.extension) !in selectedTypes) continue
                        val size = child.length()
                        if (size < criteria.minSizeBytes || size > criteria.maxSizeBytes) continue
                        if (cutoff != null && child.lastModified() < cutoff) continue
                        results.add(child)
                    } catch (_: SecurityException) {
                        // Skip entries that are not accessible and continue searching.
                    }
                }
            }
            results.sortedByDescending { it.lastModified() }
        }

    internal fun detectFileType(extension: String): FileType = when (extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> FileType.IMAGE
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v" -> FileType.VIDEO
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus" -> FileType.AUDIO
        "pdf", "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "ppt", "pptx", "csv" -> FileType.DOCUMENT
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> FileType.ARCHIVE
        "apk" -> FileType.APK
        else -> FileType.ALL
    }
}

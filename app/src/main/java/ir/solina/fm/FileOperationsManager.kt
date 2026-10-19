package ir.solina.fm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * File transfers run on Dispatchers.IO. A copy is staged to a temporary sibling and
 * promoted only after it completes, so failed/cancelled copies do not leave partial
 * destination files with their final names.
 */
object FileOperationsManager {

    data class ProgressState(
        val currentFileName: String,
        val processedCount: Int,
        val totalCount: Int,
        val percent: Int
    )

    data class OperationResult(
        val successCount: Int,
        val failureCount: Int,
        val errors: List<String>
    )

    suspend fun executeTransfer(
        sources: List<File>,
        destinationDir: File,
        isMove: Boolean,
        onProgress: (ProgressState) -> Unit
    ): OperationResult = withContext(Dispatchers.IO) {
        var successes = 0
        var failures = 0
        val errors = mutableListOf<String>()
        val total = sources.size

        if (!destinationDir.exists() || !destinationDir.isDirectory || !destinationDir.canWrite()) {
            return@withContext OperationResult(
                0, total, listOf("پوشهٔ مقصد وجود ندارد یا قابل نوشتن نیست")
            )
        }

        sources.forEachIndexed { index, src ->
            coroutineContext.ensureActive()
            withContext(Dispatchers.Main) {
                onProgress(
                    ProgressState(
                        currentFileName = src.name,
                        processedCount = index + 1,
                        totalCount = total,
                        percent = if (total == 0) 100 else ((index + 1) * 100 / total)
                    )
                )
            }

            var staged: File? = null
            try {
                coroutineContext.ensureActive()
                if (!src.exists()) throw IllegalStateException("فایل مبدأ پیدا نشد")
                if (src.canonicalFile == destinationDir.canonicalFile) {
                    throw IllegalArgumentException("مبدأ و مقصد یکسان هستند")
                }

                val sourcePath = src.canonicalPath
                val destinationPath = destinationDir.canonicalPath
                if (src.isDirectory &&
                    (destinationPath == sourcePath || destinationPath.startsWith(sourcePath + File.separator))
                ) {
                    throw IllegalArgumentException("نمی‌توان پوشه را در زیرمجموعهٔ خودش کپی کرد")
                }

                val target = getUniqueTarget(destinationDir, src)
                if (isMove && src.renameTo(target)) {
                    successes++
                    return@forEachIndexed
                }

                staged = File(destinationDir, ".solina-${UUID.randomUUID()}.partial")
                copyCancellable(src, staged!!)
                coroutineContext.ensureActive()

                if (!staged!!.exists()) throw IllegalStateException("فایل موقت مقصد ساخته نشد")
                if (target.exists() || !staged!!.renameTo(target)) {
                    throw IllegalStateException("انتقال فایل موقت به مقصد نهایی انجام نشد")
                }
                staged = null

                if (isMove) {
                    val deleted = if (src.isDirectory) src.deleteRecursively() else src.delete()
                    if (!deleted) {
                        failures++
                        errors.add("${src.name}: نسخهٔ مقصد آماده شد، اما حذف مبدأ ناموفق بود؛ هر دو نسخه نگه داشته شدند")
                    } else {
                        successes++
                    }
                } else {
                    successes++
                }
            } catch (cancelled: CancellationException) {
                staged?.let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
                throw cancelled
            } catch (e: Exception) {
                staged?.let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
                failures++
                errors.add("${src.name}: ${e.localizedMessage ?: "عملیات ناموفق بود"}")
            }
        }

        OperationResult(successes, failures, errors)
    }

    private suspend fun copyCancellable(
        source: File,
        target: File,
        visitedDirectories: MutableSet<String> = mutableSetOf()
    ) {
        coroutineContext.ensureActive()
        if (source.isDirectory) {
            val canonical = source.canonicalPath
            if (!visitedDirectories.add(canonical)) {
                throw IllegalStateException("ساختار پوشه شامل پیوند بازگشتی است")
            }
            if (!target.mkdirs() && !target.isDirectory) {
                throw IllegalStateException("ساخت پوشهٔ موقت مقصد ممکن نشد")
            }
            val children = source.listFiles()
                ?: throw IllegalStateException("فهرست پوشهٔ مبدأ قابل خواندن نیست")
            children.forEach { child ->
                coroutineContext.ensureActive()
                copyCancellable(child, File(target, child.name), visitedDirectories)
            }
            target.setLastModified(source.lastModified())
            return
        }

        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("ساخت پوشهٔ مقصد ممکن نشد")
            }
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        target.setLastModified(source.lastModified())
    }

    suspend fun deleteFiles(files: List<File>): OperationResult = withContext(Dispatchers.IO) {
        var successes = 0
        var failures = 0
        val errors = mutableListOf<String>()

        files.forEach { file ->
            coroutineContext.ensureActive()
            try {
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (ok) successes++ else {
                    failures++
                    errors.add("حذف ${file.name} ممکن نشد")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                failures++
                errors.add("${file.name}: ${e.localizedMessage ?: "خطای ناشناخته"}")
            }
        }
        OperationResult(successes, failures, errors)
    }

    fun getUniqueTarget(destDir: File, src: File): File {
        var candidate = File(destDir, src.name)
        if (!candidate.exists()) return candidate

        val baseName = if (src.isDirectory) src.name else src.nameWithoutExtension
        val ext = if (src.isDirectory || src.extension.isEmpty()) "" else ".${src.extension}"
        var counter = 1
        while (candidate.exists()) {
            candidate = File(destDir, "$baseName ($counter)$ext")
            counter++
        }
        return candidate
    }
}

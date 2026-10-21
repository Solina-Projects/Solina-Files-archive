package ir.solina.fm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import ir.solina.fm.nativecore.RustFileEngine

/**
 * مدیر اختصاصی عملیات فایل با پشتیبانی از کاتلین کوروتینز
 * جلوگیری از فریز شدن رابط کاربری و امکان لغو عملیات
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

        if (!destinationDir.isDirectory || !destinationDir.canWrite()) {
            return@withContext OperationResult(
                successCount = 0,
                failureCount = sources.size,
                errors = listOf("پوشهٔ مقصد وجود ندارد یا قابل نوشتن نیست")
            )
        }

        sources.forEachIndexed { index, src ->
            if (!isActive) return@withContext OperationResult(successes, failures, errors)

            val currentProgress = ProgressState(
                currentFileName = src.name,
                processedCount = index + 1,
                totalCount = total,
                percent = (((index + 1).toFloat() / total) * 100).toInt()
            )
            withContext(Dispatchers.Main) { onProgress(currentProgress) }

            try {
                if (!src.exists()) {
                    failures++
                    errors.add("${src.name}: فایل یافت نشد")
                    return@forEachIndexed
                }

                val sourcePath = src.canonicalPath
                val destinationPath = destinationDir.canonicalPath
                if (src.isDirectory && (destinationPath == sourcePath || destinationPath.startsWith(sourcePath + File.separator))) {
                    failures++
                    errors.add("${src.name}: نمی‌توان پوشه را در زیرمجموعه خودش کپی کرد")
                    return@forEachIndexed
                }

                val target = getUniqueTarget(destinationDir, src)

                // Prefer Rust for filesystem work; null means native code is unavailable.
                val nativeResult = if (isMove) {
                    RustFileEngine.movePath(src.absolutePath, target.absolutePath)
                } else {
                    RustFileEngine.copyPath(src.absolutePath, target.absolutePath)
                }
                if (nativeResult != null) {
                    if (nativeResult.success) {
                        successes++
                    } else {
                        failures++
                        errors.add("${src.name}: ${nativeResult.error ?: if (isMove) "انتقال ناموفق بود" else "کپی ناموفق بود"}")
                    }
                    return@forEachIndexed
                }

                if (isMove) {
                    val moved = src.renameTo(target)
                    if (moved) {
                        successes++
                    } else if (src.copyRecursively(target, overwrite = false)) {
                        if (src.deleteRecursively()) {
                            successes++
                        } else {
                            failures++
                            errors.add("${src.name}: فایل کپی شد اما حذف نسخهٔ مبدأ کامل نشد")
                        }
                    } else {
                        failures++
                        errors.add("${src.name}: انتقال ناموفق بود")
                    }
                } else if (src.copyRecursively(target, overwrite = false)) {
                    successes++
                } else {
                    failures++
                    errors.add("${src.name}: کپی ناموفق بود")
                }
            } catch (cancelled: CancellationException) {
                // Cancellation must propagate; treating it as an ordinary file error
                // makes a cancelled transfer continue processing the remaining files.
                throw cancelled
            } catch (e: Exception) {
                failures++
                errors.add("${src.name}: ${e.localizedMessage ?: "خطای ناشناخته"}")
            }
        }

        OperationResult(successes, failures, errors)
    }

    suspend fun deleteFiles(files: List<File>): OperationResult = withContext(Dispatchers.IO) {
        var successes = 0
        var failures = 0
        val errors = mutableListOf<String>()

        files.forEach { file ->
            try {
                val nativeResult = RustFileEngine.deletePath(file.absolutePath)
                if (nativeResult != null) {
                    if (nativeResult.success) successes++ else {
                        failures++
                        errors.add("${file.name}: ${nativeResult.error ?: "حذف ممکن نشد"}")
                    }
                    return@forEach
                }
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (ok) successes++ else {
                    failures++
                    errors.add("حذف ${file.name} ممکن نشد")
                }
            } catch (e: Exception) {
                failures++
                errors.add("${file.name}: ${e.message}")
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

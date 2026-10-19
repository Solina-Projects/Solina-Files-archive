package ir.solina.fm

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Filesystem operations used by pane transfers.
 *
 * This class deliberately has no Android dependencies so its behavior can be tested on the JVM.
 * Methods throw IOException on failures; callers decide how to present errors to users.
 */
open class FileRepository {

    @Throws(IOException::class)
    fun copyFile(srcPath: String, destDirPath: String): File {
        val source = File(srcPath)
        val destination = File(destDirPath)
        validate(source, destination)
        val target = resolveConflict(File(destination, source.name))
        copyEntrySafely(source, target)
        return target
    }

    @Throws(IOException::class)
    fun moveFile(srcPath: String, destDirPath: String): File {
        val source = File(srcPath)
        val destination = File(destDirPath)
        validate(source, destination)
        val target = resolveConflict(File(destination, source.name))

        // A successful rename is atomic on the same filesystem. Never delete the source
        // until a fallback copy has completed successfully.
        if (renameEntry(source, target)) return target

        copyEntrySafely(source, target)
        if (!deleteEntry(source)) {
            target.deleteRecursively()
            throw IOException("Copied destination but could not delete source: ${source.path}")
        }
        return target
    }

    @Throws(IOException::class)
    protected open fun ensureSourceReadable(source: File) {
        if (!source.exists()) {
            throw FileNotFoundException("Source file not found: ${source.path}")
        }
        if (!source.canRead()) {
            throw IOException("Source is not readable: ${source.path}")
        }
    }

    @Throws(IOException::class)
    protected open fun ensureDestinationWritable(destination: File) {
        if (!destination.exists() || !destination.isDirectory) {
            throw IOException("Destination is not a directory: ${destination.path}")
        }
        if (!destination.canWrite()) {
            throw IOException("Destination is not writable: ${destination.path}")
        }
    }

    protected open fun renameEntry(source: File, destination: File): Boolean =
        source.renameTo(destination)

    protected open fun deleteEntry(source: File): Boolean =
        source.deleteRecursively()

    @Throws(IOException::class)
    protected open fun copyEntry(source: File, destination: File) {
        if (source.isDirectory) {
            source.copyRecursively(destination, overwrite = false)
        } else {
            source.copyTo(destination, overwrite = false)
        }
    }

    private fun validate(source: File, destination: File) {
        ensureSourceReadable(source)
        ensureDestinationWritable(destination)
        if (source.canonicalFile == destination.canonicalFile ||
            (source.isDirectory && destination.canonicalFile.toPath()
                .startsWith(source.canonicalFile.toPath()))
        ) {
            throw IOException("Cannot transfer a directory into itself or its descendants")
        }
    }

    private fun copyEntrySafely(source: File, destination: File) {
        try {
            copyEntry(source, destination)
        } catch (error: Exception) {
            // Avoid leaving a partial file/directory behind after failures such as ENOSPC.
            destination.deleteRecursively()
            if (error is IOException) throw error
            throw IOException("Failed to copy ${source.path}: ${error.message}", error)
        }
    }

    private fun resolveConflict(file: File): File {
        if (!file.exists()) return file

        val extension = file.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        val baseName = if (extension.isNotEmpty()) file.name.removeSuffix(extension) else file.name
        var counter = 1
        var candidate: File
        do {
            candidate = File(file.parentFile, "$baseName($counter)$extension")
            counter++
        } while (candidate.exists())
        return candidate
    }
}

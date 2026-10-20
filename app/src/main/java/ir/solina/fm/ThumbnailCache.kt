package ir.solina.fm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small, disk-backed thumbnails for the file browser.
 * All filesystem reads and decoding happen on Dispatchers.IO; decoded results are
 * bounded to a small square and shared through a bounded memory cache.
 */
class ThumbnailCache(context: Context) {
    private val appContext = context.applicationContext
    private val diskDirectory = File(appContext.cacheDir, "thumbs").apply { mkdirs() }
    private val maxDimension = 144
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024L / 8L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    suspend fun get(file: File): Bitmap? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val extension = file.extension.lowercase(Locale.ROOT)
        val supported = file.isDirectory ||
            extension in IMAGE_EXTENSIONS || extension in VIDEO_EXTENSIONS ||
            extension in AUDIO_EXTENSIONS || extension == "pdf"
        if (!supported) return@withContext null

        val cacheKey = keyFor(file)
        synchronized(memory) { memory.get(cacheKey) }?.let { return@withContext it }

        val cachedFile = File(diskDirectory, "$cacheKey.jpg")
        if (cachedFile.isFile && cachedFile.length() > 0L) {
            val cached = decodeSampled(cachedFile, maxDimension, maxDimension)
            if (cached != null) {
                synchronized(memory) { memory.put(cacheKey, cached) }
                return@withContext cached
            }
        }

        val bitmap = runCatching {
            when {
                file.isDirectory -> findFolderImage(file)?.let { decodeSampled(it, maxDimension, maxDimension) }
                extension in IMAGE_EXTENSIONS -> decodeSampled(file, maxDimension, maxDimension)
                extension in VIDEO_EXTENSIONS -> videoFrame(file)
                extension in AUDIO_EXTENSIONS -> audioArtwork(file)
                extension == "pdf" -> pdfFirstPage(file)
                else -> null
            }
        }.getOrNull() ?: return@withContext null

        runCatching {
            FileOutputStream(cachedFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.flush()
            }
        }
        synchronized(memory) { memory.put(cacheKey, bitmap) }
        bitmap
    }

    private fun findFolderImage(folder: File): File? {
        val imageFiles = mutableListOf<File>()
        val children = runCatching { folder.listFiles()?.take(250).orEmpty() }.getOrDefault(emptyList())
        children.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS }
            .take(12).forEach(imageFiles::add)
        if (imageFiles.isEmpty()) {
            children.filter { it.isDirectory }.take(12).forEach { child ->
                runCatching { child.listFiles()?.take(80).orEmpty() }.getOrDefault(emptyList())
                    .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS }
                    .take(2).forEach(imageFiles::add)
            }
        }
        return imageFiles.minByOrNull { it.name.lowercase(Locale.ROOT) }
    }

    private fun decodeSampled(file: File, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun videoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { source ->
                val ratio = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
                if (ratio >= 1f) source else Bitmap.createScaledBitmap(
                    source, (source.width * ratio).toInt().coerceAtLeast(1),
                    (source.height * ratio).toInt().coerceAtLeast(1), true
                ).also { if (it !== source) source.recycle() }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun audioArtwork(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDimension && bounds.outHeight / (sample * 2) >= maxDimension) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun pdfFirstPage(file: File): Bitmap? {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val ratio = minOf(1f, maxDimension.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1))
                val bitmap = Bitmap.createBitmap(
                    (page.width * ratio).toInt().coerceAtLeast(1),
                    (page.height * ratio).toInt().coerceAtLeast(1),
                    Bitmap.Config.RGB_565
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    private fun keyFor(file: File): String {
        val signature = "${file.absolutePath}| ${file.length()}|${file.lastModified()}".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(signature)
            .take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff", "avif", "dng")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "mpeg", "mpg", "m4v", "wmv", "flv", "ts", "mts")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mid", "midi", "wma", "amr", "aiff")
    }
}

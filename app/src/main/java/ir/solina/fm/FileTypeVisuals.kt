package ir.solina.fm

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

/**
 * Single source of truth for file-type artwork and semantic colors.
 * Colors resolve through resources so the same icons remain legible in light and dark mode.
 */
object FileTypeVisuals {
    data class Visual(val iconRes: Int, val tintRes: Int)

    fun forFile(file: File): Visual {
        if (file.isDirectory) return Visual(R.drawable.ic_folder, R.color.solina_primary)
        return when (file.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "tif", "tiff", "avif", "raw", "dng" ->
                Visual(R.drawable.ic_file_image, R.color.solina_file_image)
            "mp3", "wav", "ogg", "oga", "m4a", "flac", "aac", "opus", "mid", "midi", "wma", "amr", "aiff" ->
                Visual(R.drawable.ic_file_audio, R.color.solina_file_audio)
            "mp4", "mkv", "mov", "avi", "webm", "3gp", "mpeg", "mpg", "m4v", "wmv", "flv", "ts", "mts" ->
                Visual(R.drawable.ic_file_video, R.color.solina_file_video)
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz2", "txz", "iso", "cab", "jar", "war" ->
                Visual(R.drawable.ic_file_archive, R.color.solina_file_archive)
            "apk" -> Visual(R.drawable.ic_file_apk, R.color.solina_file_apk)
            "pdf", "doc", "docx", "docm", "odt", "rtf", "xls", "xlsx", "xlsm", "ods", "ppt", "pptx", "pptm", "odp", "epub", "pages", "numbers", "key" ->
                Visual(R.drawable.ic_file_document, R.color.solina_file_document)
            "txt", "md", "log", "json", "xml", "csv", "tsv", "ini", "kt", "java", "smali",
            "properties", "html", "htm", "css", "js", "gradle", "kts", "yaml", "yml", "sh",
            "sql", "py", "c", "cpp", "h", "rs", "cfg", "conf" ->
                Visual(R.drawable.ic_file_code, R.color.solina_file_code)
            else -> Visual(R.drawable.ic_file, R.color.solina_file_generic)
        }
    }

    fun applyTo(image: ImageView, file: File) {
        val visual = forFile(file)
        image.setImageResource(visual.iconRes)
        image.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(image.context, visual.tintRes)
        )
    }
}

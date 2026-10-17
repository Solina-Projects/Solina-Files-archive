package ir.solina.fm

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.InputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import com.github.junrar.Junrar

/** Archive helper for ZIP, 7z, RAR, TAR and common compressed TAR streams. */
class ArchiveToolsActivity : AppCompatActivity() {
    private lateinit var file: File
    private lateinit var report: TextView
    private lateinit var extractButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("archive_path")
        if (path.isNullOrBlank()) { finish(); return }
        file = File(path)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12) }
        root.addView(TextView(this).apply { text = file.name; textSize = 20f })
        report = TextView(this).apply { text = "در حال بررسی آرشیو…"; setTextIsSelectable(true); setPadding(0, 12, 0, 12) }
        val scroll = ScrollView(this); scroll.addView(report)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        extractButton = Button(this).apply { text = "استخراج همه در پوشه جدید"; setOnClickListener { extractAll() } }
        root.addView(extractButton)
        root.addView(Button(this).apply { text = "بستن"; setOnClickListener { finish() } })
        setContentView(root)
        Thread { val summary = inspect(); runOnUiThread { report.text = summary } }.start()
    }

    private fun inspect(): String = try {
        when (file.extension.lowercase()) {
            "7z" -> {
                val out = StringBuilder("محتوای آرشیو 7z:\n")
                SevenZFile(file).use { archive ->
                    var count = 0
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        out.append(if (entry.isDirectory) "📁 " else "📄 ").append(entry.name).append('\n')
                        count++
                        if (count >= 500) { out.append("… نمایش به ۵۰۰ مورد محدود شد"); break }
                    }
                }
                out.toString()
            }
            "rar" -> "فایل RAR شناسایی شد. استخراج امن به پوشهٔ جداگانه در دسترس است. فایل‌های چندبخشی یا آرشیو آسیب‌دیده ممکن است نیاز به ابزار تخصصی داشته باشند."
            "zip" -> ZipFile(file).fileHeaders.joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.fileName }
            else -> if (isTarLike(file.name)) {
                val out = StringBuilder("محتوای TAR:\n")
                openTarInput().use { archive ->
                    var count = 0
                    while (true) {
                        val entry = archive.nextTarEntry ?: break
                        out.append(if (entry.isDirectory) "📁 " else "📄 ").append(entry.name).append('\n')
                        count++
                        if (count >= 500) { out.append("… نمایش به ۵۰۰ مورد محدود شد"); break }
                    }
                }
                out.toString()
            } else if (isSingleCompressed(file.name)) {
                "فایل فشردهٔ تکی شناسایی شد. با استخراج، نسخهٔ بازشده در پوشه‌ای جداگانه ذخیره می‌شود."
            } else "این قالب آرشیو در ابزار داخلی شناخته نشد."
        }
    } catch (e: Exception) { "خواندن آرشیو ناموفق بود: ${e.message}" }

    private fun extractAll() {
        val target = File(file.parentFile ?: filesDir, file.nameWithoutExtension + "-extracted")
        if (target.exists()) {
            AlertDialog.Builder(this).setMessage("پوشه مقصد از قبل وجود دارد. استخراج با نام دیگری انجام شود؟")
                .setNegativeButton("لغو", null).setPositiveButton("ادامه") { _, _ -> extractInto(File(file.parentFile ?: filesDir, file.nameWithoutExtension + "-extracted-" + System.currentTimeMillis())) { extractTo(it) } }.show()
        } else extractInto(target) { extractTo(it) }
    }

    private fun extractInto(target: File, work: (File) -> Unit) {
        extractButton.isEnabled = false
        report.text = "در حال استخراج…"
        Thread {
            var error: String? = null
            try { target.mkdirs(); work(target) } catch (e: Exception) { error = e.message ?: "استخراج ناموفق بود" }
            runOnUiThread {
                extractButton.isEnabled = true
                report.text = if (error == null) "استخراج انجام شد:\n${target.absolutePath}" else "استخراج ناموفق بود: $error"
                toast(if (error == null) "استخراج کامل شد" else "استخراج ناموفق بود")
            }
        }.start()
    }

    private fun extractTo(target: File) {
        when {
            isTarLike(file.name) -> openTarInput().use { archive ->
                while (true) {
                    val entry = archive.nextTarEntry ?: break
                    val out = safeTarget(target, entry.name)
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { output -> archive.copyTo(output) }
                    }
                }
            }
            isSingleCompressed(file.name) -> extractSingleCompressed(target)
            file.extension.equals("7z", true) -> SevenZFile(file).use { archive ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val out = safeTarget(target, entry.name)
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { output ->
                            while (true) {
                                val n = archive.read(buffer)
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
            file.extension.equals("rar", true) -> Junrar.extract(file, target)
            file.extension.equals("zip", true) -> ZipFile(file).extractAll(target.absolutePath)
            else -> throw IllegalArgumentException("قالب پشتیبانی نمی‌شود")
        }
    }

    private fun isTarLike(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".tar") || n.endsWith(".tar.gz") || n.endsWith(".tgz") ||
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tar.xz") || n.endsWith(".txz")
    }

    private fun isSingleCompressed(name: String): Boolean {
        val n = name.lowercase()
        return !isTarLike(n) && (n.endsWith(".gz") || n.endsWith(".bz2") || n.endsWith(".xz"))
    }

    private fun openTarInput(): TarArchiveInputStream {
        var input: InputStream = BufferedInputStream(file.inputStream())
        val name = file.name.lowercase()
        input = when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GzipCompressorInputStream(input)
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> BZip2CompressorInputStream(input)
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> XZCompressorInputStream(input)
            else -> input
        }
        return TarArchiveInputStream(input)
    }

    private fun extractSingleCompressed(target: File) {
        val name = file.name
        val outputName = when {
            name.endsWith(".bz2", true) -> name.dropLast(4)
            name.endsWith(".gz", true) -> name.dropLast(3)
            name.endsWith(".xz", true) -> name.dropLast(3)
            else -> file.nameWithoutExtension
        }.ifBlank { "decompressed-file" }
        val output = safeTarget(target, outputName)
        output.parentFile?.mkdirs()
        val raw = BufferedInputStream(file.inputStream())
        val decoded: InputStream = when {
            name.endsWith(".bz2", true) -> BZip2CompressorInputStream(raw)
            name.endsWith(".gz", true) -> GzipCompressorInputStream(raw)
            name.endsWith(".xz", true) -> XZCompressorInputStream(raw)
            else -> raw
        }
        decoded.use { input -> FileOutputStream(output).use { input.copyTo(it) } }
    }

    private fun safeTarget(root: File, entry: String): File {
        val out = File(root, entry)
        val base = root.canonicalPath + File.separator
        if (!out.canonicalPath.startsWith(base)) throw SecurityException("مسیر نامعتبر در آرشیو")
        return out
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

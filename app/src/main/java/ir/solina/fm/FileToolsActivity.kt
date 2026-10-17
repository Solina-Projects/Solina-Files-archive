package ir.solina.fm

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Extra file utilities added without changing the main explorer layout.
 * All operations work inside the currently opened directory and use explicit user choices.
 */
class FileToolsActivity : AppCompatActivity() {
    private lateinit var rootDir: File
    private lateinit var output: TextView
    private var initialSelection: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootDir = File(intent.getStringExtra("root_path") ?: android.os.Environment.getExternalStorageDirectory().absolutePath)
        if (!rootDir.isDirectory) rootDir = android.os.Environment.getExternalStorageDirectory()
        initialSelection = intent.getStringArrayListExtra("selected_paths").orEmpty().map(::File).filter { it.exists() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "ابزارهای تکمیلی فایل"
            textSize = 21f
        })
        root.addView(TextView(this).apply {
            text = "پوشهٔ کاری: ${rootDir.absolutePath}"
            textSize = 12f
            setPadding(0, dp(6), 0, dp(12))
        })
        addAction(root, "تغییر نام گروهی", "تغییر نام چند فایل با پیشوند، پسوند و جایگزینی متن") { batchRename() }
        addAction(root, "تقسیم فایل بزرگ", "تقسیم فایل به قطعه‌های قابل ادغام مجدد") { splitFile() }
        addAction(root, "ادغام قطعه‌ها", "ادغام قطعه‌های انتخاب‌شده به یک فایل") { mergeParts() }
        addAction(root, "مقایسهٔ دو فایل", "مقایسهٔ اندازه، SHA-256 و محتوای فایل‌ها") { compareFiles() }
        addAction(root, "جست‌وجو در محتوای فایل‌ها", "جست‌وجوی متن داخل فایل‌های متنی زیرپوشه‌ها") { searchContents() }
        output = TextView(this).apply {
            text = "گزارش عملیات در این قسمت نمایش داده می‌شود."
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        }
        val scroll = ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "بستن"; setOnClickListener { finish() } })
        setContentView(root)
    }

    private fun addAction(parent: LinearLayout, title: String, description: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = "$title\n$description"
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun directFiles(): List<File> = rootDir.listFiles()
        ?.filter { it.isFile && it.canRead() }
        ?.sortedBy { it.name.lowercase(Locale.ROOT) }
        ?: emptyList()

    private fun chooseFiles(title: String, multiple: Boolean, callback: (List<File>) -> Unit) {
        val files = directFiles()
        if (files.isEmpty()) {
            toast("در پوشهٔ فعلی فایل قابل‌خواندن وجود ندارد")
            return
        }
        val labels = files.map { it.name }.toTypedArray()
        if (!multiple) {
            AlertDialog.Builder(this).setTitle(title).setItems(labels) { _, which -> callback(listOf(files[which])) }
                .setNegativeButton("لغو", null).show()
            return
        }
        val checked = BooleanArray(files.size) { files[it] in initialSelection }
        AlertDialog.Builder(this).setTitle(title).setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setPositiveButton("ادامه") { _, _ -> callback(files.filterIndexed { index, _ -> checked[index] }) }
            .setNegativeButton("لغو", null).show()
    }

    private fun batchRename() {
        val selected = initialSelection.filter { it.exists() && it.isFile }
        if (selected.isNotEmpty()) {
            showRenameForm(selected)
        } else {
            chooseFiles("انتخاب فایل‌ها برای تغییر نام گروهی", true) { files ->
                if (files.isEmpty()) toast("هیچ فایلی انتخاب نشد") else showRenameForm(files)
            }
        }
    }

    private fun showRenameForm(files: List<File>) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), 0) }
        val prefix = EditText(this).apply { hint = "پیشوند (اختیاری)"; setSingleLine(true) }
        val suffix = EditText(this).apply { hint = "پسوند نام، قبل از پسوند فایل"; setSingleLine(true) }
        val find = EditText(this).apply { hint = "متن برای جایگزینی (اختیاری)"; setSingleLine(true) }
        val replacement = EditText(this).apply { hint = "جایگزین با"; setSingleLine(true) }
        form.addView(prefix); form.addView(suffix); form.addView(find); form.addView(replacement)
        AlertDialog.Builder(this).setTitle("تغییر نام ${files.size} فایل").setView(form)
            .setMessage("پسوند اصلی فایل حفظ می‌شود. نام‌های تکراری نادیده گرفته خواهند شد.")
            .setPositiveButton("اجرای تغییر نام") { _, _ ->
                val pre = prefix.text.toString()
                val suf = suffix.text.toString()
                val needle = find.text.toString()
                val repl = replacement.text.toString()
                var renamed = 0
                val errors = mutableListOf<String>()
                files.forEachIndexed { index, file ->
                    val stem = file.nameWithoutExtension
                    val changed = (if (needle.isNotEmpty()) stem.replace(needle, repl) else stem)
                    val newName = pre + changed + suf + if (file.extension.isNotEmpty()) ".${file.extension}" else ""
                    val target = File(file.parentFile, newName)
                    if (target == file) return@forEachIndexed
                    if (target.exists()) errors.add("${file.name}: نام مقصد وجود دارد")
                    else if (file.renameTo(target)) renamed++ else errors.add("${file.name}: تغییر نام ناموفق")
                }
                output.text = "تغییر نام موفق: $renamed از ${files.size}" + if (errors.isEmpty()) "" else "\n\n" + errors.joinToString("\n")
                toast("تغییر نام گروهی تمام شد: $renamed فایل")
            }.setNegativeButton("لغو", null).show()
    }

    private fun splitFile() {
        chooseFiles("انتخاب فایل برای تقسیم", false) { chosen ->
            val file = chosen.first()
            val sizeInput = EditText(this).apply {
                hint = "اندازهٔ هر قطعه به مگابایت"
                setText("100")
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            AlertDialog.Builder(this).setTitle("تقسیم ${file.name}").setView(sizeInput)
                .setMessage("قطعه‌ها در همین پوشه با پسوند .part001، .part002 و ... ساخته می‌شوند.")
                .setPositiveButton("شروع") { _, _ ->
                    val mb = sizeInput.text.toString().toLongOrNull()
                    if (mb == null || mb !in 1L..4096L) {
                        toast("اندازه باید بین ۱ تا ۴۰۹۶ مگابایت باشد")
                        return@setPositiveButton
                    }
                    runWorker("تقسیم فایل") {
                        val chunkSize = mb * 1024L * 1024L
                        var part = 1
                        var total = 0L
                        FileInputStream(file).buffered().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var remaining = 0L
                            var out: FileOutputStream? = null
                            try {
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    var offset = 0
                                    while (offset < count) {
                                        if (out == null || remaining == 0L) {
                                            out?.close()
                                            val target = File(file.parentFile, file.name + ".part" + part.toString().padStart(3, '0'))
                                            if (target.exists()) throw IllegalStateException("فایل مقصد از قبل وجود دارد: ${target.name}")
                                            out = FileOutputStream(target)
                                            remaining = chunkSize
                                            part++
                                        }
                                        val writeCount = minOf((count - offset).toLong(), remaining).toInt()
                                        out.write(buffer, offset, writeCount)
                                        offset += writeCount
                                        remaining -= writeCount
                                        total += writeCount
                                    }
                                }
                            } finally { out?.close() }
                        }
                        "تقسیم انجام شد. حجم پردازش‌شده: ${android.text.format.Formatter.formatFileSize(this, total)}\nقطعه‌ها با نام ${file.name}.part001 و شماره‌های بعدی ساخته شدند."
                    }
                }.setNegativeButton("لغو", null).show()
        }
    }

    private fun mergeParts() {
        chooseFiles("انتخاب قطعه‌ها به ترتیب نام", true) { chosen ->
            val parts = chosen.sortedBy { it.name.lowercase(Locale.ROOT) }
            if (parts.size < 2 || parts.any { !Regex(".*\\.part\\d+$", RegexOption.IGNORE_CASE).matches(it.name) }) {
                toast("حداقل دو فایل با پسوند .part001، .part002 و ... انتخاب کن")
                return@chooseFiles
            }
            val prefix = parts.first().name.replace(Regex("\\.part\\d+$", RegexOption.IGNORE_CASE), "")
            if (parts.any { !it.name.startsWith(prefix, ignoreCase = true) }) {
                toast("همهٔ قطعه‌ها باید متعلق به یک فایل باشند")
                return@chooseFiles
            }
            val target = File(rootDir, prefix)
            if (target.exists()) {
                toast("فایل مقصد از قبل وجود دارد: ${target.name}")
                return@chooseFiles
            }
            AlertDialog.Builder(this).setTitle("ادغام قطعه‌ها")
                .setMessage("${parts.size} قطعه به فایل ${target.name} ادغام می‌شوند. ترتیب بر اساس نام است.")
                .setPositiveButton("ادغام") { _, _ ->
                    runWorker("ادغام قطعه‌ها") {
                        FileOutputStream(target).buffered().use { out ->
                            parts.forEach { part -> FileInputStream(part).buffered().use { it.copyTo(out) } }
                        }
                        "ادغام کامل شد: ${target.name}\nحجم: ${android.text.format.Formatter.formatFileSize(this, target.length())}"
                    }
                }.setNegativeButton("لغو", null).show()
        }
    }

    private fun compareFiles() {
        chooseFiles("انتخاب دو فایل برای مقایسه", true) { chosen ->
            if (chosen.size != 2) {
                toast("برای مقایسه دقیقاً دو فایل انتخاب کن")
                return@chooseFiles
            }
            runWorker("مقایسه فایل‌ها") {
                val a = chosen[0]
                val b = chosen[1]
                val sameHash = sha256(a) == sha256(b)
                val sameBytes = if (a.length() != b.length()) false else a.inputStream().buffered().use { ia ->
                    b.inputStream().buffered().use { ib ->
                        val ba = ByteArray(8192); val bb = ByteArray(8192)
                        var equal = true
                        while (true) {
                            val na = ia.read(ba); val nb = ib.read(bb)
                            if (na != nb) { equal = false; break }
                            if (na < 0) break
                            if (!ba.copyOf(na).contentEquals(bb.copyOf(nb))) { equal = false; break }
                        }
                        equal
                    }
                }
                "فایل اول: ${a.name}\nحجم: ${android.text.format.Formatter.formatFileSize(this, a.length())}\nSHA-256: ${sha256(a)}\n\nفایل دوم: ${b.name}\nحجم: ${android.text.format.Formatter.formatFileSize(this, b.length())}\nSHA-256: ${sha256(b)}\n\nنتیجه: " + if (sameBytes && sameHash) "دو فایل کاملاً یکسان‌اند." else "محتوای دو فایل متفاوت است."
            }
        }
    }

    private fun searchContents() {
        val queryInput = EditText(this).apply { hint = "متن مورد جست‌وجو"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("جست‌وجو در محتوای فایل‌ها").setView(queryInput)
            .setMessage("فایل‌های متنی تا ۲ مگابایت در پوشهٔ فعلی و زیرپوشه‌ها بررسی می‌شوند.")
            .setPositiveButton("جست‌وجو") { _, _ ->
                val query = queryInput.text.toString()
                if (query.isBlank()) { toast("عبارت جست‌وجو را وارد کن"); return@setPositiveButton }
                runWorker("جست‌وجوی محتوا") {
                    val allowed = setOf("txt", "md", "log", "json", "xml", "csv", "tsv", "ini", "kt", "java", "html", "htm", "css", "js", "gradle", "kts", "yml", "yaml", "sh", "properties", "conf", "cfg", "sql", "py", "c", "cpp", "h", "rs")
                    val hits = mutableListOf<String>()
                    var scanned = 0
                    rootDir.walkTopDown().onEnter { !it.name.startsWith(".") }.forEach { file ->
                        if (file.isFile && file.extension.lowercase(Locale.ROOT) in allowed && file.length() <= 2L * 1024 * 1024) {
                            try {
                                scanned++
                                file.useLines(Charsets.UTF_8) { lines ->
                                    lines.forEachIndexed { index, line ->
                                        if (line.contains(query, ignoreCase = true) && hits.size < 200) hits.add("${file.absolutePath}:${index + 1}: ${line.trim().take(180)}")
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    "عبارت: $query\nفایل‌های بررسی‌شده: $scanned\nنتیجه‌ها: ${hits.size}\n\n" + if (hits.isEmpty()) "موردی پیدا نشد." else hits.joinToString("\n")
                }
            }.setNegativeButton("لغو", null).show()
    }

    private fun runWorker(label: String, work: () -> String) {
        output.text = "$label در حال اجراست…"
        Thread {
            val result = try { work() } catch (e: Exception) { "عملیات ناموفق بود: ${e.localizedMessage ?: e.javaClass.simpleName}" }
            runOnUiThread { output.text = result; toast("$label پایان یافت") }
        }.start()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

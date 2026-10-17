package ir.solina.fm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.Base64

/** Root-only file browser. All filesystem commands are executed through su after the root manager grants access. */
class RootExplorerActivity : AppCompatActivity() {
    private lateinit var pathText: TextView
    private lateinit var rows: LinearLayout
    private var currentPath = "/"
    private var rootAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 10) }
        root.addView(TextView(this).apply { text = "Root Explorer"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "هشدار: تغییر یا حذف فایل‌های سیستمی ممکن است باعث بوت‌نشدن دستگاه یا ازکارافتادن برنامه‌ها شود."
            setTextColor(0xFFB3261E.toInt()); setPadding(0, 6, 0, 10)
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Button(this).apply { text = "بالا"; setOnClickListener { currentPath = File(currentPath).parent ?: "/"; loadPath() } })
        bar.addView(Button(this).apply { text = "تازه‌سازی"; setOnClickListener { loadPath() } })
        bar.addView(Button(this).apply { text = "پوشه جدید"; setOnClickListener { createFolder() } })
        root.addView(bar)
        pathText = TextView(this).apply { textSize = 12f; setPadding(4, 8, 4, 8) }
        root.addView(pathText)
        val scroll = ScrollView(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Thread {
            rootAvailable = runRoot("id -u").trim() == "0"
            runOnUiThread {
                if (!rootAvailable) {
                    AlertDialog.Builder(this).setTitle("دسترسی Root در دسترس نیست")
                        .setMessage("این قابلیت به دستگاه روت‌شده و برنامهٔ مدیریت روت نیاز دارد. درخواست دسترسی را در پنجرهٔ su تأیید کن.")
                        .setPositiveButton("تلاش دوباره") { _, _ -> checkRoot() }
                        .setNegativeButton("بستن") { _, _ -> finish() }.show()
                } else loadPath()
            }
        }.start()
    }

    private fun checkRoot() {
        Thread {
            rootAvailable = runRoot("id -u").trim() == "0"
            runOnUiThread { if (rootAvailable) loadPath() else toast("دسترسی روت تأیید نشد") }
        }.start()
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun runRoot(command: String): String {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            if (code == 0) text else ""
        } catch (_: Exception) { "" }
    }

    private fun loadPath() {
        pathText.text = currentPath
        rows.removeAllViews()
        if (!rootAvailable) { rows.addView(TextView(this).apply { text = "Root در دسترس نیست" }); return }
        rows.addView(TextView(this).apply { text = "در حال خواندن فایل‌ها…"; setPadding(8, 16, 8, 16) })
        Thread {
            val p = shellQuote(currentPath)
            val command = "p=$p; for f in \"\$p\"/* \"\$p\"/.[!.]* \"\$p\"/..?*; do [ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; if [ -d \"\$f\" ]; then t=D; else t=F; fi; printf '%s\\t%s\\n' \"\$t\" \"\$f\"; done"
            val output = runRoot(command)
            val entries = output.lineSequence().mapNotNull { line ->
                val split = line.split("\t", limit = 2)
                if (split.size == 2) Pair(split[0] == "D", split[1]) else null
            }.sortedWith(compareByDescending<Pair<Boolean, String>> { it.first }.thenBy { File(it.second).name.lowercase() }).toList()
            runOnUiThread {
                rows.removeAllViews()
                if (entries.isEmpty()) rows.addView(TextView(this).apply { text = "پوشه خالی است یا دسترسی خواندن وجود ندارد."; setPadding(8, 18, 8, 18) })
                entries.forEach { (isDir, path) ->
                    val file = File(path)
                    val row = TextView(this).apply {
                        text = (if (isDir) "📁  " else "📄  ") + file.name
                        textSize = 15f; setPadding(10, 13, 10, 13)
                        setOnClickListener { if (isDir) { currentPath = path; loadPath() } else showFileActions(path) }
                        setOnLongClickListener { showFileActions(path); true }
                    }
                    rows.addView(row)
                    rows.addView(View(this).apply { setBackgroundColor(0x22333333); layoutParams = LinearLayout.LayoutParams(-1, 1) })
                }
            }
        }.start()
    }

    private fun showFileActions(path: String) {
        val file = File(path)
        val options = if (runRoot("[ -d ${shellQuote(path)} ] && echo D").trim() == "D")
            arrayOf("بازکردن پوشه", "حذف دائمی")
        else arrayOf("بازکردن/ویرایش متن", "کپی به حافظه مشترک", "حذف دائمی")
        AlertDialog.Builder(this).setTitle(file.name).setItems(options) { _, which ->
            when (options[which]) {
                "بازکردن پوشه" -> { currentPath = path; loadPath() }
                "بازکردن/ویرایش متن" -> editRootText(path)
                "کپی به حافظه مشترک" -> exportRootFile(path)
                "حذف دائمی" -> AlertDialog.Builder(this).setMessage("این مورد به‌صورت دائمی با دسترسی روت حذف شود؟")
                    .setNegativeButton("لغو", null).setPositiveButton("حذف") { _, _ ->
                        Thread { val ok = runRoot("rm -rf -- ${shellQuote(path)}").isNotEmpty() || runRoot("[ ! -e ${shellQuote(path)} ] && echo OK").trim() == "OK"
                            runOnUiThread { toast(if (ok) "حذف شد" else "حذف ناموفق بود"); loadPath() }
                        }.start()
                    }.show()
            }
        }.show()
    }

    private fun createFolder() {
        val input = EditText(this).apply { hint = "نام پوشه" }
        AlertDialog.Builder(this).setTitle("ساخت پوشه با Root").setView(input)
            .setNegativeButton("لغو", null).setPositiveButton("ساخت") { _, _ ->
                val target = File(currentPath, input.text.toString()).absolutePath
                Thread { val result = runRoot("mkdir -- ${shellQuote(target)} && echo OK")
                    runOnUiThread { toast(if (result.trim() == "OK") "پوشه ساخته شد" else "ساخت پوشه ناموفق بود"); loadPath() }
                }.start()
            }.show()
    }

    private fun editRootText(path: String) {
        Thread {
            val raw = runRoot("cat -- ${shellQuote(path)}")
            runOnUiThread {
                if (raw.length > 2 * 1024 * 1024) { toast("ویرایش فایل‌های بیشتر از ۲ مگابایت پشتیبانی نمی‌شود"); return@runOnUiThread }
                val input = EditText(this).apply { setText(raw); gravity = Gravity.TOP or Gravity.START; minLines = 12 }
                AlertDialog.Builder(this).setTitle("ویرایش: ${File(path).name}").setView(input)
                    .setNegativeButton("لغو", null).setPositiveButton("ذخیره با Root") { _, _ ->
                        val encoded = Base64.getEncoder().encodeToString(input.text.toString().toByteArray(Charsets.UTF_8))
                        Thread {
                            val result = runRoot("printf %s ${shellQuote(encoded)} | base64 -d > ${shellQuote(path)} && echo OK")
                            runOnUiThread { toast(if (result.trim() == "OK") "ذخیره شد" else "ذخیره ناموفق بود") }
                        }.start()
                    }.show()
            }
        }.start()
    }

    private fun exportRootFile(path: String) {
        Thread {
            val exportDir = File(getExternalFilesDir(null), "RootExports").apply { mkdirs() }
            val target = File(exportDir, File(path).name)
            val result = runRoot("cat -- ${shellQuote(path)} | base64 | tr -d '\\n'").trim()
            val decoded = try { Base64.getDecoder().decode(result) } catch (_: Exception) { null }
            val ok = decoded != null && try { target.writeBytes(decoded); true } catch (_: Exception) { false }
            runOnUiThread { toast(if (ok) "نسخه کپی شد: RootExports/${target.name}" else "کپی ناموفق بود") }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

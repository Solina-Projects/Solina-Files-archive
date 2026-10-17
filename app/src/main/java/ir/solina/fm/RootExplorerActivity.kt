package ir.solina.fm

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Environment
import android.view.Gravity
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Base64
import rikka.shizuku.Shizuku

/** Root/Shizuku file browser. Root uses su; the optional Shizuku backend runs with shell-level privileges. */
class RootExplorerActivity : AppCompatActivity() {
    private lateinit var pathText: TextView
    private lateinit var rows: LinearLayout
    private var currentPath = "/"
    private var rootAvailable = false
    private var shizukuAvailable = false
    private var shizukuBound = false
    private var shizukuService: IUserService? = null
    private val shizukuArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellUserService::class.java.name))
            .daemon(false).processNameSuffix("shell").debuggable(false)
    }
    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            shizukuService = IUserService.Stub.asInterface(binder)
            shizukuAvailable = shizukuService != null
            runOnUiThread {
                if (shizukuAvailable) loadPath()
                else toast("اتصال به سرویس Shizuku ناموفق بود")
            }
        }
        override fun onServiceDisconnected(componentName: ComponentName) {
            shizukuService = null
            shizukuAvailable = false
        }
    }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 4102) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) checkAccess()
            else toast("مجوز Shizuku داده نشد")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "مدیریت فایل با دسترسی ریشه"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "هشدار: تغییر یا حذف فایل‌های سیستمی ممکن است باعث بوت‌نشدن دستگاه یا ازکارافتادن برنامه‌ها شود."
            setTextColor(ContextCompat.getColor(this@RootExplorerActivity, R.color.solina_error)); setPadding(0, dp(6), 0, dp(10))
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(MaterialButton(this).apply { text = "بالا"; setOnClickListener { currentPath = File(currentPath).parent ?: "/"; loadPath() } })
        bar.addView(MaterialButton(this).apply { text = "تازه‌سازی"; setOnClickListener { loadPath() } })
        bar.addView(MaterialButton(this).apply { text = "پوشه جدید"; setOnClickListener { createFolder() } })
        root.addView(bar)
        pathText = TextView(this).apply { textSize = 12f; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        root.addView(pathText)
        val scroll = ScrollView(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        checkAccess()
    }

    private fun checkAccess() {
        Thread {
            rootAvailable = runSu("id -u").trim() == "0"
            val shizukuGranted = !rootAvailable && try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
            runOnUiThread {
                when {
                    rootAvailable -> loadPath()
                    shizukuGranted -> bindShizukuService()
                    (try { Shizuku.pingBinder() } catch (_: Exception) { false }) -> MaterialAlertDialogBuilder(this)
                        .setTitle("اجازهٔ Shizuku لازم است")
                        .setMessage("دسترسی su پیدا نشد. می‌توانی مجوز Shizuku را بدهی؛ این دسترسی معمولاً سطح shell دارد و جایگزین روت کامل نیست.")
                        .setPositiveButton("درخواست مجوز") { _, _ ->
                            try { Shizuku.requestPermission(4102) } catch (_: Exception) { toast("Shizuku در دسترس نیست") }
                        }
                        .setNegativeButton("تلاش دوباره با Root") { _, _ -> checkAccess() }
                        .setNeutralButton("بستن") { _, _ -> finish() }.show()
                    else -> MaterialAlertDialogBuilder(this).setTitle("دسترسی Root/Shizuku در دسترس نیست")
                        .setMessage("این قابلیت به دستگاه روت‌شده با مدیر su یا برنامهٔ Shizuku فعال و مجاز نیاز دارد.")
                        .setPositiveButton("تلاش دوباره") { _, _ -> checkAccess() }
                        .setNegativeButton("بستن") { _, _ -> finish() }.show()
                }
            }
        }.start()
    }

    private fun bindShizukuService() {
        try {
            Shizuku.bindUserService(shizukuArgs, shizukuConnection)
            shizukuBound = true
        } catch (e: Exception) {
            toast("اتصال Shizuku ناموفق بود: " + (e.message ?: "خطا"))
        }
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun runSu(command: String): String {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            if (code == 0) text else ""
        } catch (_: Exception) { "" }
    }

    private fun runRoot(command: String): String {
        if (rootAvailable || !shizukuAvailable) return runSu(command)
        return try {
            val result = shizukuService?.exec(command) ?: return ""
            if (result.startsWith("__SOLINA_ERROR__:")) "" else result
        } catch (_: Exception) { "" }
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) } catch (_: Exception) {}
        try { if (shizukuBound) Shizuku.unbindUserService(shizukuArgs, shizukuConnection, true) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun loadPath() {
        pathText.text = currentPath + "  •  " + if (rootAvailable) "Root" else "Shizuku (سطح shell)"
        rows.removeAllViews()
        if (!rootAvailable && !shizukuAvailable) { rows.addView(TextView(this).apply { text = "Root/Shizuku در دسترس نیست" }); return }
        rows.addView(TextView(this).apply { text = "در حال خواندن فایل‌ها…"; setPadding(dp(8), dp(16), dp(8), dp(16)) })
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
                if (entries.isEmpty()) rows.addView(TextView(this).apply { text = "پوشه خالی است یا دسترسی خواندن وجود ندارد."; setPadding(dp(8), dp(18), dp(8), dp(18)) })
                entries.forEach { (isDir, path) ->
                    val file = File(path)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@RootExplorerActivity, R.drawable.item_bg)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { if (isDir) { currentPath = path; loadPath() } else showFileActions(path) }
                        setOnLongClickListener { showFileActions(path); true }
                    }
                    val icon = ImageView(this).apply {
                        if (isDir) {
                            setImageResource(R.drawable.ic_folder)
                            imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@RootExplorerActivity, R.color.solina_primary))
                        } else FileTypeVisuals.applyTo(this, file)
                        background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@RootExplorerActivity, R.drawable.icon_bg)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        contentDescription = null
                    }
                    row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
                    row.addView(TextView(this).apply {
                        text = file.name
                        textSize = 15f
                        setTextColor(ContextCompat.getColor(this@RootExplorerActivity, R.color.solina_on_surface))
                        maxLines = 2
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
                }
            }
        }.start()
    }

    private fun showFileActions(path: String) {
        val file = File(path)
        val isDirectory = runRoot("[ -d ${shellQuote(path)} ] && echo D").trim() == "D"
        val options = if (isDirectory)
            arrayOf("بازکردن پوشه", "کپی/انتقال به مسیر…", "تغییر مجوزها (chmod)", "حذف دائمی")
        else arrayOf("بازکردن/ویرایش متن", "کپی به حافظه مشترک", "کپی/انتقال به مسیر…", "تغییر مجوزها (chmod)", "حذف دائمی")
        MaterialAlertDialogBuilder(this).setTitle(file.name.ifBlank { path }).setItems(options) { _, which ->
            when (options[which]) {
                "بازکردن پوشه" -> { currentPath = path; loadPath() }
                "بازکردن/ویرایش متن" -> editRootText(path)
                "کپی به حافظه مشترک" -> exportRootFile(path)
                "کپی/انتقال به مسیر…" -> askDestination(path)
                "تغییر مجوزها (chmod)" -> changePermissions(path)
                "حذف دائمی" -> MaterialAlertDialogBuilder(this).setMessage("این مورد به‌صورت دائمی با دسترسی روت حذف شود؟")
                    .setNegativeButton("لغو", null).setPositiveButton("حذف") { _, _ ->
                        Thread {
                            runRoot("rm -rf -- ${shellQuote(path)}")
                            val ok = runRoot("[ ! -e ${shellQuote(path)} ] && echo OK").trim() == "OK"
                            runOnUiThread { toast(if (ok) "حذف شد" else "حذف ناموفق بود"); loadPath() }
                        }.start()
                    }.show()
            }
        }.show()
    }

    private fun askDestination(source: String) {
        val input = EditText(this).apply { hint = "مسیر مقصد کامل، مانند /sdcard/Download"; setText("/sdcard/Download") }
        MaterialAlertDialogBuilder(this).setTitle("مسیر مقصد").setView(input)
            .setNegativeButton("لغو", null).setNeutralButton("کپی") { _, _ -> transferRoot(source, input.text.toString(), false) }
            .setPositiveButton("انتقال") { _, _ -> transferRoot(source, input.text.toString(), true) }.show()
    }

    private fun transferRoot(source: String, destination: String, move: Boolean) {
        if (destination.isBlank()) { toast("مسیر مقصد خالی است"); return }
        Thread {
            val command = (if (move) "mv" else "cp -a") + " -- ${shellQuote(source)} ${shellQuote(destination)} && echo OK"
            val ok = runRoot(command).trim() == "OK"
            runOnUiThread { toast(if (ok) if (move) "انتقال انجام شد" else "کپی انجام شد" else "عملیات ناموفق بود؛ مسیر و مجوزها را بررسی کن"); loadPath() }
        }.start()
    }

    private fun changePermissions(path: String) {
        val input = EditText(this).apply { hint = "حالت عددی، مانند 0644 یا 0755"; setText(if (runRoot("[ -d ${shellQuote(path)} ] && echo D").trim() == "D") "0755" else "0644") }
        MaterialAlertDialogBuilder(this).setTitle("تغییر مجوزها").setMessage("مقدار chmod را به‌شکل عددی وارد کن.")
            .setView(input).setNegativeButton("لغو", null).setPositiveButton("اعمال") { _, _ ->
                val mode = input.text.toString().trim()
                if (!mode.matches(Regex("0?[0-7]{3,4}"))) { toast("مقدار مجوز نامعتبر است"); return@setPositiveButton }
                Thread {
                    val ok = runRoot("chmod $mode -- ${shellQuote(path)} && echo OK").trim() == "OK"
                    runOnUiThread { toast(if (ok) "مجوزها تغییر کرد" else "تغییر مجوزها ناموفق بود") }
                }.start()
            }.show()
    }

    private fun createFolder() {
        val input = EditText(this).apply { hint = "نام پوشه" }
        MaterialAlertDialogBuilder(this).setTitle("ساخت پوشه با Root").setView(input)
            .setNegativeButton("لغو", null).setPositiveButton("ساخت") { _, _ ->
                val target = File(currentPath, input.text.toString()).absolutePath
                Thread { val result = runRoot("mkdir -- ${shellQuote(target)} && echo OK")
                    runOnUiThread { toast(if (result.trim() == "OK") "پوشه ساخته شد" else "ساخت پوشه ناموفق بود"); loadPath() }
                }.start()
            }.show()
    }

    private fun editRootText(path: String) {
        Thread {
            val readable = runRoot("[ -f ${shellQuote(path)} ] && [ -r ${shellQuote(path)} ] && echo OK").trim() == "OK"
            val response = if (readable) runRoot("cat -- ${shellQuote(path)} && printf '__SOLINA_ROOT_READ_OK__'") else ""
            val marker = "__SOLINA_ROOT_READ_OK__"
            val valid = readable && response.endsWith(marker)
            val raw = if (valid) response.removeSuffix(marker) else ""
            runOnUiThread {
                if (!valid) { toast("فایل قابل خواندن نیست یا دسترسی روت کافی نیست"); return@runOnUiThread }
                if (raw.length > 2 * 1024 * 1024) { toast("ویرایش فایل‌های بیشتر از ۲ مگابایت پشتیبانی نمی‌شود"); return@runOnUiThread }
                val input = EditText(this).apply { setText(raw); gravity = Gravity.TOP or Gravity.START; minLines = 12 }
                MaterialAlertDialogBuilder(this).setTitle("ویرایش: ${File(path).name}").setView(input)
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
            val readable = runRoot("[ -f ${shellQuote(path)} ] && [ -r ${shellQuote(path)} ] && echo OK").trim() == "OK"
            val result = if (readable) runRoot("base64 < ${shellQuote(path)}") else ""
            val decoded = if (readable) try { Base64.getMimeDecoder().decode(result) } catch (_: Exception) { null } else null
            val ok = decoded != null && try { target.writeBytes(decoded); true } catch (_: Exception) { false }
            runOnUiThread { toast(if (ok) "نسخه کپی شد: RootExports/${target.name}" else "کپی ناموفق بود") }
        }.start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

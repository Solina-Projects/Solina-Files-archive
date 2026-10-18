package ir.solina.fm

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** Lists user-launchable installed apps without requesting broad package visibility. */
class InstalledAppsActivity : AppCompatActivity() {
    private data class AppEntry(val label: String, val packageName: String, val version: String, val info: ApplicationInfo)
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var count: TextView
    private var apps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12)) }
        root.addView(com.google.android.material.appbar.MaterialToolbar(this).apply {
            title = "برنامه‌های نصب‌شده"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        search = EditText(this).apply { hint = "جست‌وجوی نام یا شناسه بسته"; singleLine = true }
        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        count = TextView(this).apply { textSize = 12f; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        root.addView(count)
        val scroll = android.widget.ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) loadApps()
    }

    @Suppress("DEPRECATION")
    private fun loadApps() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(launcher, 0)
        val unique = LinkedHashMap<String, AppEntry>()
        resolved.forEach { resolve ->
            val info = resolve.activityInfo?.applicationInfo ?: return@forEach
            val pkg = info.packageName
            if (pkg == packageName) return@forEach
            val label = resolve.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            val version = try { packageManager.getPackageInfo(pkg, 0).versionName ?: "نامشخص" }
                catch (_: PackageManager.NameNotFoundException) { "نامشخص" }
            unique.putIfAbsent(pkg, AppEntry(label, pkg, version, info))
        }
        apps = unique.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        render(search.text?.toString().orEmpty())
    }

    private fun render(query: String) {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val q = query.trim().lowercase(Locale.ROOT)
        val filtered = apps.filter { q.isEmpty() || it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q) }
        count.text = "${filtered.size} برنامه"
        if (filtered.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (apps.isEmpty()) "برنامهٔ قابل‌اجرا پیدا نشد." else "نتیجه‌ای برای جست‌وجو پیدا نشد."
                gravity = Gravity.CENTER
                setPadding(dp(24))
            }, LinearLayout.LayoutParams(-1, dp(100)))
            return
        }
        filtered.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8))
                isClickable = true
                isFocusable = true
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@InstalledAppsActivity, R.drawable.item_bg)
            }
            val icon = ImageView(this).apply {
                try { setImageDrawable(packageManager.getApplicationIcon(app.info)) }
                catch (_: PackageManager.NameNotFoundException) { setImageResource(android.R.drawable.sym_def_app_icon) }
                contentDescription = app.label
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(8), dp(4)) }
            details.addView(TextView(this).apply { text = app.label; textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            details.addView(TextView(this).apply {
                text = "${app.packageName}  •  ${app.version}"
                textSize = 11f
                maxLines = 2
            })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage(app.packageName)
                if (launch != null) try { startActivity(launch) }
                catch (_: Exception) { Toast.makeText(this, "اجرای برنامه ممکن نیست", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(this, "برای این بسته مسیر اجرا پیدا نشد", Toast.LENGTH_SHORT).show()
            }
            row.setOnLongClickListener {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:${app.packageName}"))) }
                catch (_: Exception) { Toast.makeText(this, "صفحهٔ اطلاعات برنامه در دسترس نیست", Toast.LENGTH_SHORT).show() }
                true
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

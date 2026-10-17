package ir.solina.fm

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** APK metadata/signature inspector and basic DEX string inspector. It does not re-sign or patch APKs. */
class ApkToolsActivity : AppCompatActivity() {
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14) }
        root.addView(TextView(this).apply { text = "ابزار APK / DEX"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "بررسی اطلاعات بسته، مجوزها، اثرانگشت گواهی و استخراج رشته‌های قابل‌خواندن از DEX."
            setPadding(0, 8, 0, 12)
        })
        root.addView(Button(this).apply { text = "انتخاب فایل APK"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/vnd.android.package-archive"
            }, 701)
        } })
        val scroll = ScrollView(this)
        result = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); gravity = Gravity.TOP; setPadding(0, 12, 0, 12) }
        scroll.addView(result)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    @Deprecated("Legacy callback retained for compatibility with minSdk 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 701 || resultCode != RESULT_OK || data?.data == null) return
        result.text = "در حال بررسی APK…"
        Thread {
            val report = try {
                val uri = data.data!!
                val temp = File(cacheDir, "inspect-${System.currentTimeMillis()}.apk")
                contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { input.copyTo(it) } }
                val info = packageManager.getPackageArchiveInfo(temp.absolutePath,
                    PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)
                val out = StringBuilder()
                out.appendLine("فایل: ${queryName(uri)}")
                out.appendLine("حجم: ${android.text.format.Formatter.formatFileSize(this, temp.length())}")
                out.appendLine("SHA-256 فایل: ${sha256(temp)}")
                if (info != null) {
                    out.appendLine("\n--- اطلاعات بسته ---")
                    out.appendLine("Package: ${info.packageName}")
                    out.appendLine("Version: ${info.versionName} (${info.longVersionCode})")
                    out.appendLine("Min SDK: ${info.applicationInfo?.minSdkVersion ?: "نامشخص"}")
                    out.appendLine("Target SDK: ${info.applicationInfo?.targetSdkVersion ?: "نامشخص"}")
                    out.appendLine("مجوزهای درخواستی:")
                    (info.requestedPermissions ?: emptyArray()).forEach { out.appendLine(" • $it") }
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        val signatures = info.signingInfo?.apkContentsSigners ?: emptyArray()
                        out.appendLine("\nSHA-256 گواهی امضا:")
                        signatures.forEach { out.appendLine(sha256Bytes(it.toByteArray())) }
                    }
                } else out.appendLine("PackageManager نتوانست اطلاعات APK را تحلیل کند.")
                out.appendLine("\n--- فایل‌های DEX ---")
                ZipFile(temp).use { zip ->
                    val dexFiles = zip.entries().asSequence().filter { !it.isDirectory && it.name.matches(Regex("classes(\\\\d*)\\\\.dex")) }.toList()
                    out.appendLine("تعداد DEX: ${dexFiles.size}")
                    dexFiles.forEach { entry ->
                        out.appendLine("\n[${entry.name}]")
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val strings = Regex("[ -~]{5,}").findAll(bytes.toString(Charsets.ISO_8859_1))
                            .map { it.value }.distinct().take(120).toList()
                        strings.forEach { out.appendLine(it.take(300)) }
                    }
                }
                temp.delete()
                out.toString()
            } catch (e: Exception) { "تحلیل APK ناموفق بود: ${e.message}" }
            runOnUiThread { result.text = report }
        }.start()
    }

    private fun queryName(uri: android.net.Uri): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "APK"
        } ?: "APK"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Bytes(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

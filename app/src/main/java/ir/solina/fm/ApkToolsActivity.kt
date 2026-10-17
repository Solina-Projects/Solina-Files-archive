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
                        out.append(dexStructureReport(bytes))
                        val strings = Regex("[ -~]{5,}").findAll(bytes.toString(Charsets.ISO_8859_1))
                            .map { it.value }.distinct().take(120).toList()
                        out.appendLine("سطرهای قابل‌خواندن (نمونه):")
                        strings.forEach { out.appendLine(it.take(300)) }
                    }
                }
                temp.delete()
                out.toString()
            } catch (e: Exception) { "تحلیل APK ناموفق بود: ${e.message}" }
            runOnUiThread { result.text = report }
        }.start()
    }

    /**
     * Reads standard DEX identifier tables without executing or modifying the APK.
     * This is a structural inspector, not a full Dalvik disassembler.
     */
    private fun dexStructureReport(data: ByteArray): String {
        if (data.size < 112 || !data.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) {
            return "هشدار: سرآیند DEX معتبر یا کامل نیست.\\n"
        }
        fun u16(offset: Int): Int {
            if (offset < 0 || offset + 2 > data.size) return -1
            return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
        }
        fun u32(offset: Int): Int {
            if (offset < 0 || offset + 4 > data.size) return -1
            return (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
        }
        fun validTable(count: Int, offset: Int, itemSize: Int): Boolean =
            count in 0..1_000_000 && offset >= 0 && offset <= data.size &&
                (count.toLong() * itemSize.toLong() + offset.toLong()) <= data.size.toLong()
        fun uleb(start: Int): Pair<Int, Int> {
            var value = 0
            var shift = 0
            var cursor = start
            while (cursor < data.size && shift <= 28) {
                val b = data[cursor++].toInt() and 0xff
                value = value or ((b and 0x7f) shl shift)
                if ((b and 0x80) == 0) return value to cursor
                shift += 7
            }
            return 0 to start
        }
        fun dexString(offset: Int): String {
            if (offset !in 0 until data.size) return "?"
            val (_, start) = uleb(offset)
            if (start !in 0 until data.size) return "?"
            var end = start
            while (end < data.size && data[end].toInt() != 0) end++
            return if (end <= data.size) String(data, start, end - start, Charsets.UTF_8) else "?"
        }
        return try {
            val fileSize = u32(32)
            val stringCount = u32(56); val stringOff = u32(60)
            val typeCount = u32(64); val typeOff = u32(68)
            val protoCount = u32(72); val protoOff = u32(76)
            val fieldCount = u32(80); val fieldOff = u32(84)
            val methodCount = u32(88); val methodOff = u32(92)
            val classCount = u32(96); val classOff = u32(100)
            if (!validTable(stringCount, stringOff, 4) || !validTable(typeCount, typeOff, 4) ||
                !validTable(protoCount, protoOff, 12) || !validTable(methodCount, methodOff, 8) ||
                !validTable(classCount, classOff, 32) || !validTable(fieldCount, fieldOff, 8)) {
                return "ساختار جدول‌های DEX خارج از محدودهٔ فایل است؛ گزارش ساختاری محدود شد.\\n"
            }
            val strings = Array(stringCount) { index -> dexString(u32(stringOff + index * 4)) }
            val types = Array(typeCount) { index ->
                val stringIndex = u32(typeOff + index * 4)
                strings.getOrNull(stringIndex) ?: "?"
            }
            val prototypes = Array(protoCount) { index ->
                val off = protoOff + index * 12
                val returnIndex = u32(off + 4)
                val paramsOff = u32(off + 8)
                val params = if (paramsOff > 0 && paramsOff + 4 <= data.size) {
                    val count = u32(paramsOff).coerceIn(0, 256)
                    (0 until count).mapNotNull { n ->
                        types.getOrNull(u16(paramsOff + 4 + n * 2))
                    }
                } else emptyList()
                (params.joinToString("") to (types.getOrNull(returnIndex) ?: "?"))
            }
            val classes = linkedSetOf<Int>()
            val classNames = mutableListOf<String>()
            for (index in 0 until classCount) {
                val classIndex = u32(classOff + index * 32)
                if (classIndex >= 0 && classIndex < types.size) {
                    classes.add(classIndex)
                    classNames.add(types[classIndex])
                }
            }
            val out = StringBuilder()
            out.appendLine("تحلیل ساختاری DEX")
            out.appendLine("نسخه: " + String(data, 4, 3, Charsets.US_ASCII))
            out.appendLine("اندازهٔ ثبت‌شده: $fileSize بایت؛ اندازهٔ واقعی: ${data.size} بایت")
            out.appendLine("جدول‌ها: رشته‌ها=$stringCount، نوع‌ها=$typeCount، پروتوتایپ‌ها=$protoCount، فیلدها=$fieldCount، متدها=$methodCount، کلاس‌ها=$classCount")
            out.appendLine("کلاس‌ها (حداکثر 80 مورد):")
            classNames.take(80).forEach { out.appendLine("  $it") }
            out.appendLine("امضاهای متد (حداکثر 160 مورد):")
            var printed = 0
            for (index in 0 until methodCount) {
                if (printed >= 160) break
                val off = methodOff + index * 8
                val classIndex = u16(off)
                val protoIndex = u16(off + 2)
                val nameIndex = u32(off + 4)
                if (classIndex !in classes) continue
                val owner = types.getOrNull(classIndex) ?: "?"
                val name = strings.getOrNull(nameIndex) ?: "?"
                val proto = prototypes.getOrNull(protoIndex)
                if (proto != null) {
                    out.appendLine("  $owner->$name(${proto.first})${proto.second}")
                    printed++
                }
            }
            if (methodCount > printed) out.appendLine("  … برای محدودیت حجم، فهرست متدها کوتاه شده است.")
            out.toString()
        } catch (e: Exception) {
            "خواندن جدول‌های DEX ناموفق بود: ${e.localizedMessage}\\n"
        }
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

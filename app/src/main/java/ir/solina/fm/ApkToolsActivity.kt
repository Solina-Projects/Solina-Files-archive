package ir.solina.fm

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "تحلیل APK و DEX"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "بررسی اطلاعات بسته، مجوزها، اثرانگشت گواهی و استخراج رشته‌های قابل‌خواندن از DEX."
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(MaterialButton(this).apply { text = "انتخاب فایل APK"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/vnd.android.package-archive"
            }, 701)
        } })
        val scroll = ScrollView(this)
        result = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); gravity = Gravity.TOP; setPadding(0, dp(12), 0, dp(12)) }
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
                try {
                    val source = contentResolver.openInputStream(uri) ?: error("فایل انتخاب‌شده قابل خواندن نیست")
                    source.use { input ->
                        temp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= 512L * 1024L * 1024L) { "حجم APK از حد مجاز ۵۱۲ مگابایت بیشتر است" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
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
                    val dexFiles = zip.entries().asSequence().filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }.toList()
                    out.appendLine("تعداد DEX: ${dexFiles.size}")
                    dexFiles.forEach { entry ->
                        out.appendLine("\n[${entry.name}]")
                        if (entry.size > 64L * 1024L * 1024L) {
                            out.appendLine("DEX بزرگ‌تر از حد مجاز ۶۴ مگابایت است؛ از این فایل صرف‌نظر شد.")
                            return@forEach
                        }
                        val bytes = zip.getInputStream(entry).use { readLimited(it, 64 * 1024 * 1024) }
                        if (bytes.size > 64 * 1024 * 1024) {
                            out.appendLine("DEX بزرگ‌تر از حد مجاز است؛ از این فایل صرف‌نظر شد.")
                            return@forEach
                        }
                        out.append(dexStructureReport(bytes))
                        val strings = Regex("[ -~]{5,}").findAll(bytes.toString(Charsets.ISO_8859_1))
                            .map { it.value }.distinct().take(120).toList()
                        out.appendLine("سطرهای قابل‌خواندن (نمونه):")
                        strings.forEach { out.appendLine(it.take(300)) }
                    }
                }
                out.toString()
                } finally {
                    temp.delete()
                }
            } catch (e: Exception) { "تحلیل APK ناموفق بود: ${e.localizedMessage ?: e.javaClass.simpleName}" }
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
            out.appendLine("\nنمونهٔ دستورهای DEX به‌شکل شبه-Smali (حداکثر 20 متد و 40 دستور از هر متد):")
            val opcodeNames = mapOf(
                0x00 to "nop", 0x01 to "move", 0x02 to "move/from16", 0x03 to "move/16",
                0x04 to "move-wide", 0x07 to "move-object", 0x0a to "move-result",
                0x0c to "move-result-object", 0x0d to "move-exception", 0x0e to "return-void",
                0x0f to "return", 0x10 to "return-wide", 0x11 to "return-object",
                0x12 to "const/4", 0x13 to "const/16", 0x14 to "const",
                0x1a to "const-string", 0x1b to "const-string/jumbo", 0x1c to "const-class",
                0x1f to "check-cast", 0x20 to "instance-of", 0x22 to "new-instance",
                0x23 to "new-array", 0x27 to "throw", 0x28 to "goto", 0x29 to "goto/16",
                0x2a to "goto/32", 0x32 to "if-eq", 0x38 to "if-eqz",
                0x44 to "aget", 0x4b to "aput", 0x52 to "iget", 0x59 to "iput",
                0x60 to "sget", 0x67 to "sput", 0x6e to "invoke-virtual",
                0x6f to "invoke-super", 0x70 to "invoke-direct", 0x71 to "invoke-static",
                0x72 to "invoke-interface", 0x7b to "neg-int", 0x7c to "not-int",
                0x90 to "add-int", 0x91 to "sub-int", 0x92 to "mul-int", 0x93 to "div-int"
            )
            var emittedMethods = 0
            for (classNumber in 0 until classCount) {
                if (emittedMethods >= 20) break
                val classOffset = classOff + classNumber * 32
                val classIndex = u32(classOffset)
                val owner = types.getOrNull(classIndex) ?: continue
                val classDataOffset = u32(classOffset + 24)
                if (classDataOffset <= 0 || classDataOffset >= data.size) continue
                var cursor = classDataOffset
                val (staticFields, c1) = uleb(cursor); cursor = c1
                val (instanceFields, c2) = uleb(cursor); cursor = c2
                val (directMethods, c3) = uleb(cursor); cursor = c3
                val (virtualMethods, c4) = uleb(cursor); cursor = c4
                repeat((staticFields.coerceAtMost(10000) + instanceFields.coerceAtMost(10000)).coerceAtMost(20000)) {
                    val (_, nextIndex) = uleb(cursor); cursor = nextIndex
                    val (_, nextFlags) = uleb(cursor); cursor = nextFlags
                }
                val totalMethods = (directMethods.coerceAtMost(10000) + virtualMethods.coerceAtMost(10000)).coerceAtMost(20000)
                var methodIndex = 0
                repeat(totalMethods) {
                    val (delta, nextIndex) = uleb(cursor); cursor = nextIndex
                    val (accessFlags, nextFlags) = uleb(cursor); cursor = nextFlags
                    val (codeOffset, nextCode) = uleb(cursor); cursor = nextCode
                    methodIndex += delta
                    if (codeOffset <= 0 || codeOffset + 16 > data.size || emittedMethods >= 20) return@repeat
                    val methodName = strings.getOrNull(u32(methodOff + methodIndex * 8 + 4)) ?: "method_$methodIndex"
                    val remainingUnits = (data.size - codeOffset - 16) / 2
                    val codeUnits = u32(codeOffset + 12).coerceIn(0, remainingUnits.coerceAtLeast(0))
                    out.appendLine(".method ${if ((accessFlags and 0x8) != 0) "static " else ""}$methodName  # $owner")
                    var pc = 0
                    var instructions = 0
                    while (pc < codeUnits && instructions < 40) {
                        val unit = u16(codeOffset + 16 + pc * 2)
                        if (unit < 0) break
                        val opcode = unit and 0xff
                        val width = when (opcode) {
                            0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f,
                            0x20, 0x22, 0x23, 0x29, 0x32, 0x38, in 0x44..0x6d, in 0x7d..0xe2 -> 2
                            0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x2a, in 0x2b..0x31,
                            in 0x6e..0x72 -> 3
                            else -> 1
                        }
                        val operands = (1 until width).mapNotNull { delta ->
                            val value = if (pc + delta < codeUnits) u16(codeOffset + 16 + (pc + delta) * 2) else -1
                            if (value < 0) null else "0x%04x".format(value)
                        }
                        val opname = opcodeNames[opcode] ?: "op_%02x".format(opcode)
                        out.appendLine("    :%04x  $opname".format(pc) + if (operands.isEmpty()) "" else " " + operands.joinToString(", "))
                        pc += width.coerceAtMost((codeUnits - pc).coerceAtLeast(1))
                        instructions++
                    }
                    if (pc < codeUnits) out.appendLine("    # … ادامهٔ دستورها کوتاه شده است")
                    out.appendLine(".end method")
                    emittedMethods++
                }
            }
            if (emittedMethods == 0) out.appendLine("کد اجرایی برای نمایش پیدا نشد؛ فایل ممکن است فقط شامل تعریف‌ها باشد یا ساختار آن پشتیبانی نشود.")
            out.appendLine("توجه: این خروجی شبه-Smali برای بررسی اولیه است؛ جایگزین disassembler کامل و آزمون‌شده نیست.")
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

    /** Reads at most maxBytes + 1 bytes so oversized entries can be rejected safely. */
    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(8192, maxBytes))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val accepted = minOf(read, maxBytes + 1 - total)
            if (accepted > 0) output.write(buffer, 0, accepted)
            total += accepted
            if (total > maxBytes) break
        }
        return output.toByteArray()
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

}

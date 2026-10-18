package ir.solina.fm

import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Bounded, read-only hexadecimal inspection screen adapted as an incremental
 * Strata Hex Editor feature port. It never writes to the source file.
 */
class HexViewerActivity : AppCompatActivity() {
    private lateinit var file: File
    private lateinit var report: TextView
    private lateinit var pageLabel: TextView
    private lateinit var offsetInput: EditText
    private var offset = 0L
    private val pageBytes = 512L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("file_path")
        if (path.isNullOrBlank()) { finish(); return }
        file = File(path)
        if (!file.isFile || !file.canRead()) {
            Toast.makeText(this, "فایل قابل خواندن نیست", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(10))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "نمایشگر هگز"
            subtitle = file.name
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "فایل: ${file.name}\nاندازه: ${android.text.format.Formatter.formatFileSize(this@HexViewerActivity, file.length())}\nحالت فقط‌خواندنی؛ هیچ تغییری در فایل اصلی ذخیره نمی‌شود."
            textSize = 12f
            setPadding(dp(4), dp(6), dp(4), dp(6))
        })
        val jumpRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        offsetInput = EditText(this).apply {
            hint = "جابجایی هگز (مثلاً 1000)"
            singleLine = true
            setText("0")
        }
        jumpRow.addView(offsetInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        jumpRow.addView(MaterialButton(this).apply {
            text = "برو"
            setOnClickListener { jumpToOffset() }
        }, LinearLayout.LayoutParams(dp(76), dp(48)))
        root.addView(jumpRow)
        pageLabel = TextView(this).apply { gravity = Gravity.CENTER; textSize = 12f; setPadding(0, dp(4), 0, dp(4)) }
        root.addView(pageLabel)
        val scroll = ScrollView(this)
        report = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        scroll.addView(report)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(MaterialButton(this).apply {
            text = "صفحهٔ قبل"
            setOnClickListener { offset = (offset - pageBytes).coerceAtLeast(0L); renderPage() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        nav.addView(MaterialButton(this).apply {
            text = "صفحهٔ بعد"
            setOnClickListener {
                if (offset + pageBytes < file.length()) { offset += pageBytes; renderPage() }
                else Toast.makeText(this@HexViewerActivity, "به انتهای فایل رسیده‌ای", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(nav)
        setContentView(root)
        renderPage()
    }

    private fun jumpToOffset() {
        val raw = offsetInput.text.toString().trim().removePrefix("0x").removePrefix("0X")
        val target = raw.toLongOrNull(16)
        if (target == null || target < 0L || target >= file.length().coerceAtLeast(1L)) {
            Toast.makeText(this, "جابجایی هگز نامعتبر است", Toast.LENGTH_SHORT).show()
            return
        }
        offset = target - target % 16L
        renderPage()
    }

    private fun renderPage() {
        val size = file.length()
        if (size == 0L) {
            pageLabel.text = "فایل خالی است"
            report.text = "(empty file)"
            return
        }
        offset = offset.coerceIn(0L, size - 1L)
        val length = minOf(pageBytes, size - offset).toInt()
        val bytes = ByteArray(length)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(bytes)
            }
        } catch (e: Exception) {
            report.text = "خواندن فایل ناموفق بود: ${e.localizedMessage}"
            return
        }
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val rowOffset = offset + i
            out.append(String.format(Locale.ROOT, "%08X  ", rowOffset))
            val rowLength = minOf(16, bytes.size - i)
            for (j in 0 until 16) {
                if (j < rowLength) out.append(String.format(Locale.ROOT, "%02X ", bytes[i + j].toInt() and 0xff))
                else out.append("   ")
                if (j == 7) out.append(' ')
            }
            out.append(" |")
            for (j in 0 until rowLength) {
                val value = bytes[i + j].toInt() and 0xff
                out.append(if (value in 32..126) value.toChar() else '.')
            }
            out.append("|\n")
            i += rowLength
        }
        report.text = out.toString()
        pageLabel.text = "جابجایی 0x${offset.toString(16).uppercase(Locale.ROOT)} — ${length} بایت از ${android.text.format.Formatter.formatFileSize(this, size)}"
        offsetInput.setText(offset.toString(16).uppercase(Locale.ROOT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

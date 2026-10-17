package ir.solina.fm

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.EditText
import android.widget.ImageView
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

class DocumentViewerActivity : AppCompatActivity() {
    private lateinit var file: File
    private lateinit var body: LinearLayout
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("file_path")
        if (path.isNullOrBlank()) { finish(); return }
        file = File(path)
        if (!file.isFile || !file.canRead()) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        root.addView(MaterialToolbar(this).apply {
            title = file.name
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        if (file.extension.equals("pdf", true)) renderPdf() else renderHex()
    }

    private fun renderPdf() {
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor!!)
            val pdf = renderer!!
            body.addView(TextView(this).apply {
                text = "PDF • ${pdf.pageCount} صفحه"
                textSize = 14f
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            val maxPages = minOf(pdf.pageCount, 12)
            for (index in 0 until maxPages) {
                val page = pdf.openPage(index)
                try {
                    val targetWidth = minOf(resources.displayMetrics.widthPixels - dp(36), 1000).coerceAtLeast(320)
                    val ratio = targetWidth.toFloat() / page.width.toFloat()
                    val bitmap = Bitmap.createBitmap(targetWidth, (page.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    body.addView(TextView(this).apply {
                        text = "صفحه ${index + 1}"
                        textSize = 12f
                        setPadding(dp(4), dp(12), dp(4), dp(4))
                    })
                    body.addView(ImageView(this).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "صفحه ${index + 1} از PDF"
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                } finally { page.close() }
            }
            if (pdf.pageCount > maxPages) body.addView(TextView(this).apply {
                text = "برای جلوگیری از مصرف بیش‌ازحد حافظه، نمایش به ${maxPages} صفحهٔ نخست محدود شده است. برای فایل کامل از برنامهٔ PDF دستگاه استفاده کن."
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
            body.addView(MaterialButton(this).apply {
                text = "بازکردن با برنامهٔ دیگر"
                isAllCaps = false
                setOnClickListener { openExternal() }
            })
        } catch (e: Exception) {
            body.addView(TextView(this).apply { text = "خواندن PDF ناموفق بود: ${e.message}" })
            body.addView(MaterialButton(this).apply {
                text = "بازکردن با برنامهٔ دیگر"
                setOnClickListener { openExternal() }
            })
        }
    }

    private fun renderHex() {
        val maxBytes = minOf(file.length(), 64L * 1024L).toInt()
        val bytes = ByteArray(maxBytes)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        body.addView(TextView(this).apply {
            text = "نمایش باینری • ${file.length()} بایت" +
                if (file.length() > maxBytes) "\nبرای ویرایش امن، فقط ۶۴ کیلوبایت نخست قابل ویرایش است." else "\nویرایش با حفظ تعداد بایت‌ها انجام می‌شود."
            textSize = 14f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })
        val editor = EditText(this).apply {
            setText(bytes.joinToString(" ") { "%02X".format(it) })
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            minLines = 12
            setHorizontallyScrolling(false)
            isSingleLine = false
        }
        body.addView(editor, LinearLayout.LayoutParams(-1, -2))
        body.addView(MaterialButton(this).apply {
            text = "ذخیره بایت‌ها"
            isAllCaps = false
            setOnClickListener {
                val tokens = editor.text.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.size != bytes.size || tokens.any { !it.matches(Regex("[0-9a-fA-F]{2}")) }) {
                    Toast.makeText(this@DocumentViewerActivity, "باید دقیقاً ${bytes.size} بایت با قالب دو رقم هگز وارد شود", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                try {
                    val changed = ByteArray(tokens.size) { tokens[it].toInt(16).toByte() }
                    RandomAccessFile(file, "rw").use { it.seek(0); it.write(changed) }
                    Toast.makeText(this@DocumentViewerActivity, "بایت‌ها ذخیره شدند", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@DocumentViewerActivity, "ذخیره ناموفق بود: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
        body.addView(MaterialButton(this).apply {
            text = "بازکردن با برنامهٔ دیگر"
            isAllCaps = false
            setOnClickListener { openExternal() }
        })
    }

    private fun openExternal() {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = if (file.extension.lowercase(Locale.ROOT) == "pdf") "application/pdf" else "*/*"
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "برنامه‌ای برای بازکردن فایل پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        renderer?.close()
        descriptor?.close()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

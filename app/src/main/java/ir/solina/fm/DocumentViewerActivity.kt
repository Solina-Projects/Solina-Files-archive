package ir.solina.fm

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
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
import java.io.FileOutputStream
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
                text = "چاپ سند PDF"
                isAllCaps = false
                setOnClickListener { printPdf() }
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

    private fun printPdf() {
        val manager = getSystemService(PRINT_SERVICE) as PrintManager
        manager.print(file.nameWithoutExtension, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: android.os.CancellationSignal,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal.isCanceled) {
                    callback.onLayoutCancelled()
                    return
                }
                val count = renderer?.pageCount ?: 0
                callback.onLayoutFinished(
                    PrintDocumentInfo.Builder(file.name)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(count)
                        .build(),
                    oldAttributes != newAttributes
                )
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: android.os.CancellationSignal,
                callback: WriteResultCallback
            ) {
                val output = android.graphics.pdf.PdfDocument()
                var sourceDescriptor: ParcelFileDescriptor? = null
                var sourceRenderer: PdfRenderer? = null
                try {
                    sourceDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    sourceRenderer = PdfRenderer(sourceDescriptor)
                    val count = sourceRenderer.pageCount
                    val requested = if (pages.isEmpty() || pages.any { it == android.print.PageRange.ALL_PAGES }) {
                        (0 until count).toList()
                    } else pages.flatMap { range ->
                        (range.start..minOf(range.end, count - 1)).toList()
                    }.filter { it in 0 until count }.distinct().sorted()
                    val width = 595
                    val height = 842
                    for ((outIndex, sourceIndex) in requested.withIndex()) {
                        if (cancellationSignal.isCanceled) {
                            callback.onWriteCancelled()
                            return
                        }
                        val sourcePage = sourceRenderer.openPage(sourceIndex)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            val scale = minOf(width.toFloat() / sourcePage.width, height.toFloat() / sourcePage.height)
                            val drawWidth = (sourcePage.width * scale).toInt().coerceAtLeast(1)
                            val drawHeight = (sourcePage.height * scale).toInt().coerceAtLeast(1)
                            val left = (width - drawWidth) / 2
                            val top = (height - drawHeight) / 2
                            sourcePage.render(bitmap, android.graphics.Rect(left, top, left + drawWidth, top + drawHeight),
                                null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val page = output.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, outIndex + 1).create())
                            page.canvas.drawBitmap(bitmap, 0f, 0f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
                            output.finishPage(page)
                        } finally {
                            bitmap.recycle()
                            sourcePage.close()
                        }
                    }
                    if (requested.isEmpty()) {
                        callback.onWriteFinished(emptyArray())
                    } else {
                        FileOutputStream(destination.fileDescriptor).use { output.writeTo(it) }
                        callback.onWriteFinished(requested.map { android.print.PageRange(it, it) }.toTypedArray())
                    }
                } catch (e: Exception) {
                    callback.onWriteFailed(e.localizedMessage)
                } finally {
                    output.close()
                    sourceRenderer?.close()
                    sourceDescriptor?.close()
                }
            }
        }, PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("solina-pdf", "Solina PDF", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build())
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

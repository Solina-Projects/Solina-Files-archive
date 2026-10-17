package ir.solina.fm

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
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
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Locale

/** Lightweight editor for text, source and Smali files, with Android's system print pipeline. */
class TextEditorActivity : AppCompatActivity() {
    private lateinit var editor: EditText
    private lateinit var titleView: TextView
    private var file: File? = null
    private var documentUri: Uri? = null
    private var dirty = false
    private var loading = false
    private var originalText = ""

    private val textExtensions = setOf(
        "txt", "md", "log", "json", "xml", "csv", "tsv", "ini", "kt", "java",
        "smali", "properties", "html", "htm", "css", "js", "gradle", "kts",
        "yaml", "yml", "sh", "sql", "py", "c", "cpp", "h", "rs", "cfg", "conf"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "ویرایشگر متن و Smali"
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        titleView = TextView(this).apply {
            text = "یک فایل متنی یا Smali باز کن"
            textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        root.addView(titleView)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this@TextEditorActivity).apply {
            text = "بازکردن"
            isAllCaps = false
            setOnClickListener { chooseDocument() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(MaterialButton(this@TextEditorActivity).apply {
            text = "ذخیره"
            isAllCaps = false
            setOnClickListener { saveDocument() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(MaterialButton(this@TextEditorActivity).apply {
            text = "چاپ / PDF"
            isAllCaps = false
            setOnClickListener { printDocument() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        editor = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setHorizontallyScrolling(false)
            isSingleLine = false
            minLines = 12
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(this)
        scroll.addView(editor, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!loading) dirty = editor.text.toString() != originalText
                updateTitle()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val path = intent.getStringExtra("file_path")
        if (!path.isNullOrBlank()) openFile(File(path)) else if (intent.getBooleanExtra("pick_on_start", false)) chooseDocument()
    }

    private fun chooseDocument() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }, 810)
    }

    @Deprecated("Legacy callback is used for compatibility with the current project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == 811) {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(editor.text.toString().toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("ذخیره‌سازی پشتیبانی نمی‌شود")
                documentUri = uri
                file = null
                originalText = editor.text.toString()
                dirty = false
                updateTitle()
                toast("فایل ذخیره شد")
            } catch (e: Exception) {
                toast("ذخیره ناموفق بود: ${e.localizedMessage}")
            }
            return
        }
        if (requestCode != 810) return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytes(2 * 1024 * 1024 + 1) }
                ?: throw IllegalStateException("فایل قابل‌خواندن نیست")
            if (bytes.size > 2 * 1024 * 1024) throw IllegalArgumentException("حداکثر حجم قابل‌ویرایش ۲ مگابایت است")
            documentUri = uri
            file = null
            setEditorText(bytes.toString(Charsets.UTF_8))
            titleView.text = queryName(uri)
            toast("فایل باز شد")
        } catch (e: Exception) {
            toast("بازکردن فایل ناموفق بود: ${e.localizedMessage}")
        }
    }

    private fun openFile(target: File) {
        try {
            if (!target.isFile || !target.canRead()) throw IllegalArgumentException("فایل قابل‌خواندن نیست")
            if (target.length() > 2L * 1024 * 1024) throw IllegalArgumentException("حداکثر حجم قابل‌ویرایش ۲ مگابایت است")
            if (target.extension.lowercase(Locale.ROOT) !in textExtensions) {
                throw IllegalArgumentException("این ویرایشگر برای فایل‌های متنی و کد است؛ برای HEX از ابزار نمایش باینری استفاده کن")
            }
            file = target
            documentUri = null
            setEditorText(target.readText(Charsets.UTF_8))
            titleView.text = target.absolutePath
        } catch (e: Exception) {
            toast("بازکردن فایل ناموفق بود: ${e.localizedMessage}")
        }
    }

    private fun setEditorText(value: String) {
        loading = true
        editor.setText(value)
        editor.setSelection(editor.text.length)
        originalText = value
        dirty = false
        loading = false
        updateTitle()
    }

    private fun saveDocument() {
        try {
            val text = editor.text.toString()
            val target = file
            val uri = documentUri
            when {
                target != null -> target.writeText(text, Charsets.UTF_8)
                uri != null -> contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("ذخیره‌سازی پشتیبانی نمی‌شود")
                else -> {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "new-file.txt")
                    }, 811)
                    return
                }
            }
            originalText = text
            dirty = false
            updateTitle()
            toast("ذخیره شد")
        } catch (e: Exception) {
            toast("ذخیره ناموفق بود: ${e.localizedMessage}")
        }
    }

    private fun printDocument() {
        val content = editor.text.toString()
        if (content.isBlank()) {
            toast("محتوایی برای چاپ وجود ندارد")
            return
        }
        val manager = getSystemService(PRINT_SERVICE) as PrintManager
        val name = (file?.name ?: documentUri?.let(::queryName) ?: "SolinaFM-document").substringBeforeLast('.', file?.name ?: "SolinaFM-document")
        manager.print(name, TextPrintAdapter(name, content), PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("solina", "Solina", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build())
    }

    private inner class TextPrintAdapter(private val jobName: String, private val content: String) : PrintDocumentAdapter() {
        private var cancelled = false

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: android.os.Bundle?
        ) {
            cancelled = cancellationSignal.isCanceled
            if (cancelled) {
                callback.onLayoutCancelled()
                return
            }
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build(),
                true
            )
        }

        override fun onWrite(
            pages: Array<out android.print.PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback
        ) {
            val pdf = PdfDocument()
            try {
                val pageWidth = 595
                val pageHeight = 842
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                val lineHeight = 13f
                val maxChars = 94
                val wrapped = content.lineSequence().flatMap { line ->
                    if (line.isEmpty()) sequenceOf("") else line.chunked(maxChars).asSequence()
                }.toList()
                val linesPerPage = 58
                val totalPages = ((wrapped.size.coerceAtLeast(1) + linesPerPage - 1) / linesPerPage)
                val requestedPages = if (pages.isEmpty() || pages.any { it == android.print.PageRange.ALL_PAGES }) {
                    (0 until totalPages).toList()
                } else {
                    pages.flatMap { range -> (range.start..minOf(range.end, totalPages - 1)).toList() }
                        .filter { it in 0 until totalPages }.distinct().sorted()
                }
                for ((outputIndex, pageIndex) in requestedPages.withIndex()) {
                    if (cancellationSignal.isCanceled) {
                        cancelled = true
                        break
                    }
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, outputIndex + 1).create())
                    val canvas = page.canvas
                    canvas.drawText(jobName.take(90), 36f, 28f, paint.apply { textSize = 10f })
                    paint.textSize = 9f
                    val start = pageIndex * linesPerPage
                    val end = minOf(start + linesPerPage, wrapped.size)
                    for (i in start until end) {
                        canvas.drawText(wrapped[i].take(maxChars), 36f, 52f + (i - start) * lineHeight, paint)
                    }
                    paint.textSize = 8f
                    canvas.drawText("SolinaFM • ${pageIndex + 1}/$totalPages", 36f, 824f, paint)
                    pdf.finishPage(page)
                }
                if (cancelled) {
                    callback.onWriteCancelled()
                } else if (requestedPages.isEmpty()) {
                    callback.onWriteFinished(emptyArray())
                } else {
                    FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
                    callback.onWriteFinished(requestedPages.map { android.print.PageRange(it, it) }.toTypedArray())
                }
            } catch (e: Exception) {
                callback.onWriteFailed(e.localizedMessage)
            } finally {
                pdf.close()
            }
        }
    }

    private fun queryName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "document.txt"
        } ?: "document.txt"
    }

    private fun updateTitle() {
        if (!::titleView.isInitialized) return
        val base = file?.name ?: documentUri?.let(::queryName) ?: "فایل جدید"
        titleView.text = base + if (dirty) " • تغییرات ذخیره‌نشده" else ""
    }

    override fun onBackPressed() {
        if (dirty) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("تغییرات ذخیره نشده")
                .setMessage("آیا بدون ذخیره خارج می‌شوی؟")
                .setPositiveButton("خروج") { _, _ -> super.onBackPressed() }
                .setNegativeButton("ماندن", null)
                .show()
        } else super.onBackPressed()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

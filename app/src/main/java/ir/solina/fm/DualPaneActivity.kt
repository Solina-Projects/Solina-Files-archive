package ir.solina.fm

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Optional two-pane file manager with independent navigation and selection. */
class DualPaneActivity : AppCompatActivity() {
    private enum class LayoutMode { VERTICAL, HORIZONTAL }
    private data class Pane(
        val root: LinearLayout, val path: TextView, val list: RecyclerView,
        val adapter: PaneAdapter, var current: File,
        val selected: LinkedHashSet<File> = linkedSetOf()
    )
    private lateinit var panesHost: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var status: TextView
    private lateinit var left: Pane
    private lateinit var right: Pane
    private var activePane = 0
    private var mode = LayoutMode.VERTICAL
    private var grid = false
    private var details = true
    private var iconDp = 38
    private var columns = 2
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = Environment.getExternalStorageDirectory()
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 0, 8, 8) }
        toolbar = MaterialToolbar(this).apply { title = "SolinaFM • دو پنل" }
        screen.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        button(controls, "پنل‌ها") { toggleMode() }
        button(controls, "نمایش") { showDisplaySettings() }
        button(controls, "چیدمان") { showLayoutSettings() }
        button(controls, "بازگشت") { goUp(activePane) }
        screen.addView(controls, LinearLayout.LayoutParams(-1, dp(48)))
        panesHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(panesHost, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "برای انتخاب، روی فایل نگه دارید؛ پنل مقصد را در سمت مقابل باز کنید."; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        screen.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        button(actions, "کپی به مقابل") { transfer(false) }
        button(actions, "انتقال به مقابل") { transfer(true) }
        button(actions, "استخراج به مقابل") { extractSelectedArchives() }
        screen.addView(actions, LinearLayout.LayoutParams(-1, dp(52)))
        setContentView(screen)
        left = createPane(base, 0)
        right = createPane(base, 1)
        renderPanes()
    }

    private fun createPane(start: File, index: Int): Pane {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val path = TextView(this).apply {
            textSize = 12f; maxLines = 2; setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { showPathActions(index) }
        }
        root.addView(path, LinearLayout.LayoutParams(-1, dp(48)))
        val list = RecyclerView(this)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val adapter = PaneAdapter(index)
        list.adapter = adapter
        return Pane(root, path, list, adapter, start)
    }

    private fun pane(index: Int) = if (index == 0) left else right
    private fun other(index: Int) = pane(1 - index)

    private fun renderPanes() {
        panesHost.removeAllViews()
        panesHost.orientation = if (mode == LayoutMode.VERTICAL) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        listOf(left, right).forEachIndexed { index, p ->
            p.root.layoutParams = if (mode == LayoutMode.VERTICAL) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(0, -1, 1f)
            p.root.setBackgroundColor(if (activePane == index) 0x143F51B5 else 0x00000000)
            p.root.setOnClickListener { activePane = index; updateTitle() }
            panesHost.addView(p.root)
            refresh(index)
        }
        updateTitle()
    }

    private fun refresh(index: Int) {
        val p = pane(index)
        p.path.text = (if (index == 0) "چپ: " else "راست: ") + p.current.absolutePath
        val files = p.current.listFiles()?.filter { it.isFile || it.isDirectory }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })) ?: emptyList()
        p.selected.retainAll(files.toSet())
        p.adapter.items = files
        p.adapter.notifyDataSetChanged()
        p.list.layoutManager = if (grid) GridLayoutManager(this, columns) else LinearLayoutManager(this)
    }

    private fun updateTitle() { toolbar.title = "SolinaFM • " + if (activePane == 0) "پنل چپ" else "پنل راست" }
    private fun toggleMode() { mode = if (mode == LayoutMode.VERTICAL) LayoutMode.HORIZONTAL else LayoutMode.VERTICAL; renderPanes() }

    private fun showDisplaySettings() {
        val options = arrayOf("جزئیات: " + if (details) "روشن" else "خاموش", "شبکه‌ای: " + if (grid) "روشن" else "خاموش", "اندازه آیکون: " + iconDp + " dp")
        AlertDialog.Builder(this).setTitle("نمایش فایل‌ها").setItems(options) { _, which ->
            when (which) { 0 -> details = !details; 1 -> grid = !grid; 2 -> showIconSize() }
            renderPanes()
        }.setNegativeButton("بستن", null).show()
    }

    private fun showLayoutSettings() {
        val values = arrayOf("۱ ستون", "۲ ستون", "۳ ستون", "۴ ستون")
        AlertDialog.Builder(this).setTitle("تعداد ستون‌ها").setSingleChoiceItems(values, columns - 1) { dialog, which ->
            columns = which + 1; grid = true; dialog.dismiss(); renderPanes()
        }.setNegativeButton("لغو", null).show()
    }

    private fun showIconSize() {
        val seek = SeekBar(this).apply { max = 72; progress = iconDp - 20 }
        AlertDialog.Builder(this).setTitle("اندازه آیکون").setView(seek)
            .setPositiveButton("ذخیره") { _, _ -> iconDp = seek.progress + 20; renderPanes() }
            .setNegativeButton("لغو", null).show()
    }

    private fun showPathActions(index: Int) {
        val p = pane(index)
        AlertDialog.Builder(this).setTitle("مسیر پنل").setItems(arrayOf("رفتن به پوشه والد", "ساخت پوشه جدید")) { _, which ->
            if (which == 0) goUp(index) else askText("نام پوشه جدید", "") { name ->
                val folder = File(p.current, name)
                if (!folder.exists() && folder.mkdir()) refresh(index) else toast("ساخت پوشه انجام نشد")
            }
        }.show()
    }

    private fun goUp(index: Int) {
        val p = pane(index)
        p.current.parentFile?.takeIf { it.exists() }?.let { p.current = it; p.selected.clear(); refresh(index) }
    }

    private fun transfer(move: Boolean) {
        val sourceIndex = activePane
        val source = pane(sourceIndex)
        val dest = other(sourceIndex).current
        val files = source.selected.toList()
        if (files.isEmpty()) { toast("ابتدا فایل یا پوشه را با نگه‌داشتن انتخاب کنید"); return }
        if (files.any { it.isDirectory && (dest.absolutePath == it.absolutePath || dest.absolutePath.startsWith(it.absolutePath + File.separator)) }) {
            toast("انتقال پوشه به داخل خودش مجاز نیست"); return
        }
        Thread {
            var failed = 0
            files.forEach { src ->
                try {
                    val target = uniqueTarget(dest, src)
                    if (move) {
                        if (!src.renameTo(target)) {
                            if (src.copyRecursively(target, overwrite = false)) src.deleteRecursively() else failed++
                        }
                    } else if (!src.copyRecursively(target, overwrite = false)) failed++
                } catch (_: Exception) { failed++ }
            }
            runOnUiThread {
                source.selected.clear(); refresh(sourceIndex); refresh(1 - sourceIndex)
                status.text = if (failed == 0) "عملیات با موفقیت انجام شد" else "$failed مورد ناموفق بود"
            }
        }.start()
    }

    private fun extractSelectedArchives() {
        val sourceIndex = activePane
        val archives = pane(sourceIndex).selected.filter { it.isFile && it.extension.lowercase() == "zip" }
        if (archives.isEmpty()) { toast("فایل ZIP را انتخاب کنید"); return }
        askText("رمز فایل فشرده (در صورت نداشتن خالی بگذارید)", "") { pass ->
            Thread {
                var failed = 0
                archives.forEach { archive ->
                    try {
                        val out = uniqueTarget(other(sourceIndex).current, File(archive.nameWithoutExtension))
                        val zip = if (pass.isNotEmpty()) ZipFile(archive, pass.toCharArray()) else ZipFile(archive)
                        zip.extractAll(out.absolutePath)
                    } catch (_: Exception) { failed++ }
                }
                runOnUiThread { refresh(1 - sourceIndex); status.text = if (failed == 0) "استخراج تمام شد" else "استخراج ناموفق؛ رمز یا سلامت فایل را بررسی کنید" }
            }.start()
        }
    }

    private fun openFile(file: File, paneIndex: Int) {
        when (file.extension.lowercase()) {
            "txt", "md", "log", "json", "xml", "csv", "ini", "kt", "java", "html", "css" -> editTextFile(file)
            "zip" -> browseArchive(file, paneIndex)
            "jpg", "jpeg", "png", "webp", "bmp", "gif" -> viewImage(file)
            "mp3", "m4a", "aac", "wav", "ogg", "flac" -> playAudio(file)
            else -> openExternal(file)
        }
    }

    private fun editTextFile(file: File) {
        try {
            val input = EditText(this).apply {
                setText(file.readText(Charsets.UTF_8)); gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 12; setSelection(text.length)
            }
            AlertDialog.Builder(this).setTitle(file.name).setView(input)
                .setPositiveButton("ذخیره") { _, _ -> try { file.writeText(input.text.toString(), Charsets.UTF_8); toast("ذخیره شد") } catch (_: Exception) { toast("ذخیره ناموفق بود") } }
                .setNegativeButton("لغو", null).show()
        } catch (_: Exception) { toast("فایل متنی قابل خواندن نیست یا حجم آن زیاد است") }
    }

    private fun browseArchive(file: File, paneIndex: Int) {
        try {
            val zip = ZipFile(file)
            val headers = zip.fileHeaders
            val names = headers.map { (if (it.isDirectory) "📁 " else "📄 ") + it.fileName + if (it.isEncrypted) " 🔒" else "" }.toTypedArray()
            AlertDialog.Builder(this).setTitle(file.name).setItems(names) { _, which ->
                val header = headers[which]
                if (!header.isDirectory) askText("رمز فایل (در صورت نداشتن خالی بگذارید)", "") { pass ->
                    Thread {
                        try {
                            val z = if (pass.isNotEmpty()) ZipFile(file, pass.toCharArray()) else ZipFile(file)
                            z.extractFile(header, other(paneIndex).current.absolutePath)
                            runOnUiThread { refresh(1 - paneIndex); toast("فایل استخراج شد") }
                        } catch (_: Exception) { runOnUiThread { toast("استخراج ناموفق؛ رمز یا فایل را بررسی کنید") } }
                    }.start()
                }
            }.setPositiveButton("استخراج همه به پنل مقابل") { _, _ -> extractOneArchive(file, paneIndex) }
                .setNegativeButton("بستن", null).show()
        } catch (_: Exception) { toast("فایل ZIP قابل مرور نیست") }
    }

    private fun extractOneArchive(file: File, paneIndex: Int) {
        askText("رمز فایل (در صورت نداشتن خالی بگذارید)", "") { pass ->
            Thread {
                try {
                    val z = if (pass.isNotEmpty()) ZipFile(file, pass.toCharArray()) else ZipFile(file)
                    z.extractAll(other(paneIndex).current.absolutePath)
                    runOnUiThread { refresh(1 - paneIndex); toast("استخراج تمام شد") }
                } catch (_: Exception) { runOnUiThread { toast("استخراج ناموفق؛ رمز یا فایل را بررسی کنید") } }
            }.start()
        }
    }

    private fun viewImage(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) { openExternal(file); return }
        val image = ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        AlertDialog.Builder(this).setTitle(file.name).setView(image)
            .setNeutralButton("ذخیره نسخه JPEG") { _, _ ->
                try {
                    val out = uniqueTarget(file.parentFile ?: Environment.getExternalStorageDirectory(), File(file.nameWithoutExtension + "-edited.jpg"))
                    FileOutputStream(out).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    toast("نسخه ذخیره شد: " + out.name)
                } catch (_: Exception) { toast("ذخیره تصویر ناموفق بود") }
            }.setPositiveButton("بستن", null).show()
    }

    private fun playAudio(file: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    val seek = SeekBar(this@DualPaneActivity).apply { max = mp.duration.coerceAtLeast(1) }
                    val layout = LinearLayout(this@DualPaneActivity).apply { orientation = LinearLayout.VERTICAL; addView(TextView(this@DualPaneActivity).apply { text = file.name; setPadding(dp(12), dp(8), dp(12), dp(8)) }); addView(seek) }
                    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) mp.seekTo(progress) }
                        override fun onStartTrackingTouch(s: SeekBar) {}
                        override fun onStopTrackingTouch(s: SeekBar) {}
                    })
                    AlertDialog.Builder(this@DualPaneActivity).setTitle("مشغل صوتی").setView(layout)
                        .setPositiveButton("پخش / توقف") { _, _ -> if (mp.isPlaying) mp.pause() else mp.start() }
                        .setNegativeButton("بستن") { _, _ -> mp.stop(); mp.release(); if (player === mp) player = null }.show()
                    mp.start()
                }
                prepareAsync()
            }
        } catch (_: Exception) { toast("پخش این فایل صوتی ممکن نیست") }
    }

    private fun openExternal(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), file.name))
        } catch (_: Exception) { toast("برنامه‌ای برای بازکردن این فایل پیدا نشد") }
    }

    private fun askText(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("تأیید") { _, _ -> onOk(input.text.toString()) }.setNegativeButton("لغو", null).show()
    }

    private fun uniqueTarget(dest: File, src: File): File {
        var target = File(dest, src.name)
        var n = 1
        while (target.exists()) {
            val suffix = if (src.extension.isEmpty()) "" else "." + src.extension
            target = File(dest, src.nameWithoutExtension + " (" + n + ")" + suffix)
            n++
        }
        return target
    }

    private fun button(parent: LinearLayout, text: String, action: () -> Unit) {
        val b = MaterialButton(this).apply { this.text = text; isAllCaps = false; setOnClickListener { action() } }
        parent.addView(b, LinearLayout.LayoutParams(0, -1, 1f))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    private inner class PaneAdapter(private val paneIndex: Int) : RecyclerView.Adapter<PaneAdapter.Holder>() {
        var items: List<File> = emptyList()
        inner class Holder(val row: LinearLayout, val icon: TextView, val name: TextView, val info: TextView) : RecyclerView.ViewHolder(row)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@DualPaneActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(8), dp(6), dp(8)); isClickable = true; isFocusable = true }
            val icon = TextView(this@DualPaneActivity).apply { gravity = Gravity.CENTER; textSize = iconDp / 2.2f }
            val name = TextView(this@DualPaneActivity).apply { gravity = Gravity.CENTER; textSize = 13f; maxLines = 2 }
            val info = TextView(this@DualPaneActivity).apply { gravity = Gravity.CENTER; textSize = 10f }
            row.addView(icon, LinearLayout.LayoutParams(-1, dp(iconDp + 4))); row.addView(name, LinearLayout.LayoutParams(-1, -2)); row.addView(info, LinearLayout.LayoutParams(-1, -2))
            return Holder(row, icon, name, info)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = items[position]
            holder.icon.text = when {
                file.isDirectory -> "📁"
                file.extension.lowercase() in listOf("zip", "rar", "7z") -> "🗜️"
                file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "gif") -> "🖼️"
                file.extension.lowercase() in listOf("mp3", "wav", "m4a", "ogg", "flac") -> "🎵"
                file.extension.lowercase() in listOf("mp4", "mkv", "webm", "avi") -> "🎬"
                file.extension.lowercase() in listOf("txt", "md", "log", "json", "xml", "csv") -> "📝"
                else -> "📄"
            }
            holder.name.text = file.name
            holder.info.text = if (!details) "" else if (file.isDirectory) "پوشه" else Formatter.formatFileSize(this@DualPaneActivity, file.length())
            holder.row.alpha = if (pane(paneIndex).selected.contains(file)) 0.65f else 1f
            holder.row.setOnClickListener { activePane = paneIndex; actionOpen(file, false); updateTitle() }
            holder.row.setOnLongClickListener { activePane = paneIndex; actionOpen(file, true); true }
        }
        private fun actionOpen(file: File, longPress: Boolean) {
            val p = pane(paneIndex)
            if (longPress || p.selected.isNotEmpty()) {
                if (!p.selected.add(file)) p.selected.remove(file)
                p.adapter.notifyDataSetChanged()
                status.text = p.selected.size.toString() + " مورد انتخاب شده"
            } else if (file.isDirectory) {
                p.current = file; p.selected.clear(); refresh(paneIndex)
            } else openFile(file, paneIndex)
        }
    }
}

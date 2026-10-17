package ir.solina.fm

import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
    private enum class SortMode { NAME, SIZE, MODIFIED, TYPE }
    private data class Pane(
        val root: LinearLayout, val path: TextView, val list: RecyclerView,
        val adapter: PaneAdapter, var current: File,
        val selected: LinkedHashSet<File> = linkedSetOf(),
        val backHistory: MutableList<File> = mutableListOf(),
        val forwardHistory: MutableList<File> = mutableListOf(),
        var query: String = "",
        var sortMode: SortMode = SortMode.NAME
    )
    private lateinit var panesHost: LinearLayout
    private lateinit var pathTitle: TextView
    private lateinit var navBack: MaterialButton
    private lateinit var navForward: MaterialButton
    private lateinit var searchInput: EditText
    private lateinit var bookmarkButton: MaterialButton
    private lateinit var sortButton: MaterialButton
    private lateinit var layoutButton: MaterialButton
    private lateinit var overflowButton: MaterialButton
    private lateinit var selectionActions: LinearLayout
    private lateinit var bottomActions: LinearLayout
    private lateinit var left: Pane
    private lateinit var right: Pane
    private var activePane = 0
    private var mode = LayoutMode.HORIZONTAL
    private var grid = false
    private var details = true
    private var iconDp = 38
    private var columns = 2
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedNightMode = getSharedPreferences("solina_preferences", MODE_PRIVATE).getInt("night_mode", 0)
        if (savedNightMode != 0) AppCompatDelegate.setDefaultNightMode(savedNightMode)
        super.onCreate(savedInstanceState)
        val base = Environment.getExternalStorageDirectory()
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(4)) }
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        navBack = MaterialButton(this).apply { text = "‹"; textSize = 26f; minWidth = dp(40); setPadding(0, 0, 0, 0); setOnClickListener { navigateHistory(activePane, true) } }
        navForward = MaterialButton(this).apply { text = "›"; textSize = 26f; minWidth = dp(40); setPadding(0, 0, 0, 0); setOnClickListener { navigateHistory(activePane, false) } }
        pathTitle = TextView(this).apply { textSize = 13f; maxLines = 1; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        searchInput = EditText(this).apply { hint = "جست‌وجوی فایل و پوشه"; setSingleLine(true); textSize = 13f; setPadding(dp(8), 0, dp(8), 0) }
        navigation.addView(navBack, LinearLayout.LayoutParams(dp(40), dp(48)))
        navigation.addView(navForward, LinearLayout.LayoutParams(dp(40), dp(48)))
        bookmarkButton = MaterialButton(this).apply { text = "☆"; minWidth = dp(34); setPadding(0, 0, 0, 0); contentDescription = "نشانک پوشه"; setOnClickListener { toggleBookmark() }; setOnLongClickListener { showBookmarks(); true } }
        sortButton = MaterialButton(this).apply { text = "☷"; minWidth = dp(36); setPadding(0, 0, 0, 0); contentDescription = "مرتب‌سازی و فیلتر"; setOnClickListener { showSortOptions(activePane) }; setOnLongClickListener { showDisplaySettings(); true } }
        layoutButton = MaterialButton(this).apply { text = "▥"; minWidth = dp(36); setPadding(0, 0, 0, 0); contentDescription = "تغییر حالت تقسیم صفحه"; setOnClickListener { toggleMode() } }
        overflowButton = MaterialButton(this).apply { text = "⋮"; minWidth = dp(34); setPadding(0, 0, 0, 0); textSize = 22f; contentDescription = "گزینه‌های بیشتر"; setOnClickListener { showTopMenu(this) } }
        navigation.addView(pathTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
        navigation.addView(searchInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        navigation.addView(sortButton, LinearLayout.LayoutParams(dp(36), dp(42)))
        navigation.addView(layoutButton, LinearLayout.LayoutParams(dp(36), dp(42)))
        navigation.addView(overflowButton, LinearLayout.LayoutParams(dp(34), dp(42)))
        screen.addView(navigation, LinearLayout.LayoutParams(-1, dp(52)))
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pane(activePane).query = s?.toString().orEmpty()
                refresh(activePane)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val selectionScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE }
        selectionActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        button(selectionActions, "انتخاب همه") { selectAll(activePane) }
        button(selectionActions, "برداشتن انتخاب") { clearPaneSelection(activePane) }
        button(selectionActions, "✎ A تغییر نام") { renameSelected(activePane) }
        button(selectionActions, "فشرده‌سازی") { compressSelected(activePane) }
        button(selectionActions, "استخراج") { extractSelectedArchives() }
        selectionScroll.addView(selectionActions)
        screen.addView(selectionScroll, LinearLayout.LayoutParams(-1, dp(48)))
        panesHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(panesHost, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        button(bottomActions, "کپی") { transfer(false) }
        button(bottomActions, "انتقال") { transfer(true) }
        button(bottomActions, "حذف") { deleteSelected(activePane) }
        screen.addView(bottomActions, LinearLayout.LayoutParams(-1, dp(52)))
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
        root.setOnDragListener { _, event -> handleDropEvent(index, event, null) }
        list.setOnDragListener { _, event -> handleDropEvent(index, event, null) }
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
            p.root.setOnClickListener { activePane = index; updateTopBar() }
            panesHost.addView(p.root)
            if (mode == LayoutMode.HORIZONTAL && index == 0) {
                panesHost.addView(View(this).apply { setBackgroundColor(0x223F51B5) },
                    LinearLayout.LayoutParams(dp(1), -1))
            }
            refresh(index)
        }
        updateTopBar()
    }

    private fun refresh(index: Int) {
        val p = pane(index)
        p.path.text = (if (index == 0) "چپ: " else "راست: ") + p.current.absolutePath
        val unsorted = p.current.listFiles()?.filter { it.isFile || it.isDirectory } ?: emptyList()
        val allFiles = when (p.sortMode) {
            SortMode.NAME -> unsorted.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            SortMode.SIZE -> unsorted.sortedWith(compareBy<File>({ !it.isDirectory }, { it.length() }))
            SortMode.MODIFIED -> unsorted.sortedByDescending { it.lastModified() }
            SortMode.TYPE -> unsorted.sortedWith(compareBy<File>({ !it.isDirectory }, { it.extension.lowercase(Locale.ROOT) }, { it.name.lowercase(Locale.ROOT) }))
        }
        p.selected.retainAll(allFiles.toSet())
        val query = p.query.trim().lowercase(Locale.ROOT)
        val files = allFiles.filter { query.isEmpty() || it.name.lowercase(Locale.ROOT).contains(query) }
        p.adapter.items = files
        p.adapter.notifyDataSetChanged()
        p.list.layoutManager = if (grid) GridLayoutManager(this, columns) else LinearLayoutManager(this)
        if (index == activePane) updateTopBar()
    }

    private fun updateTopBar() {
        listOf(left, right).forEachIndexed { index, pane ->
            pane.root.setBackgroundColor(if (activePane == index) 0x143F51B5 else 0x00000000)
            pane.path.text = (if (index == 0) "پنل چپ  •  " else "پنل راست  •  ") + pane.current.absolutePath
        }
        val p = pane(activePane)
        pathTitle.text = p.current.name.ifBlank { p.current.absolutePath }
        pathTitle.contentDescription = p.current.absolutePath
        navBack.isEnabled = p.backHistory.isNotEmpty() || p.current.parentFile != null
        navForward.isEnabled = p.forwardHistory.isNotEmpty()
        searchInput.setTextIfDifferent(p.query)
        val bookmarks = getBookmarks()
        bookmarkButton.text = if (p.current.absolutePath in bookmarks) "★" else "☆"
        layoutButton.text = if (mode == LayoutMode.HORIZONTAL) "▥" else "▤"
        layoutButton.contentDescription = if (mode == LayoutMode.HORIZONTAL) "تقسیم عمودی پنل‌ها" else "تقسیم افقی پنل‌ها"
        updateSelectionUi()
    }

    private fun EditText.setTextIfDifferent(value: String) {
        if (text.toString() != value) {
            setText(value)
            setSelection(text.length)
        }
    }

    private fun navigateTo(index: Int, dir: File) {
        val p = pane(index)
        if (p.current.path == dir.path) return
        p.backHistory.add(p.current)
        p.forwardHistory.clear()
        p.current = dir
        p.selected.clear()
        refresh(index)
        updateTopBar()
    }

    private fun navigateHistory(index: Int, back: Boolean) {
        val p = pane(index)
        val source = if (back) p.backHistory else p.forwardHistory
        val target = if (back) p.forwardHistory else p.backHistory
        if (source.isEmpty()) {
            if (back) goUp(index)
            return
        }
        target.add(p.current)
        p.current = source.removeAt(source.lastIndex)
        p.selected.clear()
        refresh(index)
        updateTopBar()
    }

    private fun getBookmarks(): MutableSet<String> =
        getSharedPreferences("solina_preferences", MODE_PRIVATE).getStringSet("bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun toggleBookmark() {
        val p = pane(activePane)
        val bookmarks = getBookmarks()
        if (!bookmarks.add(p.current.absolutePath)) bookmarks.remove(p.current.absolutePath)
        getSharedPreferences("solina_preferences", MODE_PRIVATE).edit().putStringSet("bookmarks", bookmarks).apply()
        updateTopBar()
        toast(if (p.current.absolutePath in bookmarks) "پوشه به نشانک‌ها اضافه شد" else "نشانک حذف شد")
    }

    private fun showBookmarks() {
        val bookmarks = getBookmarks().map { File(it) }.filter { it.exists() }
        if (bookmarks.isEmpty()) { toast("هنوز نشانکی ثبت نشده است"); return }
        AlertDialog.Builder(this).setTitle("نشانک‌ها")
            .setItems(bookmarks.map { it.absolutePath }.toTypedArray()) { _, which ->
                navigateTo(activePane, bookmarks[which])
            }.setNegativeButton("بستن", null).show()
    }

    private fun showSortOptions(index: Int) {
        val p = pane(index)
        val labels = arrayOf("نام", "حجم", "تاریخ تغییر", "نوع فایل")
        AlertDialog.Builder(this).setTitle("مرتب‌سازی")
            .setSingleChoiceItems(labels, p.sortMode.ordinal) { dialog, which ->
                p.sortMode = SortMode.values()[which]
                dialog.dismiss()
                refresh(index)
            }.setNegativeButton("لغو", null).show()
    }

    private fun directorySize(dir: File): Long = try {
        dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    } catch (_: Exception) { 0L }

    private fun selectAll(index: Int) {
        val p = pane(index)
        p.current.listFiles()?.filter { it.isFile || it.isDirectory }?.let { p.selected.addAll(it) }
        refresh(index)
        updateSelectionUi()
    }

    private fun clearPaneSelection(index: Int) {
        pane(index).selected.clear()
        refresh(index)
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        if (!::selectionActions.isInitialized || !::bottomActions.isInitialized) return
        val p = pane(activePane)
        val hasSelection = p.selected.isNotEmpty()
        (selectionActions.parent as? HorizontalScrollView)?.visibility = if (hasSelection) View.VISIBLE else View.GONE
        bottomActions.visibility = if (hasSelection) View.VISIBLE else View.GONE
        selectionActions.getChildAt(2)?.visibility = if (p.selected.size == 1) View.VISIBLE else View.GONE
        selectionActions.getChildAt(4)?.visibility = if (p.selected.any { it.isFile && it.extension.equals("zip", true) }) View.VISIBLE else View.GONE
    }

    private fun renameSelected(index: Int) {
        val p = pane(index)
        val file = p.selected.singleOrNull() ?: run { toast("برای تغییر نام فقط یک مورد انتخاب کنید"); return }
        askText("نام جدید", file.name) { name ->
            if (name.isBlank() || name.contains("/") || File(file.parentFile, name).exists()) {
                toast("نام نامعتبر است یا قبلاً وجود دارد")
            } else if (!file.renameTo(File(file.parentFile, name))) toast("تغییر نام انجام نشد")
            p.selected.clear()
            refresh(index)
        }
    }

    private fun compressSelected(index: Int) {
        val p = pane(index)
        val items = p.selected.toList()
        if (items.isEmpty()) return
        val target = uniqueTarget(p.current, File(if (items.size == 1) items[0].nameWithoutExtension + ".zip" else "archive.zip"))
        Thread {
            var failed = false
            try {
                ZipOutputStream(target.outputStream().buffered()).use { zip -> items.forEach { addToZip(it, it.name, zip) } }
            } catch (_: Exception) { failed = true; target.delete() }
            runOnUiThread {
                if (failed) toast("ساخت فایل فشرده ناموفق بود") else toast("فایل فشرده ساخته شد")
                p.selected.clear()
                refresh(index)
            }
        }.start()
    }

    private fun addToZip(file: File, entryName: String, zip: ZipOutputStream) {
        if (file.isDirectory) {
            val dirName = if (entryName.endsWith("/")) entryName else entryName + "/"
            zip.putNextEntry(ZipEntry(dirName))
            zip.closeEntry()
            file.listFiles()?.forEach { child -> addToZip(child, dirName + child.name, zip) }
        } else {
            zip.putNextEntry(ZipEntry(entryName))
            file.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun deleteSelected(index: Int) {
        val p = pane(index)
        val items = p.selected.toList()
        if (items.isEmpty()) return
        AlertDialog.Builder(this).setTitle("حذف موارد انتخاب‌شده")
            .setMessage("آیا از حذف " + items.size + " مورد مطمئن هستید؟")
            .setPositiveButton("حذف") { _, _ ->
                Thread {
                    var failed = 0
                    items.forEach { if (it.exists() && !it.deleteRecursively()) failed++ }
                    runOnUiThread {
                        p.selected.clear()
                        refresh(index)
                        toast(if (failed == 0) "حذف انجام شد" else "حذف برخی موارد ناموفق بود")
                    }
                }.start()
            }.setNegativeButton("لغو", null).show()
    }
    private fun toggleMode() {
        mode = if (mode == LayoutMode.VERTICAL) LayoutMode.HORIZONTAL else LayoutMode.VERTICAL
        renderPanes()
    }

    private fun showTopMenu(anchor: View) {
        android.widget.PopupMenu(this, anchor).apply {
            menu.add("نشانک این پوشه").setOnMenuItemClickListener { toggleBookmark(); true }
            menu.add("پوشه‌های نشانک‌شده").setOnMenuItemClickListener { showBookmarks(); true }
            menu.add("نمایش فایل‌ها").setOnMenuItemClickListener { showDisplaySettings(); true }
            menu.add("تعداد ستون‌ها").setOnMenuItemClickListener { showLayoutSettings(); true }
            menu.add("تم روشن/تیره").setOnMenuItemClickListener { toggleTheme(); true }
            show()
        }
    }

    private fun toggleTheme() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val nextMode = if (isNight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        getSharedPreferences("solina_preferences", MODE_PRIVATE).edit().putInt("night_mode", nextMode).apply()
        AppCompatDelegate.setDefaultNightMode(nextMode)
    }

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
        p.current.parentFile?.takeIf { it.exists() }?.let { navigateTo(index, it) }
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
                            val copied = src.copyRecursively(target, overwrite = false); if (copied) src.deleteRecursively() else { target.deleteRecursively(); failed++ }
                        }
                    } else if (!src.copyRecursively(target, overwrite = false)) failed++
                } catch (_: Exception) { failed++ }
            }
            runOnUiThread {
                source.selected.clear(); refresh(sourceIndex); refresh(1 - sourceIndex)
                updateSelectionUi()
                toast(if (failed == 0) "عملیات با موفقیت انجام شد" else "$failed مورد ناموفق بود")
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
                runOnUiThread { refresh(1 - sourceIndex); toast(if (failed == 0) "استخراج تمام شد" else "استخراج ناموفق؛ رمز یا سلامت فایل را بررسی کنید") }
            }.start()
        }
    }

    private fun handleDropEvent(targetPaneIndex: Int, event: android.view.DragEvent, targetFile: File?): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> return event.clipDescription?.label == "solina-file"
            android.view.DragEvent.ACTION_DRAG_ENTERED, android.view.DragEvent.ACTION_DRAG_EXITED -> return true
            android.view.DragEvent.ACTION_DROP -> {
                val sourcePath = event.clipData?.getItemAt(0)?.text?.toString() ?: return false
                val source = File(sourcePath)
                val destDir = targetFile?.takeIf { it.isDirectory } ?: pane(targetPaneIndex).current
                if (!source.exists() || !destDir.isDirectory || destDir.absolutePath == source.absolutePath ||
                    (source.isDirectory && destDir.absolutePath.startsWith(source.absolutePath + File.separator))) {
                    toast("مقصد انتخاب‌شده معتبر نیست")
                    return true
                }
                if (source.parentFile?.absolutePath == destDir.absolutePath) return true
                Thread {
                    var failed = false
                    try {
                        val target = uniqueTarget(destDir, source)
                        if (!source.renameTo(target)) {
                            val copied = source.copyRecursively(target, overwrite = false)
                            if (copied) source.deleteRecursively() else { target.deleteRecursively(); failed = true }
                        }
                    } catch (_: Exception) { failed = true }
                    runOnUiThread {
                        pane(0).selected.remove(source); pane(1).selected.remove(source)
                        refresh(0); refresh(1); updateTopBar()
                        toast(if (failed) "جابه‌جایی ناموفق بود" else "فایل جابه‌جا شد")
                    }
                }.start()
                return true
            }
        }
        return true
    }

    private fun openFile(file: File, paneIndex: Int) {
        when (file.extension.lowercase(Locale.ROOT)) {
            "txt", "md", "log", "json", "xml", "csv", "tsv", "ini", "kt", "java",
            "html", "htm", "css", "js", "gradle", "kts", "yml", "yaml", "sh",
            "properties", "conf", "cfg", "sql", "py", "c", "cpp", "h", "rs" -> editTextFile(file)
            "zip" -> browseArchive(file, paneIndex)
            "rar", "7z" -> startActivity(Intent(this, ArchiveToolsActivity::class.java).putExtra("archive_path", file.absolutePath))
            "jpg", "jpeg", "png", "webp", "bmp", "gif" -> viewImage(file)
            "mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mid", "midi" -> playAudio(file)
            "mp4", "m4v", "3gp", "webm", "mkv", "avi", "mov", "mpeg", "mpg" -> playVideo(file)
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
        try {
            val original = BitmapFactory.decodeFile(file.absolutePath)
            if (original == null) { toast("تصویر خراب است یا قالب آن پشتیبانی نمی‌شود"); return }
            var edited = original
            val image = ImageView(this).apply {
                setImageBitmap(edited); adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val dialog = AlertDialog.Builder(this).setTitle(file.name).setView(image)
                .setNeutralButton("چرخش ۹۰°", null)
                .setPositiveButton("ذخیره نسخه ویرایش‌شده", null)
                .setNegativeButton("بستن", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    try {
                        val matrix = Matrix().apply { postRotate(90f) }
                        val next = android.graphics.Bitmap.createBitmap(edited, 0, 0, edited.width, edited.height, matrix, true)
                        if (edited !== original) edited.recycle()
                        edited = next
                        image.setImageBitmap(edited)
                    } catch (_: Exception) { toast("چرخش تصویر ناموفق بود") }
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    try {
                        val out = uniqueTarget(file.parentFile ?: Environment.getExternalStorageDirectory(),
                            File(file.nameWithoutExtension + "-edited.jpg"))
                        FileOutputStream(out).use { edited.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                        toast("نسخه ویرایش‌شده ذخیره شد: " + out.name)
                    } catch (_: Exception) { toast("ذخیره تصویر ناموفق بود") }
                }
            }
            dialog.setOnDismissListener { if (edited !== original) edited.recycle(); original.recycle() }
            dialog.show()
        } catch (_: Exception) { toast("بازکردن تصویر ناموفق بود") }
    }

    private fun playAudio(file: File) {
        try {
            player?.release()
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(file.absolutePath)
            val seek = SeekBar(this)
            val play = Button(this).apply { text = "پخش / توقف" }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(TextView(this@DualPaneActivity).apply { text = file.name; setPadding(dp(4), dp(8), dp(4), dp(8)) })
                addView(seek)
                addView(play)
            }
            val dialog = AlertDialog.Builder(this).setTitle("پخش‌کننده صوتی").setView(layout)
                .setNegativeButton("بستن", null).create()
            mp.setOnPreparedListener { prepared ->
                seek.max = prepared.duration.coerceAtLeast(1)
                play.setOnClickListener {
                    if (prepared.isPlaying) { prepared.pause(); play.text = "ادامه پخش" }
                    else { prepared.start(); play.text = "توقف" }
                }
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) prepared.seekTo(progress) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
                prepared.start()
                play.text = "توقف"
            }
            mp.setOnErrorListener { _, _, _ -> toast("این قالب صوتی پشتیبانی نمی‌شود"); true }
            dialog.setOnDismissListener {
                try { if (mp.isPlaying) mp.stop() } catch (_: Exception) {}
                mp.release()
                if (player === mp) player = null
            }
            dialog.show()
            mp.prepareAsync()
        } catch (_: Exception) { toast("پخش این فایل صوتی ممکن نیست؛ قالب یا سلامت فایل را بررسی کنید") }
    }

    private fun playVideo(file: File) {
        try {
            val video = VideoView(this)
            video.setVideoPath(file.absolutePath)
            val play = Button(this).apply { text = "پخش / توقف" }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(video, LinearLayout.LayoutParams(-1, dp(260)))
                addView(play)
            }
            val dialog = AlertDialog.Builder(this).setTitle(file.name).setView(layout)
                .setNegativeButton("بستن", null).create()
            video.setOnPreparedListener { video.start() }
            video.setOnErrorListener { _, _, _ -> toast("این قالب ویدئویی پشتیبانی نمی‌شود"); true }
            play.setOnClickListener {
                if (video.isPlaying) { video.pause(); play.text = "ادامه پخش" }
                else { video.start(); play.text = "توقف" }
            }
            dialog.setOnDismissListener { video.stopPlayback() }
            dialog.show()
        } catch (_: Exception) { toast("پخش این ویدئو ممکن نیست؛ قالب یا سلامت فایل را بررسی کنید") }
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
        val params = if (::selectionActions.isInitialized && parent === selectionActions)
            LinearLayout.LayoutParams(-2, -1)
        else LinearLayout.LayoutParams(0, -1, 1f)
        parent.addView(b, params)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    private inner class PaneAdapter(private val paneIndex: Int) : RecyclerView.Adapter<PaneAdapter.Holder>() {
        var items: List<File> = emptyList()
        inner class Holder(val row: LinearLayout, val check: CheckBox, val icon: ImageView, val content: LinearLayout, val name: TextView, val info: TextView) : RecyclerView.ViewHolder(row)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@DualPaneActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6)); isClickable = true; isFocusable = true }
            val check = CheckBox(this@DualPaneActivity)
            val content = LinearLayout(this@DualPaneActivity).apply {
                orientation = if (mode == LayoutMode.VERTICAL) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val icon = ImageView(this@DualPaneActivity).apply {
                val tv = android.util.TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                imageTintList = android.content.res.ColorStateList.valueOf(tv.data)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val name = TextView(this@DualPaneActivity).apply { gravity = if (mode == LayoutMode.VERTICAL) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER; textSize = 13f; maxLines = 2 }
            val info = TextView(this@DualPaneActivity).apply { gravity = if (mode == LayoutMode.VERTICAL) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER; textSize = 10f }
            row.addView(check, LinearLayout.LayoutParams(-2, -2))
            if (mode == LayoutMode.VERTICAL) {
                content.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
                val textBlock = LinearLayout(this@DualPaneActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                textBlock.addView(name, LinearLayout.LayoutParams(-1, -2))
                textBlock.addView(info, LinearLayout.LayoutParams(-1, -2))
                content.addView(textBlock)
            } else {
                content.addView(icon, LinearLayout.LayoutParams(-1, dp(iconDp + 4)))
                content.addView(name, LinearLayout.LayoutParams(-1, -2))
                content.addView(info, LinearLayout.LayoutParams(-1, -2))
            }
            row.addView(content)
            return Holder(row, check, icon, content, name, info)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = items[position]
            holder.icon.setImageResource(if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
            holder.content.orientation = if (mode == LayoutMode.VERTICAL) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val iconParams = holder.icon.layoutParams
            if (mode == LayoutMode.VERTICAL) {
                iconParams.width = dp(42); iconParams.height = dp(42)
            } else {
                iconParams.width = -1; iconParams.height = dp(iconDp + 4)
            }
            holder.icon.layoutParams = iconParams
            val tint = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tint, true)
            holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(tint.data)
            holder.name.text = file.name
            holder.info.text = if (!details) "" else if (file.isDirectory) "پوشه" else Formatter.formatFileSize(this@DualPaneActivity, file.length())
            val p = pane(paneIndex)
            holder.row.alpha = if (p.selected.contains(file)) 0.85f else 1f
            holder.check.setOnCheckedChangeListener(null)
            holder.check.visibility = if (p.selected.isNotEmpty()) View.VISIBLE else View.GONE
            holder.check.isChecked = p.selected.contains(file)
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked) p.selected.add(file) else p.selected.remove(file)
                activePane = paneIndex
                updateTopBar()
                refresh(paneIndex)
            }
            holder.row.setOnClickListener { activePane = paneIndex; actionOpen(file, false); updateTopBar() }
            holder.row.setOnLongClickListener {
                activePane = paneIndex
                val selectedAlready = p.selected.contains(file)
                if (!selectedAlready) {
                    actionOpen(file, true)
                } else {
                    val clip = ClipData.newPlainText("solina-file", file.absolutePath)
                    holder.row.startDragAndDrop(clip, View.DragShadowBuilder(holder.row), file.absolutePath, 0)
                }
                true
            }
            holder.row.setOnDragListener { _, event -> handleDropEvent(paneIndex, event, file) }
        }
        private fun actionOpen(file: File, longPress: Boolean) {
            val p = pane(paneIndex)
            if (longPress || p.selected.isNotEmpty()) {
                if (!p.selected.add(file)) p.selected.remove(file)
                refresh(paneIndex)
                updateTopBar()
                toast(p.selected.size.toString() + " مورد انتخاب شده")
            } else if (file.isDirectory) {
                navigateTo(paneIndex, file)
            } else openFile(file, paneIndex)
        }
    }
}

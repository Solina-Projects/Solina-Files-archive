package ir.solina.fm

import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        var sortMode: SortMode = SortMode.NAME,
        var isEmpty: Boolean = false
    )
    private lateinit var panesHost: LinearLayout
    private lateinit var searchIconButton: MaterialButton
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
    private var showHiddenFiles = false
    private var iconDp = 38
    private var columns = 2
    private var pendingInstallApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedNightMode = getSharedPreferences("solina_preferences", MODE_PRIVATE).getInt("night_mode", 0)
        if (savedNightMode != 0) AppCompatDelegate.setDefaultNightMode(savedNightMode)
        super.onCreate(savedInstanceState)
        val base = Environment.getExternalStorageDirectory()
        val preferences = getSharedPreferences("solina_preferences", MODE_PRIVATE)
        val requestedRight = intent.getStringExtra(EXTRA_RIGHT_PATH)?.let(::File)?.takeIf { it.isDirectory }
            ?: preferences.getString(KEY_RIGHT_PATH, null)?.let(::File)?.takeIf { it.isDirectory }
            ?: base
        val savedLeft = preferences.getString(KEY_LEFT_PATH, null)?.let(::File)?.takeIf { it.isDirectory }
        val surfaceColor = android.util.TypedValue().also { theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, it, true) }.data
        val onSurfaceColor = android.util.TypedValue().also { theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, it, true) }.data
        val surfaceVariantColor = android.util.TypedValue().also { theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, it, true) }.data
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(4)); setBackgroundColor(surfaceColor) }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(2), 0, dp(2), 0)
        }
        searchIconButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setIconResource(android.R.drawable.ic_menu_search)
            contentDescription = "جست‌وجو"
            minWidth = dp(34)
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                searchInput.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        searchInput = EditText(this).apply {
            hint = "جست‌وجوی فایل و پوشه"
            setSingleLine(true)
            textSize = 13f
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(onSurfaceColor)
            setHintTextColor(surfaceVariantColor)
            background = null
            contentDescription = "جست‌وجوی فایل و پوشه"
        }
        bookmarkButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setIconResource(R.drawable.ic_pin); minWidth = dp(34); setPadding(0, 0, 0, 0); contentDescription = "نشانک پوشه"; setOnClickListener { toggleBookmark() }; setOnLongClickListener { showBookmarks(); true } }
        sortButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setIconResource(R.drawable.ic_sort); minWidth = dp(36); setPadding(0, 0, 0, 0); contentDescription = "مرتب‌سازی و فیلتر"; setOnClickListener { showSortOptions(activePane) }; setOnLongClickListener { showDisplaySettings(); true } }
        layoutButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setIconResource(R.drawable.ic_splitscreen); minWidth = dp(36); setPadding(0, 0, 0, 0); contentDescription = "تغییر حالت تقسیم صفحه"; setOnClickListener { toggleMode() } }
        overflowButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setIconResource(R.drawable.ic_more_vert); minWidth = dp(34); setPadding(0, 0, 0, 0); contentDescription = "گزینه‌های بیشتر"; setOnClickListener { showTopMenu(this) } }
        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@DualPaneActivity, R.drawable.search_field_bg)
            setPadding(dp(4), 0, dp(4), 0)
        }
        searchContainer.addView(searchIconButton, LinearLayout.LayoutParams(dp(32), dp(36)))
        searchContainer.addView(searchInput, LinearLayout.LayoutParams(0, dp(36), 1f))
        navigation.addView(searchContainer, LinearLayout.LayoutParams(0, dp(38), 1f))
        navigation.addView(sortButton, LinearLayout.LayoutParams(dp(42), dp(40)))
        navigation.addView(layoutButton, LinearLayout.LayoutParams(dp(42), dp(40)))
        navigation.addView(overflowButton, LinearLayout.LayoutParams(dp(42), dp(40)))
        screen.addView(navigation, LinearLayout.LayoutParams(-1, dp(44)))
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
        button(selectionActions, "بیشتر") { showSelectedTools() }
        selectionScroll.addView(selectionActions)
        screen.addView(selectionScroll, LinearLayout.LayoutParams(-1, dp(46)))
        panesHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(panesHost, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        button(bottomActions, "کپی") { transfer(false) }
        button(bottomActions, "انتقال") { transfer(true) }
        button(bottomActions, "حذف") { deleteSelected(activePane) }
        screen.addView(bottomActions, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(screen)
        left = createPane(savedLeft ?: base, 0).apply { isEmpty = savedLeft == null }
        right = createPane(requestedRight, 1)
        activePane = 1
        renderPanes()
    }

    private fun createPane(start: File, index: Int): Pane {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(2), 0)
        }
        val path = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                activePane = index
                updateTopBar()
                if (pane(index).isEmpty) chooseFolderForPane(index) else showPathActions(index)
            }
            setOnLongClickListener {
                activePane = index
                updateTopBar()
                val clipboard = android.content.ClipData.newPlainText("path", pane(index).current.absolutePath)
                val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                manager.setPrimaryClip(clipboard)
                toast("مسیر پوشه کپی شد")
                true
            }
        }
        val upButton = MaterialButton(this).apply {
            text = "↑"
            textSize = 18f
            minWidth = dp(36)
            minimumWidth = dp(36)
            setPadding(0, 0, 0, 0)
            contentDescription = "رفتن به پوشه والد"
            setOnClickListener {
                activePane = index
                goUp(index)
                updateTopBar()
            }
        }
        header.addView(path, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(upButton, LinearLayout.LayoutParams(dp(38), dp(40)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(48)))
        val list = RecyclerView(this).apply {
            setOnClickListener {
                activePane = index
                updateTopBar()
            }
        }
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
            p.root.setBackgroundColor(if (activePane == index) androidx.core.graphics.ColorUtils.setAlphaComponent(resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer), 38) else 0x00000000)
            p.root.alpha = if (activePane == index) 1f else 0.55f
            p.root.setOnClickListener { activePane = index; updateTopBar() }
            panesHost.addView(p.root)
            if (mode == LayoutMode.HORIZONTAL && index == 0) {
                panesHost.addView(View(this).apply { setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(resolveThemeColor(com.google.android.material.R.attr.colorOutline), 100)) },
                    LinearLayout.LayoutParams(dp(1), -1))
            }
            refresh(index)
        }
        updateTopBar()
    }

    private fun refresh(index: Int) {
        val p = pane(index)
        p.path.text = if (p.isEmpty) "چپ • انتخاب پوشه" else (if (index == 0) "چپ • " else "راست • ") + p.current.name.ifBlank { "/" }
        if (p.isEmpty) {
            p.adapter.items = emptyList()
            p.adapter.notifyDataSetChanged()
            p.list.layoutManager = if (grid) GridLayoutManager(this, columns) else LinearLayoutManager(this)
            if (index == activePane) updateTopBar()
            return
        }
        val unsorted = p.current.listFiles()
            ?.filter { (it.isFile || it.isDirectory) && (showHiddenFiles || !it.name.startsWith(".")) }
            ?: emptyList()
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
            pane.root.setBackgroundColor(
                if (activePane == index) androidx.core.graphics.ColorUtils.setAlphaComponent(
                    resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer), 38
                ) else 0x00000000
            )
            pane.root.alpha = if (activePane == index) 1f else 0.55f
            pane.path.text = if (pane.isEmpty) "چپ • انتخاب پوشه" else (if (index == 0) "چپ • " else "راست • ") + pane.current.name.ifBlank { "/" }
            pane.path.contentDescription = if (pane.isEmpty) "برای انتخاب پوشه لمس کنید" else pane.current.absolutePath
        }
        val p = pane(activePane)
        searchInput.setTextIfDifferent(p.query)
        val bookmarks = getBookmarks()
        bookmarkButton.text = if (p.current.absolutePath in bookmarks) "★" else "☆"
        layoutButton.setIconResource(if (mode == LayoutMode.HORIZONTAL) R.drawable.ic_splitscreen else R.drawable.ic_grid_view)
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
        if (!dir.isDirectory) return
        if (!p.isEmpty && p.current.path == dir.path) return
        if (!p.isEmpty) p.backHistory.add(p.current)
        p.isEmpty = false
        p.forwardHistory.clear()
        p.current = dir
        p.selected.clear()
        getSharedPreferences("solina_preferences", MODE_PRIVATE).edit()
            .putString(if (index == 0) KEY_LEFT_PATH else KEY_RIGHT_PATH, dir.absolutePath).apply()
        refresh(index)
        updateTopBar()
    }

    private fun chooseFolderForPane(index: Int) {
        val base = Environment.getExternalStorageDirectory()
        val candidates = (listOf(base) + (base.listFiles()?.filter { it.isDirectory } ?: emptyList()))
            .distinctBy { it.absolutePath }
        AlertDialog.Builder(this)
            .setTitle("انتخاب پوشه برای پنل چپ")
            .setItems(candidates.map { if (it == base) "حافظهٔ داخلی" else it.name }.toTypedArray()) { _, which ->
                activePane = index
                navigateTo(index, candidates[which])
            }
            .setNegativeButton("لغو", null)
            .show()
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
        val files = p.selected.sortedBy { it.name.lowercase(Locale.ROOT) }
        if (files.isEmpty()) {
            toast("ابتدا فایل یا پوشه‌ای را انتخاب کنید")
            return
        }
        if (files.size == 1) {
            val file = files.single()
            askText("نام جدید", file.name) { name ->
                if (name.isBlank() || name == "." || name == ".." || name.contains("/") ||
                    File(file.parentFile, name).exists()) {
                    toast("نام نامعتبر است یا قبلاً وجود دارد")
                } else if (!file.renameTo(File(file.parentFile, name))) {
                    toast("تغییر نام انجام نشد")
                }
                p.selected.clear()
                refresh(index)
            }
            return
        }

        askText("الگوی تغییر نام گروهی", "{name}_{n}") { pattern ->
            if (pattern.isBlank() || pattern.contains("/")) {
                toast("الگوی نامعتبر است")
                return@askText
            }
            val targets = files.mapIndexed { i, file ->
                val ext = file.extension
                pattern.replace("{name}", file.nameWithoutExtension)
                    .replace("{ext}", ext)
                    .replace("{n}", (i + 1).toString())
                    .let { if ("{ext}" !in pattern && ext.isNotEmpty()) "$it.$ext" else it }
            }
            val sourcePaths = files.map { it.absoluteFile.toPath().normalize().toString() }.toSet()
            val targetFiles = files.zip(targets).map { (file, name) -> File(file.parentFile, name) }
            val duplicateNames = targets.map { it.lowercase(Locale.ROOT) }.toSet().size != targets.size
            val invalidNames = targets.any {
                it.isBlank() || it == "." || it == ".." || it.contains("/") ||
                    it.toByteArray(Charsets.UTF_8).size > 255
            }
            val collides = targetFiles.any { target ->
                target.exists() && target.absoluteFile.toPath().normalize().toString() !in sourcePaths
            }
            if (duplicateNames || invalidNames || collides) {
                toast("الگو نام تکراری، نامعتبر یا مقصد موجود ایجاد می‌کند")
                return@askText
            }

            Thread {
                val temporary = mutableListOf<Pair<File, File>>()
                val finalized = mutableListOf<Pair<File, File>>()
                var failed = false
                try {
                    files.forEachIndexed { i, source ->
                        val temp = File(source.parentFile, ".solina-rename-${System.nanoTime()}-$i")
                        if (!source.renameTo(temp)) throw IllegalStateException("temporary rename failed")
                        temporary += source to temp
                    }
                    temporary.forEachIndexed { i, (original, temp) ->
                        val target = targetFiles[i]
                        if (!temp.renameTo(target)) throw IllegalStateException("final rename failed")
                        finalized += original to target
                    }
                } catch (_: Exception) {
                    failed = true
                    finalized.asReversed().forEach { (original, target) ->
                        if (target.exists()) target.renameTo(original)
                    }
                    temporary.filter { (_, temp) -> temp.exists() }.asReversed().forEach { (original, temp) ->
                        if (temp.exists()) temp.renameTo(original)
                    }
                }
                runOnUiThread {
                    if (!failed) p.selected.clear()
                    refresh(index)
                    updateSelectionUi()
                    toast(if (failed) "تغییر نام گروهی کامل نشد؛ بازگردانی تا حد امکان انجام شد" else "${files.size} مورد تغییر نام یافت")
                }
            }.start()
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
    private fun showSelectedTools() {
        val selectedFiles = pane(activePane).selected.toList()
        if (selectedFiles.isEmpty()) {
            toast("ابتدا فایل یا پوشه‌ای را انتخاب کنید")
            return
        }
        val labels = arrayListOf("مشخصات", "کپی مسیر", "اشتراک‌گذاری فایل‌ها", "ابزارهای تکمیلی")
        val single = selectedFiles.singleOrNull()
        if (single != null && single.isFile &&
            single.extension.lowercase(Locale.ROOT) in setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")) {
            labels += "مرور و استخراج آرشیو"
        }
        if (single != null && single.isFile && single.extension.equals("apk", ignoreCase = true)) {
            labels += "نصب APK"
            labels += "تحلیل APK و DEX"
        }
        if (single != null && single.isFile && single.extension.equals("pdf", true)) {
            labels += "مشاهده PDF"
        }
        if (single != null && single.isFile) {
            labels += "نمایش هگز"
        }
        AlertDialog.Builder(this)
            .setTitle(if (selectedFiles.size == 1) selectedFiles.first().name else "${selectedFiles.size} مورد انتخاب‌شده")
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    "مشخصات" -> showSelectedProperties(selectedFiles)
                    "کپی مسیر" -> {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("paths", selectedFiles.joinToString("\n") { it.absolutePath }))
                        toast("مسیرها کپی شدند")
                    }
                    "اشتراک‌گذاری فایل‌ها" -> shareSelectedFiles(selectedFiles)
                    "ابزارهای تکمیلی" -> startActivity(
                        Intent(this, FileToolsActivity::class.java)
                            .putExtra("root_path", pane(activePane).current.absolutePath)
                            .putStringArrayListExtra("selected_paths", ArrayList(selectedFiles.map { it.absolutePath }))
                    )
                    "مرور و استخراج آرشیو" -> single?.let {
                        startActivity(Intent(this, ArchiveToolsActivity::class.java).putExtra("archive_path", it.absolutePath))
                    }
                    "نصب APK" -> single?.let { installApk(it) }
                    "تحلیل APK و DEX" -> single?.let { apk ->
                        startActivity(Intent(this, ApkToolsActivity::class.java).putExtra("apk_path", apk.absolutePath))
                    }
                    "مشاهده PDF" -> single?.let { file ->
                        startActivity(Intent(this, DocumentViewerActivity::class.java).putExtra("file_path", file.absolutePath))
                    }
                    "نمایش هگز" -> single?.let { file ->
                        startActivity(Intent(this, HexViewerActivity::class.java).putExtra("file_path", file.absolutePath))
                    }
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun showSelectedProperties(files: List<File>) {
        val detailsText = files.joinToString("\n\n") { file ->
            val kind = when {
                file.isDirectory -> "پوشه"
                file.extension.isNotBlank() -> "فایل • ${file.extension.uppercase(Locale.ROOT)}"
                else -> "فایل"
            }
            val size = if (file.isFile) Formatter.formatFileSize(this, file.length()) else "${file.listFiles()?.size ?: 0} مورد داخل پوشه"
            val modified = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified()))
            "${file.name}\nنوع: $kind\nاندازه: $size\nآخرین تغییر: $modified\nمسیر: ${file.absolutePath}"
        }
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = detailsText
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        })
        AlertDialog.Builder(this)
            .setTitle(if (files.size == 1) "مشخصات فایل" else "مشخصات ${files.size} مورد")
            .setView(scroll)
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun shareSelectedFiles(files: List<File>) {
        val shareable = files.filter { it.isFile }
        if (shareable.isEmpty()) {
            toast("برای اشتراک‌گذاری، فایل انتخاب کنید")
            return
        }
        try {
            val uris = ArrayList<Uri>()
            shareable.forEach { file ->
                uris += FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }
            val shareIntent = if (uris.size == 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
            shareIntent.type = "*/*"
            if (uris.size == 1) shareIntent.putExtra(Intent.EXTRA_STREAM, uris.first())
            else shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(shareIntent, "اشتراک‌گذاری با"))
        } catch (_: Exception) {
            toast("اشتراک‌گذاری فایل انجام نشد")
        }
    }

    private fun toggleMode() {
        mode = if (mode == LayoutMode.VERTICAL) LayoutMode.HORIZONTAL else LayoutMode.VERTICAL
        if (mode == LayoutMode.HORIZONTAL) grid = false
        renderPanes()
    }

    private fun showTopMenu(anchor: View) {
        android.widget.PopupMenu(this, anchor).apply {
            menu.add("نشانک این پوشه").setIcon(R.drawable.ic_pin).setOnMenuItemClickListener { toggleBookmark(); true }
            menu.add("پوشه‌های نشانک‌شده").setIcon(R.drawable.ic_folder).setOnMenuItemClickListener { showBookmarks(); true }
            menu.add("نمایش فایل‌ها").setIcon(R.drawable.ic_list_view).setOnMenuItemClickListener { showDisplaySettings(); true }
            menu.add("تعداد ستون‌ها").setIcon(R.drawable.ic_grid_view).setOnMenuItemClickListener { showLayoutSettings(); true }
            menu.add("تم روشن/تیره").setIcon(R.drawable.ic_menu).setOnMenuItemClickListener { toggleTheme(); true }
            menu.add("ابزارها و قابلیت‌های بیشتر").setIcon(R.drawable.ic_clean).setOnMenuItemClickListener {
                showIntegratedTools()
                true
            }
            try {
                val field = javaClass.getDeclaredField("mPopup")
                field.isAccessible = true
                (field.get(this) as? androidx.appcompat.view.menu.MenuPopupHelper)?.setForceShowIcon(true)
            } catch (_: Exception) { }
            show()
        }
    }

    /**
     * Opens the file-management tools already shipped with Solina from inside Dual Pane.
     * This does not imply that every Strata-only feature has been ported.
     */
    private fun showIntegratedTools() {
        val labels = arrayOf(
            "برنامه‌های نصب‌شده و اطلاعات برنامه",
            "ابزارهای APK",
            "مدیریت آرشیوها",
            "ابزارهای فایل و تغییرنام گروهی",
            "ذخیره‌سازی شبکه",
            "اشتراک‌گذاری از طریق Wi-Fi",
            "سطل بازیافت",
            "فایل‌های اخیر",
            "گاوصندوق",
            "مرورگر فایل با دسترسی ریشه",
            "تنظیمات پیشرفته"
        )
        AlertDialog.Builder(this)
            .setTitle("ابزارهای Solina در پنل دوگانه")
            .setItems(labels) { _, which ->
                val intent = when (which) {
                    0 -> Intent(this, InstalledAppsActivity::class.java)
                    1 -> Intent(this, ApkToolsActivity::class.java)
                    2 -> Intent(this, ArchiveToolsActivity::class.java)
                    3 -> Intent(this, FileToolsActivity::class.java)
                        .putExtra("root_path", pane(activePane).current.absolutePath)
                    4 -> Intent(this, NetworkStorageActivity::class.java)
                    5 -> Intent(this, WifiTransferActivity::class.java)
                    6 -> Intent(this, RecycleBinActivity::class.java)
                    7 -> Intent(this, RecentFilesActivity::class.java)
                    8 -> Intent(this, VaultActivity::class.java)
                    9 -> Intent(this, RootExplorerActivity::class.java)
                    else -> Intent(this, AdvancedSettingsActivity::class.java)
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    toast("بازکردن این ابزار ممکن نشد")
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun toggleTheme() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val nextMode = if (isNight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        getSharedPreferences("solina_preferences", MODE_PRIVATE).edit().putInt("night_mode", nextMode).apply()
        AppCompatDelegate.setDefaultNightMode(nextMode)
    }

    private fun showDisplaySettings() {
        val options = arrayOf(
            "جزئیات: " + if (details) "روشن" else "خاموش",
            "شبکه‌ای: " + if (grid) "روشن" else "خاموش",
            "فایل‌های مخفی: " + if (showHiddenFiles) "نمایش داده می‌شوند" else "پنهان",
            "اندازه آیکون: " + iconDp + " dp"
        )
        AlertDialog.Builder(this).setTitle("نمایش فایل‌ها").setItems(options) { _, which ->
            when (which) {
                0 -> details = !details
                1 -> grid = !grid
                2 -> showHiddenFiles = !showHiddenFiles
                3 -> { showIconSize(); return@setItems }
            }
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
        val destinationPane = other(sourceIndex)
        if (destinationPane.isEmpty) {
            activePane = 1 - sourceIndex
            updateTopBar()
            toast("ابتدا پوشهٔ مقصد را در پنل چپ انتخاب کنید")
            chooseFolderForPane(activePane)
            return
        }
        val dest = destinationPane.current
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

    /**
     * Drag-and-drop for one or many selected entries. JSON keeps paths unambiguous,
     * including file names that contain line breaks. Collisions use uniqueTarget().
     */
    private fun handleDropEvent(targetPaneIndex: Int, event: android.view.DragEvent, targetFile: File?): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> {
                val label = event.clipDescription?.label?.toString()
                return label == "solina-files-v1" || label == "solina-file"
            }
            android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                pane(targetPaneIndex).root.isActivated = true
                pane(targetPaneIndex).root.alpha = 0.96f
                return true
            }
            android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> {
                pane(targetPaneIndex).root.isActivated = false
                pane(targetPaneIndex).root.alpha = 1f
                return true
            }
            android.view.DragEvent.ACTION_DROP -> {
                val raw = event.clipData?.getItemAt(0)?.text?.toString() ?: return false
                val sourcePaths = runCatching {
                    if (event.clipDescription?.label?.toString() == "solina-files-v1") {
                        val json = org.json.JSONArray(raw)
                        (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
                    } else listOf(raw)
                }.getOrElse { listOf(raw) }.distinct()
                val sources = sourcePaths.map(::File).filter { it.exists() }
                val destDir = targetFile?.takeIf { it.isDirectory } ?: pane(targetPaneIndex).current
                pane(targetPaneIndex).root.isActivated = false
                pane(targetPaneIndex).root.alpha = 1f

                if (sources.isEmpty() || !destDir.isDirectory) {
                    toast("فایل مبدأ یا پوشهٔ مقصد در دسترس نیست")
                    return true
                }
                val invalid = sources.any { source ->
                    source.absoluteFile == destDir.absoluteFile ||
                        (source.isDirectory && destDir.absolutePath.startsWith(source.absolutePath.trimEnd(File.separatorChar) + File.separator))
                }
                if (invalid) {
                    toast("انتقال پوشه به داخل خودش مجاز نیست")
                    return true
                }

                val candidates = sources.filter { it.parentFile?.absolutePath != destDir.absolutePath }
                if (candidates.isEmpty()) return true

                Thread {
                    val failed = mutableListOf<File>()
                    var moved = 0
                    candidates.forEach { source ->
                        try {
                            val target = uniqueTarget(destDir, source)
                            val success = if (source.renameTo(target)) {
                                true
                            } else {
                                val copied = source.copyRecursively(target, overwrite = false)
                                if (copied && source.deleteRecursively()) {
                                    true
                                } else {
                                    // If the copy itself failed, remove its partial destination.
                                    if (!copied) target.deleteRecursively()
                                    false
                                }
                            }
                            if (success) moved++ else failed += source
                        } catch (_: Exception) {
                            failed += source
                        }
                    }
                    runOnUiThread {
                        candidates.filter { it !in failed }.forEach { source ->
                            pane(0).selected.remove(source)
                            pane(1).selected.remove(source)
                        }
                        refresh(0)
                        refresh(1)
                        updateTopBar()
                        val summary = if (failed.isEmpty()) {
                            if (moved == 1) "۱ مورد جابه‌جا شد" else "$moved مورد جابه‌جا شد"
                        } else "$moved مورد جابه‌جا شد؛ ${failed.size} مورد ناموفق بود"
                        toast(summary)
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
            "apk" -> installApk(file)
            "zip" -> browseArchive(file, paneIndex)
            "rar", "7z" -> startActivity(Intent(this, ArchiveToolsActivity::class.java).putExtra("archive_path", file.absolutePath))
            "pdf" -> startActivity(Intent(this, DocumentViewerActivity::class.java).putExtra("file_path", file.absolutePath))
            "jpg", "jpeg", "png", "webp", "bmp", "gif" -> viewImage(file)
            "mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mid", "midi" -> playAudio(file)
            "mp4", "m4v", "3gp", "webm", "mkv", "avi", "mov", "mpeg", "mpg" -> playVideo(file)
            else -> openExternal(file)
        }
    }

    /**
     * Starts Android's package installer for a single APK after checking the
     * per-app "install unknown apps" permission. Installation is always confirmed
     * by Android; this activity never silently installs or bypasses system security.
     */
    private fun installApk(file: File) {
        if (!file.isFile || !file.canRead()) {
            toast("فایل APK قابل خواندن نیست")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = file
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                    9102
                )
            } catch (_: Exception) {
                pendingInstallApk = null
                toast("برای نصب APK، مجوز نصب برنامه‌های ناشناس را در تنظیمات فعال کنید")
            }
            return
        }
        prepareApkWithWaveform(file)
    }

    /** Shows animated waveform while validating/copying the APK before handing off to Android Installer. */
    private fun prepareApkWithWaveform(file: File) {
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * density).toInt(), (12 * density).toInt(), (22 * density).toInt(), (8 * density).toInt())
        }
        val status = TextView(this).apply {
            text = "در حال آماده‌سازی فایل نصب…"
            textSize = 14f
        }
        val waveform = WaveformProgressView(this).apply {
            contentDescription = "پیشرفت آماده‌سازی نصب برنامه"
            setDuration(1000)
        }
        val details = TextView(this).apply {
            text = file.name
            textSize = 12f
            setTextColor(0xFF808080.toInt())
        }
        content.addView(status)
        content.addView(waveform, LinearLayout.LayoutParams(-1, (58 * density).toInt()))
        content.addView(details)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("آماده‌سازی نصب")
            .setView(content)
            .setNegativeButton("لغو") { _, _ -> cancelled.set(true) }
            .create()
        dialog.setOnCancelListener { cancelled.set(true) }
        dialog.show()
        Thread {
            var prepared: File? = null
            var failure: Exception? = null
            try {
                val targetDir = File(cacheDir, "apk-install").apply { mkdirs() }
                prepared = File(targetDir, "install-${file.nameWithoutExtension}-${System.currentTimeMillis()}.apk")
                file.inputStream().buffered().use { input ->
                    prepared!!.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            if (cancelled.get()) throw InterruptedException("cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val progressValue = if (file.length() > 0) ((copied * 1000L) / file.length()).toInt().coerceIn(0, 1000) else 1000
                            runOnUiThread {
                                if (!cancelled.get() && !isFinishing) {
                                    waveform.setPosition(progressValue)
                                    status.text = if (progressValue >= 1000) "آمادهٔ انتقال به نصب‌کننده…" else "در حال آماده‌سازی… ${progressValue / 10}%"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                failure = e
            }
            val readyFile = prepared
            runOnUiThread {
                if (cancelled.get() || isFinishing || isDestroyed) {
                    readyFile?.delete()
                    return@runOnUiThread
                }
                dialog.dismiss()
                if (failure != null || readyFile == null || !readyFile.isFile) {
                    readyFile?.delete()
                    toast("آماده‌سازی فایل APK ناموفق بود")
                    return@runOnUiThread
                }
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", readyFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    toast("نصب‌کنندهٔ بستهٔ اندروید پیدا نشد")
                } catch (_: Exception) {
                    toast("بازکردن نصب‌کنندهٔ APK ناموفق بود")
                }
            }
        }.start()
    }

    @Deprecated("Legacy callback retained for compatibility with minSdk 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 9102) return
        val pending = pendingInstallApk
        pendingInstallApk = null
        if (pending == null) return
        if (packageManager.canRequestPackageInstalls()) installApk(pending)
        else toast("مجوز نصب برنامه‌های ناشناس فعال نشد")
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
            val image = ZoomableImageView(this).apply {
                setImageBitmap(edited)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = file.name
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
        val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mid", "midi")
        if (!file.isFile || !file.canRead()) {
            toast("فایل صوتی قابل خواندن نیست")
            return
        }

        val title = TextView(this).apply {
            text = file.nameWithoutExtension
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        val positionLabel = TextView(this).apply {
            text = "00:00 / --:--"
            gravity = Gravity.CENTER
            textSize = 12f
        }
        val seek = SeekBar(this)
        val playPause = MaterialButton(this).apply { text = "پخش / مکث"; isAllCaps = false }
        val previous = MaterialButton(this).apply { text = "قبلی"; isAllCaps = false }
        val next = MaterialButton(this).apply { text = "بعدی"; isAllCaps = false }
        val shuffle = MaterialButton(this).apply { text = "تصادفی: خاموش"; isAllCaps = false }
        val repeat = MaterialButton(this).apply { text = "تکرار: خاموش"; isAllCaps = false }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(previous, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(playPause, LinearLayout.LayoutParams(0, dp(48), 1.4f))
            addView(next, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(shuffle, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(repeat, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(positionLabel, LinearLayout.LayoutParams(-1, -2))
            addView(seek, LinearLayout.LayoutParams(-1, -2))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
            addView(options, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("پخش‌کنندهٔ صوتی")
            .setView(layout)
            .setNegativeButton("بستن", null)
            .create()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var controller: androidx.media3.session.MediaController? = null
        var mediaListener: androidx.media3.common.Player.Listener? = null
        var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
        var repeatEnabled = false

        fun timeLabel(ms: Long): String {
            if (ms < 0L) return "--:--"
            val seconds = ms / 1000L
            return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
        }
        lateinit var ticker: Runnable
        ticker = Runnable {
            val c = controller
            if (dialog.isShowing && c != null) {
                val duration = c.duration
                val safeDuration = if (duration > 0L) duration else 0L
                seek.max = safeDuration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                if (!seek.isPressed) seek.progress = c.currentPosition.coerceIn(0L, seek.max.toLong()).toInt()
                positionLabel.text = "${timeLabel(c.currentPosition)} / ${timeLabel(duration)}"
                handler.postDelayed(ticker, 500L)
            }
        }

        dialog.setOnDismissListener {
            handler.removeCallbacks(ticker)
            val c = controller
            if (c != null && mediaListener != null) c.removeListener(mediaListener!!)
            future?.let { androidx.media3.session.MediaController.releaseFuture(it) }
            controller = null
        }
        dialog.show()

        val token = androidx.media3.session.SessionToken(
            this,
            android.content.ComponentName(this, StrataPlaybackService::class.java)
        )
        future = androidx.media3.session.MediaController.Builder(this, token).buildAsync()
        future!!.addListener({
            val f = future ?: return@addListener
            val c = try { f.get() } catch (_: Exception) {
                if (dialog.isShowing) toast("راه‌اندازی موتور پخش صوتی ناموفق بود")
                return@addListener
            }
            if (!dialog.isShowing) {
                androidx.media3.session.MediaController.releaseFuture(f)
                return@addListener
            }
            controller = c
            val siblings = (file.parentFile?.listFiles()?.filter {
                it.isFile && it.canRead() && it.extension.lowercase(Locale.ROOT) in audioExtensions
            } ?: listOf(file)).sortedBy { it.name.lowercase(Locale.ROOT) }
            val startIndex = siblings.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
            val items = siblings.map { audio ->
                androidx.media3.common.MediaItem.Builder()
                    .setUri(Uri.fromFile(audio))
                    .setMediaId(audio.absolutePath)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setDisplayTitle(audio.nameWithoutExtension)
                            .build()
                    )
                    .build()
            }
            c.setMediaItems(items, startIndex, 0L)
            c.prepare()
            c.play()

            mediaListener = object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: androidx.media3.common.MediaItem?,
                    reason: Int
                ) {
                    title.text = c.mediaMetadata.title?.toString()
                        ?: c.currentMediaItem?.mediaMetadata?.displayTitle?.toString()
                        ?: file.nameWithoutExtension
                }
                override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) {
                    title.text = metadata.title?.toString()
                        ?: metadata.displayTitle?.toString()
                        ?: file.nameWithoutExtension
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playPause.text = if (isPlaying) "مکث" else "ادامهٔ پخش"
                }
            }
            c.addListener(mediaListener!!)
            title.text = c.mediaMetadata.title?.toString()
                ?: c.currentMediaItem?.mediaMetadata?.displayTitle?.toString()
                ?: file.nameWithoutExtension
            playPause.setOnClickListener {
                if (c.isPlaying) c.pause() else c.play()
            }
            previous.setOnClickListener { c.seekToPreviousMediaItem() }
            next.setOnClickListener { c.seekToNextMediaItem() }
            shuffle.setOnClickListener {
                c.shuffleModeEnabled = !c.shuffleModeEnabled
                shuffle.text = if (c.shuffleModeEnabled) "تصادفی: روشن" else "تصادفی: خاموش"
            }
            repeat.setOnClickListener {
                repeatEnabled = !repeatEnabled
                c.repeatMode = if (repeatEnabled) androidx.media3.common.Player.REPEAT_MODE_ALL
                    else androidx.media3.common.Player.REPEAT_MODE_OFF
                repeat.text = if (repeatEnabled) "تکرار: همه" else "تکرار: خاموش"
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) c.seekTo(progress.toLong())
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
            handler.post(ticker)
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun playVideo(file: File) {
        if (!file.isFile || !file.canRead()) {
            toast("فایل ویدئویی قابل خواندن نیست")
            return
        }
        val exoPlayer = try {
            androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        } catch (_: Exception) {
            toast("راه‌اندازی موتور پخش ویدئو ناموفق بود")
            return
        }
        val playerView = androidx.media3.ui.PlayerView(this).apply {
            player = exoPlayer
            useController = true
            setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(playerView, LinearLayout.LayoutParams(-1, dp(280)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(layout)
            .setNegativeButton("بستن", null)
            .create()
        exoPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (dialog.isShowing) toast("پخش ویدئو ناموفق بود: " + (error.errorCodeName ?: "قالب پشتیبانی‌نشده"))
            }
        })
        dialog.setOnShowListener {
            exoPlayer.prepare()
            exoPlayer.play()
        }
        dialog.setOnDismissListener {
            playerView.player = null
            exoPlayer.release()
        }
        dialog.show()
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
        val iconRes = when (text) {
            "انتخاب همه" -> R.drawable.ic_select_all
            "برداشتن انتخاب" -> R.drawable.ic_close
            "✎ A تغییر نام", "✎ تغییر نام", "تغییر نام" -> R.drawable.ic_edit
            "فشرده‌سازی" -> R.drawable.ic_archive
            "استخراج" -> R.drawable.ic_unarchive
            "بیشتر" -> R.drawable.ic_more_vert
            "کپی" -> R.drawable.ic_content_copy
            "انتقال" -> R.drawable.ic_content_cut
            "حذف" -> R.drawable.ic_delete
            else -> R.drawable.ic_file
        }
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = ""
            isAllCaps = false
            setIconResource(iconRes)
            iconSize = dp(25)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            insetTop = 0
            insetBottom = 0
            minWidth = dp(44)
            minimumWidth = dp(44)
            setPadding(dp(4), 0, dp(4), 0)
            contentDescription = text
            tooltipText = text
            setOnClickListener { action() }
        }
        val params = if (parent === selectionActions)
            LinearLayout.LayoutParams(dp(48), dp(44))
        else LinearLayout.LayoutParams(0, dp(48), 1f)
        parent.addView(b, params)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { super.onDestroy() }

    private inner class PaneAdapter(private val paneIndex: Int) : RecyclerView.Adapter<PaneAdapter.Holder>() {
        var items: List<File> = emptyList()

        inner class Holder(
            val row: LinearLayout,
            val check: CheckBox,
            val icon: ImageView,
            val name: TextView,
            val info: TextView
        ) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@DualPaneActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                isClickable = true
                isFocusable = true
                background = if (mode == LayoutMode.VERTICAL) null else
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(this@DualPaneActivity, R.drawable.item_bg)
            }
            val check = CheckBox(this@DualPaneActivity)
            val icon = ImageView(this@DualPaneActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(3), dp(3), dp(3), dp(3))
                val tv = android.util.TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                imageTintList = android.content.res.ColorStateList.valueOf(tv.data)
                background = if (mode == LayoutMode.VERTICAL) android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant))
                } else null
            }
            val textBlock = LinearLayout(this@DualPaneActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val name = TextView(this@DualPaneActivity).apply {
                textSize = 14f
                maxLines = 2
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
            }
            val info = TextView(this@DualPaneActivity).apply {
                textSize = 11f
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            textBlock.addView(name, LinearLayout.LayoutParams(-1, -2))
            textBlock.addView(info, LinearLayout.LayoutParams(-1, -2))
            row.addView(check, LinearLayout.LayoutParams(-2, -2))
            row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            row.addView(textBlock)
            return Holder(row, check, icon, name, info)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = items[position]
            holder.row.background = if (mode == LayoutMode.VERTICAL) null else
                androidx.appcompat.content.res.AppCompatResources.getDrawable(this@DualPaneActivity, R.drawable.item_bg)
            holder.icon.background = if (mode == LayoutMode.VERTICAL) android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant))
            } else null
            FileTypeVisuals.applyTo(holder.icon, file)
            holder.name.text = file.name
            holder.info.text = if (!details) "" else if (file.isDirectory) "پوشه" else Formatter.formatFileSize(this@DualPaneActivity, file.length())
            val p = pane(paneIndex)
            holder.row.isActivated = p.selected.contains(file)
            holder.row.alpha = if (p.selected.contains(file)) 0.92f else 1f
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
                if (!p.selected.contains(file)) actionOpen(file, true)
                else {
                    // Drag every selected item from the active pane, not just the row
                    // used to start the gesture.
                    val payload = org.json.JSONArray().apply {
                        p.selected.forEach { selectedFile -> put(selectedFile.absolutePath) }
                    }.toString()
                    val clip = ClipData.newPlainText("solina-files-v1", payload)
                    holder.row.startDragAndDrop(clip, View.DragShadowBuilder(holder.row), payload, 0)
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
            } else if (file.isDirectory) navigateTo(paneIndex, file)
            else openFile(file, paneIndex)
        }
    }

    companion object {
        const val EXTRA_RIGHT_PATH = "ir.solina.fm.extra.RIGHT_PATH"
        private const val KEY_LEFT_PATH = "dual_pane_left_path"
        private const val KEY_RIGHT_PATH = "dual_pane_right_path"
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }
}

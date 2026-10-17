package ir.solina.fm

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.VideoView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile

class MainActivity : AppCompatActivity() {

    private enum class Kind { INTERNAL, SD, USB }
    private enum class SortMode { NAME, SIZE, MODIFIED, TYPE }

    private class Vol(val name: String, val dir: File, val kind: Kind)

    private lateinit var mainBar: MaterialToolbar
    private lateinit var selectionBar: MaterialToolbar
    private lateinit var storageButton: MaterialButton
    private lateinit var storageQuickBar: LinearLayout
    private lateinit var sortFilterButton: MaterialButton
    private lateinit var overflowButton: MaterialButton
    private lateinit var drawerButton: MaterialButton
    private lateinit var dualPaneButton: MaterialButton
    private lateinit var pathView: TextView
    private lateinit var navBack: MaterialButton
    private lateinit var navForward: MaterialButton
    private lateinit var searchInput: EditText
    private lateinit var bottomActions: LinearLayout
    private lateinit var bottomCopy: MaterialButton
    private lateinit var bottomMove: MaterialButton
    private lateinit var bottomDelete: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var list: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var emptyText: TextView
    private lateinit var grantButton: MaterialButton
    private lateinit var pasteFab: ExtendedFloatingActionButton

    private val adapter = FileAdapter()
    private var volumes: List<Vol> = emptyList()
    private var currentVol: Vol? = null
    private var current: File = Environment.getExternalStorageDirectory()
    private var showHidden = false
    private var sortMode = SortMode.NAME
    private var allFiles: List<File> = emptyList()
    private val backHistory = ArrayDeque<File>()
    private val forwardHistory = ArrayDeque<File>()

    private var clipFiles: List<File> = emptyList()
    private var clipCut = false
    private val selected = LinkedHashSet<File>()

    private val dateFmt: android.icu.text.DateFormat by lazy {
        if (Locale.getDefault().language == "fa") {
            android.icu.text.DateFormat.getDateTimeInstance(
                android.icu.text.DateFormat.SHORT,
                android.icu.text.DateFormat.SHORT,
                android.icu.util.ULocale("fa_IR@calendar=persian")
            )
        } else {
            android.icu.text.DateFormat.getDateTimeInstance(
                android.icu.text.DateFormat.SHORT,
                android.icu.text.DateFormat.SHORT
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedNightMode = getSharedPreferences("solina_preferences", MODE_PRIVATE).getInt("night_mode", 0)
        if (savedNightMode != 0) AppCompatDelegate.setDefaultNightMode(savedNightMode)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainBar = findViewById(R.id.mainBar)
        selectionBar = findViewById(R.id.selectionBar)
        storageButton = findViewById(R.id.storageButton)
        storageQuickBar = findViewById(R.id.storageQuickBar)
        sortFilterButton = findViewById(R.id.sortFilterButton)
        overflowButton = findViewById(R.id.overflowButton)
        drawerButton = findViewById(R.id.drawerButton)
        dualPaneButton = findViewById(R.id.dualPaneButton)
        pathView = findViewById(R.id.pathView)
        navBack = findViewById(R.id.navBack)
        navForward = findViewById(R.id.navForward)
        searchInput = findViewById(R.id.searchInput)
        bottomActions = findViewById(R.id.bottomActions)
        bottomCopy = findViewById(R.id.bottomCopy)
        bottomMove = findViewById(R.id.bottomMove)
        bottomDelete = findViewById(R.id.bottomDelete)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.list)
        emptyBox = findViewById(R.id.emptyBox)
        emptyText = findViewById(R.id.emptyText)
        grantButton = findViewById(R.id.grantButton)
        pasteFab = findViewById(R.id.pasteFab)

        mainBar.inflateMenu(R.menu.menu_main)
        mainBar.setOnMenuItemClickListener { onMainMenu(it) }
        selectionBar.inflateMenu(R.menu.menu_selection)
        selectionBar.setNavigationOnClickListener { clearSelection() }
        selectionBar.setOnMenuItemClickListener { onSelectionMenu(it) }

        storageButton.setOnClickListener { showStorageSheet() }
        sortFilterButton.setOnClickListener { showSortOptions() }
        sortFilterButton.setOnLongClickListener {
            showHidden = !showHidden
            refresh()
            toast(if (showHidden) "نمایش فایل‌های مخفی فعال شد" else "نمایش فایل‌های مخفی غیرفعال شد")
            true
        }
        overflowButton.setOnClickListener { showTopMenu(it) }
        drawerButton.setOnClickListener { showRightDrawer() }
        dualPaneButton.setOnClickListener { startActivity(Intent(this, DualPaneActivity::class.java)) }
        mainBar.visibility = View.GONE
        navBack.setOnClickListener { navigateHistory(back = true) }
        navForward.setOnClickListener { navigateHistory(back = false) }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
        bottomCopy.setOnClickListener { setClip(false) }
        bottomMove.setOnClickListener { setClip(true) }
        bottomDelete.setOnClickListener { confirmDelete() }
        grantButton.setOnClickListener { requestPermission() }
        pasteFab.setOnClickListener { paste() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

        if (!hasPermission()) requestPermission()
    }

    override fun onResume() {
        super.onResume()
        loadVolumes()
        val vol = volumes.firstOrNull { inside(current, it.dir) }
        if (vol == null || !current.exists()) {
            currentVol = volumes.first()
            current = volumes.first().dir
            selected.clear()
        } else {
            currentVol = vol
        }
        renderStorageQuickBar()
        refresh()
    }

    // ---------- storage volumes ----------

    private fun inside(file: File, root: File): Boolean =
        file.path == root.path || file.path.startsWith(root.path + "/")

    private fun loadVolumes() {
        val sm = getSystemService(StorageManager::class.java)
        val result = ArrayList<Vol>()
        for (v in sm.storageVolumes) {
            val state = v.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            val dir = v.directory ?: continue
            val desc = v.getDescription(this) ?: ""
            val kind = when {
                v.isPrimary -> Kind.INTERNAL
                desc.contains("usb", ignoreCase = true) -> Kind.USB
                else -> Kind.SD
            }
            val name = when (kind) {
                Kind.INTERNAL -> getString(R.string.internal_storage)
                Kind.USB -> if (desc.isBlank()) getString(R.string.usb_drive) else desc
                Kind.SD -> if (desc.isBlank()) getString(R.string.sd_card) else desc
            }
            result.add(Vol(name, dir, kind))
        }
        if (result.isEmpty()) {
            result.add(
                Vol(
                    getString(R.string.internal_storage),
                    Environment.getExternalStorageDirectory(),
                    Kind.INTERNAL
                )
            )
        }
        volumes = result
        renderStorageQuickBar()
    }

    private fun renderStorageQuickBar() {
        if (!::storageQuickBar.isInitialized) return
        storageQuickBar.removeAllViews()
        volumes.forEach { volume ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(3), dp(8), dp(2))
                isClickable = true
                isFocusable = true
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity, R.drawable.item_bg)
                contentDescription = volume.name
                setOnClickListener {
                    currentVol = volume
                    navigate(volume.dir)
                    renderStorageQuickBar()
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(when (volume.kind) {
                    Kind.INTERNAL -> R.drawable.ic_hard_drive
                    Kind.SD -> R.drawable.ic_sd_card
                    Kind.USB -> R.drawable.ic_usb
                })
                val tv = android.util.TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                imageTintList = android.content.res.ColorStateList.valueOf(tv.data)
                contentDescription = volume.name
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(27), dp(27)))
            val label = TextView(this).apply {
                text = volume.name
                textSize = 10f
                maxLines = 1
                val tv = android.util.TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true)
                setTextColor(tv.data)
            }
            item.addView(label, LinearLayout.LayoutParams(-2, dp(17)))
            item.addView(TextView(this).apply {
                text = "⌄"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                val tv = android.util.TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
                setTextColor(tv.data)
            }, LinearLayout.LayoutParams(-1, dp(14)))
            item.isSelected = currentVol?.dir?.path == volume.dir.path
            storageQuickBar.addView(item, LinearLayout.LayoutParams(dp(88), dp(66)))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun fmt(bytes: Long): String = Formatter.formatFileSize(this, bytes)

    private fun showStorageSheet() {
        loadVolumes()
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_storage, null)
        val container = view.findViewById<LinearLayout>(R.id.volumeList)
        for (v in volumes) {
            val row = layoutInflater.inflate(R.layout.item_volume, container, false)
            val iconRes = when (v.kind) {
                Kind.INTERNAL -> R.drawable.ic_phone
                Kind.SD -> R.drawable.ic_sd_card
                Kind.USB -> R.drawable.ic_usb
            }
            row.findViewById<ImageView>(R.id.volIcon).setImageResource(iconRes)
            row.findViewById<TextView>(R.id.volName).text = v.name
            var total = 0L
            var free = 0L
            try {
                val st = StatFs(v.dir.path)
                total = st.totalBytes
                free = st.availableBytes
            } catch (e: Exception) {
                // volume not readable
            }
            val used = (total - free).coerceAtLeast(0L)
            row.findViewById<LinearProgressIndicator>(R.id.volBar).progress =
                if (total > 0L) ((used * 100L) / total).toInt() else 0
            row.findViewById<TextView>(R.id.volStats).text =
                getString(R.string.storage_stats, fmt(used), fmt(free), fmt(total))
            row.isActivated = currentVol?.dir?.path == v.dir.path
            row.setOnClickListener {
                dialog.dismiss()
                currentVol = v
                navigate(v.dir)
            }
            container.addView(row)
        }
        dialog.setContentView(view)
        dialog.show()
    }

    // ---------- permission ----------

    private fun hasPermission(): Boolean = Environment.isExternalStorageManager()

    private fun requestPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    // ---------- listing ----------

    private fun navigate(dir: File, recordHistory: Boolean = true) {
        if (dir.path == current.path) return
        if (recordHistory) {
            backHistory.addLast(current)
            forwardHistory.clear()
        }
        current = dir
        selected.clear()
        refresh()
        list.scrollToPosition(0)
    }

    private fun navigateHistory(back: Boolean) {
        val source = if (back) backHistory else forwardHistory
        val targetHistory = if (back) forwardHistory else backHistory
        if (source.isEmpty()) {
            if (back) handleBack()
            return
        }
        targetHistory.addLast(current)
        current = source.removeLast()
        selected.clear()
        refresh()
    }

    private fun refresh() {
        updateBars()
        if (!hasPermission()) {
            adapter.submit(emptyList())
            showEmpty(getString(R.string.permission_needed), true)
            updateSelectionUi()
            return
        }
        val raw = current.listFiles()
        val unsorted = (raw ?: emptyArray()).filter { showHidden || !it.name.startsWith(".") }
        allFiles = when (sortMode) {
            SortMode.NAME -> unsorted.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            SortMode.SIZE -> unsorted.sortedWith(compareBy<File>({ !it.isDirectory }, { if (it.isDirectory) 0L else it.length() }))
            SortMode.MODIFIED -> unsorted.sortedByDescending { it.lastModified() }
            SortMode.TYPE -> unsorted.sortedWith(compareBy<File>({ !it.isDirectory }, { it.extension.lowercase(Locale.ROOT) }, { it.name.lowercase(Locale.ROOT) }))
        }
        selected.retainAll(allFiles.toSet())
        val query = searchInput.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val files = allFiles.filter { query.isEmpty() || it.name.lowercase(Locale.ROOT).contains(query) }
        adapter.submit(files)
        if (files.isEmpty()) {
            showEmpty(getString(if (raw == null) R.string.cannot_read else R.string.empty_folder), false)
        } else {
            emptyBox.visibility = View.GONE
        }
        updateSelectionUi()
    }

    private fun showEmpty(text: String, grant: Boolean) {
        emptyText.text = text
        grantButton.visibility = if (grant) View.VISIBLE else View.GONE
        emptyBox.visibility = View.VISIBLE
    }

    private fun updateBars() {
        storageButton.text = currentVol?.name ?: getString(R.string.internal_storage)
        val base = currentVol?.dir?.path ?: ""
        val rel = current.path.removePrefix(base).trimStart('/')
        val folderName = if (current.path == currentVol?.dir?.path) {
            currentVol?.name ?: current.name.ifBlank { current.absolutePath }
        } else {
            current.name.ifBlank { current.absolutePath }
        }
        val pathParts = rel.split('/').filter { it.isNotBlank() }
        val shortPath = when {
            pathParts.isEmpty() -> "/"
            pathParts.size > 2 -> "…/" + pathParts.takeLast(2).joinToString("/")
            else -> "/" + pathParts.joinToString("/")
        }
        pathView.text = "$folderName\n$shortPath"
        pathView.contentDescription = current.absolutePath
        navBack.isEnabled = backHistory.isNotEmpty() || (currentVol?.dir?.path != current.path && current.parentFile != null)
        navForward.isEnabled = forwardHistory.isNotEmpty()
    }

    private fun handleBack() {
        val root = currentVol?.dir
        val parent = current.parentFile
        when {
            selected.isNotEmpty() -> clearSelection()
            root != null && current.path != root.path && parent != null -> navigate(parent)
            else -> finish()
        }
    }

    // ---------- selection ----------

    private fun toggle(file: File) {
        if (!selected.remove(file)) selected.add(file)
        updateSelectionUi()
    }

    private fun clearSelection() {
        selected.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val sel = selected.isNotEmpty()
        mainBar.visibility = View.GONE
        selectionBar.visibility = if (sel) View.VISIBLE else View.GONE
        bottomActions.visibility = if (sel) View.VISIBLE else View.GONE
        if (sel) {
            selectionBar.title = getString(R.string.selected_count, selected.size)
            selectionBar.menu.findItem(R.id.action_rename).isVisible = selected.size == 1
            selectionBar.menu.findItem(R.id.action_extract).isVisible =
                selected.any { it.isFile && it.extension.equals("zip", ignoreCase = true) }
        }
        pasteFab.visibility =
            if (clipFiles.isNotEmpty() && !sel) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun onMainMenu(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_folder -> newFolder()
            R.id.action_dual_pane -> startActivity(Intent(this, DualPaneActivity::class.java))
            R.id.action_refresh -> refresh()
            R.id.action_bookmark -> toggleBookmark()
            R.id.action_bookmarks -> showBookmarks()
            R.id.action_sort -> showSortOptions()
            R.id.action_storage_analysis -> analyzeStorage()
            R.id.action_theme -> toggleTheme()
            R.id.action_vault -> startActivity(Intent(this, VaultActivity::class.java))
            R.id.action_root -> startActivity(Intent(this, RootExplorerActivity::class.java))
            R.id.action_network -> startActivity(Intent(this, NetworkStorageActivity::class.java))
            R.id.action_apk_tools -> startActivity(Intent(this, ApkToolsActivity::class.java))
            R.id.action_hidden -> {
                showHidden = !item.isChecked
                item.isChecked = showHidden
                refresh()
            }
        }
        return true
    }

    private fun showTopMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        for (index in 0 until mainBar.menu.size()) {
            val original = mainBar.menu.getItem(index)
            if (original.itemId == R.id.action_dual_pane || original.itemId == R.id.action_theme) continue
            val item = popup.menu.add(original.groupId, original.itemId, original.order, original.title)
            item.isCheckable = original.isCheckable
            item.isChecked = if (original.itemId == R.id.action_hidden) showHidden else original.isChecked
        }
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_hidden) {
                showHidden = !showHidden
                item.isChecked = showHidden
                refresh()
                toast(if (showHidden) "نمایش فایل‌های مخفی فعال شد" else "نمایش فایل‌های مخفی غیرفعال شد")
                true
            } else onMainMenu(item)
        }
        popup.show()
    }

    private fun showRightDrawer() {
        loadVolumes()
        val dialog = android.app.Dialog(this)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val surface = android.util.TypedValue().also { theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, it, true) }.data
        val onSurface = android.util.TypedValue().also { theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, it, true) }.data
        val muted = android.util.TypedValue().also { theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, it, true) }.data

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(12))
            setBackgroundColor(surface)
        }
        root.addView(TextView(this).apply {
            text = "تنظیمات و حافظه"
            textSize = 20f
            setTextColor(onSurface)
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }, LinearLayout.LayoutParams(-1, -2))

        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity, R.drawable.item_bg)
            contentDescription = if (night) "تغییر به تم روشن" else "تغییر به تم تیره"
            setOnClickListener { dialog.dismiss(); toggleTheme() }
        }
        themeRow.addView(TextView(this).apply {
            text = if (night) "☀" else "☾"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(onSurface)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        themeRow.addView(TextView(this).apply {
            text = if (night) "تم روشن" else "تم تیره"
            textSize = 16f
            setTextColor(onSurface)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(themeRow, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "انتخاب حافظه"
            textSize = 16f
            setTextColor(onSurface)
            setPadding(dp(8), dp(20), dp(8), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))

        val scroll = android.widget.ScrollView(this).apply { isFillViewport = false }
        val volumeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(volumeList, ViewGroup.LayoutParams(-1, -2))
        for (v in volumes) {
            val row = layoutInflater.inflate(R.layout.item_volume, volumeList, false)
            val iconRes = when (v.kind) {
                Kind.INTERNAL -> R.drawable.ic_hard_drive
                Kind.SD -> R.drawable.ic_sd_card
                Kind.USB -> R.drawable.ic_usb
            }
            row.findViewById<ImageView>(R.id.volIcon).setImageResource(iconRes)
            row.findViewById<TextView>(R.id.volName).text = v.name
            var total = 0L
            var free = 0L
            try {
                val stat = StatFs(v.dir.path)
                total = stat.totalBytes
                free = stat.availableBytes
            } catch (_: Exception) {}
            val used = (total - free).coerceAtLeast(0L)
            row.findViewById<LinearProgressIndicator>(R.id.volBar).progress =
                if (total > 0L) ((used * 100L) / total).toInt() else 0
            row.findViewById<TextView>(R.id.volStats).text =
                getString(R.string.storage_stats, fmt(used), fmt(free), fmt(total))
            row.isActivated = currentVol?.dir?.path == v.dir.path
            row.setOnClickListener {
                dialog.dismiss()
                currentVol = v
                navigate(v.dir)
            }
            volumeList.addView(row)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
        dialog.window?.apply {
            setLayout(minOf(dp(360), resources.displayMetrics.widthPixels - dp(40)), -1)
            setGravity(android.view.Gravity.END)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.28f }
        }
    }

    private fun toggleTheme() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val nextMode = if (isNight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        getSharedPreferences("solina_preferences", MODE_PRIVATE).edit().putInt("night_mode", nextMode).apply()
        AppCompatDelegate.setDefaultNightMode(nextMode)
    }

    private fun onSelectionMenu(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_rename -> selected.firstOrNull()?.let { rename(it) }
            R.id.action_select_all -> {
                selected.addAll(allFiles)
                updateSelectionUi()
            }
            R.id.action_deselect_all -> clearSelection()
            R.id.action_compress -> compressSelected()
            R.id.action_extract -> extractSelectedArchives()
            R.id.action_details -> showDetails()
        }
        return true
    }

    private fun setClip(cut: Boolean) {
        clipFiles = selected.toList()
        clipCut = cut
        selected.clear()
        updateSelectionUi()
        toast(getString(if (cut) R.string.cut_done else R.string.copied))
    }

    // ---------- file operations ----------

    private fun runInBackground(work: () -> Unit) {
        progress.visibility = View.VISIBLE
        Thread {
            try {
                work()
            } catch (e: Exception) {
                // ignored; the list is refreshed afterwards
            }
            runOnUiThread {
                progress.visibility = View.GONE
                selected.clear()
                refresh()
            }
        }.start()
    }

    private fun uniqueTarget(dest: File, src: File): File {
        var target = File(dest, src.name)
        var n = 1
        while (target.exists()) {
            val base = if (src.isDirectory) src.name else src.nameWithoutExtension
            val ext = if (src.isDirectory || src.extension.isEmpty()) "" else "." + src.extension
            target = File(dest, "$base ($n)$ext")
            n++
        }
        return target
    }

    private fun paste() {
        val files = clipFiles
        val cut = clipCut
        val dest = current
        if (files.isEmpty()) return
        progress.visibility = View.VISIBLE
        Thread {
            var failed = 0
            for (src in files) {
                try {
                    if (!src.exists()) {
                        failed++
                        continue
                    }
                    if (src.isDirectory && inside(dest, src)) {
                        failed++
                        continue
                    }
                    if (cut && src.parentFile?.path == dest.path) continue
                    val target = uniqueTarget(dest, src)
                    if (cut) {
                        if (!src.renameTo(target)) {
                            src.copyRecursively(target); if (target.exists()) src.deleteRecursively() else failed++
                        }
                    } else {
                        if (!src.copyRecursively(target)) failed++
                    }
                } catch (e: Exception) {
                    failed++
                }
            }
            runOnUiThread {
                progress.visibility = View.GONE
                if (cut) clipFiles = emptyList()
                if (failed > 0) toast(getString(R.string.some_failed, failed))
                refresh()
            }
        }.start()
    }


    private fun compressSelected() {
        val items = selected.toList()
        if (items.isEmpty()) return
        val input = TextInputEditText(this).apply {
            setText(if (items.size == 1) items.first().nameWithoutExtension + ".zip" else "archive.zip")
            hint = "برای ساخت 7z پسوند .7z وارد کن"
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("نام فایل فشرده")
            .setView(input)
            .setPositiveButton("ساخت") { _, _ ->
                var name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || name.contains("/")) {
                    toast("نام فایل معتبر نیست")
                    return@setPositiveButton
                }
                val use7z = name.endsWith(".7z", true)
                if (!use7z && !name.endsWith(".zip", true)) name += ".zip"
                val target = File(current, name)
                if (target.exists()) {
                    toast("فایلی با این نام وجود دارد")
                    return@setPositiveButton
                }
                progress.visibility = View.VISIBLE
                Thread {
                    var failed = false
                    try {
                        if (use7z) {
                            SevenZOutputFile(target).use { archive ->
                                items.forEach { addTo7z(it, it.name, archive) }
                            }
                        } else {
                            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                                items.forEach { addToZip(it, it.name, zip) }
                            }
                        }
                    } catch (_: Exception) {
                        failed = true
                        target.delete()
                    }
                    runOnUiThread {
                        progress.visibility = View.GONE
                        if (failed) toast("ساخت فایل فشرده ناموفق بود") else toast("فایل فشرده ساخته شد")
                        selected.clear()
                        refresh()
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addTo7z(file: File, entryName: String, archive: SevenZOutputFile) {
        val entry = archive.createArchiveEntry(file, entryName)
        archive.putArchiveEntry(entry)
        if (file.isFile) {
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    archive.write(buffer, 0, count)
                }
            }
        }
        archive.closeArchiveEntry()
        if (file.isDirectory) file.listFiles()?.forEach { child ->
            addTo7z(child, entryName.trimEnd('/') + "/" + child.name, archive)
        }
    }

    private fun addToZip(file: File, entryName: String, zip: ZipOutputStream) {
        if (file.isDirectory) {
            val dirName = if (entryName.endsWith("/")) entryName else entryName + "/"
            zip.putNextEntry(ZipEntry(dirName))
            zip.closeEntry()
            file.listFiles()?.forEach { child -> addToZip(child, dirName + child.name, zip) }
        } else {
            zip.putNextEntry(ZipEntry(entryName))
            file.inputStream().buffered().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun extractSelectedArchives() {
        val archives = selected.filter { it.isFile && it.extension.equals("zip", true) }
        if (archives.isEmpty()) {
            toast("ابتدا فایل ZIP را انتخاب کنید")
            return
        }
        val passwordInput = TextInputEditText(this).apply { hint = "رمز (در صورت نداشتن خالی بگذارید)" }
        MaterialAlertDialogBuilder(this)
            .setTitle("استخراج فایل ZIP")
            .setView(passwordInput)
            .setPositiveButton(R.string.ok) { _, _ ->
                val password = passwordInput.text?.toString().orEmpty()
                progress.visibility = View.VISIBLE
                Thread {
                    var failed = 0
                    archives.forEach { archive ->
                        try {
                            val out = uniqueTarget(current, File(archive.nameWithoutExtension))
                            out.mkdirs()
                            val zip = if (password.isNotEmpty())
                                net.lingala.zip4j.ZipFile(archive, password.toCharArray())
                            else net.lingala.zip4j.ZipFile(archive)
                            zip.extractAll(out.absolutePath)
                        } catch (_: Exception) { failed++ }
                    }
                    runOnUiThread {
                        progress.visibility = View.GONE
                        selected.clear()
                        refresh()
                        toast(if (failed == 0) "استخراج انجام شد" else "استخراج برخی فایل‌ها ناموفق بود؛ رمز یا سلامت ZIP را بررسی کنید")
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        val items = selected.toList()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_message, items.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                runInBackground { items.forEach { it.deleteRecursively() } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askText(titleRes: Int, initial: String, onOk: (String) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        val input = view.findViewById<TextInputEditText>(R.id.input)
        input.setText(initial)
        input.setSelection(0, initial.substringBeforeLast('.', initial).length)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty() && !text.contains('/')) onOk(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newFolder() {
        askText(R.string.new_folder, "") { name ->
            val dir = File(current, name)
            if (dir.exists()) toast(getString(R.string.name_exists))
            else if (!dir.mkdirs()) toast(getString(R.string.folder_failed))
            refresh()
        }
    }

    private fun rename(file: File) {
        askText(R.string.rename, file.name) { name ->
            val target = File(file.parentFile, name)
            if (name != file.name) {
                if (target.exists()) toast(getString(R.string.name_exists))
                else if (!file.renameTo(target)) toast(getString(R.string.rename_failed))
            }
            selected.clear()
            refresh()
        }
    }

    private fun getBookmarks(): MutableSet<String> =
        getSharedPreferences("solina_preferences", MODE_PRIVATE).getStringSet("bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun toggleBookmark() {
        val bookmarks = getBookmarks()
        val path = current.absolutePath
        val added = if (bookmarks.contains(path)) { bookmarks.remove(path); false } else { bookmarks.add(path); true }
        getSharedPreferences("solina_preferences", MODE_PRIVATE).edit().putStringSet("bookmarks", bookmarks).apply()
        toast(if (added) "پوشه به نشانک‌ها اضافه شد" else "نشانک پوشه حذف شد")
    }

    private fun showBookmarks() {
        val folders = getBookmarks().map { File(it) }.filter { it.exists() && it.isDirectory }
        if (folders.isEmpty()) { toast("هنوز نشانکی ثبت نشده است"); return }
        MaterialAlertDialogBuilder(this).setTitle("نشانک‌های پوشه")
            .setItems(folders.map { it.absolutePath }.toTypedArray()) { _, which ->
                val target = folders[which]
                currentVol = volumes.firstOrNull { inside(target, it.dir) }
                navigate(target)
            }
            .setNeutralButton("مدیریت") { _, _ -> manageBookmarks(folders) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun manageBookmarks(folders: List<File>) {
        if (folders.isEmpty()) return
        MaterialAlertDialogBuilder(this).setTitle("حذف نشانک")
            .setItems(folders.map { it.name.ifBlank { it.absolutePath } }.toTypedArray()) { _, which ->
                val bookmarks = getBookmarks()
                bookmarks.remove(folders[which].absolutePath)
                getSharedPreferences("solina_preferences", MODE_PRIVATE).edit().putStringSet("bookmarks", bookmarks).apply()
                toast("نشانک حذف شد")
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showSortOptions() {
        val labels = arrayOf("نام", "حجم", "تاریخ تغییر", "نوع فایل")
        MaterialAlertDialogBuilder(this).setTitle("مرتب‌سازی")
            .setSingleChoiceItems(labels, sortMode.ordinal) { dialog, which ->
                sortMode = SortMode.values()[which]
                dialog.dismiss()
                refresh()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun directorySize(dir: File): Long = try {
        dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    } catch (_: Exception) { 0L }

    private fun analyzeStorage() {
        val root = current
        progress.visibility = View.VISIBLE
        Thread {
            val results = root.listFiles()?.map { file ->
                val size = if (file.isDirectory) directorySize(file) else file.length()
                file to size
            }?.sortedByDescending { it.second } ?: emptyList()
            runOnUiThread {
                progress.visibility = View.GONE
                if (results.isEmpty()) {
                    toast("موردی برای تحلیل وجود ندارد")
                } else {
                    val total = results.sumOf { it.second }
                    val lines = results.take(30).map { (file, size) ->
                        val percent = if (total > 0) (size * 100 / total).toInt() else 0
                        "${file.name}  •  ${fmt(size)}  •  $percent%"
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle("تحلیل فضا: ${root.name.ifBlank { root.absolutePath }}")
                        .setMessage("حجم موارد سطح فعلی: ${fmt(total)}\n\n" + lines.joinToString("\n"))
                        .setPositiveButton(R.string.ok, null).show()
                }
            }
        }.start()
    }

    private fun showDetails() {
        val items = selected.toList()
        progress.visibility = View.VISIBLE
        Thread {
            var size = 0L
            for (f in items) {
                size += if (f.isDirectory) {
                    f.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
                } else {
                    f.length()
                }
            }
            val hash = if (items.size == 1 && items[0].isFile) sha256(items[0]) else null
            runOnUiThread {
                progress.visibility = View.GONE
                val single = items.size == 1
                val title = if (single) items[0].name else getString(R.string.details)
                val message = if (single) {
                    getString(
                        R.string.details_single,
                        items[0].absolutePath,
                        fmt(size),
                        formatDate(items[0].lastModified())
                    ) + (hash?.let { "\nSHA-256: $it" } ?: "")
                } else {
                    getString(R.string.details_multi, items.size, fmt(size))
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }.start()
    }

    private fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    private fun editTextFile(file: File) {
        try {
            if (file.length() > 2L * 1024L * 1024L) {
                toast("برای جلوگیری از کندی، ویرایشگر داخلی فایل‌های بزرگ‌تر از ۲ مگابایت را باز نمی‌کند")
                return
            }
            val editor = EditText(this).apply {
                setText(file.readText(Charsets.UTF_8))
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 12
                setSelection(text.length)
            }
            MaterialAlertDialogBuilder(this).setTitle(file.name).setView(editor)
                .setPositiveButton("ذخیره") { _, _ ->
                    try { file.writeText(editor.text.toString(), Charsets.UTF_8); toast("فایل ذخیره شد") }
                    catch (_: Exception) { toast("ذخیره فایل ناموفق بود") }
                }
                .setNegativeButton(R.string.cancel, null).show()
        } catch (_: Exception) { toast("فایل متنی قابل خواندن نیست") }
    }

    private fun openFile(file: File) {
        when (file.extension.lowercase(Locale.ROOT)) {
            "txt", "md", "log", "json", "xml", "csv", "tsv", "ini", "kt", "java",
            "html", "htm", "css", "js", "gradle", "kts", "yml", "yaml", "sh",
            "properties", "conf", "cfg", "sql", "py", "c", "cpp", "h", "rs" -> editTextFile(file)
            "jpg", "jpeg", "png", "webp", "bmp", "gif" -> viewImage(file)
            "mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mid", "midi" -> playAudio(file)
            "mp4", "m4v", "3gp", "webm", "mkv", "avi", "mov", "mpeg", "mpg" -> playVideo(file)
            "zip" -> browseArchive(file)
            "rar", "7z" -> startActivity(Intent(this, ArchiveToolsActivity::class.java).putExtra("archive_path", file.absolutePath))
            else -> openExternal(file)
        }
    }

    private fun openExternal(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, file.name))
        } catch (e: ActivityNotFoundException) {
            toast("برنامه‌ای برای بازکردن این نوع فایل نصب نیست")
        } catch (e: Exception) {
            toast("بازکردن فایل ناموفق بود؛ فرمت یا مجوز دسترسی را بررسی کنید")
        }
    }

    private fun viewImage(file: File) {
        try {
            val original = BitmapFactory.decodeFile(file.absolutePath)
            if (original == null) { toast("تصویر خراب است یا قالب آن پشتیبانی نمی‌شود"); return }
            var edited = original
            val preview = ImageView(this).apply {
                setImageBitmap(edited)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(12, 12, 12, 12)
            }
            val dialog = MaterialAlertDialogBuilder(this).setTitle(file.name).setView(preview)
                .setNeutralButton("چرخش ۹۰°", null)
                .setPositiveButton("ذخیره نسخه ویرایش‌شده", null)
                .setNegativeButton("بستن", null).create()
            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    try {
                        val matrix = Matrix().apply { postRotate(90f) }
                        val next = android.graphics.Bitmap.createBitmap(edited, 0, 0, edited.width, edited.height, matrix, true)
                        if (edited !== original) edited.recycle()
                        edited = next
                        preview.setImageBitmap(edited)
                    } catch (_: Exception) { toast("چرخش تصویر ناموفق بود") }
                }
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    try {
                        val target = uniqueTarget(file.parentFile ?: Environment.getExternalStorageDirectory(),
                            File(file.nameWithoutExtension + "-edited.jpg"))
                        target.outputStream().buffered().use { edited.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                        toast("نسخه ویرایش‌شده ذخیره شد: ${target.name}")
                    } catch (_: Exception) { toast("ذخیره تصویر ناموفق بود") }
                }
            }
            dialog.setOnDismissListener { if (edited !== original) edited.recycle(); original.recycle() }
            dialog.show()
        } catch (_: Exception) { toast("بازکردن تصویر ناموفق بود") }
    }

    private fun playAudio(file: File) {
        try {
            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 8) }
            val status = TextView(this).apply { text = file.name; textSize = 14f }
            val seek = SeekBar(this)
            val play = MaterialButton(this).apply { text = "پخش / توقف"; isAllCaps = false }
            controls.addView(status)
            controls.addView(seek)
            controls.addView(play)
            val dialog = MaterialAlertDialogBuilder(this).setTitle("پخش‌کننده صوتی").setView(controls)
                .setNegativeButton("بستن", null).create()
            player.setOnPreparedListener { mp ->
                seek.max = mp.duration.coerceAtLeast(1)
                play.setOnClickListener { if (mp.isPlaying) { mp.pause(); play.text = "ادامه پخش" } else { mp.start(); play.text = "توقف" } }
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) mp.seekTo(value) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
                mp.start()
                play.text = "توقف"
            }
            player.setOnErrorListener { mp, _, _ -> mp.release(); toast("این قالب صوتی یا فایل پشتیبانی نمی‌شود"); true }
            dialog.setOnDismissListener { try { player.stop() } catch (_: Exception) {}; player.release() }
            dialog.show()
            player.prepareAsync()
        } catch (_: Exception) { toast("پخش این فایل صوتی ممکن نیست؛ قالب یا سلامت فایل را بررسی کنید") }
    }

    private fun playVideo(file: File) {
        try {
            val video = VideoView(this)
            video.setVideoPath(file.absolutePath)
            val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
            controls.addView(video, LinearLayout.LayoutParams(-1, (260 * resources.displayMetrics.density).toInt()))
            val play = MaterialButton(this).apply { text = "پخش / توقف"; isAllCaps = false }
            controls.addView(play)
            val dialog = MaterialAlertDialogBuilder(this).setTitle(file.name).setView(controls)
                .setNegativeButton("بستن", null).create()
            video.setOnPreparedListener { mp -> mp.isLooping = false; video.start() }
            play.setOnClickListener { if (video.isPlaying) { video.pause(); play.text = "ادامه پخش" } else { video.start(); play.text = "توقف" } }
            dialog.setOnDismissListener { video.stopPlayback() }
            dialog.show()
        } catch (_: Exception) { toast("پخش این ویدئو ممکن نیست؛ قالب یا سلامت فایل را بررسی کنید") }
    }

    private fun browseArchive(file: File) {
        try {
            val zip = ZipFile(file)
            val headers = zip.fileHeaders
            if (headers.isEmpty()) { toast("فایل فشرده خالی است"); return }
            val labels = headers.map { (if (it.isDirectory) "📁 " else "📄 ") + it.fileName + if (it.isEncrypted) " 🔒" else "" }.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle(file.name).setItems(labels) { _, which ->
                val header = headers[which]
                if (!header.isDirectory) {
                    val input = EditText(this).apply { hint = "رمز فایل (در صورت نداشتن خالی بگذارید)" }
                    MaterialAlertDialogBuilder(this).setTitle("استخراج مورد انتخابی").setView(input)
                        .setPositiveButton("استخراج") { _, _ ->
                            Thread {
                                try {
                                    val password = input.text?.toString().orEmpty()
                                    val z = if (password.isNotEmpty()) ZipFile(file, password.toCharArray()) else ZipFile(file)
                                    z.extractFile(header, current.absolutePath)
                                    runOnUiThread { refresh(); toast("مورد استخراج شد") }
                                } catch (_: Exception) { runOnUiThread { toast("استخراج ناموفق؛ رمز یا سلامت ZIP را بررسی کنید") } }
                            }.start()
                        }.setNegativeButton(R.string.cancel, null).show()
                }
            }.setPositiveButton("استخراج همه") { _, _ ->
                val input = EditText(this).apply { hint = "رمز فایل (در صورت نداشتن خالی بگذارید)" }
                MaterialAlertDialogBuilder(this).setTitle("استخراج ZIP").setView(input)
                    .setPositiveButton("شروع") { _, _ ->
                        Thread {
                            try {
                                val password = input.text?.toString().orEmpty()
                                val z = if (password.isNotEmpty()) ZipFile(file, password.toCharArray()) else ZipFile(file)
                                z.extractAll(current.absolutePath)
                                runOnUiThread { refresh(); toast("استخراج کامل شد") }
                            } catch (_: Exception) { runOnUiThread { toast("استخراج ناموفق؛ رمز یا سلامت ZIP را بررسی کنید") } }
                        }.start()
                    }.setNegativeButton(R.string.cancel, null).show()
            }.setNegativeButton(R.string.cancel, null).show()
        } catch (_: Exception) { toast("این فایل ZIP قابل بازکردن نیست؛ فرمت یا سلامت آن را بررسی کنید") }
    }

    private fun formatDate(millis: Long): String = dateFmt.format(Date(millis))

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---------- list adapter ----------

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {

        var items: List<File> = emptyList()

        fun submit(newItems: List<File>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.name)
            val info: TextView = view.findViewById(R.id.info)
            val check: CheckBox = view.findViewById(R.id.fileCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_file, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = items[position]
            holder.name.text = file.name
            holder.icon.setImageResource(if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
            val date = formatDate(file.lastModified())
            holder.info.text =
                if (file.isDirectory) date else fmt(file.length()) + " • " + date
            holder.itemView.isActivated = selected.contains(file)
            holder.check.setOnCheckedChangeListener(null)
            holder.check.visibility = if (selected.isNotEmpty()) View.VISIBLE else View.GONE
            holder.check.isChecked = selected.contains(file)
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(file) else selected.remove(file)
                updateSelectionUi()
            }
            holder.itemView.setOnClickListener {
                when {
                    selected.isNotEmpty() -> toggle(file)
                    file.isDirectory -> navigate(file)
                    else -> openFile(file)
                }
            }
            holder.itemView.setOnLongClickListener {
                toggle(file)
                true
            }
        }
    }
}

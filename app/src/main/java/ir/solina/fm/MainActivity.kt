package ir.solina.fm

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {

    private enum class Kind { INTERNAL, SD, USB }

    private class Vol(val name: String, val dir: File, val kind: Kind)

    private lateinit var mainBar: MaterialToolbar
    private lateinit var selectionBar: MaterialToolbar
    private lateinit var storageButton: MaterialButton
    private lateinit var pathView: TextView
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainBar = findViewById(R.id.mainBar)
        selectionBar = findViewById(R.id.selectionBar)
        storageButton = findViewById(R.id.storageButton)
        pathView = findViewById(R.id.pathView)
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
    }

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

    private fun navigate(dir: File) {
        current = dir
        selected.clear()
        refresh()
        list.scrollToPosition(0)
    }

    private fun refresh() {
        updateBars()
        if (!hasPermission()) {
            adapter.submit(emptyList())
            showEmpty(getString(R.string.permission_needed), true)
            updateSelectionUi()
            return
        }
        val all = current.listFiles()
        val files = (all ?: emptyArray())
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        selected.retainAll(files.toSet())
        adapter.submit(files)
        if (files.isEmpty()) {
            showEmpty(getString(if (all == null) R.string.cannot_read else R.string.empty_folder), false)
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
        pathView.text = rel
        pathView.visibility = if (rel.isEmpty()) View.GONE else View.VISIBLE
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
        mainBar.visibility = if (sel) View.GONE else View.VISIBLE
        selectionBar.visibility = if (sel) View.VISIBLE else View.GONE
        if (sel) {
            selectionBar.title = getString(R.string.selected_count, selected.size)
            selectionBar.menu.findItem(R.id.action_rename).isVisible = selected.size == 1
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
            R.id.action_hidden -> {
                showHidden = !item.isChecked
                item.isChecked = showHidden
                refresh()
            }
        }
        return true
    }

    private fun onSelectionMenu(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_copy -> setClip(false)
            R.id.action_cut -> setClip(true)
            R.id.action_rename -> selected.firstOrNull()?.let { rename(it) }
            R.id.action_select_all -> {
                selected.addAll(adapter.items)
                updateSelectionUi()
            }
            R.id.action_delete -> confirmDelete()
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
                            if (src.copyRecursively(target)) src.deleteRecursively() else failed++
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
                    )
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

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, file.name))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app))
        } catch (e: Exception) {
            toast(getString(R.string.open_error))
        }
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

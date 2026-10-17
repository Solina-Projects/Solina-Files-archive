package ir.solina.fm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {

    private lateinit var pathView: TextView
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: FileAdapter

    private val root: File = Environment.getExternalStorageDirectory()
    private var current: File = root

    private var clipFile: File? = null
    private var clipCut = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pathView = findViewById(R.id.pathView)
        listView = findViewById(R.id.listView)
        emptyView = findViewById(R.id.emptyView)

        adapter = FileAdapter()
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val file = adapter.getItem(position) ?: return@setOnItemClickListener
            if (file.isDirectory) {
                current = file
                refresh()
            } else {
                openFile(file)
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { showFileMenu(it) }
            true
        }

        if (!hasPermission()) requestPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val parent = current.parentFile
        if (current.absolutePath != root.absolutePath && parent != null) {
            current = parent
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    // ---------- دسترسی به حافظه ----------

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- نمایش لیست ----------

    private fun refresh() {
        pathView.text = current.absolutePath
        title = if (current.absolutePath == root.absolutePath) "SolinaFM" else current.name

        if (!hasPermission()) {
            adapter.clear()
            emptyView.text = "برای مشاهده فایل‌ها باید اجازه دسترسی به حافظه را بدهید.\n\nروی این پیام بزنید."
            emptyView.visibility = View.VISIBLE
            emptyView.setOnClickListener { requestPermission() }
            return
        }

        val files = current.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()

        adapter.clear()
        adapter.addAll(files)
        adapter.notifyDataSetChanged()

        if (files.isEmpty()) {
            emptyView.text = "این پوشه خالی است"
            emptyView.visibility = View.VISIBLE
            emptyView.setOnClickListener(null)
        } else {
            emptyView.visibility = View.GONE
        }
    }

    private inner class FileAdapter : ArrayAdapter<File>(this@MainActivity, 0) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_file, parent, false)
            val file = getItem(position)!!
            view.findViewById<TextView>(R.id.icon).text = if (file.isDirectory) "📁" else "📄"
            view.findViewById<TextView>(R.id.name).text = file.name
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(file.lastModified()))
            view.findViewById<TextView>(R.id.info).text =
                if (file.isDirectory) {
                    val count = file.list()?.size ?: 0
                    "$count مورد  •  $date"
                } else {
                    "${Formatter.formatFileSize(context, file.length())}  •  $date"
                }
            return view
        }
    }

    // ---------- باز کردن فایل ----------

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val ext = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "باز کردن با"))
        } catch (e: ActivityNotFoundException) {
            toast("برنامه‌ای برای باز کردن این فایل پیدا نشد")
        } catch (e: Exception) {
            toast("خطا در باز کردن فایل")
        }
    }

    // ---------- منوی بالا ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "پوشه جدید")
        menu.add(0, 2, 1, "چسباندن")
        menu.add(0, 3, 2, "بازگشت به بالا")
        menu.add(0, 4, 3, "بازخوانی")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> newFolder()
            2 -> paste()
            3 -> onBackPressed()
            4 -> refresh()
        }
        return true
    }

    // ---------- عملیات روی فایل ----------

    private fun showFileMenu(file: File) {
        val options = arrayOf("تغییر نام", "کپی", "انتقال", "حذف", "مشخصات")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> rename(file)
                    1 -> { clipFile = file; clipCut = false; toast("کپی شد؛ در پوشه مقصد «چسباندن» را بزنید") }
                    2 -> { clipFile = file; clipCut = true; toast("برای انتقال، در پوشه مقصد «چسباندن» را بزنید") }
                    3 -> confirmDelete(file)
                    4 -> showInfo(file)
                }
            }
            .show()
    }

    private fun newFolder() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("پوشه جدید")
            .setView(input)
            .setPositiveButton("ساختن") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val dir = File(current, name)
                if (dir.exists()) toast("این نام قبلاً وجود دارد")
                else if (!dir.mkdirs()) toast("ساخت پوشه ناموفق بود")
                refresh()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun rename(file: File) {
        val input = EditText(this)
        input.setText(file.name)
        AlertDialog.Builder(this)
            .setTitle("تغییر نام")
            .setView(input)
            .setPositiveButton("تأیید") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == file.name) return@setPositiveButton
                val target = File(file.parentFile, name)
                if (target.exists()) toast("این نام قبلاً وجود دارد")
                else if (!file.renameTo(target)) toast("تغییر نام ناموفق بود")
                refresh()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("حذف")
            .setMessage("«${file.name}» برای همیشه حذف شود؟")
            .setPositiveButton("حذف") { _, _ ->
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (!ok) toast("حذف ناموفق بود")
                refresh()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun showInfo(file: File) {
        val size = if (file.isDirectory) {
            Formatter.formatFileSize(this, file.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        } else {
            Formatter.formatFileSize(this, file.length())
        }
        val date = DateFormat.getDateTimeInstance().format(Date(file.lastModified()))
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage("مسیر:\n${file.absolutePath}\n\nحجم: $size\nآخرین تغییر: $date")
            .setPositiveButton("باشه", null)
            .show()
    }

    private fun paste() {
        val src = clipFile
        if (src == null || !src.exists()) {
            toast("چیزی برای چسباندن وجود ندارد")
            return
        }
        if (src.isDirectory && current.absolutePath.startsWith(src.absolutePath)) {
            toast("نمی‌توان پوشه را داخل خودش چسباند")
            return
        }
        var target = File(current, src.name)
        var n = 1
        while (target.exists()) {
            val base = src.nameWithoutExtension
            val ext = if (src.extension.isEmpty()) "" else ".${src.extension}"
            target = File(current, "$base ($n)$ext")
            n++
        }
        try {
            if (clipCut) {
                if (!src.renameTo(target)) {
                    src.copyRecursively(target)
                    src.deleteRecursively()
                }
                clipFile = null
            } else {
                src.copyRecursively(target)
            }
        } catch (e: Exception) {
            toast("عملیات ناموفق بود")
        }
        refresh()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

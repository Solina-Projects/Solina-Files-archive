package ir.solina.fm

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.sardine.DavResource
import com.github.sardine.Sardine
import com.github.sardine.SardineFactory
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileOutputStream
import java.util.Vector

/** FTP, SFTP, SMB and WebDAV remote browser. Credentials are kept only in memory. */
class NetworkStorageActivity : AppCompatActivity() {
    private enum class Protocol { FTP, SFTP, SMB, WEBDAV }
    private data class RemoteEntry(val name: String, val directory: Boolean, val path: String)
    private lateinit var protocol: Protocol
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var pathInput: EditText
    private lateinit var status: TextView
    private lateinit var entries: LinearLayout
    private var ftp: FTPClient? = null
    private var sftp: ChannelSftp? = null
    private var sftpSession: Session? = null
    private var smbContext: CIFSContext? = null
    private var sardine: Sardine? = null
    private var baseHost = ""
    private var user = ""
    private var password = ""
    private var remotePath = "/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protocol = Protocol.FTP
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 10) }
        root.addView(TextView(this).apply { text = "اتصال به فضای شبکه"; textSize = 22f })
        val protocols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Protocol.values().forEach { p ->
            protocols.addView(Button(this@NetworkStorageActivity).apply {
                text = p.name
                setOnClickListener { protocol = p; portInput.setText(defaultPort(p)); hostInput.hint = hostHint(p); status.text = "پروتکل انتخاب‌شده: ${p.name}" }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(protocols)
        hostInput = field("میزبان / آدرس سرور (برای SMB: host/share)")
        portInput = field("پورت")
        portInput.setText(defaultPort(protocol))
        userInput = field("نام کاربری")
        passInput = field("رمز عبور").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        pathInput = field("مسیر راه دور (معمولاً /)")
        pathInput.setText("/")
        root.addView(hostInput); root.addView(portInput); root.addView(userInput); root.addView(passInput); root.addView(pathInput)
        root.addView(Button(this).apply { text = "اتصال"; setOnClickListener { connect() } })
        root.addView(Button(this).apply { text = "قطع اتصال"; setOnClickListener { disconnect(); status.text = "اتصال قطع شد"; entries.removeAllViews() } })
        status = TextView(this).apply { text = "اطلاعات سرور را وارد کن."; setPadding(4, 8, 4, 8) }
        root.addView(status)
        val scroll = ScrollView(this)
        entries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(entries)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText; singleLine = true; textSize = 14f
        setPadding(8, 4, 8, 4)
    }

    private fun defaultPort(p: Protocol) = when (p) { Protocol.FTP -> "21"; Protocol.SFTP -> "22"; Protocol.SMB -> "445"; Protocol.WEBDAV -> "443" }
    private fun hostHint(p: Protocol) = when (p) {
        Protocol.FTP -> "ftp.example.com"
        Protocol.SFTP -> "sftp.example.com"
        Protocol.SMB -> "192.168.1.10/share"
        Protocol.WEBDAV -> "https://cloud.example.com/remote.php/dav/files/user/"
    }

    private fun connect() {
        baseHost = hostInput.text.toString().trim().trimEnd('/')
        user = userInput.text.toString()
        password = passInput.text.toString()
        remotePath = pathInput.text.toString().ifBlank { "/" }
        if (baseHost.isBlank()) { toast("میزبان را وارد کن"); return }
        status.text = "در حال اتصال…"
        Thread {
            var error: String? = null
            try {
                disconnect()
                when (protocol) {
                    Protocol.FTP -> {
                        val client = FTPClient()
                        client.connect(baseHost, portInput.text.toString().toIntOrNull() ?: 21)
                        if (!client.login(user, password)) throw IllegalStateException("ورود FTP رد شد")
                        client.enterLocalPassiveMode()
                        client.setFileType(FTP.BINARY_FILE_TYPE)
                        ftp = client
                    }
                    Protocol.SFTP -> {
                        val session = JSch().getSession(user, baseHost, portInput.text.toString().toIntOrNull() ?: 22)
                        session.setPassword(password)
                        session.setConfig("StrictHostKeyChecking", "no")
                        session.connect(15000)
                        val channel = session.openChannel("sftp") as ChannelSftp
                        channel.connect(15000)
                        sftpSession = session; sftp = channel
                    }
                    Protocol.SMB -> {
                        val auth = NtlmPasswordAuthenticator("", user, password)
                        smbContext = SingletonContext.getInstance().withCredentials(auth)
                        val share = smbUrl(remotePath)
                        val probe = SmbFile(share, smbContext)
                        probe.connect()
                    }
                    Protocol.WEBDAV -> {
                        sardine = SardineFactory.begin(user, password)
                        sardine!!.list(webDavUrl(remotePath))
                    }
                }
            } catch (e: Exception) { error = e.message ?: "اتصال ناموفق بود"; disconnect() }
            runOnUiThread {
                if (error != null) { status.text = "خطا: $error"; toast("اتصال ناموفق بود: $error") }
                else listRemote()
            }
        }.start()
    }

    private fun listRemote() {
        status.text = "مسیر: $remotePath"
        entries.removeAllViews()
        Thread {
            val result = try {
                when (protocol) {
                    Protocol.FTP -> ftp!!.listFiles(remotePath).filter { it.name != "." && it.name != ".." }.map {
                        RemoteEntry(it.name, it.isDirectory, joinRemote(remotePath, it.name))
                    }
                    Protocol.SFTP -> (sftp!!.ls(remotePath) as Vector<ChannelSftp.LsEntry>).filter { it.filename != "." && it.filename != ".." }.map {
                        RemoteEntry(it.filename, it.attrs.isDir, joinRemote(remotePath, it.filename))
                    }
                    Protocol.SMB -> SmbFile(smbUrl(remotePath), smbContext).listFiles().map {
                        RemoteEntry(it.name.trimEnd('/'), it.isDirectory, joinRemote(remotePath, it.name.trimEnd('/')))
                    }
                    Protocol.WEBDAV -> sardine!!.list(webDavUrl(remotePath)).filter { it.path.trimEnd('/') != webDavUrl(remotePath).trimEnd('/') }.map { resource ->
                        val name = resource.name.trimEnd('/').substringAfterLast('/')
                        RemoteEntry(name, resource.isDirectory, joinRemote(remotePath, name))
                    }
                }.sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطا در فهرست‌گیری: ${e.message}" }
                emptyList()
            }
            runOnUiThread {
                entries.removeAllViews()
                entries.addView(Button(this).apply { text = "رفتن به پوشه والد"; setOnClickListener {
                    remotePath = remotePath.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }
                    pathInput.setText(remotePath); listRemote()
                } })
                result.forEach { entry ->
                    val row = TextView(this).apply {
                        text = (if (entry.directory) "📁  " else "📄  ") + entry.name
                        textSize = 15f; setPadding(10, 14, 10, 14)
                        setOnClickListener {
                            if (entry.directory) { remotePath = entry.path; pathInput.setText(remotePath); listRemote() }
                            else download(entry)
                        }
                        setOnLongClickListener { if (!entry.directory) download(entry); true }
                    }
                    entries.addView(row)
                    entries.addView(View(this).apply { setBackgroundColor(0x22333333); layoutParams = LinearLayout.LayoutParams(-1, 1) })
                }
            }
        }.start()
    }

    private fun download(entry: RemoteEntry) {
        status.text = "در حال دریافت ${entry.name}…"
        Thread {
            val folder = File(getExternalFilesDir(null), "NetworkDownloads").apply { mkdirs() }
            val target = File(folder, entry.name.replace(Regex("[/\\\\]"), "_"))
            var ok = false
            try {
                when (protocol) {
                    Protocol.FTP -> {
                        val input = ftp!!.retrieveFileStream(entry.path) ?: throw IllegalStateException("FTP دریافت فایل را رد کرد")
                        input.use { ins -> FileOutputStream(target).use { ins.copyTo(it) } }
                        if (!ftp!!.completePendingCommand()) throw IllegalStateException("تکمیل دریافت FTP ناموفق بود")
                    }
                    Protocol.SFTP -> FileOutputStream(target).use { sftp!!.get(entry.path, it) }
                    Protocol.SMB -> SmbFile(smbUrl(entry.path), smbContext).getInputStream().use { ins -> FileOutputStream(target).use { ins.copyTo(it) } }
                    Protocol.WEBDAV -> sardine!!.get(webDavUrl(entry.path)).use { ins -> FileOutputStream(target).use { ins.copyTo(it) } }
                }
                ok = true
            } catch (e: Exception) { target.delete(); runOnUiThread { status.text = "دریافت ناموفق: ${e.message}" } }
            runOnUiThread { if (ok) { status.text = "ذخیره شد: NetworkDownloads/${target.name}"; toast("فایل در پوشه خصوصی برنامه دریافت شد") } }
        }.start()
    }

    private fun joinRemote(parent: String, child: String): String = parent.trimEnd('/') + "/" + child.trimStart('/')
    private fun smbUrl(path: String): String {
        val hostAndShare = baseHost.removePrefix("smb://")
        val prefix = if (hostAndShare.contains('/')) hostAndShare else hostAndShare + "/"
        return "smb://${prefix.trimEnd('/')}/${path.trim('/')}".replace("smb://", "smb://")
    }
    private fun webDavUrl(path: String): String {
        val base = if (baseHost.startsWith("http://") || baseHost.startsWith("https://")) baseHost else "https://$baseHost"
        return if (path.startsWith("http://") || path.startsWith("https://")) path else base.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun disconnect() {
        try { ftp?.logout(); ftp?.disconnect() } catch (_: Exception) {}
        try { sftp?.disconnect(); sftpSession?.disconnect() } catch (_: Exception) {}
        try { sardine?.shutdown() } catch (_: Exception) {}
        ftp = null; sftp = null; sftpSession = null; sardine = null; smbContext = null
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

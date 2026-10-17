package ir.solina.fm

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections
import kotlin.concurrent.thread

class WifiTransferActivity : AppCompatActivity() {
    private lateinit var rootFolder: File
    private lateinit var status: TextView
    private lateinit var toggle: MaterialButton
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    private val port = 8765
    private val token = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootFolder = File(intent.getStringExtra("root_path") ?: filesDir.absolutePath)
        if (!rootFolder.exists() || !rootFolder.isDirectory) rootFolder = filesDir
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(16))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "انتقال بی‌سیم فایل"
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "پوشهٔ اشتراک‌گذاری: " + rootFolder.absolutePath +
                "\n\nدستگاه دیگر باید به همان شبکهٔ Wi-Fi متصل باشد. سرور فقط تا وقتی این صفحه باز است فعال می‌ماند."
            textSize = 14f
            setPadding(dp(4), dp(12), dp(4), dp(12))
        })
        status = TextView(this).apply {
            text = "سرور خاموش است"
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        root.addView(status)
        toggle = MaterialButton(this).apply {
            text = "شروع انتقال"
            isAllCaps = false
            setOnClickListener { if (running) stopServer() else startServer() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = "فایل‌ها از مرورگر دستگاه دیگر قابل دانلود هستند و می‌توان فایل بارگذاری کرد. از شبکهٔ مورداعتماد استفاده کن؛ حداکثر حجم بارگذاری ۲۰ مگابایت است."
            setPadding(dp(4), dp(12), dp(4), dp(12))
        })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun startServer() {
        try {
            val socket = ServerSocket(port)
            server = socket
            running = true
            toggle.text = "توقف انتقال"
            val addresses = localAddresses()
            status.text = "سرور فعال است روی پورت " + port + "\nرمز موقت: " + token + "\n" +
                addresses.joinToString("\n") { "http://" + it + ":" + port + "/?token=" + token } +
                if (addresses.isEmpty()) "\nنشانی شبکه پیدا نشد؛ Wi-Fi را بررسی کن." else ""
            thread(name = "SolinaWifiTransfer") {
                while (running) {
                    try {
                        val client = socket.accept()
                        thread(name = "SolinaWifiClient") { handleClient(client) }
                    } catch (_: Exception) {
                        if (running) runOnUiThread {
                            Toast.makeText(this, "اتصال شبکه قطع شد", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            running = false
            status.text = "شروع سرور ناموفق بود: " + (e.message ?: "خطای نامشخص")
            Toast.makeText(this, "پورت در دسترس نیست؛ برنامهٔ دیگری ممکن است از آن استفاده کند", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        toggle.text = "شروع انتقال"
        status.text = "سرور خاموش است"
    }

    private fun handleClient(socket: java.net.Socket) {
        socket.use { client ->
            client.soTimeout = 15000
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { respond(client, 400, "Bad Request", "درخواست نامعتبر"); return }
            val method = parts[0]
            val rawTarget = parts[1]
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val query = rawTarget.substringAfter('?', "")
                .split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            if (query["token"] != token) { respond(client, 403, "Forbidden", "دسترسی رد شد"); return }

            if (method == "GET" && query.containsKey("download")) {
                val name = File(query["download"].orEmpty()).name
                val target = File(rootFolder, name)
                if (!target.isFile || !target.canonicalPath.startsWith(rootFolder.canonicalPath + File.separator)) {
                    respond(client, 404, "Not Found", "فایل پیدا نشد"); return
                }
                val output = BufferedOutputStream(client.getOutputStream())
                val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " +
                    target.length() + "\r\nContent-Disposition: attachment; filename=\"" +
                    target.name.replace("\r", "").replace("\n", "").replace("\"", "") + "\"\r\nConnection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.UTF_8))
                target.inputStream().use { it.copyTo(output) }
                output.flush()
                return
            }

            if (method == "PUT") {
                val length = headers["content-length"]?.toLongOrNull() ?: 0L
                if (length <= 0 || length > 20L * 1024L * 1024L) {
                    respond(client, 413, "Payload Too Large", "حداکثر حجم بارگذاری ۲۰ مگابایت است"); return
                }
                val encodedName = headers["x-filename"] ?: "upload.bin"
                val name = File(URLDecoder.decode(encodedName, "UTF-8")).name
                if (name.isBlank() || name == "." || name == "..") {
                    respond(client, 400, "Bad Request", "نام فایل نامعتبر است"); return
                }
                val target = uniqueTarget(rootFolder, name)
                var remaining = length
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                if (remaining != 0L) {
                    target.delete()
                    respond(client, 400, "Bad Request", "بارگذاری ناقص بود")
                } else respond(client, 200, "OK", "بارگذاری شد: " + target.name)
                return
            }

            if (method != "GET") { respond(client, 405, "Method Not Allowed", "روش پشتیبانی نمی‌شود"); return }
            val entries = rootFolder.listFiles()?.filter { it.isFile && !it.name.startsWith(".solina-trash-") }?.take(500).orEmpty()
            val links = entries.joinToString("") { f ->
                val encoded = URLEncoder.encode(f.name, "UTF-8")
                "<li><a href=\"/?token=" + token + "&download=" + encoded + "\">" +
                    html(f.name) + "</a> (" + f.length() + " bytes)</li>"
            }
            val page = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
                "<title>SolinaFM</title></head><body><h2>SolinaFM • انتقال فایل</h2>" +
                "<input id=\"f\" type=\"file\"><button onclick=\"upload()\">بارگذاری فایل</button><p id=\"s\"></p>" +
                "<script>async function upload(){const f=document.getElementById('f').files[0];if(!f)return;" +
                "document.getElementById('s').textContent='در حال بارگذاری…';" +
                "const r=await fetch('/?token=" + token + "',{method:'PUT',headers:{'X-Filename':encodeURIComponent(f.name)},body:f});" +
                "document.getElementById('s').textContent=await r.text();}</script>" +
                "<h3>دانلود فایل‌ها</h3><ul>" + links + "</ul><p>حداکثر حجم بارگذاری: ۲۰ مگابایت</p></body></html>"
            respond(client, 200, "OK", page, "text/html; charset=utf-8")
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == 10) break
            if (b != 13) out.write(b)
            if (out.size() > 8192) return null
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun respond(socket: java.net.Socket, code: Int, reason: String, body: String, type: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type +
            "\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun localAddresses(): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .map { it.hostAddress }.distinct()
    } catch (_: Exception) { emptyList() }

    private fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        var n = 1
        while (target.exists()) {
            val source = File(name)
            target = File(dir, source.nameWithoutExtension + " (" + n + ")" +
                if (source.extension.isBlank()) "" else "." + source.extension)
            n++
        }
        return target
    }

    private fun html(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    override fun onDestroy() { stopServer(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

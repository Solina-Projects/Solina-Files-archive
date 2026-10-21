package ir.solina.fm

import android.net.Uri
import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small Android-native WebDAV client.
 *
 * Uses HttpURLConnection and Android's XmlPullParser instead of Sardine/JAXB,
 * whose transitive desktop-JVM classes cannot be packaged by R8 on Android.
 * Credentials are held only in memory and sent over HTTPS when configured.
 */
internal class WebDavClient(
    private val username: String,
    private val password: String
) {
    data class Entry(val name: String, val directory: Boolean)

    fun list(url: String): List<Entry> {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop>
            </d:propfind>""".trimIndent()
        val connection = open(url, "PROPFIND").apply {
            setRequestProperty("Depth", "1")
            setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            doOutput = true
        }
        var responseStream: InputStream? = null
        try {
            connection.outputStream.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("WebDAV فهرست‌گیری ناموفق بود (HTTP $code)")
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            val stream = connection.inputStream
            responseStream = stream
            parser.setInput(stream, "UTF-8")
            val results = mutableListOf<Entry>()
            var inResponse = false
            var href: String? = null
            var isDirectory = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                        "response" -> { inResponse = true; href = null; isDirectory = false }
                        "href" -> if (inResponse) href = parser.nextText()
                        "collection" -> if (inResponse) isDirectory = true
                    }
                    XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "response" && inResponse) {
                        val value = href
                        if (!value.isNullOrBlank()) {
                            val resolved = URL(URL(url), value)
                            val currentPath = Uri.parse(URL(url).toString()).path.orEmpty().trimEnd('/')
                            val entryPath = Uri.parse(resolved.toString()).path.orEmpty().trimEnd('/')
                            if (entryPath != currentPath) {
                                val name = Uri.decode(entryPath.substringAfterLast('/'))
                                if (name.isNotBlank()) results += Entry(name, isDirectory)
                            }
                        }
                        inResponse = false
                    }
                }
                event = parser.next()
            }
            return results.distinctBy { it.name }
                .sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() })
        } finally {
            try { responseStream?.close() } catch (_: Exception) {}
            connection.disconnect()
        }
    }

    fun get(url: String): InputStream {
        val connection = open(url, "GET")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("WebDAV دریافت فایل ناموفق بود (HTTP $code)")
        }
        // Caller must close the stream; disconnect when its stream closes.
        val stream = connection.inputStream
        return object : java.io.FilterInputStream(stream) {
            override fun close() {
                try { super.close() } finally { connection.disconnect() }
            }
        }
    }

    fun put(url: String, input: InputStream, contentType: String = "application/octet-stream") {
        val connection = open(url, "PUT").apply {
            doOutput = true
            setRequestProperty("Content-Type", contentType)
        }
        try {
            connection.outputStream.use { output -> input.copyTo(output) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("WebDAV بارگذاری فایل ناموفق بود (HTTP $code)")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "*/*")
        if (username.isNotEmpty() || password.isNotEmpty()) {
            val token = Base64.encodeToString(
                "$username:$password".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            connection.setRequestProperty("Authorization", "Basic $token")
        }
        return connection
    }
}

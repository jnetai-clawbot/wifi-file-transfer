package com.wififiletransfer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var fileServer: FileServer? = null
    private var serverPort: Int = 8080
    private var isServerRunning: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        webView = WebView(this)
        setContentView(webView)
        setupWebView()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun setupWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .build()

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }
            }
            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || ni.isPointToPoint ||
                    ni.name.contains("docker") || ni.name.contains("tun") || ni.name.contains("p2p")) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("127.") || ip.startsWith("0.")) continue
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP: ${e.message}")
        }
        return "Unknown"
    }

    private fun getReceivedDir(): File {
        val dir = File(getExternalFilesDir(null), "received")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun startServer(port: Int) {
        if (isServerRunning) return
        serverPort = port
        fileServer = FileServer(port)
        thread {
            try {
                fileServer?.start()
                runOnUiThread {
                    isServerRunning = true
                    notifyServerStatus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server: ${e.message}")
                runOnUiThread { notifyServerError(e.message ?: "Failed to start server") }
            }
        }
    }

    fun stopServer() {
        fileServer?.stop()
        fileServer = null
        isServerRunning = false
        runOnUiThread { notifyServerStatus() }
    }

    private fun notifyServerStatus() {
        val ip = getLocalIpAddress()
        val status = if (isServerRunning) "running" else "stopped"
        val url = if (isServerRunning) "http://$ip:$serverPort" else ""
        webView.evaluateJavascript("window.app.onServerStatus('$status', '$url', $serverPort)", null)
    }

    private fun notifyServerError(error: String) {
        val escaped = error.replace("'", "\\'")
        webView.evaluateJavascript("window.app.onServerError('$escaped')", null)
    }

    fun notifyNewFile(filename: String, size: Long) {
        val escaped = filename.replace("'", "\\'")
        webView.evaluateJavascript("window.app.onFileReceived('$escaped', $size)", null)
    }

    fun getReceivedFiles(): String {
        val dir = getReceivedDir()
        val files = dir.listFiles() ?: return "[]"
        val json = files
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .joinToString(",", "[", "]") { file ->
                """{"name":"${file.name.replace("\"", "\\\"")}","size":${file.length()},"timestamp":${file.lastModified()}}"""
            }
        return json
    }

    // ===== HTTP File Server =====
    inner class FileServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            return when {
                uri == "/" || uri == "/index.html" -> serveMainPage()
                uri.startsWith("/upload") && method == Method.POST -> handleUpload(session)
                uri.startsWith("/download/") -> handleDownload(uri)
                uri.startsWith("/list") -> handleListFiles()
                uri.startsWith("/delete/") -> handleDelete(uri)
                uri.startsWith("/api/status") -> handleApiStatus()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }

        private fun serveMainPage(): Response {
            try {
                val html = applicationContext.assets.open("web/index.html")
                    .bufferedReader().use { it.readText() }
                return newFixedLengthResponse(Response.Status.OK, "text/html", html)
            } catch (e: Exception) {
                return newFixedLengthResponse(Response.Status.OK, "text/html", buildSimplePage())
            }
        }

        private fun buildSimplePage(): String = """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>WiFi File Transfer</title>
            <style>
                *{margin:0;padding:0;box-sizing:border-box}
                body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#0d1117;color:#e6edf3;padding:24px}
                h1{margin-bottom:16px;font-size:22px}
                .card{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:20px;margin-bottom:16px}
                .btn{display:inline-block;background:#238636;color:#fff;padding:10px 20px;border-radius:8px;border:none;cursor:pointer;font-size:15px}
                .btn:hover{background:#2ea043}
                input[type=file]{margin:12px 0}
                .file{display:flex;justify-content:space-between;padding:10px 0;border-bottom:1px solid #30363d}
                .file:last-child{border-bottom:none}
            </style>
            </head><body>
                <h1>📁 WiFi File Transfer</h1>
                <div class="card"><h3>Upload Files</h3>
                <form action="/upload" method="post" enctype="multipart/form-data">
                <input type="file" name="file" multiple><br><br>
                <button class="btn" type="submit">Upload</button></form></div>
                <div class="card"><h3>Download Files</h3><div id="fileList">Loading...</div></div>
                <script>
                fetch('/list').then(r=>r.json()).then(files=>{
                    const el=document.getElementById('fileList');
                    el.innerHTML=files.length==0?'<p>No files yet</p>':files.map(f=>
                        '<div class="file"><span>'+f.name+' ('+(f.size/1024).toFixed(1)+' KB)</span>' +
                        '<a href="/download/'+f.name+'" style="color:#58a6ff">Download</a></div>'
                    ).join('');
                });
                </script>
            </body></html>
        """.trimIndent()

        private fun handleUpload(session: IHTTPSession): Response {
            try {
                val tempFile = File(cacheDir, "upload_temp")
                streamToFile(session.inputStream, tempFile)
                val contentType = session.headers["content-type"]
                val boundary = contentType?.substringAfter("boundary=")?.trim()
                if (boundary != null) {
                    val parts = parseMultipart(tempFile, boundary)
                    for (part in parts) {
                        if (part.filename.isNotBlank()) {
                            val dest = File(getReceivedDir(), part.filename)
                            dest.writeBytes(part.data)
                            notifyNewFile(part.filename, part.data.size.toLong())
                        }
                    }
                } else {
                    val filename = session.parameters["name"]?.firstOrNull()
                        ?: "file_${System.currentTimeMillis()}"
                    val dest = File(getReceivedDir(), filename)
                    tempFile.copyTo(dest, overwrite = true)
                    notifyNewFile(filename, dest.length())
                }
                tempFile.delete()
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}")
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }

        private fun handleDownload(uri: String): Response {
            val filename = uri.removePrefix("/download/")
            val file = File(getReceivedDir(), URLDecoder.decode(filename, "UTF-8"))
            if (!file.exists() || !file.isFile) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }
            val mime = when {
                filename.endsWith(".apk") -> "application/vnd.android.package-archive"
                filename.endsWith(".mp4") || filename.endsWith(".mkv") -> "video/mp4"
                filename.endsWith(".mp3") -> "audio/mpeg"
                filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg"
                filename.endsWith(".png") -> "image/png"
                filename.endsWith(".gif") -> "image/gif"
                filename.endsWith(".pdf") -> "application/pdf"
                filename.endsWith(".zip") || filename.endsWith(".tar.gz") -> "application/zip"
                else -> "application/octet-stream"
            }
            val response = newChunkedResponse(Response.Status.OK, mime, FileInputStream(file))
            response.addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
            return response
        }

        private fun handleListFiles(): Response {
            return newFixedLengthResponse(Response.Status.OK, "application/json", getReceivedFiles())
        }

        private fun handleDelete(uri: String): Response {
            val filename = uri.removePrefix("/delete/")
            val file = File(getReceivedDir(), URLDecoder.decode(filename, "UTF-8"))
            if (file.exists()) file.delete()
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        }

        private fun handleApiStatus(): Response {
            val ip = getLocalIpAddress()
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"status":"running","ip":"$ip","port":$serverPort,"clients":0}""")
        }

        private fun streamToFile(inputStream: java.io.InputStream, dest: File): Long {
            val fos = FileOutputStream(dest)
            val buf = ByteArray(8192)
            var total: Long = 0
            var read: Int
            while (inputStream.read(buf).also { read = it } != -1) {
                fos.write(buf, 0, read)
                total += read
            }
            fos.close()
            return total
        }
    }

    // ===== Multipart parsing (outside inner class for Kotlin compatibility) =====
    data class MultipartEntry(val filename: String, val data: ByteArray)

    private fun findSubarray(array: ByteArray, subarray: ByteArray, start: Int): Int {
        if (subarray.isEmpty()) return start
        val end = array.size - subarray.size
        for (i in start..end) {
            var match = true
            for (j in subarray.indices) {
                if (array[i + j] != subarray[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }

    private fun parseMultipart(file: File, boundary: String): List<MultipartEntry> {
        val parts = mutableListOf<MultipartEntry>()
        val bytes = file.readBytes()
        val boundaryBytes = "--$boundary".toByteArray()
        var pos = 0
        while (true) {
            val start = findSubarray(bytes, boundaryBytes, pos)
            if (start == -1) break
            val nextStart = findSubarray(bytes, boundaryBytes, start + boundaryBytes.size)
            if (nextStart == -1) break
            val section = bytes.copyOfRange(start + boundaryBytes.size, nextStart)
            if (section.isNotEmpty() && section[0] == '-'.toByte()) break
            val sectionStr = String(section, Charsets.UTF_8)
            val headerEnd = sectionStr.indexOf("\r\n\r\n")
            if (headerEnd == -1) { pos = nextStart; continue }
            val headers = sectionStr.substring(0, headerEnd)
            val dataStart = headerEnd + 4
            val dataEnd = if (section.size >= 2) section.size - 2 else section.size
            val data = section.copyOfRange(dataStart, dataEnd)
            val filenameMatch = Regex("""filename="([^"]*)"""").find(headers)
            val filename = filenameMatch?.groupValues?.get(1) ?: ""
            if (filename.isNotBlank()) {
                parts.add(MultipartEntry(filename, data))
            }
            pos = nextStart
        }
        return parts
    }

    // ===== JavaScript Bridge =====
    inner class AndroidBridge(private val activity: MainActivity) {

        @JavascriptInterface
        fun getLocalIp(): String = activity.getLocalIpAddress()

        @JavascriptInterface
        fun startServer(port: Int) {
            activity.runOnUiThread { activity.startServer(port) }
        }

        @JavascriptInterface
        fun stopServer() {
            activity.runOnUiThread { activity.stopServer() }
        }

        @JavascriptInterface
        fun isServerRunning(): Boolean = activity.isServerRunning

        @JavascriptInterface
        fun getReceivedFiles(): String = activity.getReceivedFiles()

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            try {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("WiFi File Transfer", text))
            } catch (e: Exception) { Log.e(TAG, "Clipboard error: ${e.message}") }
        }

        @JavascriptInterface
        fun shareApp() {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Transfer files over WiFi: https://github.com/jnetai-clawbot/wifi-file-transfer")
                    putExtra(Intent.EXTRA_SUBJECT, "WiFi File Transfer")
                }
                activity.startActivity(Intent.createChooser(intent, "Share WiFi File Transfer"))
            } catch (e: Exception) { Log.e(TAG, "Share error: ${e.message}") }
        }

        @JavascriptInterface
        fun setKeepScreenOn(enabled: Boolean) {
            activity.runOnUiThread {
                if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            activity.runOnUiThread {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: Exception) { Log.e(TAG, "Open URL error: ${e.message}") }
            }
        }

        @JavascriptInterface
        fun openFile(filename: String) {
            activity.runOnUiThread {
                try {
                    val file = File(activity.getReceivedDir(), filename)
                    if (file.exists()) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(android.net.Uri.fromFile(file), getMimeType(filename))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(activity.packageManager) != null) {
                            activity.startActivity(intent)
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "Open file error: ${e.message}") }
            }
        }

        private fun getMimeType(filename: String): String = when {
            filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg"
            filename.endsWith(".png") -> "image/png"
            filename.endsWith(".gif") -> "image/gif"
            filename.endsWith(".mp4") -> "video/mp4"
            filename.endsWith(".mp3") -> "audio/mpeg"
            filename.endsWith(".pdf") -> "application/pdf"
            filename.endsWith(".txt") || filename.endsWith(".md") -> "text/plain"
            filename.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "WiFiFileTransfer"
        private val cacheDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    }
}

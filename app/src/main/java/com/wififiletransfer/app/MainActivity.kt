package com.wififiletransfer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.common.BitMatrix
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var fileServer: FileServer? = null
    private var serverPort: Int = 8080
    private var isServerRunning: Boolean = false
    private var authEnabled: Boolean = false
    private var authPassword: String = ""
    private var serverBaseDir: File = Environment.getExternalStorageDirectory()

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanResult = result.data?.getStringExtra("SCAN_RESULT")
            if (scanResult != null && (scanResult.startsWith("http://") || scanResult.startsWith("https://"))) {
                webView.loadUrl(scanResult)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("wft_prefs", Context.MODE_PRIVATE)
        authEnabled = prefs.getBoolean("auth_enabled", false)
        authPassword = prefs.getString("auth_password", "") ?: ""
        serverPort = prefs.getInt("server_port", 8080)

        val savedDir = prefs.getString("base_dir", null)
        if (savedDir != null) {
            val dir = File(savedDir)
            if (dir.exists() && dir.isDirectory) serverBaseDir = dir
        } else {
            val appDir = getExternalFilesDir(null)
            if (appDir != null && appDir.exists()) serverBaseDir = appDir
        }

        webView = WebView(this)
        setContentView(webView)
        setupWebView()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun setupWebView() {
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
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val path = uri.path ?: return null
                    val assetPath = path.removePrefix("/assets/")
                    return try {
                        val inputStream = assets.open(assetPath)
                        val mime = when {
                            assetPath.endsWith(".html") -> "text/html"
                            assetPath.endsWith(".css") -> "text/css"
                            assetPath.endsWith(".js") -> "application/javascript"
                            assetPath.endsWith(".png") -> "image/png"
                            assetPath.endsWith(".jpg") || assetPath.endsWith(".jpeg") -> "image/jpeg"
                            assetPath.endsWith(".svg") -> "image/svg+xml"
                            assetPath.endsWith(".json") -> "application/json"
                            assetPath.endsWith(".woff2") -> "font/woff2"
                            else -> "text/plain"
                        }
                        WebResourceResponse(mime, "UTF-8", inputStream)
                    } catch (e: Exception) { null }
                }
            }

            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            val html = assets.open("web/index.html").bufferedReader().use { it.readText() }
            loadDataWithBaseURL("file:///android_asset/web/", html, "text/html", "UTF-8", null)
        }
    }

    private fun getLocalAddresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                if (ni.name.contains("docker") || ni.name.contains("tun") || ni.name.contains("p2p")) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.isBlank() || ip.startsWith("0.")) continue
                    val type = when (addr) {
                        is Inet4Address -> if (ip.startsWith("127.")) continue else "IPv4"
                        is Inet6Address -> {
                            val pct = ip.indexOf('%')
                            val clean = if (pct >= 0) ip.substring(0, pct) else ip
                            if (clean.startsWith("::1") || clean.startsWith("fe80:")) continue
                            result.add(Pair("IPv6", "[$clean]"))
                            continue
                        }
                        else -> continue
                    }
                    result.add(Pair(type, ip))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IPs: ${e.message}")
        }
        if (result.isEmpty()) result.add(Pair("IPv4", "Unknown"))
        return result
    }

    fun startServer(port: Int) {
        if (isServerRunning) return
        serverPort = port
        prefs.edit().putInt("server_port", port).apply()
        fileServer = FileServer(port)
        kotlin.concurrent.thread {
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
        val addresses = getLocalAddresses()
        val ip4 = addresses.find { it.first == "IPv4" }?.second ?: "Unknown"
        val ip6 = addresses.find { it.first == "IPv6" }?.second ?: ""
        val urls = addresses.mapNotNull { addr ->
            val cleanIp = if (addr.first == "IPv6") addr.second else addr.second
            "http://$cleanIp:$serverPort"
        }
        val primaryUrl = urls.firstOrNull() ?: ""
        val status = if (isServerRunning) "running" else "stopped"
        val authStr = if (authEnabled) "true" else "false"
        webView.evaluateJavascript(
            "window.app.onServerStatus('$status', '$primaryUrl', $serverPort, '$ip4', '$ip6', $authStr)",
            null
        )
    }

    private fun notifyServerError(error: String) {
        val escaped = error.replace("'", "\\'")
        webView.evaluateJavascript("window.app.onServerError('$escaped')", null)
    }

    fun getReceivedFiles(): String {
        val dir = serverBaseDir
        val files = dir.listFiles() ?: return "[]"
        return files.sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() })
            .joinToString(",", "[", "]") { file ->
                val escName = file.name.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"name":"$escName","size":${if (file.isDirectory) 0 else file.length()},"timestamp":${file.lastModified()},"dir":${file.isDirectory}}"""
            }
    }

    // ===== HTTP File Server =====
    inner class FileServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            if (authEnabled) {
                val cookieHeader = session.headers["cookie"] ?: ""
                val authFromCookie = cookieHeader.split(";")
                    .map { it.trim() }
                    .find { it.startsWith("wft_auth=") }
                    ?.removePrefix("wft_auth=")
                val auth = authFromCookie ?: session.parameters["auth"]?.firstOrNull()
                if (auth != authPassword && authPassword.isNotEmpty()) {
                    return serveLoginPage()
                }
            }

            val uri = session.uri
            val method = session.method

            return when {
                uri == "/login" && method == Method.POST -> handleLogin(session)
                uri.startsWith("/api/info") -> handleApiInfo()
                uri.startsWith("/api/list") -> handleListDir(session)
                uri == "/list" -> handleListDir(session)
                uri.startsWith("/api/status") -> handleApiStatus()
                uri == "/upload" && method == Method.POST -> handleUpload(session)
                uri.startsWith("/download") -> handleDownload(session)
                uri == "/delete" && method == Method.POST -> handleDelete(session)
                uri == "/move" && method == Method.POST -> handleMove(session)
                uri == "/download-zip" && method == Method.POST -> handleDownloadZip(session)
                uri == "/mkdir" && method == Method.POST -> handleMkdir(session)
                uri == "/qrcode" -> handleQrCode(session)
                uri == "/" || uri == "/index.html" -> serveFileBrowser()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }

        private fun serveLoginPage(): Response {
            val html = buildLoginPage()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun handleLogin(session: IHTTPSession): Response {
            val password = session.parameters["password"]?.firstOrNull() ?: ""
            val redirect = session.parameters["redirect"]?.firstOrNull() ?: "/"
            if (password == authPassword) {
                val resp = newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "<html><body>Redirecting...</body></html>")
                resp.addHeader("Location", redirect)
                resp.addHeader("Set-Cookie", "wft_auth=$password; Path=/; Max-Age=86400")
                return resp
            }
            val html = buildLoginPage("Wrong password")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/html", html)
        }

        private fun buildLoginPage(error: String? = null): String {
            val errorHtml = if (error != null) """<div class="error">$error</div>""" else ""
            val redirect = "/"
            return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>WiFi File Transfer</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,sans-serif;background:#0d1117;color:#e6edf3;display:flex;align-items:center;justify-content:center;min-height:100vh}
.card{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:32px;width:90%;max-width:360px;text-align:center}
h1{font-size:22px;margin-bottom:8px}
.desc{font-size:13px;color:#8b949e;margin-bottom:24px}
input[type=password]{width:100%;padding:12px;background:#21262d;border:1px solid #30363d;border-radius:8px;color:#e6edf3;font-size:15px;outline:none;margin-bottom:8px}
input[type=password]:focus{border-color:#58a6ff}
button{width:100%;padding:12px;background:#238636;color:#fff;border:none;border-radius:8px;font-size:15px;cursor:pointer;font-weight:600}
button:hover{background:#2ea043}
.error{background:rgba(248,81,73,0.15);color:#f85149;padding:8px 12px;border-radius:8px;margin-bottom:12px;font-size:13px}
</style>
</head>
<body>
<div class="card">
<h1>WiFi File Transfer</h1>
<p class="desc">This server requires authentication</p>
$errorHtml
<form method="post" action="/login">
<input type="hidden" name="redirect" value="$redirect">
<input type="password" name="password" placeholder="Enter password" autofocus>
<button type="submit">Login</button>
</form>
</div>
</body>
</html>
""".trimIndent()
        }

        private fun serveFileBrowser(): Response {
            val html = buildFileBrowserHTML()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun buildFileBrowserHTML(): String {
            val basePath = serverBaseDir.absolutePath
            return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>WiFi File Transfer</title>
<style>
:root{--bg:#0d1117;--card:#161b22;--border:#30363d;--text:#e6edf3;--muted:#8b949e;--accent:#238636;--accent-h:#2ea043;--blue:#58a6ff;--red:#f85149;--orange:#f0883e}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,sans-serif;background:var(--bg);color:var(--text);min-height:100vh;padding-bottom:80px}
.topbar{position:sticky;top:0;z-index:10;background:var(--card);border-bottom:1px solid var(--border);padding:12px 16px;display:flex;align-items:center;gap:12px}
.topbar h1{font-size:16px;font-weight:600;white-space:nowrap}
.topbar .sep{flex:1}
.btn{padding:8px 16px;border:none;border-radius:6px;font-size:13px;cursor:pointer;font-weight:500;display:inline-flex;align-items:center;gap:6px;transition:.2s}
.btn-green{background:var(--accent);color:#fff}.btn-green:hover{background:var(--accent-h)}
.btn-blue{background:rgba(88,166,255,.15);color:var(--blue)}.btn-danger{background:rgba(248,81,73,.15);color:var(--red)}.btn-warn{background:rgba(240,136,62,.15);color:var(--orange)}
.btn-sm{padding:6px 10px;font-size:11px}
.toolbar{display:flex;align-items:center;gap:8px;padding:8px 16px;flex-wrap:wrap;border-bottom:1px solid var(--border)}
.toolbar input{background:var(--card);border:1px solid var(--border);color:var(--text);padding:8px 12px;border-radius:6px;font-size:13px;outline:none}
.path-bar{padding:8px 16px;background:var(--bg);border-bottom:1px solid var(--border);font-size:12px;color:var(--muted);display:flex;align-items:center;gap:4px;flex-wrap:wrap;overflow-x:auto}
.path-bar a{color:var(--blue);text-decoration:none}.path-bar a:hover{text-decoration:underline}
.files{width:100%;max-height:calc(100vh - 180px);overflow-y:auto}
.file-row{display:flex;align-items:center;padding:7px 16px;border-bottom:1px solid rgba(48,54,61,.5);gap:8px;transition:.2s}
.file-row:hover{background:var(--card)}
.file-row .cb{flex:0 0 30px}
.file-row .name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;display:flex;align-items:center;gap:6px}
.file-row .size{flex:0 0 70px;font-size:11px;color:var(--muted);text-align:right}
.file-row .actions{flex:0 0 70px;text-align:right}
.dir-link{color:var(--blue);text-decoration:none;font-weight:500}.dir-link:hover{text-decoration:underline}
.file-link{color:var(--text);text-decoration:none;display:flex;align-items:center;gap:6px}
.file-link:hover{color:var(--blue)}
.empty{text-align:center;padding:48px 16px;color:var(--muted)}
.upload-area{padding:12px 16px;border-bottom:1px solid var(--border);text-align:center;cursor:pointer;color:var(--muted);font-size:12px;transition:.2s}.upload-area:hover,.upload-area.dragover{color:var(--blue);background:rgba(88,166,255,.08)}
.upload-area input{display:none}
.progress{height:3px;background:var(--border);overflow:hidden;display:none}.progress-fill{height:100%;background:var(--blue);width:0;transition:width .3s}
.toast{position:fixed;bottom:16px;left:50%;transform:translateX(-50%);background:var(--card);border:1px solid var(--border);padding:8px 20px;border-radius:20px;font-size:12px;z-index:100;box-shadow:0 4px 12px rgba(0,0,0,.4);opacity:0;transition:opacity .3s;max-width:90vw}
.toast.show{opacity:1}
.modal-overlay{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.6);z-index:50;align-items:center;justify-content:center}
.modal-overlay.show{display:flex}
.modal{background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;width:90%;max-width:400px}
.modal h3{font-size:16px;margin-bottom:12px}
.modal input{width:100%;padding:10px;background:#21262d;border:1px solid var(--border);color:var(--text);border-radius:6px;font-size:14px;outline:none;margin-bottom:12px}
.modal input:focus{border-color:var(--blue)}
.modal-btns{display:flex;gap:8px;justify-content:flex-end}
.check-all{display:flex;align-items:center;gap:6px;font-size:12px;color:var(--muted);cursor:pointer}
.check-all input{width:16px;height:16px;accent-color:var(--blue)}
@media(max-width:600px){.topbar h1{font-size:14px}.toolbar{padding:6px 8px}.file-row{padding:6px 8px}}
</style>
</head>
<body>
<div class="topbar">
<h1>WiFi File Transfer</h1>
<span class="sep"></span>
<button class="btn btn-blue btn-sm" onclick="reload()">Refresh</button>
</div>
<div class="upload-area" id="uploadArea" onclick="document.getElementById('fileInput').click()">
<input type="file" id="fileInput" multiple onchange="handleUploadFiles(event)">
Click to upload files to current directory
<div class="progress" id="progressBar"><div class="progress-fill" id="progressFill"></div></div>
</div>
<div class="toolbar">
<span class="check-all"><input type="checkbox" id="selectAll" onclick="toggleSelectAll()"> All</span>
<button class="btn btn-blue btn-sm" id="downloadBtn" onclick="downloadSelected()" style="display:none">Download ZIP</button>
<button class="btn btn-danger btn-sm" id="deleteBtn" onclick="deleteSelected()" style="display:none">Delete</button>
<button class="btn btn-warn btn-sm" id="moveBtn" onclick="showMoveModal()" style="display:none">Move</button>
<div style="flex:1"></div>
<button class="btn btn-green btn-sm" id="newFolderBtn" onclick="showNewFolderModal()">New Folder</button>
</div>
<div class="path-bar" id="pathBar"></div>
<div class="files" id="fileList">
<div class="empty">Loading...</div>
</div>
<div class="modal-overlay" id="moveModal">
<div class="modal">
<h3>Move Selected Files</h3>
<input type="text" id="moveDest" placeholder="/path/to/destination">
<div class="modal-btns">
<button class="btn btn-blue btn-sm" onclick="closeMoveModal()">Cancel</button>
<button class="btn btn-green btn-sm" onclick="executeMove()">Move</button>
</div>
</div>
</div>
<div class="modal-overlay" id="newFolderModal">
<div class="modal">
<h3>Create New Folder</h3>
<input type="text" id="newFolderName" placeholder="Folder name">
<div class="modal-btns">
<button class="btn btn-blue btn-sm" onclick="closeNewFolderModal()">Cancel</button>
<button class="btn btn-green btn-sm" onclick="createNewFolder()">Create</button>
</div>
</div>
</div>
<div class="toast" id="toast"></div>
<script>
var currentDir='$basePath';
var selected=new Set();
function reload(){loadFiles(currentDir)}
function updateBar(){
document.getElementById('deleteBtn').style.display=selected.size?'inline-flex':'none';
document.getElementById('moveBtn').style.display=selected.size?'inline-flex':'none';
document.getElementById('downloadBtn').style.display=selected.size?'inline-flex':'none';
}
function toggleSelectAll(){
var boxes=document.querySelectorAll('.file-cb:not(.ignore)');
var on=document.getElementById('selectAll').checked;
boxes.forEach(function(b){b.checked=on;if(on)selected.add(b.value);else selected.delete(b.value)});
updateBar();
}
function loadFiles(dir){
currentDir=dir;
fetch('/api/list?dir='+encodeURIComponent(dir)).then(function(r){return r.json()}).then(function(d){
var par=d.parent;
var html='';
if(par!==null){
html+='<div class="file-row"><div class="cb"></div><div class="name"><a class="dir-link" href="#" onclick="loadFiles(parentPath());return false">\u2190 ../</a></div><div class="size"></div><div class="actions"></div></div>';
}
if(d.files.length===0 && par===null){html+='<div class="empty">Directory is empty</div>';}
d.files.forEach(function(f){
var isDir=f.dir;
var esc=f.name.replace(/"/g,'&quot;').replace(/</g,'&lt;');
var p=f.path;
if(isDir){
html+='<div class="file-row"><div class="cb"><input type="checkbox" class="file-cb ignore" value="'+(p)+'" onchange="toggleCb(this)"'+(selected.has(p)?' checked':'')+'></div><div class="name"><a class="dir-link" href="#" onclick="loadFiles(\''+(p)+'\');return false">'+esc+'/</a></div><div class="size"></div><div class="actions"></div></div>';
}else{
html+='<div class="file-row"><div class="cb"><input type="checkbox" class="file-cb ignore" value="'+(p)+'" onchange="toggleCb(this)"'+(selected.has(p)?' checked':'')+'></div><div class="name"><span class="file-link">'+esc+'</span></div><div class="size">'+formatSize(f.size)+'</div><div class="actions"><a class="btn btn-blue btn-sm" href="/download?path='+encodeURIComponent(p)+'" download>DL</a></div></div>';
}
});
document.getElementById('fileList').innerHTML=html;
renderPath(dir);
updateBar();
}).catch(function(){showToast('Failed to load')});
}
function parentPath(){var parts=currentDir.split('/').filter(Boolean);parts.pop();return parts.length?'/'+parts.join('/'):'$basePath'}
function renderPath(dir){
var bar=document.getElementById('pathBar');
var html='<a href="#" onclick="loadFiles(\'$basePath\');return false">/</a>';
if(dir!=='$basePath'){
var rel=dir.replace('$basePath','').split('/').filter(Boolean);
var accum='$basePath';
rel.forEach(function(p,i){accum+='/'+p;
html+=' <span>/</span> ';
if(i===rel.length-1)html+='<span style="color:var(--text)">'+p+'</span>';
else html+='<a href="#" onclick="loadFiles(\''+accum+'\');return false">'+p+'</a>';
});
}
bar.innerHTML=html;
}
function toggleCb(cb){if(cb.checked)selected.add(cb.value);else selected.delete(cb.value);updateBar()}
function handleUploadFiles(e){var files=e.target.files;if(!files||files.length===0)return;uploadFiles(files);e.target.value=''}
function uploadFiles(files){
var bar=document.getElementById('progressBar');var fill=document.getElementById('progressFill');
bar.style.display='block';fill.style.width='0%';
var done=0;
for(var i=0;i<files.length;i++){
(function(file){
var form=new FormData();form.append('file',file);form.append('dir',currentDir);
var xhr=new XMLHttpRequest();
xhr.addEventListener('load',function(){done++;fill.style.width=Math.round(done/files.length*100)+'%';if(done===files.length){showToast('Upload complete');reload();setTimeout(function(){bar.style.display='none'},2000)}});
xhr.open('POST','/upload');xhr.send(form);
})(files[i]);
}
}
function deleteSelected(){
if(selected.size===0)return;
if(!confirm('Delete '+selected.size+' item(s)?'))return;
var paths=Array.from(selected);
fetch('/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({paths:paths})})
.then(function(){showToast('Deleted');selected.clear();reload()});
}
function downloadSelected(){
if(selected.size===0)return;
showToast('Preparing ZIP...');
var paths=Array.from(selected);
fetch('/download-zip',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({paths:paths})})
.then(function(r){if(!r.ok)throw Error('fail');return r.blob()})
.then(function(blob){var a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='wft-download.zip';a.click();showToast('Download started');})
.catch(function(){showToast('Download failed')});
}
function showMoveModal(){document.getElementById('moveModal').classList.add('show');document.getElementById('moveDest').value=currentDir}
function closeMoveModal(){document.getElementById('moveModal').classList.remove('show')}
function executeMove(){
var dest=document.getElementById('moveDest').value;
closeMoveModal();
fetch('/move',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({paths:Array.from(selected),dest:dest})})
.then(function(){showToast('Moved');selected.clear();reload()});
}
function showNewFolderModal(){document.getElementById('newFolderModal').classList.add('show');document.getElementById('newFolderName').value='';document.getElementById('newFolderName').focus()}
function closeNewFolderModal(){document.getElementById('newFolderModal').classList.remove('show')}
function createNewFolder(){
var name=document.getElementById('newFolderName').value.trim();
if(!name){showToast('Enter a folder name');return}
closeNewFolderModal();
fetch('/mkdir',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:name,parent:currentDir})})
.then(function(){showToast('Folder created');reload()})
.catch(function(){showToast('Failed to create folder')});
}
function formatSize(b){if(!b||b===0)return'';var u=['B','KB','MB','GB'];var i=Math.floor(Math.log(b)/Math.log(1024));return(b/Math.pow(1024,i)).toFixed(1)+' '+u[i]}
var tt;
function showToast(m){var t=document.getElementById('toast');t.textContent=m;t.classList.add('show');clearTimeout(tt);tt=setTimeout(function(){t.classList.remove('show')},2500)}
var ua=document.getElementById('uploadArea');
ua.addEventListener('dragover',function(e){e.preventDefault();e.stopPropagation();ua.classList.add('dragover')});
ua.addEventListener('dragleave',function(e){e.preventDefault();e.stopPropagation();ua.classList.remove('dragover')});
ua.addEventListener('drop',function(e){e.preventDefault();e.stopPropagation();ua.classList.remove('dragover');var files=e.dataTransfer.files;if(files.length)uploadFiles(files)});
loadFiles(currentDir);
</script>
</body>
</html>
""".trimIndent()
        }

        private fun handleApiInfo(): Response {
            val addresses = getLocalAddresses()
            val deviceName = android.os.Build.MODEL
            val ipv4 = addresses.find { it.first == "IPv4" }?.second ?: ""
            val ipv6 = addresses.find { it.first == "IPv6" }?.second ?: ""
            val ip4url = if (ipv4.isNotEmpty()) "http://$ipv4:$serverPort" else ""
            val ip6url = if (ipv6.isNotEmpty()) "http://$ipv6:$serverPort" else ""
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"deviceName":"$deviceName","ipv4":"$ipv4","ipv6":"$ipv6","port":$serverPort,"ipv4url":"$ip4url","ipv6url":"$ip6url","auth":$authEnabled}""")
        }

        private fun handleListDir(session: IHTTPSession): Response {
            val dirParam = session.parameters["dir"]?.firstOrNull() ?: serverBaseDir.absolutePath
            val dir = File(dirParam)
            if (!dir.exists() || !dir.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                    """{"error":"Directory not found","files":[],"parent":null}""")
            }
            val parent = if (dir.absolutePath != serverBaseDir.absolutePath) {
                val p = dir.parentFile?.absolutePath
                if (p != null && p.startsWith(serverBaseDir.absolutePath)) p else serverBaseDir.absolutePath
            } else null

            val files = dir.listFiles()?.filter { !it.name.startsWith(".") }?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            ) ?: emptyList()

            val json = files.joinToString(",", "[", "]") { file ->
                val escName = file.name.replace("\\", "\\\\").replace("\"", "\\\"")
                val escPath = file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"name":"$escName","size":${if (file.isDirectory) 0 else file.length()},"timestamp":${file.lastModified()},"dir":${file.isDirectory},"path":"$escPath"}"""
            }
            val parentJson = if (parent != null) "\"$parent\"" else "null"
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"files":$json,"parent":$parentJson}""")
        }

        private fun handleApiStatus(): Response {
            val addresses = getLocalAddresses()
            val ipv4 = addresses.find { it.first == "IPv4" }?.second ?: "Unknown"
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"status":"running","ip":"$ipv4","port":$serverPort,"clients":0}""")
        }

        private fun handleUpload(session: IHTTPSession): Response {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val dirParam = session.parameters["dir"]?.firstOrNull() ?: serverBaseDir.absolutePath
                val targetDir = File(dirParam)
                if (!targetDir.exists()) targetDir.mkdirs()
                var savedCount = 0
                for ((_, tmpPath) in files) {
                    val tmpFile = File(tmpPath)
                    if (!tmpFile.exists()) continue
                    val formField = tmpPath.substringAfterLast("/")
                    val originalName = session.parameters[formField]?.firstOrNull()
                    val filename = if (originalName.isNullOrBlank()) "file_${System.currentTimeMillis()}" else originalName
                    val dest = File(targetDir, filename)
                    tmpFile.copyTo(dest, overwrite = true)
                    Log.d(TAG, "Uploaded: $filename to $dirParam")
                    savedCount++
                    tmpFile.delete()
                }
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK ($savedCount files)")
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }

        private fun handleDownload(session: IHTTPSession): Response {
            val path = session.parameters["path"]?.firstOrNull()
                ?: session.uri.removePrefix("/download").removePrefix("/")
            if (path.isNullOrBlank()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing path")
            }
            val file = File(URLDecoder.decode(path, "UTF-8"))
            if (!file.exists() || !file.isFile) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }
            if (!file.absolutePath.startsWith(serverBaseDir.absolutePath)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access denied")
            }
            val mime = when {
                file.name.endsWith(".apk") -> "application/vnd.android.package-archive"
                file.name.endsWith(".mp4") || file.name.endsWith(".mkv") -> "video/mp4"
                file.name.endsWith(".mp3") -> "audio/mpeg"
                file.name.endsWith(".jpg") || file.name.endsWith(".jpeg") -> "image/jpeg"
                file.name.endsWith(".png") -> "image/png"
                file.name.endsWith(".gif") -> "image/gif"
                file.name.endsWith(".pdf") -> "application/pdf"
                file.name.endsWith(".zip") -> "application/zip"
                file.name.endsWith(".html") || file.name.endsWith(".htm") -> "text/html"
                else -> "application/octet-stream"
            }
            val response = newChunkedResponse(Response.Status.OK, mime, FileInputStream(file))
            response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            return response
        }

        private fun handleDelete(session: IHTTPSession): Response {
            try {
                val body = session.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(body)
                val paths = json.getJSONArray("paths")
                var count = 0
                for (i in 0 until paths.length()) {
                    val path = paths.getString(i)
                    val file = File(path)
                    if (file.exists() && file.absolutePath.startsWith(serverBaseDir.absolutePath)) {
                        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                        if (deleted) count++
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "Deleted $count items")
            } catch (e: Exception) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Error: ${e.message}")
            }
        }

        private fun handleMove(session: IHTTPSession): Response {
            try {
                val body = session.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(body)
                val paths = json.getJSONArray("paths")
                val destDir = json.getString("dest")
                val dest = File(destDir)
                if (!dest.exists()) dest.mkdirs()
                if (!dest.absolutePath.startsWith(serverBaseDir.absolutePath)) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access denied")
                }
                var count = 0
                for (i in 0 until paths.length()) {
                    val src = File(paths.getString(i))
                    if (src.exists() && src.absolutePath.startsWith(serverBaseDir.absolutePath)) {
                        val target = File(dest, src.name)
                        if (src.renameTo(target)) count++
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "Moved $count items")
            } catch (e: Exception) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Error: ${e.message}")
            }
        }

        private fun handleMkdir(session: IHTTPSession): Response {
            try {
                val body = session.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(body)
                val folderName = json.getString("name")
                val parentDir = json.optString("parent", serverBaseDir.absolutePath)
                val newDir = File(parentDir, folderName)
                if (!newDir.absolutePath.startsWith(serverBaseDir.absolutePath)) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access denied")
                }
                val created = newDir.mkdirs()
                return if (created) {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "Folder created")
                } else {
                    newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", "Folder already exists or could not be created")
                }
            } catch (e: Exception) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Error: ${e.message}")
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun handleQrCode(session: IHTTPSession): Response {
            try {
                val addresses = getLocalAddresses()
                val ipv4 = addresses.find { it.first == "IPv4" }?.second ?: ""
                val url = if (ipv4.isNotEmpty()) "http://$ipv4:$serverPort" else ""
                val writer = QRCodeWriter()
                val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, 300, 300)
                val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
                for (x in 0 until 300) {
                    for (y in 0 until 300) {
                        bitmap.setPixel(x, y, if (bitMatrix[x, y]) -1 else 0xFF000000.toInt())
                    }
                }
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val pngBytes = baos.toByteArray()
                val stream = java.io.ByteArrayInputStream(pngBytes)
                val response = newFixedLengthResponse(Response.Status.OK, "image/png", stream, pngBytes.size.toLong())
                response.addHeader("Cache-Control", "no-cache")
                return response
            } catch (e: Exception) {
                Log.e(TAG, "QR generation failed: ${e.message}")
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error generating QR")
            }
        }

        private fun handleDownloadZip(session: IHTTPSession): Response {
            try {
                val body = session.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(body)
                val paths = json.getJSONArray("paths")
                if (paths.length() == 0) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No files selected")
                }
                val zipFile = File(cacheDir, "wft_download_${System.currentTimeMillis()}.zip")
                FileOutputStream(zipFile).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        for (i in 0 until paths.length()) {
                            val path = paths.getString(i)
                            val file = File(path)
                            if (file.exists() && file.isFile && file.absolutePath.startsWith(serverBaseDir.absolutePath)) {
                                val entry = ZipEntry(file.name)
                                zos.putNextEntry(entry)
                                FileInputStream(file).use { it.copyTo(zos) }
                                zos.closeEntry()
                            } else if (file.exists() && file.isDirectory && file.absolutePath.startsWith(serverBaseDir.absolutePath)) {
                                addDirToZip(file, file.name + "/", zos)
                            }
                        }
                    }
                }
                val response = newChunkedResponse(Response.Status.OK, "application/zip", FileInputStream(zipFile))
                response.addHeader("Content-Disposition", "attachment; filename=\"wft-download.zip\"")
                return response
            } catch (e: Exception) {
                Log.e(TAG, "ZIP download failed: ${e.message}")
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }

        private fun addDirToZip(dir: File, prefix: String, zos: ZipOutputStream) {
            zos.putNextEntry(ZipEntry(prefix))
            zos.closeEntry()
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    addDirToZip(file, prefix + file.name + "/", zos)
                } else {
                    zos.putNextEntry(ZipEntry(prefix + file.name))
                    FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    // ===== JavaScript Bridge =====
    inner class AndroidBridge(private val activity: MainActivity) {

        @JavascriptInterface
        fun getLocalIp(): String = activity.getLocalAddresses().find { it.first == "IPv4" }?.second ?: "Unknown"

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
        fun scanQrCode() {
            activity.runOnUiThread {
                val intent = Intent(activity, QrScannerActivity::class.java)
                activity.scanLauncher.launch(intent)
            }
        }

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
                    putExtra(Intent.EXTRA_TEXT, "Transfer files over WiFi: https://github.com/jnetai-clawbot/wifi-file-transfer/releases/latest")
                    putExtra(Intent.EXTRA_SUBJECT, "WiFi File Transfer")
                }
                activity.startActivity(Intent.createChooser(intent, "Share WiFi File Transfer"))
            } catch (e: Exception) { Log.e(TAG, "Share error: ${e.message}") }
        }

        @JavascriptInterface
        fun getAuthEnabled(): Boolean = prefs.getBoolean("auth_enabled", false)

        @JavascriptInterface
        fun setAuthConfig(enabled: Boolean, password: String) {
            prefs.edit().putBoolean("auth_enabled", enabled).putString("auth_password", password).apply()
            activity.authEnabled = enabled
            activity.authPassword = password
        }

        @JavascriptInterface
        fun getAuthPassword(): String = prefs.getString("auth_password", "") ?: ""

        @JavascriptInterface
        fun vibrate(duration: Long) {
            try {
                val vibrator = activity.getSystemService(android.os.Vibrator::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            } catch (e: Exception) { Log.e(TAG, "Vibrate error: ${e.message}") }
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
                    val file = File(activity.serverBaseDir, filename)
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

        @JavascriptInterface
        fun getIpAddressesJson(): String {
            val addrs = activity.getLocalAddresses()
            return addrs.joinToString(",", "[", "]") { (type, ip) ->
                "{\"type\":\"$type\",\"ip\":\"$ip\"}"
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
    }
}

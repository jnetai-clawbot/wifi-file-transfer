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
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on by default
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        setContentView(webView)

        setupWebView()
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

    private fun getNotesDir(): File {
        val dir = File(filesDir, "notes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    inner class AndroidBridge(private val activity: MainActivity) {

        @JavascriptInterface
        fun saveNote(title: String, content: String, timestamp: Long) {
            try {
                val note = JSONObject().apply {
                    put("title", title)
                    put("content", content)
                    put("timestamp", timestamp)
                    put("updatedAt", System.currentTimeMillis())
                }
                val notesDir = activity.getNotesDir()
                val file = File(notesDir, "$timestamp.json")
                FileWriter(file).use { writer ->
                    writer.write(note.toString())
                }
                Log.d(TAG, "Saved note: $title")
            } catch (e: Exception) {
                ErrorLogger.log("saveNote", e)
            }
        }

        @JavascriptInterface
        fun getNotes(): String {
            return try {
                val notesDir = activity.getNotesDir()
                val files = notesDir.listFiles { file -> file.extension == "json" }
                val notesArray = JSONArray()

                if (files != null) {
                    // Sort by timestamp descending (newest first)
                    files.sortByDescending { it.nameWithoutExtension.toLongOrNull() ?: 0L }

                    for (file in files) {
                        try {
                            val content = FileReader(file).use { it.readText() }
                            val note = JSONObject(content)
                            notesArray.put(note)
                        } catch (e: Exception) {
                            ErrorLogger.log("getNotes:parse", e)
                        }
                    }
                }

                notesArray.toString()
            } catch (e: Exception) {
                ErrorLogger.log("getNotes", e)
                "[]"
            }
        }

        @JavascriptInterface
        fun deleteNote(timestamp: Long) {
            try {
                val notesDir = activity.getNotesDir()
                val file = File(notesDir, "$timestamp.json")
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Deleted note: $timestamp")
                }
            } catch (e: Exception) {
                ErrorLogger.log("deleteNote", e)
            }
        }

        @JavascriptInterface
        fun getNote(timestamp: Long): String {
            return try {
                val notesDir = activity.getNotesDir()
                val file = File(notesDir, "$timestamp.json")
                if (file.exists()) {
                    FileReader(file).use { it.readText() }
                } else {
                    "{}"
                }
            } catch (e: Exception) {
                ErrorLogger.log("getNote", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun setKeepScreenOn(enabled: Boolean) {
            activity.runOnUiThread {
                if (enabled) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            try {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Note content", text)
                clipboard.setPrimaryClip(clip)
            } catch (e: Exception) {
                ErrorLogger.log("copyToClipboard", e)
            }
        }

        @JavascriptInterface
        fun shareApp(url: String) {
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                    putExtra(Intent.EXTRA_SUBJECT, "Local Notes for Android")
                }
                activity.startActivity(Intent.createChooser(shareIntent, "Share Local Notes"))
            } catch (e: Exception) {
                ErrorLogger.log("shareApp", e)
            }
        }
    }

    object ErrorLogger {
        private const val TAG = "LocalNotesError"

        fun log(context: String, e: Exception) {
            Log.e(TAG, "Error in $context: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "LocalNotes"
    }
}

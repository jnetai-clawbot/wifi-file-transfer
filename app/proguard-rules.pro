# WiFi File Transfer ProGuard Rules

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Keep JavaScript bridge interface
-keepclassmembers class com.wififiletransfer.app.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebView asset loader
-keep class androidx.webkit.** { *; }

# Keep JSON
-keep class org.json.** { *; }

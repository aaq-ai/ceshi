# ProGuard rules for NotificationDemo

# Keep notification helper
-keep class com.example.notificationdemo.NotificationHelper { *; }

# Keep WebView JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView
-keepattributes JavascriptInterface
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, java.lang.String);
}

# AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# Material Components
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

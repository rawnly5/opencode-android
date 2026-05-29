# OpenCode Android ProGuard Rules

# Keep Node.js bridge
-keep class org.aspect.** { *; }
-dontwarn org.aspect.**

# Keep WebView
-keepclassmembers class * extends android.webkit.WebView {
    public *;
}

# Keep JavaScript interface
-keepclassmembers class com.opencode.android.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Node.js mobile classes
-keep class com.nodejs.** { *; }
-dontwarn com.nodejs.**

# General
-keepattributes *Annotation*
-keepattributes JavascriptInterface
-keepattributes EnclosingMethod

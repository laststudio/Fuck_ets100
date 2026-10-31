# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Vendored androidx.navigationevent.compose ABI consumed by the miuix-navigation3
# binary. Keep the exact class/member names it links against.
-keep class androidx.navigationevent.compose.** { *; }
-dontwarn androidx.navigationevent.compose.**

# WebView JavaScript bridge used by ChangyanWebLoginActivity.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# JNI symbols and BuildConfig integrity constants.
-keep class com.shuaiqiu.fuckets100.ResolverNative { *; }
-keep class com.shuaiqiu.fuckets100.CloudNative { *; }
-keep class com.shuaiqiu.fuckets100.BuildConfig { *; }
-keepclassmembers class com.shuaiqiu.fuckets100.AppIntegrityGuard {
    public static final java.lang.String PROPRIETARY_COMPLIANCE_DECLARATION;
}

# Shizuku manager reflects on these API methods at runtime.
-keep class rikka.shizuku.Shizuku { *; }

# kotlinx.coroutines debug agent, referenced via reflection in some toolchains.
-dontwarn kotlinx.coroutines.debug.**

# net.i2p.crypto:eddsa 仅使用其 EdDSAEngine/EdDSAPrivateKey（本地授权签名），
# 其中针对 JDK X509Key 的分支在 Android 上不可用，R8 直接忽略即可。
-dontwarn sun.security.x509.**
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# Aggressive R8 / ProGuard Optimization & Obfuscation
-optimizationpasses 5
-overloadaggressively
-repackageclasses ""
-allowaccessmodification
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Strip debug Log statements in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

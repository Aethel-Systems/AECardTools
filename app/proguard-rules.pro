# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# Chaquopy
-keep class com.chaquo.python.** { *; }
-keep class java.lang.ProcessBuilder
-keep class java.lang.Runtime
-dontwarn com.chaquo.python.**

# GSON
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.JsonSerializable
-keep class * implements com.google.gson.JsonDeserializer
-keepattributes Signature

# Python native modules
-keep class **.Python_* { *; }
-keepclasseswithmembernames class ** {
    native <methods>;
}

# androidx.nfc
-keep class androidx.nfc.** { *; }

# kotlinx.coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.debug.**

# timber
-keep class timber.log.** { *; }

# Keep your app's classes
-keep class com.aethel.aecardtools.** { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep generic signatures
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# Optimization settings
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Add project specific ProGuard rules here.
# Keep stacktrace line numbers while obfuscating source filenames.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Firestore / RTDB data models & domain DTOs serialized via reflection
-keep class com.example.data.model.** { *; }
-keepclassmembers class com.example.data.model.** { *; }
-keep class com.example.domain.** { *; }
-keep class com.example.data.map.LiveDriverLocation { *; }
-keep class com.example.data.map.ActiveTripTracking { *; }

# Keep Firebase annotations and Kotlin enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# MapLibre GL Android
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
-dontwarn com.mapbox.**

# OkHttp / Okio
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jetpack Credential Manager & Google Identity Services
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Coroutines debugging
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**

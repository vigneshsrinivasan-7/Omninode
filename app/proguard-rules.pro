# Add project specific ProGuard rules here.

# ─── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# ─── Hilt / Dagger ────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}

# ─── Moshi ────────────────────────────────────────────────────────────────────
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}

# ─── OkHttp / Retrofit ────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ─── OmniNode domain models (used by Moshi reflection adapter) ────────────────
-keep class com.omninode.hub.data.model.** { *; }

# ─── LiteRT / TFLite (keep JNI entry points) ─────────────────────────────────
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ─── Qualcomm QNN Delegate native bindings ────────────────────────────────────
-keep class com.qualcomm.** { *; }
-dontwarn com.qualcomm.**

# ─── Google Play Services Home (Matter) ───────────────────────────────────────
-keep class com.google.android.gms.home.** { *; }
-dontwarn com.google.android.gms.home.**

# ─── CameraX ─────────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ─── Timber ───────────────────────────────────────────────────────────────────
-dontwarn com.jakewharton.timber.**

# ─── Suppress warnings for missing Play Services stubs ────────────────────────
-dontwarn com.google.android.gms.**

# ── SocaTV Nova ProGuard / R8 Rules ─────────────────────────────────────────

# Keep annotation metadata (required for Retrofit, Gson, Room)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── Retrofit ─────────────────────────────────────────────────────────────────
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# ── Gson ─────────────────────────────────────────────────────────────────────
# Keep data model classes used in Gson serialization/deserialization
-keep class com.socatv.nova.data.model.** { *; }
-keep class com.socatv.nova.api.** { *; }
-keep class com.socatv.nova.utils.RemoteConfigManager$NovaConfig { *; }
-keep class com.socatv.nova.utils.RemoteConfigManager$FeatureFlags { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.migration.Migration { *; }

# ── OkHttp / Okio ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Glide ─────────────────────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep @com.bumptech.glide.annotation.GlideModule class *
-dontwarn com.bumptech.glide.**

# ── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── ExoPlayer / Media3 ───────────────────────────────────────────────────────
-keep class com.google.android.exoplayer2.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Leanback ─────────────────────────────────────────────────────────────────
-keep class androidx.leanback.** { *; }
-dontwarn androidx.leanback.**

# ── ViewBinding ──────────────────────────────────────────────────────────────
-keep class com.socatv.nova.databinding.** { *; }

# ── Activities / Services (entry points must be kept) ────────────────────────
-keep class com.socatv.nova.NovaApp { *; }
-keep class com.socatv.nova.ui.splash.SplashActivity { *; }

# ── Security — keep AppSecurity but obfuscate internals ──────────────────────
-keep class com.socatv.nova.utils.AppSecurity {
    public static void verify(android.content.Context);
}

# ── Aggressive obfuscation settings ──────────────────────────────────────────
# Rename all packages to a single flat namespace to defeat decompilation
-repackageclasses 'x'
-allowaccessmodification
-overloadaggressively

# Remove all logging in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Remove Kotlin null-check boilerplate (reduces size + hides logic)
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
    public static void throwUninitializedPropertyAccessException(...);
}

# ── Zxing (QR codes) ─────────────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── Lottie ───────────────────────────────────────────────────────────────────
-keep class com.airbnb.lottie.** { *; }
-dontwarn com.airbnb.lottie.**

# ── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Suppress common warnings ──────────────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

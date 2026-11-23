# =============================================================================
# R8 keep rules for Aria release builds (isMinifyEnabled + isShrinkResources).
#
# Library-provided consumer rules already cover Firebase (firebase-messaging,
# play-services), CameraX, ML Kit, OkHttp, AndroidX and Tink's shaded protobuf
# (tink-android ships META-INF/proguard/protobuf.pro). Components declared in
# the manifest (activities, services, receivers, FirebaseMessagingService) are
# kept by AAPT2-generated rules. Only what those do not cover lives here.
#
# Verified 2026-11-23 on the minified release build (aria_pixel_b, API 37):
# EncryptedSharedPreferences (security-crypto 1.1.0-alpha06 / Tink 1.8.0)
# writes, reads back and survives a cold restart with only the rules below;
# Tink's shaded protobuf ships its own consumer rules, so the broad
# com.google.crypto.tink.** / protobuf keep rules removed in #11 are not
# needed. Logcat showed no GeneralSecurityException, "No KeyManager",
# ClassNotFound or NoSuchField errors. Re-check after bumping security-crypto.
# =============================================================================

# -----------------------------------------------------------------------------
# Google Tink (via androidx.security:security-crypto)
# -----------------------------------------------------------------------------
# tink-android 1.8.0's com.google.crypto.tink.util.KeysDownloader references the
# Google HTTP client and Joda-Time, which are optional dependencies that Tink
# does not declare. Nothing in the app reaches KeysDownloader, but R8 fails the
# build on the dangling references. These are exactly the classes R8 reports
# missing (app/build/outputs/mapping/release/missing_rules.txt).
-dontwarn com.google.api.client.http.GenericUrl
-dontwarn com.google.api.client.http.HttpHeaders
-dontwarn com.google.api.client.http.HttpRequest
-dontwarn com.google.api.client.http.HttpRequestFactory
-dontwarn com.google.api.client.http.HttpResponse
-dontwarn com.google.api.client.http.HttpTransport
-dontwarn com.google.api.client.http.javanet.NetHttpTransport$Builder
-dontwarn com.google.api.client.http.javanet.NetHttpTransport
-dontwarn org.joda.time.Instant

# Compile-only annotations Tink references.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# -----------------------------------------------------------------------------
# JNA + UniFFI bindings (Rust core, uniffi/aria_mobile/aria_mobile.kt)
# -----------------------------------------------------------------------------
# JNA binds native symbols to Java method names reflectively, reads Structure
# fields by name (@Structure.FieldOrder), and calls Callback implementations
# through their single public method. The JNA AAR ships no consumer rules, so
# nothing it reflects on may be renamed or removed.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    public <fields>;
    public <init>(...);
}
-dontwarn java.awt.**

# The generated bindings: the UniffiLib Library interface (method names are the
# native symbol names), RustBuffer/ForeignFuture Structures, the
# callback-interface vtables (Callback subclasses invoked from Rust), and the
# exception/record classes the Rust side lifts by name. Keeping the package
# whole is the only safe option for generated FFI glue.
-keep class uniffi.aria_mobile.** { *; }
-keep interface uniffi.aria_mobile.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }

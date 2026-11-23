import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// ── Version: single source of truth ─────────────────────────────────────────
// gradle.properties holds ariaVersionName / ariaVersionCode. A release build
// can override either with -PariaVersionCode=… without editing a file.
val ariaVersionName = providers.gradleProperty("ariaVersionName").get()
val ariaVersionCode = providers.gradleProperty("ariaVersionCode").get().toInt()

// ── Release signing (optional) ──────────────────────────────────────────────
// Read from a gitignored keystore.properties at the repo root, or from the
// ARIA_KEYSTORE_* environment variables (env wins). With neither present the
// release build stays unsigned, which is what Play App Signing uploads expect
// to be signed later with the upload key. See README "Release signing".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }
val releaseStoreFile = signingValue("ARIA_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("ARIA_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ARIA_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ARIA_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it != null }

android {
    namespace = "com.solutions5060.aria"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.solutions5060.aria"
        minSdk = 26
        targetSdk = 36
        versionCode = ariaVersionCode
        versionName = ariaVersionName

        // Instrumented Compose UI tests (src/androidTest), run against fixture mode.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Rust core is built for these three ABIs only (build-rust.sh).
        // Without the filter, JNA and ML Kit pull in x86/armeabi/mips copies
        // and an x86 device would install an app whose core cannot load.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Ship the Rust core's symbol tables in the bundle so Play can
            // symbolicate native crashes (the .so in the APK stays stripped).
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    // Include the Rust .so libraries
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM (versions pinned in gradle/libs.versions.toml)
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    // Compose UI + Material 3 Expressive
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.graphics.shapes)

    // Activity & Navigation
    implementation(libs.activity.compose)
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Foreground/background of the whole process (reconnect on return)
    implementation(libs.lifecycle.process)

    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Telecom (ConnectionService)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-telecom:1.1.0")

    // Splash Screen API (single system splash on every API level)
    implementation("androidx.core:core-splashscreen:1.2.0")

    // JNA for UniFFI Rust bindings
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // CameraX + ML Kit for QR scanning
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:1.7.0")
    implementation("androidx.camera:camera-lifecycle:1.7.0")
    implementation("androidx.camera:camera-view:1.7.0")

    // Encrypted storage for sensitive credentials (SIP password, JWT, gateway token)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // HTTP client for PBX API
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // JSON parsing
    implementation("org.json:json:20240303")

    // Debug
    debugImplementation(libs.compose.ui.tooling)

    // Unit tests (JVM, src/test)
    testImplementation("junit:junit:4.13.2")
    // The android.jar stubs throw for org.json; tests need the real thing.
    testImplementation("org.json:json:20240303")
    // PBX repositories against a local HTTP server
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented UI tests (src/androidTest): Compose test rule, runner,
    // intents (emergency hand-off) and UiAutomator (system permission dialogs).
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.intents)
    // Compose's test rule idles through Espresso; 3.7 works on API 36+ (InputManager change).
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}

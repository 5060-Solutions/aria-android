# Aria Android

Native Android softphone built with Jetpack Compose, Android Telecom ConnectionService, and Firebase Cloud Messaging. Uses [aria-mobile-core](https://github.com/5060-Solutions/aria-mobile-core) (Rust via UniFFI) for SIP signaling and RTP media.

## Features

- **Telecom integration** -- a self-managed ConnectionService: every call, incoming and outgoing, is a Telecom connection, so the system arbitrates audio focus, routing and other calls
- **FCM push** -- high-priority data messages are reported to Telecom, which raises one full-screen, call-style incoming notification with Answer and Decline actions
- **Event-driven call state** -- call state comes from the engine's event handler and is mirrored to the Telecom connection and the UI; nothing polls
- **Full dial pad** -- Material 3 design with haptic feedback
- **Mid-call controls** -- mute, hold, DTMF keypad, blind transfer, and audio routing (earpiece, speaker, headset, Bluetooth) through Telecom
- **Encryption indicator** -- the call screen shows whether media was actually negotiated as SRTP
- **Contacts integration** -- search and call from Android contacts
- **Call history** -- recorded when a call ends, including missed, declined and transferred calls
- **Voicemail** -- list, play, mark read and delete messages through the Aria PBX API
- **Provisioning** -- scan an `aria://provision` QR code; the endpoints are shown for confirmation before sign-in, and signalling defaults to TLS
- **On-device transcription** -- opt-in, with transcripts encrypted at rest (arm64 builds with `AI=1`)
- **Splash and icon** -- AndroidX Splash Screen API; adaptive icon with monochrome (themed) layer

## Requirements

- Android SDK 36 (minSdk 26 / Android 8.0+)
- Android Studio Ladybug or later
- JDK 17 or 21 for Gradle (Android Studio's bundled JBR works; Gradle 8.11 does not run on JDK 24)
- Rust toolchain with Android targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
- Android NDK (set `ANDROID_NDK_HOME`)

## Building

### 1. Build the Rust core

```bash
./build-rust.sh        # SIP only
AI=1 ./build-rust.sh   # with on-device transcription on arm64
```

This cross-compiles `aria-mobile-core` (at `../aria-mobile-core`, or wherever `RUST_DIR` points: `RUST_DIR=~/src/aria-mobile-core ./build-rust.sh release`) for all Android ABIs into `app/src/main/jniLibs/`, and regenerates the UniFFI Kotlin bindings with the settings in `uniffi-android.toml`. The native libraries are not committed, so run this before the first build; the generated bindings are committed and must match the libraries they were built with.

### 2. Build the APK

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew assembleDebug
```

Unit tests and lint:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:lint
```

Or open in Android Studio and run.

### 3. Release build

```bash
./build-rust.sh release
./gradlew :app:bundleRelease      # app/build/outputs/bundle/release/app-release.aab
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/
```

Release builds are minified with R8 and resource shrinking (`app/proguard-rules.pro`
explains every rule), ship one `libaria_mobile_core.so` per ABI (arm64-v8a,
armeabi-v7a, x86_64), and put the core's native symbol tables in the bundle for
Play's crash symbolication.

**Version.** `ariaVersionName` / `ariaVersionCode` in `gradle.properties` are the
single source of truth (Settings shows `BuildConfig.VERSION_NAME`). Bump
`ariaVersionCode` for every Play upload, or override per build:
`./gradlew :app:bundleRelease -PariaVersionCode=42`.

**Release signing.** Without signing material the release outputs are unsigned.
To sign with the Play upload key, either create a gitignored `keystore.properties`
in the repo root:

```properties
storeFile=/absolute/or/repo-relative/path/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

or set `ARIA_KEYSTORE_FILE`, `ARIA_KEYSTORE_PASSWORD`, `ARIA_KEY_ALIAS` and
`ARIA_KEY_PASSWORD` (environment variables win over the file). All four must be
present or the build stays unsigned. `keystore.properties`, `*.jks` and
`*.keystore` are gitignored; never commit them.

### 4. Firebase Setup

1. Create a Firebase project at console.firebase.google.com
2. Add an Android app with package `com.solutions5060.aria`
3. Download `google-services.json` to `app/`
4. The FCM token is auto-fetched and used during device registration

## Architecture

```
app/src/main/kotlin/com/solutions5060/aria/
  AriaApplication.kt              -- App init, notification channels, phone account
  MainActivity.kt                 -- Compose entry point, notification Answer action
  bridge/uniffi/                  -- UniFFI-generated Kotlin bindings (from build-rust.sh)
  call/
    CallManager.kt                -- Owns the call: engine events <-> Telecom connection <-> UI state
    CallStateMapping.kt           -- Pure engine-state -> Telecom/UI mapping
    AudioRoutes.kt                -- Telecom audio routes / call endpoints -> UI options
    IncomingCallNotifier.kt       -- The incoming-call notification (full-screen intent)
    TelecomAccount.kt             -- Self-managed PhoneAccount
  service/
    AriaConnectionService.kt      -- Telecom ConnectionService and Connection
    AriaFirebaseMessagingService.kt -- FCM: incoming-call pushes, token refresh
    EngineManager.kt              -- Builds and registers the engine from the saved account
    AudioBridge.kt                -- AudioTrack/AudioRecord with AEC, NS, AGC
    IncomingCallService.kt        -- Foreground service while a call is connected
    PbxApiClient.kt               -- Aria PBX REST API (login, devices, voicemail)
  history/                        -- Call history model, recording rules and storage
  provisioning/                   -- QR payload parsing and validation
  voicemail/                      -- Voicemail API model and repository
  security/                       -- Encrypted preferences, sensitive clipboard
  ui/
    permissions/                  -- Contextual runtime-permission helpers
    AriaApp.kt                    -- Navigation + call screen overlay
    call/CallScreen.kt            -- Call UI (renders CallManager state)
    dialer/, contacts/, history/, voicemail/, settings/, transcripts/
    theme/Theme.kt                -- Material 3 + dynamic color
```

## Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | SIP/RTP network traffic |
| `RECORD_AUDIO` | Microphone for calls (asked before the first call) |
| `READ_CONTACTS` | Contacts screen (asked on the Contacts tab) |
| `CAMERA` | Provisioning QR scanner (asked when scanning) |
| `MANAGE_OWN_CALLS` | Self-managed ConnectionService |
| `FOREGROUND_SERVICE_PHONE_CALL` / `FOREGROUND_SERVICE_MICROPHONE` | Active call service |
| `POST_NOTIFICATIONS` | Incoming call notifications (asked after sign-in, with the reason) |
| `USE_FULL_SCREEN_INTENT` | Lock screen call UI |

## Ecosystem

| Component | Repository |
|-----------|-----------|
| Desktop softphone | [aria](https://github.com/5060-Solutions/aria) |
| RTP media engine | [rtp-engine](https://github.com/5060-Solutions/rtp-engine) |
| SIP protocol library | [aria-sip-core](https://github.com/5060-Solutions/aria-sip-core) |
| Push gateway | [aria-push-gateway](https://github.com/5060-Solutions/aria-push-gateway) |
| Mobile core (Rust) | [aria-mobile-core](https://github.com/5060-Solutions/aria-mobile-core) |
| iOS app | [aria-ios](https://github.com/5060-Solutions/aria-ios) |
| **Android app** | **aria-android** |

## License

MIT

# Aria Android

Native Android softphone built with Jetpack Compose, Android Telecom ConnectionService, and Firebase Cloud Messaging. Uses [aria-mobile-core](https://github.com/5060-Solutions/aria-mobile-core) (Rust via UniFFI) for SIP signaling and RTP media.

## Features

- **ConnectionService integration** -- incoming calls use Android's native call management UI
- **FCM push** -- high-priority data messages for instant call delivery
- **Full dial pad** -- Material 3 design with haptic feedback
- **Mid-call controls** -- mute, hold, speaker, DTMF keypad (wired through Rust engine)
- **Contacts integration** -- search and call from Android contacts
- **Call history** -- persisted via SharedPreferences with direction/duration tracking
- **Settings** -- gateway URL, API key, SIP credentials, FCM token auto-fetch, registration
- **Outgoing calls** -- routed through the push gateway B2BUA
- **Dynamic color** -- Material You theming on Android 12+

## Requirements

- Android SDK 36 (minSdk 26 / Android 8.0+)
- Android Studio Ladybug or later
- Rust toolchain with Android targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
- Android NDK (set `ANDROID_NDK_HOME`)

## Building

### 1. Build the Rust core

```bash
./build-rust.sh
```

This cross-compiles `aria-mobile-core` for all Android ABIs and generates UniFFI Kotlin bindings.

### 2. Build the APK

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew assembleDebug
```

Or open in Android Studio and run.

### 3. Firebase Setup

1. Create a Firebase project at console.firebase.google.com
2. Add an Android app with package `com.solutions5060.aria`
3. Download `google-services.json` to `app/`
4. The FCM token is auto-fetched and used during device registration

## Architecture

```
app/src/main/kotlin/com/solutions5060/aria/
  AriaApplication.kt              -- App init, notification channels
  MainActivity.kt                  -- Compose entry point
  bridge/
    AriaMobileCore.kt             -- JNI + UniFFI stub types (replaced at build)
  service/
    AriaConnectionService.kt      -- Android Telecom ConnectionService
    AriaFirebaseMessagingService.kt -- FCM handler, reports calls to Telecom
    IncomingCallService.kt         -- Foreground service for active calls
  ui/
    AriaApp.kt                    -- Navigation + active call overlay
    dialer/DialerScreen.kt       -- Dial pad
    call/CallScreen.kt           -- Full-screen call UI (observes engine state)
    contacts/ContactsScreen.kt   -- Android contacts integration
    history/HistoryScreen.kt     -- Persisted call history
    settings/SettingsScreen.kt   -- Account config + FCM registration
    theme/Theme.kt               -- Material 3 + dynamic color
```

## Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | SIP/RTP network traffic |
| `RECORD_AUDIO` | Microphone for calls |
| `READ_CONTACTS` | Contacts screen |
| `MANAGE_OWN_CALLS` | ConnectionService |
| `FOREGROUND_SERVICE_PHONE_CALL` | Active call service |
| `POST_NOTIFICATIONS` | Incoming call notifications |
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

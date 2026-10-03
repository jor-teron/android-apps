# Mini-TV — "Phone TV" (v0.1)

Android phone app that opens the TV page
**https://jor-teron.github.io/sites/tv/** full screen in a WebView, with a small
floating keypad for changing channels and an About page.

## Features

- Full-screen (immersive) WebView, status/navigation bars hidden, screen kept on while open.
- Loads only `https://jor-teron.github.io/sites/tv/`. The page itself decides which
  version to show; localStorage / DOM storage is kept between launches, so the last
  channel / choice is remembered.
- Follows the physical phone orientation (portrait ⇄ landscape) **even when rotation
  lock is on** (`screenOrientation="fullSensor"`). Rotation does not reload the page.
- HTML5 fullscreen video supported. Autoplay without a tap. http streams allowed
  (cleartext + mixed content).
- Links stay inside the app for `http/https/about/blob/data`; `intent:`, `tel:` and other
  schemes are ignored.
- **Floating button**: small semi-transparent round button over the video. Drag it anywhere;
  its position is saved and restored (also in the other orientation). Tap it to open the keypad.
- **Keypad panel**: `1–9`, `0`, `CH ▲`, `CH ▼`, `OK`, `✕` (close) and an `ⓘ` icon (About).
  Hides itself after ~5 s without use. Keys are sent to the page as real Android key events:

  | Keypad | Android key | Page sees (`KeyboardEvent.key`) | Page action |
  |---|---|---|---|
  | 0–9 | `KEYCODE_0..9` | `"0".."9"` | type channel number, switches after ~1.5 s |
  | CH ▲ | `KEYCODE_DPAD_UP` | `ArrowUp` | show list, move highlight up |
  | CH ▼ | `KEYCODE_DPAD_DOWN` | `ArrowDown` | show list, move highlight down |
  | OK | `KEYCODE_ENTER` | `Enter` | play / open highlighted item |

- Phone **Back** key: closes fullscreen video → closes keypad → goes back in the page → exits.
- **About** page: app name, version, TV page link, "Made by J T", repo link, how-to.
- Permissions: `INTERNET` and `ACCESS_NETWORK_STATE` only. No overlay, accessibility, SMS or PiP.

## Build

Requirements: JDK 17 and the Android SDK (platform 34, build-tools 34). Plain Java, no Kotlin,
no AndroidX. AGP 8.5.2, Gradle 8.7 (wrapper included), minSdk 26, targetSdk 34.

```sh
cd Mini-TV
echo "sdk.dir=/path/to/android-sdk" > local.properties
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Package: `com.jorteron.phonetv` · versionName `0.1` · versionCode `1`.

# Mini-TV — "Phone TV" (v0.2)

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
  It is hidden while the keypad is open.
- **Keypad panel** (big keys, semi-transparent, floats over the video):

  ```
  [ 1 ] [ 2 ] [ 3 ]
  [ 4 ] [ 5 ] [ 6 ]
  [ 7 ] [ 8 ] [ 9 ]
  [ 0 ] [  Cancel   ]
  [CH▲] [ OK ] [CH▼]
  ```

  plus a small `ⓘ` in the top corner (About).
  - Portrait: docked at the bottom, full width, about 40% of the screen height.
  - Landscape: docked to the left or right edge, full height, about one third of the width.
    The side is whichever half of the screen the floating button is on when you open the keypad.
  - Switches layout live on rotation (the page is not reloaded). Hides after 5 s without use.
  - Keys are sent to the page as real Android key events:

  | Keypad | Android key | Page sees (`KeyboardEvent.key`) | Page action |
  |---|---|---|---|
  | 0–9 | `KEYCODE_0..9` | `"0".."9"` | type channel number, switches after ~1.5 s |
  | CH▲ | `KEYCODE_DPAD_UP` | `ArrowUp` | show list, move highlight up |
  | CH▼ | `KEYCODE_DPAD_DOWN` | `ArrowDown` | show list, move highlight down |
  | OK | `KEYCODE_ENTER` | `Enter` | play / open highlighted item |

- Phone **Back** key: closes fullscreen video → closes keypad → goes back in the page → exits.
- **About** page: app icon and name, "How to use", and an About section with credit (Jor Teron),
  version, release date (stamped at build time) and package name. No links.
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

Package: `com.jorteron.phonetv` · versionName `0.2` · versionCode `2`.

## Changelog

- **0.2** — Bigger dial-pad keypad (bottom dock in portrait, side dock in landscape, side picked
  by the floating button's position), Cancel key, redesigned About page without links, build date
  shown as release date.
- **0.1** — First version: full-screen TV page, floating button, compact keypad, About page.

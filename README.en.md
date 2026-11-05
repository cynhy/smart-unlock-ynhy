# Smart Unlock · Automatic unlock on screen wake

[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](LICENSE)
![minSdk](https://img.shields.io/badge/minSdk-26-green)
![targetSdk](https://img.shields.io/badge/targetSdk-34-green)

[简体中文](README.md) | English

When a paired Bluetooth device (watch, earbuds, etc.) is connected, the phone enters its lock-screen PIN and dismisses the keyguard automatically every time the screen wakes — saving hundreds of manual unlocks a day.

No root, no always-on PC, no Magisk module. Shell access comes from one of three engines — **Shizuku / Root / built-in wireless debugging** — and a single on-device daemon script listens to `logcat` events and performs the unlock.

> This project injects your lock-screen password into the system. Read [Security](#security) and [Disclaimer](#disclaimer) first.

> **Recommended build: 3.3.3** — fixes a false-unlock caused by reusing the previous run's online cache after deploy/restart. Other builds are under [releases/](releases/); the detailed guide is [README-9.0.md](README-9.0.md).

## Download

- **3.3.3, officially signed**: [watch-unlock-3.3.3-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.3.3/watch-unlock-3.3.3-official.apk) (3.2 MB, direct download)
- **Detailed guide** (how it works, config keys, troubleshooting, comparisons): [README-9.0.md](README-9.0.md) · [English](README-9.0.en.md)
- Older builds: [releases/](releases/)

---

## Features

| Capability | Description |
| --- | --- |
| Unlock on wake | Target Bluetooth device online + screen on → `wm dismiss-keyguard` and inject the PIN |
| Wi-Fi conditions | Use the currently connected Wi-Fi as an unlock condition — standalone or combined with Bluetooth |
| Three engines | **Shizuku** (recommended, no root) / **Root** (`su`) / **built-in wireless debugging** (the app acts as its own ADB client, no Shizuku needed) |
| Music lock-screen return | After unlocking, optionally open a chosen app or swipe right; when both are on, opening the app wins |
| Haptics | Light vibration on taps and toggles, with a master switch in Settings |
| Unlock hardening | SIM PIN guard (never type the phone PIN into a SIM prompt), custom pause duration |
| Built-in ADB console | Once wireless debugging is connected, run `adb shell` commands inside the app |
| Announcements | Three-level info page: build notes, usage notes, open-source credits |
| Runtime log | View the daemon log in-app, with one-tap copy |
| Low overhead | Event-driven; measured background CPU ≈ 0 and about 9 MB RAM |

## Versions

| Version | Highlights |
| --- | --- |
| 1.0 | Shizuku engine, Bluetooth unlock on wake, Wi-Fi unlock, logs |
| 1.1 | Standalone Wi-Fi unlock and Wi-Fi requirement, SIM PIN guard, custom pause, three-level settings, Android 12 fixes |
| 2.0 / 3.x | Three engines (Shizuku / Root / wireless debugging), haptics, music lock-screen return, announcements, built-in ADB console |
| 3.3.3 | Fixes false unlocks caused by reusing the previous online cache after redeploy; deploy/restart clears runtime leftovers; settings and announcements remind you to redeploy after changes |

## How it works

```
Screen on / Wi-Fi change
        │  logcat -b events -s power_screen_state,wifi_network_state_changed
        ▼
wu-daemon.sh (sh, running as shell uid 2000)
        │  reads /sdcard/watch-unlock/config.conf
        │  checks Bluetooth/Wi-Fi conditions, debounce, directory locks
        ▼
wm dismiss-keyguard  →  input text <PIN>  →  (optional) music_return
```

- There is only one daemon script, installed in `/sdcard/watch-unlock/` and kept alive with `sh`; it has no native binary dependency.
- The three engines are abstracted behind one exec interface: `ShizukuBridge`, `RootBridge` (`su -c`), and `AdbBridge` (Kadb, connecting to `127.0.0.1:<port>` on the phone itself).
- The app only pushes configuration and displays status. All unlocking happens in the daemon, so killing the app does not disable it.

## Installation

1. Download an APK from [Releases](../../releases) (`3.0` is test-signed, `3.3` is officially signed; the signatures differ and **cannot overwrite each other**).
2. Open the app and grant notifications and nearby-devices permissions when prompted.
3. Go to **Settings → Engine**:
   - **Shizuku**: install and start [Shizuku](https://shizuku.rikka.app/), then tap “Request permission” in this app.
   - **Root**: set Root access to “Apps and ADB” in Developer options and allow the `su` prompt on first run.
   - **Wireless debugging**: enable it in Developer options → open the app's “Wireless debugging” page → pair with the pairing code → connect.
4. Return to the home screen and tick the target devices in the Bluetooth list (tap “Rescan” to refresh); for the Wi-Fi condition, tap “Add current Wi-Fi”.
5. Tap “Deploy & enable”; the status turns to “Running” when active.

> **For stability, return to the home screen and tap “Deploy & enable” again after changing any option.** Redeploying clears leftovers from the previous run (old daemon, old logcat listener, cross-process locks and the online cache) but never wipes your settings, selected devices or logs.

## Build

No Gradle. The build uses the native toolchain `aapt2 → javac → d8 → zipalign → apksigner`.

```powershell
$env:JAVA_HOME    = 'C:\path\to\jdk17'
$env:ANDROID_HOME = 'C:\path\to\android-sdk'   # build-tools >= 36 and platform android-34 required
# Put dependency jars in libs\; see libs\README.md

powershell -ExecutionPolicy Bypass -File app\build.ps1 -VersionName 3.3 -VersionCode 33
```

The signing password is passed via an environment variable and never written to disk:

```powershell
$env:SMART_KS_PASS = '<keystore password>'
powershell -ExecutionPolicy Bypass -File app\build.ps1 -Keystore 'C:\path\my.jks' -KeyAlias 'my alias'
```

## Layout

```
app\
  AndroidManifest.xml
  java\com\watchunlock\app\      # Activities / service / the three engine bridges
  res\                           # icons and strings (Chinese and English)
  assets\wu-daemon.sh            # daemon script shipped with the app
  build.ps1                      # manual build script
docs\                            # tutorials and design notes
screenshots\
libs\                            # build-time dependencies (binaries not committed; see README)
```

## Compatibility

Shizuku and Root paths have been verified on Android 12 / 13 / 14 / 16 devices (HyperOS and LineageOS included); the wireless-debugging path has been verified for connections on Android 14, while the pairing-code flow is best tested on your own device.

## FAQ

- **Status shows “authorization service disconnected”**: Shizuku was reclaimed by the system. Reopen Shizuku and keep it resident (add it to the battery whitelist).
- **Stuck on the music lock screen after unlocking**: enable “Music lock-screen return” in Settings, fill in the music app package name, or enable the right swipe.
- **The Wi-Fi you want is missing from the list**: only the currently connected Wi-Fi can be added; manual SSID entry is not supported.
- **Wireless debugging says “not configured”**: finish pairing and connection on the “Wireless debugging” page first; the pairing port and the connect port are different numbers.
- **Changing the engine has no effect**: switching engines requires tapping “Deploy & enable” again, and possibly restarting the app once.

## Security

- The app injects your lock-screen PIN. Do not use it on shared or untrusted devices.
- The PIN is stored only in the app's private directory and the script config file; it is never uploaded. Anyone with shell access can read it.
- Enable “SIM PIN guard” and use pause as needed to avoid unintended unlocks.

## Disclaimer

This project is intended for automating your own device. Any consequences of using it — including but not limited to a locked device, data loss, violation of local laws, or breach of warranty terms — are your responsibility. The author accepts no liability.

## Credits

- [Shizuku](https://github.com/RikkaApps/Shizuku-API) (Apache-2.0): root-free shell authorization
- [Kadb](https://github.com/flyfishxu/Kadb) (Apache-2.0): in-app wireless debugging
- [Bouncy Castle](https://www.bouncycastle.org/) (MIT-style), [Okio](https://github.com/square/okio), [Kotlin](https://kotlinlang.org/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (all Apache-2.0)
- Full third-party licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## License

[BSD 3-Clause](LICENSE) © 2026 ynhyttmy

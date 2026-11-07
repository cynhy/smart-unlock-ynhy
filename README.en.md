# Smart Unlock

**Device nearby, screen wakes, phone unlocks.**  
Use a paired Bluetooth device (watch / earbuds / band) as a key: while it is online, the phone enters its lock-screen PIN automatically on screen wake; when it is away, nothing happens.

[![License](https://img.shields.io/badge/license-BSD--3--Clause-2ea44f)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)]()
[![Stable](https://img.shields.io/badge/stable-1.1%20%7C%203.3.3%20%7C%206.3.6-1f6feb)]()
[![Latest](https://img.shields.io/badge/latest-6.4.3%20beta-f59e0b)]()

[简体中文](README.md) | English

> [!IMPORTANT]
> **Only 1.1, 3.3.3 and 6.3.6 are marked stable.** Other signed builds are kept as historical / test releases.
>
> **Wireless debugging: every build before 6.4.0 has a known bug and may fail to pair or connect.** Use **6.4.0 or later** (the current beta is **6.4.3**) if you need wireless debugging; 6.4.0 fixes service discovery, connect-port discovery and the notification send button. **6.3.9 is a beta** (versionCode 639) but its wireless debugging is unreliable — do not rely on it. Unsigned APKs are never uploaded to Releases.

## What it does

Smart Unlock treats “a paired Bluetooth device is online” as an unlock condition and injects the PIN with shell access when the screen wakes. Everything runs on-device; the app makes no network requests and uploads nothing.

| Engine | Requirement | Notes |
| --- | --- | --- |
| **Shizuku** | Shizuku installed and running | No root; recommended |
| **Root** | Device rooted, `su` grants the app | Root access must be “Apps and ADB”; much more stable in 6.3.7 |
| **Wireless debugging** | Android 11+, enable “Wireless debugging” manually | Turns off after reboot and the port changes; pair again |

## 6.4.3 (beta)

> **Important: every build before 6.4.0 (including 6.3.9) has a known wireless-debugging bug — the pairing service may not be found, the connect port may be missing after pairing, or the notification send button may not respond.** Use 6.4.0 or later (6.4.3 recommended).

Note: the core wireless-debugging fixes shipped in **6.4.0**; 6.4.3 keeps them and adds the split-screen hint.

- Rewritten mDNS discovery (6.4.0): resolve queue, resolve retries and discovery restart retries;
- The connect port is discovered in parallel and cached; after pairing the app tries the cached/last port and keeps waiting up to 25 seconds for a new port instead of failing immediately;
- The notification is no longer rebuilt on every state callback, so the inline input is not collapsed and the send button does not need repeated taps;
- New “Big input” action (6.4.0) opens a full-size input page with a large “Send” button (single tap);
- **New in 6.4.3**: the input page recommends split screen or a floating window, because the system pairing-code card may collapse and the code becomes invalid;
- Web ADB now documents the two-phone flow and provides 9 copyable command cards.

### Wireless-debugging engine (official ADB since 6.3.7, kept in 6.4.3)

The built-in wireless debugging no longer uses the third-party Kadb engine. Since 6.3.7 the app bundles the **official AOSP adb client** (Termux android-tools 37.0.0p1 build, Apache-2.0) and runs `adb pair` / `adb connect` / `adb shell` / `adb push` as its own uid: `libadb.so` is shipped in `lib/arm64-v8a/`, its dependencies are unzipped from `assets/adb-libs.zip` into app-private storage on first use. Pairing is unchanged: enter the 6-digit code in the notification, or use split screen with “Manual pairing”.

### Root stability

- Reuses one persistent `su` shell instead of spawning `su` for every command;
- Writes every command to a unique script file, removing concurrent overwrite bugs;
- First authorization wait is 15 s, failure cache 20 s, success cache 5 min;
- Starts the daemon with `setsid` (falls back to `nohup`) so it survives the `su` session;
- Moves Root status probing off the UI thread;
- Adds a “Test root” button in Settings.

### Web ADB fallback

Google may tighten local loopback ADB (`127.0.0.1`). The wireless-debugging page now has a **Web ADB** entry:

- Uses WebUSB instead of the phone's own adbd loopback port;
- Host can be **any device except iOS** (Windows / macOS / Linux / Android tablet or another phone);
- Requires a Chromium browser with WebUSB support (Chrome / Edge / Brave / Opera);
- Target must be an Android device with USB debugging enabled;
- The page is owned by **晨钟酱 (CzJam)**, version **6.3.9**, part of the **“搞机工具箱” Web edition** (**GPL-3.0**).

> The app only links to the page and explains how to use it. It does **not** bundle or modify the page.  
> Online: <https://czjam.github.io/gjgjxweb/>　Source: <https://github.com/CzJam/gjgjxweb>

See [docs/网页版ADB-使用说明.md](docs/网页版ADB-使用说明.md) (Chinese).

## Download

### Stable

| Version | File | Notes |
| --- | --- | --- |
| **1.1** | [watch-unlock-1.1.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v1.1/watch-unlock-1.1.apk) | Early stable; same official signature as 3.3+ |
| **3.3.3** | [watch-unlock-3.3.3-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.3.3/watch-unlock-3.3.3-official.apk) | Stable baseline |
| **6.3.6** | [watch-unlock-6.3.6-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v6.3.6/watch-unlock-6.3.6-signed.apk) | **Recommended stable**, fixes MAC parsing and pairing notifications |

### Beta

| Version | File | Notes |
| --- | --- | --- |
| 6.4.3 | `watch-unlock-6.4.3-signed.apk` (uploaded after signing) | **Wireless-debugging fixes** (pairing discovery / connect port / big send button) + Root fixes + Web ADB two-phone flow; beta |

### All signed builds

| Version | File |
| --- | --- |
| 3.0 | [watch-unlock-3.0-test.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.0-test/watch-unlock-3.0-test.apk) |
| 3.3 | [watch-unlock-3.3-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.3/watch-unlock-3.3-official.apk) |
| 3.6 | [watch-unlock-3.6-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.6/watch-unlock-3.6-official.apk) |
| 3.8 | [watch-unlock-3.8-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.8/watch-unlock-3.8-official.apk) |
| 3.8.6 | [watch-unlock-3.8.6-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.8.6/watch-unlock-3.8.6-signed.apk) |
| 3.8.8 | [watch-unlock-3.8.8-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.8.8/watch-unlock-3.8.8-signed.apk) |
| 3.9 | [watch-unlock-3.9-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.9/watch-unlock-3.9-signed.apk) |
| 6.3.3 | [watch-unlock-6.3.3-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v6.3.3/watch-unlock-6.3.3-signed.apk) |

> Except for 3.0-test (separate test key), 1.1 and every signed build from 3.3 onward share the same official certificate (SHA-256 `18c39f81f5ba24f82d9553ce469b45c1b44d5c93aef21c71c634a2659d681008`) and can overwrite each other; Android still blocks version-code downgrades. 3.0-test **cannot** overwrite official builds — uninstall first (in-app settings will be lost).

## Quick start

1. Install 6.3.6 (stable) or 6.4.3 (beta).
2. Settings → Engine → choose Shizuku (recommended) / Root / Wireless debugging, and authorize.
3. Pair the Bluetooth device in system settings, tap “Rescan” in the app, and tick it.
4. Enter the same 4–16 digit PIN as the system lock screen.
5. Tap “Deploy & enable” and test by locking and waking the screen.

Full guide: [docs/使用说明.md](docs/使用说明.md) (Chinese).

## Build

Source lives in [`app/`](app). No Gradle; the build uses `aapt2 → javac → d8 → zipalign → apksigner`.

```powershell
Remove-Item Env:SMART_KS_PASS -ErrorAction SilentlyContinue
$env:WU_VC=643
$env:WU_VN='6.4.3'
powershell -NoProfile -ExecutionPolicy Bypass -File app\build.ps1
```

- Output: `app\build\aligned.apk` (unsigned);
- Set `SMART_KS_PASS` to produce `watch-unlock-6.4.3-signed.apk` (override with `WU_APK`);
- Non-ASCII source paths are mirrored to `%TEMP%\watch-unlock-build` automatically;
- Dependency jars are not committed; see [`libs/README.md`](libs/README.md).

## Compatibility

Android 8.0+ (minSdk 26, targetSdk 34). Shizuku and Root paths have been verified on Android 12 / 13 / 14 / 16; wireless debugging has been verified on Android 14, and pairing is best tested on your own ROM.

## Security

- The PIN is stored in plain text in the shell-private config (mode 600). This is unavoidable for lock-screen injection.
- Shizuku / Root / Wireless debugging all grant shell capability; install APKs only from trusted sources.
- The extended-unlock feature temporarily removes the lock-screen password; it is risky if the device is lost.
- The app makes no network requests. Web ADB is an external GPL-3.0 page loaded by the browser; data goes directly from the browser to the USB device, not through this app.
- Logs include device names, MACs and SSIDs; redact them before posting publicly.

## Third-party licenses

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Main components: Shizuku API, Android Debug Bridge / adb (AOSP) and the Termux android-tools build with its dependency libraries (Apache-2.0 / BSD-style), and the **“搞机工具箱” Web edition 6.3.9** by 晨钟酱 (CzJam, **GPL-3.0**, linked only, not bundled).

## License

[BSD 3-Clause](LICENSE) © 2026 ynhyttmy

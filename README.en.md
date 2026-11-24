# Smart Unlock

**Device nearby, screen wakes, phone unlocks.**  
Use a paired Bluetooth device (watch / earbuds / band) as a key: while it is online, the phone enters its lock-screen PIN automatically on screen wake; when it is away, nothing happens.

[![License](https://img.shields.io/badge/license-BSD--3--Clause-2ea44f)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)]()

[简体中文](README.md) | English

[Download](#download) · [Quick start](#quick-start) · [Web ADB](#web-adb-no-loopback-port) · [User guide](docs/使用说明.md) · [FAQ](#faq)

---

> [!IMPORTANT]
> **Use 6.4.6.** Other versions may have functional defects or security vulnerabilities; they are kept for history only — use them with caution.
>
> **Wireless debugging: every build before 6.4.0 (including 6.3.9) has a known bug and may fail to pair or connect.** Use **6.4.0 or later** (the current recommendation is **6.4.6**) if you need wireless debugging; 6.4.0 fixes pairing-service discovery, connect-port discovery and the notification send button. **6.3.9 is a historical build** (versionCode 639) whose wireless debugging is unreliable — do not rely on it.
>
> **Android 17 / HyperOS 4: you must use 6.4.6 and keep “Settings → Unlock enhancements → Android 17 compatibility” enabled.** 6.4.3 and earlier cannot detect the keyguard on Android 17 and will never unlock.
>
> **Unsigned APKs are never uploaded to any Release.** The repository holds source code and documentation only; Releases contain signed builds only.

## What this project is

Smart Unlock is a small Android tool: it treats “a Bluetooth device is online” as the unlock condition and injects the PIN through a shell when the screen wakes, saving you from typing it every time. The whole chain runs locally — **the app makes no network requests and uploads nothing**.

Not suitable for:

- Pattern or mixed-password lock screens (only a 4–16 digit numeric PIN is supported);
- Users with very high security requirements who cannot accept shell-level access;

## 6.4.6 update (recommended)

> **Important: on Android 17 / HyperOS 4 you must use 6.4.6 and keep “Settings → Unlock enhancements → Android 17 compatibility” enabled.** 6.4.3 and earlier never trigger an unlock on Android 17.

This release is an **Android 17 / HyperOS 4 compatibility patch**; behaviour on Android 8.0 – 16 is identical to 6.4.3. The three authorization paths, Bluetooth / Wi-Fi conditions and extended unlock are unchanged.

### What was fixed

| Change on Android 17 | Behaviour in 6.4.3 | Handling in 6.4.6 |
| --- | --- | --- |
| `dumpsys window policy` drops `mIsShowing` | Misread as “not locked”, unlock skipped | Reads `KeyguardServiceDelegate showing=` instead, with a focus-window fallback |
| The `power_screen_state` event is no longer emitted | No trigger on screen wake | Adds a 0.9 s low-frequency poll as a safety net, edge-triggered |
| `ACTION_SCREEN_ON` is not delivered to background apps | The app-side wake chain dies | App wake chain plus daemon polling |

Other improvements:

- The PIN is injected **only after the keyboard gains focus**, so `input text` no longer lands in the wrong window;
- A full-screen Activity such as a music lock screen is no longer treated as unlocked when `occluded=true` — the daemon waits for the keypad instead;
- New “Unlock enhancements → **Android 17 compatibility**” switch (`A17_COMPAT`, on by default). Turning it off skips polling and focus waiting (faster), but the device will never unlock on Android 17;
- `wu-daemon.sh --check` now reports `a17_compat`, and the startup log carries `a17=1`.

### Measured

| Scenario | Result |
| --- | --- |
| Screen off → screen on | Unlocked in about 370–620 ms |
| Screen off → screen on after `am force-stop` | Unlocked in about 760 ms |
| “Android 17 compatibility” turned off | No trigger for 8+ seconds; the screen stays locked |

### Cost

- The poll costs about **0.2–0.4 mA** (0.3–0.5% of one core, roughly 5–10 mAh per day, ~0.1–0.2% battery);
- The whole solution stays at about **1.5–2 mA** (36–48 mAh per day, ~0.7–1.0%);
- Polling starts only when `mIsShowing` is absent, so Android 16 and earlier pay nothing extra.

### Upgrading

- Same official signature as 6.4.3, so it installs over it (versionCode 646).
- After installing, open the app and tap **“Deploy and enable”** once: the daemon script ships inside the APK and is not replaced automatically.
- Confirm “Settings → Unlock enhancements → Android 17 compatibility” is on.

## 6.4.3 update (wireless-debugging fixes)

> **Important: every build before 6.4.0 (including 6.3.9) has a known wireless-debugging bug — the pairing service may not be found, the connect port may be missing after pairing, or the notification send button may not respond.** Use 6.4.0 or later (6.4.6 recommended).

Note: the core wireless-debugging fixes shipped in **6.4.0**; 6.4.3 keeps them and adds the split-screen hint.

- Rewritten mDNS discovery (6.4.0): resolve queue, resolve retries, and discovery-restart retries, fixing the long “pairing service not found” wait;
- The connect port is discovered in parallel and cached; after pairing the app tries the cached/last port and keeps waiting up to 25 seconds for a new port instead of failing immediately;
- The notification is no longer rebuilt on every state callback, so the inline input is not collapsed and the send button does not need repeated taps;
- New “Big input” action (6.4.0): opens a full-size input page with a large “Send” button (single tap);
- **New in 6.4.3**: the input page recommends split screen or a floating window, because leaving the system pairing-code page may collapse the card and invalidate the code;
- Web ADB now documents the two-phone flow and provides 9 copyable command cards.

### Wireless-debugging engine (official ADB since 6.3.7, kept in 6.4.3)

The built-in wireless debugging no longer uses the third-party Kadb engine. Since 6.3.7 the app bundles the **official AOSP adb client** (Termux android-tools 37.0.0p1 build, Apache-2.0) and runs `adb pair` / `adb connect` / `adb shell` / `adb push` as its own uid:

- `libadb.so` ships in `lib/arm64-v8a/` and is unpacked by the system into `nativeLibraryDir`;
- Its dependencies ship in `assets/adb-libs.zip` and are unzipped into app-private storage on first use (set read-only);
- Pairing entry points are unchanged: type the 6-digit code in the notification, or use split screen with “Manual pairing”;
- The APK no longer contains Kadb / Kotlin / Bouncy Castle / Okio.

### Root stability fixes

| Problem | 6.3.6 and earlier | 6.4.3 |
| --- | --- | --- |
| `su` spawned for every command | Screen-on unlock / status refresh waits for `su` startup, occasional timeouts | One persistent `su` interactive shell is reused |
| Concurrent commands overwrite each other | Three callers shared `wu-root.sh`, contents could be truncated | Every command writes a unique script file |
| First authorization too late to tap | 2.5 s timeout, 120 s failure cache, “root unavailable” right after tapping Allow | First wait is 15 s; failure cache 20 s; success cache 5 min |
| `su` exit kills the daemon | Some root implementations kill the daemon too | Daemon starts with `setsid` (falls back to `nohup`) |
| Root probing blocks the UI | `statusText` ran `su` on the UI thread | Probing moved to a background thread |
| No way to retest manually | Only triggered during deployment | New “Test root” button in Settings |

### New Web ADB fallback

To prepare for Google possibly tightening local loopback ADB (`127.0.0.1`), Settings → Authorization method now has a 4th item, **Web ADB** (a fallback entry only; it does not change the current engine; tapping it opens an explanation page first):

- It does not use the phone's own adbd loopback port; it uses **WebUSB** to connect directly to an Android device;
- The host can be **any device except iOS** (Windows / macOS / Linux / Android tablet or another phone);
- The browser must be Chromium-based with WebUSB support (Chrome / Edge / Brave / Opera);
- The target must be an Android device with “USB debugging” enabled;
- The page is owned and maintained by **晨钟酱 (CzJam)**, version **6.3.9**, part of the open-source **“搞机工具箱” Web edition** (**GPL-3.0**).

> The app only provides the entry and usage notes. It does **not** bundle or modify the page; copyright, content and data handling belong to that project.  
> Online: <https://czjam.github.io/gjgjxweb/>　Source: <https://github.com/CzJam/gjgjxweb>

See [docs/网页版ADB-使用说明.md](docs/网页版ADB-使用说明.md) (Chinese).

## Three authorization tiers

| Engine | Requirement | Stability | Who it is for | Notes |
| --- | --- | --- | --- | --- |
| **Shizuku** | Shizuku installed and running | Stable | Most users | No root; recommended |
| **Root** | Device rooted, `su` grants the app | Stable (much better since 6.3.7) | Rooted users | Root access must be “Apps and ADB” |
| **Built-in wireless debugging** | Android 11+, enable “Wireless debugging” manually | Fairly stable | Users who do not want Shizuku | Turns off after reboot and the port changes; pair again |

The three are mutually exclusive; after switching, tap “Deploy & enable” again.

## Download

### Recommended build (6.4.6)

| Version | Download | Notes |
| --- | --- | --- |
| **6.4.6** | [watch-unlock-6.4.6-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v6.4.6/watch-unlock-6.4.6-signed.apk) | **Currently recommended**: Android 17 / HyperOS 4 compatibility (keyguard detection + wake polling + keypad focus), plus every 6.4.3 fix |
| 6.4.3 | [watch-unlock-6.4.3-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v6.4.3/watch-unlock-6.4.3-signed.apk) | Previous build: wireless-debugging fixes (pairing discovery / connect port / big send button) + Root stability fixes + Web ADB two-phone flow; **cannot unlock on Android 17** |

### Other signed builds (historical, may have functional defects or security vulnerabilities)

| Version | Download | Notes |
| --- | --- | --- |
| 1.1 | [watch-unlock-1.1.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v1.1/watch-unlock-1.1.apk) | Early build; same official signature as 3.3 and later — use with caution |
| 3.3.3 | [watch-unlock-3.3.3-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.3.3/watch-unlock-3.3.3-official.apk) | Baseline build — use with caution |
| 6.3.6 | [watch-unlock-6.3.6-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v6.3.6/watch-unlock-6.3.6-signed.apk) | Fixed MAC parsing and pairing notifications — use with caution |

### All signed builds (historical / test)

| Version | Download | Signature | Role |
| --- | --- | --- | --- |
| 3.0 | [watch-unlock-3.0-test.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.0-test/watch-unlock-3.0-test.apk) | Test key | Test |
| 3.3 | [watch-unlock-3.3-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.3/watch-unlock-3.3-official.apk) | Official | Historical |
| 3.6 | [watch-unlock-3.6-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.6/watch-unlock-3.6-official.apk) | Official | Historical |
| 3.8 | [watch-unlock-3.8-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.8/watch-unlock-3.8-official.apk) | Official | Historical |
| 3.8.6 | [watch-unlock-3.8.6-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.8.6/watch-unlock-3.8.6-signed.apk) | Official | Historical |
| 3.8.8 | [watch-unlock-3.8.8-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.8.8/watch-unlock-3.8.8-signed.apk) | Official | Historical |
| 3.9 | [watch-unlock-3.9-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.9/watch-unlock-3.9-signed.apk) | Official | Historical |
| 6.3.3 | [watch-unlock-6.3.3-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v6.3.3/watch-unlock-6.3.3-signed.apk) | Official | Historical |
| 1.1 / 3.3.3 / 6.3.6 | See historical versions above | Official | Historical |

> **Signing**: except for 3.0-test (separate test key), 1.1 and every signed build from 3.3 onward share the same official certificate (SHA-256 `18c39f81f5ba24f82d9553ce469b45c1b44d5c93aef21c71c634a2659d681008`) and can overwrite each other (Android still blocks version-code downgrades). 3.0-test and official builds **cannot** overwrite each other — uninstall first (in-app settings will be lost).

## Quick start

1. **Install**: use 6.4.6; other versions may have functional defects or security vulnerabilities.
2. **Authorize**: Settings → Authorization method → Shizuku (recommended) / Root / Wireless debugging, and follow the on-screen steps.
3. **Pair the device**: pair the watch / earbuds in system Bluetooth first, then tap “Rescan” in the app and tick it.
4. **Enter the PIN**: use the same 4–16 digit PIN as the system lock screen.
5. **Deploy & enable**: back on the home page, tap “Deploy & enable”, then lock and wake the screen to test.

The complete steps, optional features and troubleshooting are in **[docs/使用说明.md](docs/使用说明.md)** (Chinese, updated to 6.4.6).

## Web ADB (no loopback port)

When local loopback ADB is restricted by the system and wireless debugging cannot pair, use Web ADB over USB:

**Host (the device running the browser)**

- Windows / macOS / Linux / Android tablet or another phone, **except iOS**;
- Chrome / Edge / Brave / Opera — Chromium-based with WebUSB support;
- Firefox does not support WebUSB; iOS browsers do not either.

**Target**

- Android device with “USB debugging” enabled in developer options;
- Allow the RSA prompt on first connection.

**Steps**

1. Connect a cable (computer direct; OTG adapter when a phone is the host; C-to-C cable between two phones);
2. Open <https://czjam.github.io/gjgjxweb/> in Chrome / Edge on the host (network required on first load);
3. Tap “Connect” → select the USB device → allow USB debugging on the target;
4. Run commands in the page's ADB terminal.

The in-app explanation page also provides 9 copyable command cards: tap a card to copy, then paste it into the page terminal.

See [docs/网页版ADB-使用说明.md](docs/网页版ADB-使用说明.md) (Chinese).

## Compatibility and limits

| Item | Requirement |
| --- | --- |
| System | Android 8.0+ |
| Lock screen | 4–16 digit numeric PIN |
| Wireless debugging | Android 11+ (the release where system “Wireless debugging” appeared) |
| Web ADB | Host non-iOS + Chromium browser; target Android + USB debugging |
| Permissions | Notifications, nearby devices (Android 12+); network for wireless debugging |
| Recommended | Battery policy “unrestricted”, allow autostart / background pop-ups |

Not supported: pattern / mixed passwords, iOS as a Web ADB host, auto-lock when the device leaves, remote unlocking.

## How it works

```text
Screen on / USER_PRESENT
        │
        ├─ Read wu.cfg (devices, Wi-Fi, cooldown, pause, extended unlock)
        ├─ Bluetooth scan: are the ticked devices online (RSSI / multi-device / hardening)
        ├─ Wi-Fi check: optional required condition / BSSID binding
        ├─ Lock-screen check: keyguard / SIM PIN / another unlock in progress
        └─ Conditions met → input PIN → verify unlocked → cooldown
```

The daemon script `wu-daemon.sh` is executed through one of the three authorization tiers; the app only handles configuration, deployment, status display and the watchdog.

## Build

Source lives in [`app/`](app). No Gradle; the build uses `aapt2 + javac + d8 + zipalign`.

```powershell
Remove-Item Env:SMART_KS_PASS -ErrorAction SilentlyContinue   # no passphrase = unsigned output only
$env:WU_VC=646
$env:WU_VN='6.4.6'
powershell -NoProfile -ExecutionPolicy Bypass -File app\build.ps1
```

- Output: `app\build\aligned.apk` (unsigned);
- With `SMART_KS_PASS` set (official passphrase), the output is `watch-unlock-6.4.6-signed.apk` (rename with `WU_APK`);
- Non-ASCII source paths are mirrored to `%TEMP%\watch-unlock-build` automatically, and the artifact is copied back to `app\build`;
- Dependency jars are not committed; see [`libs/README.md`](libs/README.md).

Signing:

```powershell
apksigner sign --ks <your keystore> --ks-pass pass:<passphrase> `
  --out watch-unlock-6.4.6-signed.apk app\build\aligned.apk
```

## Layout

```text
app/                       Android source (Java + hand-written layouts)
  assets/wu-daemon.sh       Daemon script (runs on the device)
  java/com/watchunlock/app  App code
docs/                     User guide, Web ADB, historical tutorials
releases/                 Release notes per version
libs/                     Dependency and bundled-adb instructions (jars not committed)
app/jniLibs/              Bundled adb executable (libadb.so)
app/assets/adb-libs.zip   Dependencies for the bundled adb
```

## FAQ

| Problem | What to do |
| --- | --- |
| Nothing happens on screen wake | Check the `skip` reason in the log; confirm the daemon is running and at least one device is ticked |
| Device always reported offline | Run `sh /data/local/tmp/watch-unlock/wu-daemon.sh --check` in the ADB console; confirm the device is paired and its name is unchanged |
| Settings changes do not take effect | Hot reload since 6.3; if it says “not written to the daemon”, tap “Deploy & enable” |
| Stops working after reboot | The daemon does not survive a reboot; restart Shizuku or pair wireless debugging again, then deploy |
| No input field in the notification on Xiaomi / HyperOS | Switch the notification style to “native style” |
| Wireless debugging pairing fails | Keep the system pairing dialog open; pull down the notification and tap “Enter pairing code”, or use split screen and fill in the pairing port + 6-digit code under “Manual pairing”; do not close the dialog |
| “Root unavailable” right after tapping Allow | Since 6.3.7 the first wait is 15 s; tap “Test root” again; confirm Root access is “Apps and ADB” |
| Web ADB cannot see the device | Try Chrome / Edge; try another USB cable / OTG; confirm USB debugging and RSA on the target; iOS / Firefox will not work |
| Fingerprint fails after extended unlock | Expected; re-enroll fingerprints after the password is restored |
| Disabled after repeated failures | Usually the system PIN changed; update the PIN in the app and redeploy |

More in [docs/使用说明.md §7](docs/使用说明.md#7-常见问题) (Chinese).

## Security and privacy

- The PIN is stored in plain text in the shell-private directory (mode 600); this is unavoidable for lock-screen injection;
- Shizuku / Root / Wireless debugging all grant shell capability; install APKs only from trusted sources;
- Extended unlock temporarily removes the lock-screen password and is risky if the device is lost;
- The app makes no network requests; wireless-debugging mDNS only discovers the local port on the LAN;
- Web ADB is an external page that needs network access on first load; after that the browser talks directly to the USB device and data does not pass through this app. Enter via the official address; content and data handling belong to CzJam's project;
- Logs contain device names, MACs and SSIDs; redact them before posting publicly.

## Third-party licenses

This project is released under **BSD-3-Clause**, see [LICENSE](LICENSE). Third-party components, licenses and copyright notices are listed in **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**, mainly:

- Shizuku / Shizuku API (Apache-2.0) — authorization and shell execution;
- Android Debug Bridge / adb (AOSP, Apache-2.0) — the built-in wireless-debugging client;
- Termux android-tools 37.0.0p1 build (Apache-2.0) — source of the adb binary;
- Abseil, Protobuf, Brotli, fmt, LZ4, PCRE2 (Apache-2.0), zstd, zlib (BSD-style) — adb dependencies;
- **“搞机工具箱” Web edition 6.3.9** (晨钟酱 / CzJam, **GPL-3.0**) — the external Web ADB page, **linked only; not bundled or modified**.

Special thanks to 晨钟酱 for the public “搞机工具箱” Web edition, which provided both the idea and the ready-made page for loopback-free ADB.

## Credits

- Shizuku / 香草 (RikkaApps)
- AOSP adb, Termux android-tools, and community wireless-debugging implementations such as LADB
- 晨钟酱 (CzJam) — “搞机工具箱” Web edition
- Everyone who filed logs, issues and test feedback

## Disclaimer

This project is for learning and personal devices only. The unlock script, extended unlock, and Root / Shizuku / ADB authorization all touch system security boundaries; back up your data and assess the risks before use. The author is not liable for losses caused by misuse, ROM differences, revoked authorization or a lost device. Follow local laws and do not use it on devices you are not authorized to access.

# Smart Unlock · 亮屏自动解锁

[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](LICENSE)
![minSdk](https://img.shields.io/badge/minSdk-26-green)
![targetSdk](https://img.shields.io/badge/targetSdk-34-green)

连着手表（或耳机等已配对蓝牙设备）时，手机一亮屏就自动输入锁屏 PIN 并解除锁屏，省掉每天上百次的解锁动作。

不需要 root、不需要电脑常驻、不需要刷模块。通过 **Shizuku / Root / 内置无线调试** 三档授权之一拿到 shell 权限，由手机本机的一个守护脚本监听 `logcat` 事件完成解锁。

> 本项目会向系统注入锁屏密码。请先阅读 [安全说明](#安全说明) 与 [免责声明](#免责声明)，确认风险后再使用。

---

## 功能

| 能力 | 说明 |
| --- | --- |
| 亮屏解锁 | 蓝牙目标设备在线 + 屏幕点亮 → 自动 `wm dismiss-keyguard` 并注入 PIN |
| Wi-Fi 联动 | 可指定「当前连接的 Wi-Fi」作为解锁条件，可单独或与蓝牙组合生效 |
| 三档授权 | **Shizuku**（推荐，免 root）/ **Root**（`su` 直接执行）/ **内置无线调试**（App 自己当 ADB 客户端，不依赖 Shizuku） |
| 音乐锁屏返回 | 密码解锁后若停在音乐锁屏页：可「打开指定应用主界面」或「向右滑动」，两者同时开启时优先打开应用 |
| 触感反馈 | 点击按钮与切换开关时的轻震动，可在设置内关闭 |
| 解锁增强 | SIM PIN 防呆（避免解锁后 SIM 仍锁死）、暂停时长自定义 |
| 内置 ADB 控制台 | 无线调试连上后，可在应用内直接输入并执行 `adb shell` 命令 |
| 公告栏 | 三级说明页（测试包提示、注意事项、开源鸣谢） |
| 运行日志 | 应用内查看守护日志，支持一键复制 |
| 低开销 | 事件驱动监听，实测后台 CPU ≈ 0、内存约 9 MB |

## 版本

| 版本 | 主要内容 |
| --- | --- |
| 1.0 | Shizuku 授权、亮屏蓝牙解锁、Wi-Fi 解锁、日志 |
| 1.1 | Wi-Fi 独立解锁与条件校验、SIM PIN 防呆、暂停时长、三级设置页与汉化、安卓 12 兼容修正 |
| 2.0 / 3.x | 三档授权（Shizuku / Root / 无线调试）、触感反馈、音乐锁屏返回、公告栏、内置 ADB 控制台 |

## 原理

```
亮屏 / Wi-Fi 变化
        │  logcat -b events -s power_screen_state,wifi_network_state_changed
        ▼
wu-daemon.sh（sh，跑在 shell uid 2000）
        │  读取 /sdcard/watch-unlock/config.conf
        │  判断蓝牙/Wi-Fi 条件、防重复、目录锁
        ▼
wm dismiss-keyguard  →  input text <PIN>  →  （可选）music_return
```

- 守护脚本只有一份，部署在 `/sdcard/watch-unlock/`，用 `sh` 常驻，不依赖任何 native 二进制。
- 三档授权被抽象为同一条执行接口：`ShizukuBridge`、`RootBridge`（`su -c`）、`AdbBridge`（Kadb，手机自己连 `127.0.0.1:端口`）。
- 应用侧只负责配置下发与状态展示，解锁动作全部在守护脚本里完成，应用被系统杀掉也不影响。

## 安装

1. 从 [Releases](../../releases) 下载 APK（`3.0` 为测试签名，`3.3` 为正式签名，两者签名不同，**不能互相覆盖安装**）。
2. 安装后打开应用，按提示授予通知、附近设备权限。
3. 进入 **设置 → 授权方式**：
   - **Shizuku**：先安装并启动 [Shizuku](https://shizuku.rikka.app/)，回到本应用点「请求授权」。
   - **Root**：开发者选项里把 Root access 设为「Apps and ADB」，首次执行时在 `su` 弹窗里允许。
   - **无线调试**：开发者选项打开「无线调试」→ 进入本应用「无线调试」页 → 用配对码完成配对 → 连接。
4. 回到主页添加目标设备：点右上角「添加当前设备/当前 Wi-Fi」。
5. 点「部署并启用」，状态变成「运行中」即生效。

## 构建

不使用 Gradle，走 `aapt2 → javac → d8 → zipalign → apksigner` 的原生工具链。

```powershell
$env:JAVA_HOME    = 'C:\path\to\jdk17'
$env:ANDROID_HOME = 'C:\path\to\android-sdk'   # 需 build-tools >= 36、platform android-34
# 依赖 jar 放到 libs\，清单见 libs\README.md

powershell -ExecutionPolicy Bypass -File app\build.ps1 -VersionName 3.3 -VersionCode 33
```

签名由环境变量传入口令，不落盘：

```powershell
$env:SMART_KS_PASS = '<密钥库口令>'
powershell -ExecutionPolicy Bypass -File app\build.ps1 -Keystore 'C:\path\my.jks' -KeyAlias 'my alias'
```

## 目录结构

```
app\
  AndroidManifest.xml
  java\com\watchunlock\app\      # Activity / Service / 三档授权桥
  res\                           # 图标、字符串（中英）
  assets\wu-daemon.sh            # 守护脚本（随应用下发）
  build.ps1                      # 手工构建脚本
docs\                            # 使用教程与原理说明
screenshots\
libs\                            # 编译期依赖（不提交二进制，见 README）
```

## 兼容性

已在 Android 12 / 13 / 14 / 16（含 HyperOS、LineageOS）设备上验证过 Shizuku 与 Root 路径；无线调试路径已在 Android 14 上验证连接，配对码流程建议自行实测。

## 常见问题

- **状态显示「授权服务已断开」**：Shizuku 被系统回收，重新打开 Shizuku 并保持后台常驻（加入电池白名单）。
- **解锁后停在音乐锁屏**：在设置里开启「音乐锁屏返回」，填写音乐应用包名或开启右滑。
- **Wi-Fi 列表里没有想加的网络**：只能添加「当前已连接」的 Wi-Fi，不支持手动输入 SSID。
- **无线调试提示 not configured**：先在「无线调试」页完成配对与连接，配对端口和连接端口不是同一个数字。
- **换授权方式后不生效**：切换档位需要重新点「部署并启用」，必要时重启一次应用。

## 安全说明

- 应用需要注入锁屏 PIN，请勿在不受信任的设备或共享设备上使用。
- PIN 只保存在本机应用私有目录 / 脚本配置文件里，不会上传；但拿到 shell 权限的人可以读到它。
- 建议开启「SIM PIN 防呆」并按需设置暂停时长，避免误触发。

## 免责声明

本项目仅用于个人设备自动化。使用本软件所产生的任何后果（包括但不限于设备被锁、数据丢失、违反所在地法律法规或设备保修条款）由使用者自行承担，作者不承担任何责任。

## 鸣谢

- [Shizuku](https://github.com/RikkaApps/Shizuku-API)（Apache-2.0）：免 root 的 shell 授权通道
- [Kadb](https://github.com/flyfishxu/Kadb)（Apache-2.0）：应用内无线调试
- [Bouncy Castle](https://www.bouncycastle.org/)（MIT 风格）、[Okio](https://github.com/square/okio)、[Kotlin](https://kotlinlang.org/)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)（均为 Apache-2.0）
- 详细的第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 许可证

[BSD 3-Clause](LICENSE) © 2026 ynhyttmy

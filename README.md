<div align="center">

# Smart Unlock

**设备在旁，亮屏即解。**  
用已配对的蓝牙设备（手表 / 耳机 / 手环）作为钥匙：设备在线时，手机亮屏自动输入锁屏 PIN；设备不在线时，什么都不做。

[![License](https://img.shields.io/badge/license-BSD--3--Clause-2ea44f)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)]()
[![Stable](https://img.shields.io/badge/stable-1.1%20%7C%203.3.3%20%7C%206.3.6-1f6feb)]()
[![Latest](https://img.shields.io/badge/latest-6.4.3%20beta-f59e0b)]()

[下载](#下载) · [快速开始](#快速开始) · [网页版 ADB](#网页版-adb免回环端口) · [使用手册](docs/使用说明.md) · [常见问题](#常见问题)

</div>

---

> [!IMPORTANT]
> **稳定版只有 1.1、3.3.3、6.3.6。** 其余已签名版本都会保留，但只作为历史 / 测试版提供，不承诺同等稳定性。
>
> **无线调试：6.4.3 之前的所有版本都存在已知 bug，可能无法完成配对或连接。** 需要无线调试请使用 **6.4.3 及以后**；6.4.3 修复了配对服务发现、连接端口发现和通知输码按钮的问题。**6.3.9 是测试版**（versionCode 639），但无线调试不可靠，请勿用于实际使用。
>
> **未签名 APK 不会上传到任何 Release。** 仓库只收录源码与文档；Release 中只放已签名包。

## 这个项目是什么

Smart Unlock 是一个 Android 小工具：它把「蓝牙设备在线」当作解锁条件，在亮屏时用 shell 注入 PIN，帮你省掉每次手动输入。整个链路在本地完成，**应用不联网、不上传数据**。

适合：

- 手表 / 手环 / 耳机常年戴在身上，希望亮屏即进桌面；
- 开发机、备用机、家里固定位置的手机；
- 想用 Shizuku / Root / 内置 ADB 三种方式之一，不想装 Xposed 模块。

不适合：

- 图案 / 混合密码锁屏（只支持 4～16 位纯数字 PIN）；
- 对安全性要求极高、不能接受 shell 权限的用户；
- 需要「设备离开就立刻锁屏」的强安全场景（本项目只负责解锁，不负责锁屏）。

## 6.4.3 更新（测试版）

> **重要：6.4.3 之前的所有版本（含 6.3.9），无线调试都有已知 bug，可能搜不到配对服务、配对成功后找不到连接端口，或通知里点发送没反应。** 请使用 6.4.3 及以后。

本次修复：

- mDNS 发现重写：解析队列、失败重试、发现服务重启重试，修掉长时间搜不到配对服务；
- 连接端口提前并行发现并缓存，配对后优先使用缓存/上次端口，25 秒内持续等待新端口，不再过早报“没找到连接端口”；
- 通知不再反复重建，避免内联输入框被收起、发送按钮需要猛点；
- 新增「大按钮输入」：打开独立输入页，使用大号「发送」按钮，点一次即提交；页面提示推荐分屏 / 小窗，避免设备码卡片收起；
- 网页版 ADB 新增「两台手机互连」流程与 9 张可复制 ADB 指令卡片。

### 无线调试内核重写（6.3.7 起，6.4.3 沿用）

内置无线调试原本依赖第三方 Kadb 内核；6.3.7 起换成随包打包的**官方 AOSP adb 客户端**（Termux android-tools 37.0.0p1 构建，Apache-2.0），6.4.3 沿用同一实现：

- 可执行文件 `libadb.so` 放在 `lib/arm64-v8a/`，安装后由系统解压到 `nativeLibraryDir`；
- 依赖库放在 `assets/adb-libs.zip`，首次使用时解压到应用私有目录并设为只读；
- 以应用自身 uid 执行 `adb pair` / `adb connect` / `adb shell` / `adb push`，不依赖 Shizuku；
- 配对入口不变：通知输入 6 位配对码，或分屏 +「手动配对」；
- APK 不再包含 Kadb / Kotlin / Bouncy Castle / Okio。

### Root 通道稳定性修复

| 问题 | 6.3.6 及以前 | 6.4.3 |
| --- | --- | --- |
| 每次操作都重新拉起 `su` | 亮屏解锁 / 状态刷新都要等 su 启动，偶发超时 | 复用一条常驻 `su` 交互 shell |
| 并发命令互相覆盖 | 三个调用共用 `wu-root.sh`，内容可能被截断 | 每条命令写入唯一脚本文件 |
| 首次授权来不及点 | 2.5 秒超时，失败缓存 120 秒，刚点允许仍显示不可用 | 首次等待 15 秒；失败缓存 20 秒；成功缓存 5 分钟 |
| `su` 退出带走守护 | 部分 root 实现会连带杀死守护 | 守护用 `setsid` 进入独立会话，退回 `nohup` |
| Root 检测卡主界面 | `statusText` 在 UI 线程执行 `su` | 检测移入后台线程 |
| 无法手动复测 | 只能等部署时触发弹窗 | 设置页新增「检测 Root 授权」 |

### 新增网页版 ADB（备用通道）

为应对谷歌可能收紧「本机回环 ADB」（`127.0.0.1`）的风险，设置页「授权方式」新增第 4 项 **网页版 ADB**（仅备用入口，不改变当前授权方式；点进去先看说明页）：

- 不依赖手机自己的 adbd 回环端口，改用 **WebUSB** 直连安卓设备；
- 主机可以是 **除 iOS 以外的任何设备**（Windows / macOS / Linux / Android 平板或另一台手机）；
- 浏览器需为 Chromium 内核且支持 WebUSB（Chrome / Edge / Brave / Opera）；
- 被调试端必须是安卓设备，并打开「USB 调试」；
- 网页由 **晨钟酱（CzJam）** 所有并维护，版本 **6.3.9**，属于开源项目 **《搞机工具箱》Web 版**（**GPL-3.0**）。

> 本应用只提供入口和使用说明，**没有内置、也没有修改其网页代码**。网页的版权、内容与数据安全由该网页自行负责。  
> 在线地址：<https://czjam.github.io/gjgjxweb/>　源码：<https://github.com/CzJam/gjgjxweb>

详见 [网页版 ADB 使用说明](docs/网页版ADB-使用说明.md)。

## 三档授权

| 方式 | 前提 | 稳定性 | 适合谁 | 备注 |
| --- | --- | --- | --- | --- |
| **Shizuku** | 已安装并启动 Shizuku | 稳定 | 大多数用户 | 免 root，推荐 |
| **Root** | 设备已 root，`su` 放行本应用 | 稳定（6.3.7 起明显改善） | 已 root 的用户 | 开发者选项 Root access 需为「Apps and ADB」 |
| **内置无线调试** | Android 11+，手动打开「无线调试」 | 较稳定 | 不想装 Shizuku 的用户 | 重启后自动关闭，端口会变，需要重新配对 |

三种方式互斥，切换后需要重新「部署并启用」。

## 下载

### 稳定版（只推荐这三个）

| 版本 | 下载 | 说明 |
| --- | --- | --- |
| **1.1** | [watch-unlock-1.1.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v1.1/watch-unlock-1.1.apk) | 早期稳定版；与 3.3 及之后使用同一正式签名 |
| **3.3.3** | [watch-unlock-3.3.3-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.3.3/watch-unlock-3.3.3-official.apk) | 基础功能稳定版 |
| **6.3.6** | [watch-unlock-6.3.6-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v6.3.6/watch-unlock-6.3.6-signed.apk) | **当前推荐稳定版**，修复 MAC 解析与配对通知问题 |

### 6.4.3 测试版

| 版本 | 下载 | 说明 |
| --- | --- | --- |
| 6.4.3 | `watch-unlock-6.4.3-signed.apk`（签名后上传） | **修复无线调试**（配对发现 / 连接端口 / 大按钮发送）+ Root 稳定性修复 + 网页版 ADB 两台手机流程；测试版，不承诺稳定 |

### 全部已签名版本（历史 / 测试）

| 版本 | 下载 | 签名 | 定位 |
| --- | --- | --- | --- |
| 3.0 | [watch-unlock-3.0-test.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.0-test/watch-unlock-3.0-test.apk) | 测试密钥 | 测试 |
| 3.3 | [watch-unlock-3.3-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.3/watch-unlock-3.3-official.apk) | 正式 | 历史 |
| 3.6 | [watch-unlock-3.6-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.6/watch-unlock-3.6-official.apk) | 正式 | 历史 |
| 3.8 | [watch-unlock-3.8-official.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.8/watch-unlock-3.8-official.apk) | 正式 | 历史 |
| 3.8.6 | [watch-unlock-3.8.6-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.8.6/watch-unlock-3.8.6-signed.apk) | 正式 | 历史 |
| 3.8.8 | [watch-unlock-3.8.8-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.8.8/watch-unlock-3.8.8-signed.apk) | 正式 | 历史 |
| 3.9 | [watch-unlock-3.9-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v3.9/watch-unlock-3.9-signed.apk) | 正式 | 历史 |
| 6.3.3 | [watch-unlock-6.3.3-signed.apk](https://github.com/cynhy/smart-unlock-ynhy/releases/download/v6.3.3/watch-unlock-6.3.3-signed.apk) | 正式 | 历史 |
| 3.3.3 / 6.3.6 / 1.1 | 见上方稳定版 | 正式 | 稳定 |

> **签名说明**：除 3.0-test 使用独立测试密钥外，1.1 与 3.3 及之后的全部已签名版本均使用同一正式证书（SHA-256 `18c39f81f5ba24f82d9553ce469b45c1b44d5c93aef21c71c634a2659d681008`），可以互相覆盖升级（系统仍会阻止版本号降级）。3.0-test 与正式包**不能互相覆盖**，切换前必须先卸载（应用内设置会丢失）。

## 快速开始

1. **安装**：优先选 6.3.6 稳定版；需要无线调试、Root 修复或网页版 ADB 请装 6.4.3。
2. **授权**：设置 → 授权方式 → Shizuku（推荐）/ Root / 无线调试，按页面提示完成授权。
3. **配对设备**：先让手表 / 耳机在系统蓝牙里配对，回到应用点「重新扫描」，勾选设备。
4. **填 PIN**：输入与系统锁屏一致的 4～16 位数字。
5. **部署并启用**：回主页点「部署并启用」，锁屏后亮屏测试。

更完整的步骤、可选项与故障排查见 **[使用说明](docs/使用说明.md)**（文档可能仍标注 6.3.9，无线调试以 6.4.3 为准）。

## 网页版 ADB（免回环端口）

当本机回环 ADB 被系统限制、无线调试无法配对时，可以用网页版 ADB 走 USB：

**主机（打开网页的一端）**

- Windows / macOS / Linux / Android 平板或另一台手机，**iOS 除外**；
- Chrome / Edge / Brave / Opera 等 Chromium 内核且支持 WebUSB 的浏览器；
- Firefox 不支持 WebUSB；iOS 浏览器也不支持。

**目标端**

- 安卓设备，开发者选项打开「USB 调试」；
- 首次连接时在弹窗里允许 RSA 授权。

**步骤**

1. 用数据线连接（电脑直连；手机主机用 OTG；两台手机用 C-to-C 数据线）；
2. 在主机 Chrome / Edge 打开 <https://czjam.github.io/gjgjxweb/>（首次加载需联网）；
3. 点「连接」→ 选择 USB 设备 → 在目标端允许 USB 调试；
4. 在网页的 ADB 终端执行命令。

详见 [网页版 ADB 使用说明](docs/网页版ADB-使用说明.md)。

## 适用与限制

| 项目 | 要求 |
| --- | --- |
| 系统 | Android 8.0+ |
| 锁屏 | 4～16 位纯数字 PIN |
| 无线调试 | Android 11+（系统「无线调试」起始版本） |
| 网页版 ADB | 主机非 iOS + Chromium 浏览器；目标端 Android + USB 调试 |
| 权限 | 通知、附近设备（Android 12+）；无线调试需网络权限 |
| 建议 | 电池策略「无限制」、允许自启动 / 后台弹出界面 |

不支持：图案 / 混合密码、iOS 作为网页版主机、设备离开自动锁屏、远程解锁。

## 工作原理

```text
屏幕亮起 / USER_PRESENT
        │
        ├─ 读取 wu.cfg（设备、WiFi、冷却、暂停、延长解锁）
        ├─ 蓝牙扫描：勾选设备是否在线（RSSI / 多设备 / 增强防护）
        ├─ Wi-Fi 判定：可选必要条件 / BSSID 绑定
        ├─ 锁屏状态检查：keyguard / SIM PIN / 已有解锁进程
        └─ 满足条件 → input PIN → 复核是否已解锁 → 冷却
```

守护脚本 `wu-daemon.sh` 由三档授权之一执行；应用只负责配置、部署、状态展示与看门狗。

## 构建

源码在 [`app/`](app)。没有 Gradle 依赖，使用 `aapt2 + javac + d8 + zipalign` 直接构建。

```powershell
Remove-Item Env:SMART_KS_PASS -ErrorAction SilentlyContinue   # 不设口令 = 只出未签名包
$env:WU_VC=643
$env:WU_VN='6.4.3'
powershell -NoProfile -ExecutionPolicy Bypass -File app\build.ps1
```

- 输出：`app\build\aligned.apk`（未签名）；
- 设置 `SMART_KS_PASS`（正式签名口令）后输出 `watch-unlock-6.4.3-signed.apk`（可用 `WU_APK` 改名）；
- 源码放在中文路径下也能构建：脚本会自动镜像到 `%TEMP%\watch-unlock-build`，产物复制回 `app\build`；
- 依赖 jar 不随仓库提交，请按 [`libs/README.md`](libs/README.md) 自行放入 `app/libs`。

签名：

```powershell
apksigner sign --ks <你的密钥库> --ks-pass pass:<口令> `
  --out watch-unlock-6.4.3-signed.apk app\build\aligned.apk
```

## 目录

```text
app/                       Android 源码（Java + 手写布局）
  assets/wu-daemon.sh      守护脚本（设备端执行）
  java/com/watchunlock/app 应用代码
docs/                     使用说明、网页版 ADB、历史教程
releases/                 各版本发布说明
libs/                     依赖与内置 adb 的获取说明（jar 不入库）
app/jniLibs/              内置 adb 可执行文件（libadb.so）
app/assets/adb-libs.zip   内置 adb 的依赖库
```

## 常见问题

| 问题 | 处理 |
| --- | --- |
| 亮屏没反应 | 看日志 `skip` 原因；确认守护运行、至少勾选一个设备 |
| 设备一直判离线 | 用 ADB 控制台执行 `sh /data/local/tmp/watch-unlock/wu-daemon.sh --check`；确认设备已配对、名称未变 |
| 改设置不生效 | 6.3 起热重载；若提示「未写入守护」，点「部署并启用」 |
| 重启后失效 | 守护随重启消失；重启 Shizuku 或重新配对无线调试后部署 |
| 小米 / 澎湃通知没有输入框 | 通知样式切换为「原生样式」 |
| 无线调试配对失败 | 保持系统配对弹窗打开；下拉通知点「输入配对码」，或分屏后在「手动配对」里填配对端口 + 6 位码；不要关闭弹窗 |
| Root 刚点允许仍不可用 | 6.3.7 起首次等 15 秒；再点「检测 Root 授权」；确认 Root access 为「Apps and ADB」 |
| 网页版 ADB 连不上 | 换 Chrome / Edge；换数据线 / OTG；目标端确认 USB 调试与 RSA；iOS / Firefox 不行 |
| 延长解锁后指纹失效 | 预期行为，恢复密码后重新录入 |
| 连续失败后停用 | 多半是系统 PIN 变了，改成新 PIN 后重新部署 |

更多见 [使用说明 §7](docs/使用说明.md#7-常见问题)。

## 安全与隐私

- PIN 以明文保存在 shell 私有目录（权限 600），这是锁屏注入的必要代价；
- Shizuku / Root / 无线调试都会给应用 shell 能力，请只安装可信来源的 APK；
- 延长解锁会临时移除锁屏密码，设备丢失时风险很高；
- 应用不联网、不上传数据；无线调试的 mDNS 只在局域网内发现本机端口；
- 网页版 ADB 是外部网页，首次加载需联网；打开后由浏览器直连 USB 设备，数据不经过本应用。请从官方地址进入，网页内容与数据处理由晨钟酱的项目负责；
- 日志包含设备名、MAC、SSID，公开反馈前请打码。

## 开源声明

本项目以 **BSD-3-Clause** 发布，见 [LICENSE](LICENSE)。使用的第三方组件、许可证与版权声明见 **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**，主要包括：

- Shizuku / Shizuku API（Apache-2.0）——授权与 shell 执行；
- Android Debug Bridge / adb（AOSP，Apache-2.0）——内置无线调试客户端；
- Termux android-tools 37.0.0p1 构建（Apache-2.0）——adb 二进制来源；
- Abseil、Protobuf、Brotli、fmt、LZ4、PCRE2（Apache-2.0），zstd、zlib（BSD 式许可）——adb 依赖库；
- **《搞机工具箱》Web 版 6.3.9**（晨钟酱 / CzJam，**GPL-3.0**）——网页版 ADB 链接的外部页面，**仅链接，未打包、未修改**。

特别感谢晨钟酱公开的《搞机工具箱》Web 版，为本项目提供了免回环 ADB 的备用思路与现成页面。

## 致谢

- Shizuku / 香草（RikkaApps）
- AOSP adb、Termux android-tools，以及 LADB 等无线调试社区实现
- 晨钟酱（CzJam）——《搞机工具箱》Web 版
- 所有提交日志、Issue 和测试反馈的朋友

## 免责声明

本项目仅供学习与个人设备使用。解锁脚本、延长解锁、Root / Shizuku / ADB 授权均涉及系统安全边界，使用前请备份数据并自行评估风险。因误操作、ROM 差异、授权被回收或设备丢失造成的损失，作者不承担责任。请遵守当地法律法规，不要用于未经授权的设备。

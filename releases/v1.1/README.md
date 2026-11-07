# Smart Unlock 1.1 · 亮屏自动解锁（稳定版）

[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](../../LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)
![Authorize](https://img.shields.io/badge/Authorize-Shizuku-informational)

连着手表或耳机时，手机一亮屏就自动输入锁屏 PIN 并解除锁屏。不需要 root、不需要电脑常驻。

本目录是 **1.1 版本**的说明与发布信息。完整项目介绍、原理与构建方式见仓库根目录的 [README](../../README.md)。

> **推荐使用 3.3.3**：功能完整（包含 1.1 全部能力）且已修复部署缓存问题。1.1 仅适合只需要基础蓝牙/Wi-Fi 解锁、或环境不满足 3.x 的情况。

## 适用平台

- **Android 8.0 ～ 16**（minSdk 26）
- 实测：Android 12、Android 13、Android 16（HyperOS 3）
- 授权方式：**仅 Shizuku**（免 root）

## 功能

- 亮屏自动解锁，实测 550～1070ms
- 任意已配对蓝牙设备（耳机 / 手表 / 手环 / 音箱 / 键盘），逐设备勾选
- 多设备判定：任一在线（默认）或全部在线
- Wi-Fi 独立解锁；Wi-Fi 亦可作为必要条件
- SIM 卡 PIN 防呆
- 临时暂停 15 分钟 / 1 小时 / 4 小时
- 三级设置页、独立日志页（2.5 秒自动刷新）、一键清理
- 中英双语

## 稳定性说明

1.1 只有 Shizuku 一条授权路径，逻辑简单，长期运行未发现解锁失败、误触发或守护进程掉线的问题。

已知限制：

- 判断的是「蓝牙是否保持连接」，而非「设备是否佩戴」。
- 仅支持数字 PIN 锁屏，不支持图案与混合密码。
- Wi-Fi 只能添加当前已连接的网络，不支持手动输入 SSID。
- Shizuku 被系统回收后需要重新激活。

## 安装

1. 从 [Releases](../../../releases) 下载 `1.1` 的 APK。
2. 安装后授予通知与「附近的设备」权限。
3. 安装并启动 [Shizuku](https://shizuku.rikka.app/)，在本应用设置页点「请求授权」。
4. 在主页添加目标蓝牙设备或当前 Wi-Fi，点「部署并启用」。

## 升级提示

- 2.0 是调试签名、3.0 测试版是另一把测试密钥，均**不能**与 1.1 互相覆盖。
- 1.1 与 3.3 及之后的正式版签名一致（SHA-256 `18c39f81...d681008`），可直接覆盖升级。
- 只有跨签名升级才需先卸载；卸载会清空勾选设备与设置。
- 建议把本应用与 Shizuku 加入电池白名单。

## 构建

见根目录 [README · 构建](../../README.md#构建)，或直接使用 `app/build.ps1`。

## 许可证

[BSD 3-Clause](../../LICENSE) © 2026 ynhyttmy

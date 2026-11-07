# v6.3.7（测试版）

> **状态：测试版。** 稳定版仍为 **1.1、3.3.3、6.3.6**。6.3.7 需要自行签名后安装；未签名包不会发布。

## 下载

| 文件 | 说明 |
| --- | --- |
| `watch-unlock-6.3.7-signed.apk` | 正式签名包（用户签名后上传） |
| `watch-unlock-6.3.7-unsigned.apk` | **不发布**；只用于本地签名 |

- versionCode：637
- versionName：6.3.7
- minSdk：26（Android 8.0）
- targetSdk：34

## 本版重点

### Root 稳定性

- 常驻 `su` 交互 shell，不再每次操作重新拉起 su；
- 每条命令写入唯一脚本文件，修掉并发覆盖；
- 首次授权等待 15 秒，失败缓存 20 秒，成功缓存 5 分钟；
- 守护用 `setsid` 脱离 `su` 会话；
- Root 状态检测移出 UI 线程；
- 设置页新增「检测 Root 授权」。

### 网页版 ADB（备用通道）

- 入口：无线调试页最下方；
- 不依赖本机回环 ADB，WebUSB 直连安卓设备；
- 主机：除 iOS 外的设备 + Chrome/Edge 等 Chromium 浏览器；
- 被调试端：Android + USB 调试；
- 网页归属：晨钟酱（CzJam），版本 6.3.9，《搞机工具箱》Web 版，GPL-3.0；
- **仅链接，未打包、未修改其代码**。

### 构建

- `build.ps1` 支持中文路径：自动镜像到 `%TEMP%\watch-unlock-build`，产物复制回 `app/build`。

## 升级须知

- 6.3.7 与 6.3.6 使用同一正式签名，可直接覆盖安装；
- 安装后请打开应用点一次「部署并启用」（守护脚本随 APK 更新）；
- 1.1 与正式包同一签名，可直接覆盖升级；从 3.0-test（测试密钥）或 2.0（调试签名）升级需要先卸载。

## 已知问题

- 网页版 ADB 首次加载必须联网；iOS / Firefox 不能作为主机；
- Root 稳定性仍取决于具体 root 实现；若异常请附 `wu.log` 中的 `su` / `timing` 行；
- 3.3.3 及以前的旧版说明中的部分行为已过时，以 `docs/使用说明-6.3.7.md` 为准。

## 校验

签名后请用以下命令确认：

```powershell
apksigner verify --print-certs watch-unlock-6.3.7-signed.apk
```

SHA256 将在签名包上传后补充到 Release 说明。

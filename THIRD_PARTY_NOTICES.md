# 第三方组件与许可

本项目在运行时使用以下第三方组件。各组件版权归其作者所有，遵循各自许可证。

## Shizuku API

- 项目：https://github.com/RikkaApps/Shizuku-API
- 用途：申请以 shell(uid 2000) 身份执行命令
- 许可证：Apache License 2.0

## Android Debug Bridge / adb

- 项目：https://android.googlesource.com/platform/packages/modules/adb
- 用途：应用内无线调试客户端（`adb pair` / `adb connect` / `adb shell` / `adb push`）
- 许可证：Apache License 2.0

## Termux android-tools（adb 二进制构建来源）

- 项目：https://github.com/termux/termux-packages（`android-tools` 包，版本 37.0.0p1-3）
- 用途：提供可运行的 aarch64 adb 可执行文件
- 许可证：Apache License 2.0
- 说明：二进制按原样打包，未修改源码；可通过 Termux 的 `.deb` 包重新获取

## adb 的依赖库

- Abseil、Protobuf、Brotli、fmt、LZ4、PCRE2：Apache License 2.0
- zstd（Meta）、zlib（Jean-loup Gailly / Mark Adler）：BSD 式许可
- 说明：随 adb 二进制一起打包在 `app/assets/adb-libs.zip`，按原样分发

## 《搞机工具箱》Web 版 6.3.9

- 作者：晨钟酱（CzJam）
- 项目：https://github.com/CzJam/gjgjxweb
- 在线地址：https://czjam.github.io/gjgjxweb/
- 用途：本应用「网页版 ADB」入口链接的外部网页（WebUSB 直连安卓设备）
- 许可证：**GNU General Public License v3.0（GPL-3.0）**
- 说明：**仅提供链接，未打包、未修改其代码**。本应用不是该网页的衍生作品，也不与其作者存在隶属关系；网页的版权、内容与数据安全由该项目自行负责。
- 如再分发该网页或其修改版，须遵循 GPL-3.0：提供对应源码、保留版权与许可声明。
- GPL-3.0 全文：https://www.gnu.org/licenses/gpl-3.0.html

---

Apache License 2.0 全文见 https://www.apache.org/licenses/LICENSE-2.0  
GNU GPL v3.0 全文见 https://www.gnu.org/licenses/gpl-3.0.html

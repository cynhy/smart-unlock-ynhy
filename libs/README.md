# 编译依赖

本目录用于放置编译期所需的 jar。仓库不提交二进制依赖，首次构建前请按下面的说明自行获取，或直接运行 `scripts\fetch-libs.ps1`（需要能访问 Maven Central）。

## Shizuku API（必需）

从 [Shizuku API 的 Maven 仓库](https://github.com/RikkaApps/Shizuku-API/releases) 或 JitPack 下载 `13.1.5` 版本，得到 AAR 后解压出 `classes.jar`，按下面命名放入本目录：

| 文件 | 来源 |
| --- | --- |
| `api-13.1.5-classes.jar` | `dev.rikka.shizuku:api:13.1.5` |
| `provider-13.1.5-classes.jar` | `dev.rikka.shizuku:provider:13.1.5` |
| `aidl-13.1.5-classes.jar` | `dev.rikka.shizuku:aidl:13.1.5` |
| `shared-13.1.5-classes.jar` | `dev.rikka.shizuku:shared:13.1.5` |

## 内置无线调试（adb 二进制，6.3.7 起）

内置无线调试不再依赖 jar，改为打包官方 adb 客户端：

| 文件 | 内容 |
| --- | --- |
| `app/jniLibs/arm64-v8a/libadb.so` | adb 可执行文件，来自 Termux `android-tools_37.0.0p1-3_aarch64.deb` 的 `data/data/com.termux/files/usr/bin/adb` |
| `app/assets/adb-libs.zip` | adb 的依赖库（Abseil、Protobuf、Brotli、fmt、libc++、LZ4、PCRE2、zstd、zlib 等），**文件名必须保持 soname**，例如 `libz.so.1` |

获取方式：从 Termux 仓库（<https://packages.termux.dev/apt/termux-main/pool/main/a/android-tools/>）下载 aarch64 的 `.deb` 解包；再按 `readelf -d adb` 里列出的 `NEEDED` 逐项下载对应依赖包。也可以直接从本项目已发布的源码包里取这两个文件。

注意：目前只打包 arm64-v8a（aarch64）。armeabi-v7a / x86_64 需要各自的 Termux 构建与依赖，未做适配。

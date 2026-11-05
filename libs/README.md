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

## 内置无线调试（可选，仅 2.0+ 需要）

| 文件 | 来源 |
| --- | --- |
| `kadb-classes.jar` | `com.flyfishxu:kadb-android:1.2.1`（解压 AAR 取 classes.jar） |
| `okio-jvm-3.9.1.jar` | `com.squareup.okio:okio-jvm:3.9.1` |
| `kotlin-stdlib-2.1.0-RC2.jar` | `org.jetbrains.kotlin:kotlin-stdlib:2.1.0-RC2` |
| `kotlinx-coroutines-core-jvm-1.9.0.jar` | `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0` |
| `bcprov-jdk18on-1.77.jar` | `org.bouncycastle:bcprov-jdk18on:1.77` |
| `bcpkix-jdk18on-1.77.jar` | `org.bouncycastle:bcpkix-jdk18on:1.77` |
| `bcutil-jdk18on-1.77.jar` | `org.bouncycastle:bcutil-jdk18on:1.77`（必需，缺它会在配对时报 `NoClassDefFoundError`） |
| `spake2-classes.jar` | `com.flyfishxu:spake2-android:0.0.5` |
| `hiddenapibypass-classes.jar` | `com.flyfishxu:hiddenapibypass:4.3` |
| `kadb-stub.jar` | 仅用于编译期（Kadb 是 Java 21 字节码，javac 17 无法读取），见 `scripts/make-kadb-stub.ps1` |

如果不打算使用内置无线调试，可以在 `app/build.ps1` 里加 `-NoKadb`，只编译 Shizuku/Root 两档。

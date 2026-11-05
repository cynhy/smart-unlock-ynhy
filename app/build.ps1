# 手工构建 APK（不依赖 Gradle）：aapt2 -> javac -> d8 -> zipalign -> apksigner
#
# 用法示例：
#   powershell -ExecutionPolicy Bypass -File app\build.ps1                       # 3.3，未签名
#   powershell -ExecutionPolicy Bypass -File app\build.ps1 -VersionName 3.0 -VersionCode 30 -NoKadb
#
# 前置条件：
#   $env:JAVA_HOME     指向 JDK 17
#   $env:ANDROID_HOME  指向 Android SDK（需含 build-tools >= 36 与 platform android-34）
#   依赖 jar 放在 libs\，见 libs\README.md
#
# 签名（可选）：设置环境变量 SMART_KS_PASS 为密钥库口令，并传 -Keystore 与 -KeyAlias。
# 口令只从环境变量读取，不落盘、不写入命令行历史。

param(
  [string]$VersionName = '3.3',
  [int]$VersionCode = 33,
  [string]$Keystore = '',
  [string]$KeyAlias = 'phone unlock',
  [switch]$NoKadb,
  [switch]$Unsigned
)

$ErrorActionPreference = 'Stop'

$HERE = Split-Path -Parent $MyInvocation.MyCommand.Path
$LIB = Join-Path $HERE 'libs'
$OUT = Join-Path $HERE 'build'

if (-not $env:JAVA_HOME) { throw '请先设置 JAVA_HOME（JDK 17）' }
if (-not $env:ANDROID_HOME) { throw '请先设置 ANDROID_HOME（Android SDK 根目录）' }

$JDK = $env:JAVA_HOME
$SDK = $env:ANDROID_HOME

# 选版本号最大的 build-tools；Kadb 是 Java 21 字节码，d8 必须 >= 36 才能处理
$btDir = Get-ChildItem (Join-Path $SDK 'build-tools') -Directory |
  Sort-Object { [version](($_.Name -split '-')[0]) } -Descending | Select-Object -First 1
$BT = $btDir.FullName
if ([version](($btDir.Name -split '-')[0]) -lt [version]'36.0.0') {
  Write-Warning "当前 build-tools 为 $($btDir.Name)，处理 Kadb 需要 >= 36"
}

$AJAR = Join-Path $SDK 'platforms\android-34\android.jar'
if (-not (Test-Path $AJAR)) {
  $p = Get-ChildItem (Join-Path $SDK 'platforms') -Directory |
    Sort-Object { [version]($_.Name -replace 'android-', '') } -Descending | Select-Object -First 1
  $AJAR = Join-Path $p.FullName 'android.jar'
}

if (Test-Path $OUT) {
  try { Remove-Item $OUT -Recurse -Force -ErrorAction Stop }
  catch {
    # 目录被占用（杀毒/残留进程）时改名让路，下次再清
    Rename-Item $OUT ("build_old_" + (Get-Date -Format 'HHmmss')) -ErrorAction SilentlyContinue
  }
}
New-Item -ItemType Directory -Force -Path $OUT, "$OUT\gen", "$OUT\classes", "$OUT\dex" | Out-Null

# --- 依赖 ---
$LIBS = @('api-13.1.5', 'provider-13.1.5', 'aidl-13.1.5', 'shared-13.1.5') |
  ForEach-Object { Join-Path $LIB "$_-classes.jar" }

$KADB = @()
if (-not $NoKadb) {
  $KADB = @('kadb-classes.jar', 'okio-jvm-3.9.1.jar', 'kotlin-stdlib-2.1.0-RC2.jar',
    'kotlinx-coroutines-core-jvm-1.9.0.jar', 'bcprov-jdk18on-1.77.jar',
    'bcpkix-jdk18on-1.77.jar', 'bcutil-jdk18on-1.77.jar',
    'spake2-classes.jar', 'hiddenapibypass-classes.jar') |
    ForEach-Object { Join-Path $LIB $_ }
}

$LIBS = $LIBS + $KADB
foreach ($j in $LIBS) { if (-not (Test-Path $j)) { throw "缺少依赖 $j（见 libs/README.md）" } }

Write-Host '>>> aapt2 compile'
& "$BT\aapt2.exe" compile --dir "$HERE\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

Write-Host '>>> aapt2 link'
& "$BT\aapt2.exe" link -o "$OUT\base.apk" -I $AJAR --manifest "$HERE\AndroidManifest.xml" `
  -R "$OUT\res.zip" --java "$OUT\gen" --auto-add-overlay `
  --min-sdk-version 26 --target-sdk-version 34 `
  --version-code $VersionCode --version-name $VersionName
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '>>> javac'
$srcs = @(Get-ChildItem "$HERE\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem "$OUT\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })

# Kadb 的 jar 是 Java 21 字节码，javac 17 无法读取；编译期用 stub 替换，d8 阶段仍用真实 jar
$CP = $LIBS | Where-Object { $_ -notlike '*kadb-classes.jar' }
$stub = Join-Path $LIB 'kadb-stub.jar'
if (-not $NoKadb) {
  if (-not (Test-Path $stub)) { throw "缺少编译期 stub：$stub（见 libs/README.md）" }
  $CP = $CP + $stub
}
$cp = ($CP -join ';') + ';' + $AJAR

& (Join-Path $JDK 'bin\javac.exe') -encoding UTF-8 --release 11 -nowarn `
  -classpath $cp -d "$OUT\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '>>> jar + d8'
& (Join-Path $JDK 'bin\jar.exe') cf "$OUT\app-classes.jar" -C "$OUT\classes" .
& "$BT\d8.bat" --release --min-api 26 --lib $AJAR --output "$OUT\dex" "$OUT\app-classes.jar" $LIBS
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '>>> 打包 assets + dex'
Copy-Item "$OUT\base.apk" "$OUT\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\unsigned.apk", 'Update')
$entries = @{}
foreach ($dx in (Get-ChildItem "$OUT\dex" -Filter *.dex)) { $entries[$dx.Name] = $dx.FullName }
foreach ($e in (Get-ChildItem "$HERE\assets" -File)) { $entries["assets/$($e.Name)"] = $e.FullName }
foreach ($name in $entries.Keys) {
  $old = $zip.Entries | Where-Object { $_.FullName -eq $name }
  if ($old) { $old.Delete() }
  $entry = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
  $s = $entry.Open(); $fs = [System.IO.File]::OpenRead($entries[$name])
  $fs.CopyTo($s); $fs.Close(); $s.Close()
}
$zip.Dispose()

Write-Host '>>> zipalign'
& "$BT\zipalign.exe" -p -f 4 "$OUT\unsigned.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

$APKNAME = "watch-unlock-$VersionName.apk"
if ($Unsigned -or -not $env:SMART_KS_PASS -or -not $Keystore) {
  Write-Host '未提供签名参数（-Keystore / SMART_KS_PASS），输出未签名包：'
  Copy-Item "$OUT\aligned.apk" "$OUT\$APKNAME" -Force
  Write-Host "$OUT\$APKNAME"
  exit 0
}

if (-not (Test-Path -LiteralPath $Keystore)) { throw "缺少密钥库：$Keystore" }

Write-Host '>>> 签名'
& "$BT\apksigner.bat" sign --ks $Keystore --ks-pass 'env:SMART_KS_PASS' `
  --key-pass 'env:SMART_KS_PASS' --ks-key-alias $KeyAlias `
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
  --out "$OUT\$APKNAME" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败（检查口令与别名）' }

& "$BT\apksigner.bat" verify -v --print-certs "$OUT\$APKNAME" |
  Select-String -Pattern 'Verified using|Signer #1 certificate DN|SHA-256 digest'

Write-Host ''
Write-Host "完成: $OUT\$APKNAME"

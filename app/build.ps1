# 手工构建 APK（无 Gradle）：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 用法：powershell -ExecutionPolicy Bypass -File app\build.ps1
# 依赖：Shizuku api/provider；内置无线调试改用打包的官方 adb 二进制
# （jniLibs\arm64-v8a\libadb.so + assets\adb-libs.zip，见 LocalAdb.java）
$ErrorActionPreference = 'Stop'

$SDK = 'D:\shengchanli\sdk'
# 使用 build-tools 36（d8 需要能处理高版本字节码）
$BT = Join-Path $SDK 'build-tools\36.0.0'
$AJAR = Join-Path $SDK 'platforms\android-34\android.jar'
$JDK = 'D:\shengchanli\jdk17.0.21_0'
$env:JAVA_HOME = $JDK

$HERE = Split-Path -Parent $MyInvocation.MyCommand.Path
# aapt2 36 在含中文的路径下会报 “failed to open directory (2)”，
# 自动把源码镜像到 ASCII 暂存目录构建，产物再复制回原目录，避免路径问题。
$ORIG_OUT = Join-Path $HERE 'build'
$ORIG_HERE = $HERE
if ($HERE -match '[^\x00-\x7F]') {
  $STAGE = Join-Path $env:TEMP 'watch-unlock-build'
  if (Test-Path $STAGE) {
    try { Remove-Item $STAGE -Recurse -Force -ErrorAction Stop } catch {
      Rename-Item $STAGE ("watch-unlock-build_" + (Get-Date -Format 'HHmmss')) -ErrorAction SilentlyContinue
    }
  }
  New-Item -ItemType Directory -Force -Path $STAGE | Out-Null
  Copy-Item "$HERE\res" $STAGE -Recurse -Force
  Copy-Item "$HERE\assets" $STAGE -Recurse -Force
  if (Test-Path "$HERE\jniLibs") { Copy-Item "$HERE\jniLibs" $STAGE -Recurse -Force }
  Copy-Item "$HERE\java" $STAGE -Recurse -Force
  Copy-Item "$HERE\AndroidManifest.xml" $STAGE -Force
  $HERE = $STAGE
  Write-Host ">>> 路径含中文，已镜像到 $STAGE"
}
$LIB = Join-Path $ORIG_HERE 'libs'
$OUT = Join-Path $HERE 'build'
# 版本可通过环境变量覆盖：WU_VC=386 WU_VN=3.8.6 WU_APK=...
$VC = if ($env:WU_VC) { [int]$env:WU_VC } else { 639 }
$VN = if ($env:WU_VN) { $env:WU_VN } else { '6.3.9' }
$APKNAME = if ($env:WU_APK) { $env:WU_APK } else { "watch-unlock-$VN-signed.apk" }

if (Test-Path $OUT) {
  try { Remove-Item $OUT -Recurse -Force -ErrorAction Stop }
  catch {
    # 目录被占用（杀毒/残留进程）时改名让路，下次再清
    Rename-Item $OUT ("build_old_" + (Get-Date -Format 'HHmmss')) -ErrorAction SilentlyContinue
  }
}
New-Item -ItemType Directory -Force -Path $OUT, "$OUT\gen", "$OUT\classes", "$OUT\dex" | Out-Null

# 依赖：Shizuku api/provider 及其 transitive 依赖
$LIBS = @('api-13.1.5', 'provider-13.1.5', 'aidl-13.1.5', 'shared-13.1.5') | ForEach-Object { Join-Path $LIB "$_-classes.jar" }
foreach ($j in $LIBS) { if (-not (Test-Path $j)) { throw "缺少依赖 $j（见 build.ps1 顶部说明）" } }

Write-Host '>>> aapt2 compile'
& "$BT\aapt2.exe" compile --dir "$HERE\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

Write-Host '>>> aapt2 link'
# aapt2 的 --version-code/--version-name 在部分版本不会覆盖清单里的值，
# 这里生成一份临时清单写入目标版本，保证产物版本号一定正确
$MANIFEST = Join-Path $OUT 'AndroidManifest.xml'
$mf = (Get-Content "$HERE\AndroidManifest.xml" -Raw)
$mf = $mf -replace 'android:versionCode="[0-9]+"', "android:versionCode=`"$VC`""
$mf = $mf -replace 'android:versionName="[^"]*"', "android:versionName=`"$VN`""
[System.IO.File]::WriteAllText($MANIFEST, $mf, (New-Object System.Text.UTF8Encoding($false)))
& "$BT\aapt2.exe" link -o "$OUT\base.apk" -I $AJAR --manifest $MANIFEST `
  -R "$OUT\res.zip" --java "$OUT\gen" --auto-add-overlay `
  --min-sdk-version 26 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '>>> javac'
$srcs = @(Get-ChildItem "$HERE\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem "$OUT\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$cp = ($LIBS -join ';') + ';' + $AJAR
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
# 内置 adb 可执行文件必须落在 lib/<abi>/ 下，安装时才会解压到 nativeLibraryDir
$ADB_SO = Join-Path $HERE 'jniLibs\arm64-v8a\libadb.so'
if (Test-Path $ADB_SO) { $entries['lib/arm64-v8a/libadb.so'] = $ADB_SO }
foreach ($name in $entries.Keys) {
  $old = $zip.Entries | Where-Object { $_.FullName -eq $name }
  if ($old) { $old.Delete() }
  $level = if ($name.EndsWith('.so')) { [System.IO.Compression.CompressionLevel]::NoCompression }
           else { [System.IO.Compression.CompressionLevel]::Optimal }
  $entry = $zip.CreateEntry($name, $level)
  $s = $entry.Open(); $fs = [System.IO.File]::OpenRead($entries[$name])
  $fs.CopyTo($s); $fs.Close(); $s.Close()
}
$zip.Dispose()

Write-Host '>>> zipalign'
& "$BT\zipalign.exe" -p -f 4 "$OUT\unsigned.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host '>>> 签名（正式密钥库）'
# 正式包使用 key0 密钥库；口令不落盘，通过环境变量 SMART_KS_PASS 传入
$KS = 'E:\keys\key0\smart unlock.jks'
$KS_ALIAS = 'phone unlock'
if (-not (Test-Path -LiteralPath $KS)) { throw "缺少密钥库：$KS" }
if (-not $env:SMART_KS_PASS) {
  Write-Host '未设置环境变量 SMART_KS_PASS（签名口令），仅生成未签名包：'
  if ($ORIG_HERE -ne $HERE) {
    New-Item -ItemType Directory -Force -Path $ORIG_OUT | Out-Null
    Copy-Item "$OUT\aligned.apk" (Join-Path $ORIG_OUT 'aligned.apk') -Force
    Write-Host (Join-Path $ORIG_OUT 'aligned.apk')
  } else {
    Write-Host "$OUT\aligned.apk"
  }
  exit 0
}

# v1 + v2 + v3 全部启用（v1 兼容 Android 6 及更早/部分国产 ROM 的校验路径）
& "$BT\apksigner.bat" sign --ks $KS --ks-pass 'env:SMART_KS_PASS' `
  --key-pass 'env:SMART_KS_PASS' --ks-key-alias $KS_ALIAS `
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
  --out "$OUT\$APKNAME" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败（检查口令与别名）' }
& "$BT\apksigner.bat" verify -v --print-certs "$OUT\$APKNAME" | Select-String -Pattern 'Verified using|Signer #1 certificate DN|SHA-256 digest'

Write-Host ''
if ($ORIG_HERE -ne $HERE) {
  New-Item -ItemType Directory -Force -Path $ORIG_OUT | Out-Null
  Copy-Item "$OUT\$APKNAME" (Join-Path $ORIG_OUT $APKNAME) -Force
  Write-Host "完成: $(Join-Path $ORIG_OUT $APKNAME)"
} else {
  Write-Host "完成: $OUT\$APKNAME"
}

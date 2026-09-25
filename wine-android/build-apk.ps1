# 端到端构建 APK 脚本
# 用法：powershell -ExecutionPolicy Bypass -File build-apk.ps1

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

Write-Host "=== 1. 解压 Gradle 8.5 ==="
$gradleZip = "D:\Android\gradle-8.5-bin.zip"
$gradleHome = "D:\Android\gradle-8.5"
if (-not (Test-Path "$gradleHome\bin\gradle.bat")) {
    if (Test-Path $gradleHome) { Remove-Item $gradleHome -Recurse -Force }
    Expand-Archive -Path $gradleZip -DestinationPath "D:\Android\" -Force
    Write-Host "Gradle extracted to $gradleHome"
} else {
    Write-Host "Gradle already extracted"
}
if (-not (Test-Path "$gradleHome\bin\gradle.bat")) { throw "Gradle extraction failed" }

Write-Host "=== 2. 配置环境变量 ==="
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "D:\Programs\Java\jdk-21"
$env:Path = "$gradleHome\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"
Write-Host "JAVA_HOME    = $env:JAVA_HOME"
Write-Host "Gradle       = $(Get-Command gradle -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)"

Write-Host "=== 3. 验证 SDK 组件 ==="
foreach ($p in @(
    "$env:ANDROID_HOME\platforms\android-34",
    "$env:ANDROID_HOME\build-tools\34.0.0",
    "$env:ANDROID_HOME\platform-tools\adb.exe"
)) {
    if (Test-Path $p) { Write-Host "  OK: $p" } else { Write-Host "  MISSING: $p" -ForegroundColor Red }
}

Write-Host "=== 4. 写入 local.properties ==="
$localProps = "sdk.dir=D:\\Android\\Sdk"
Set-Content -Path "g:\WorkSpace\WineProject\wine-android\local.properties" -Value $localProps -Encoding ASCII
Write-Host "  -> local.properties written"

Write-Host "=== 5. 生成 Gradle Wrapper ==="
Push-Location "g:\WorkSpace\WineProject\wine-android"
try {
    if (-not (Test-Path "gradlew.bat")) {
        & gradle wrapper --gradle-version 8.5 --distribution-type all --no-daemon 2>&1 | Select-Object -Last 5
        Write-Host "Wrapper generated"
    } else {
        Write-Host "Wrapper already exists"
    }
} finally {
    Pop-Location
}

Write-Host "=== 6. 执行 assembleDebug 构建 ==="
Push-Location "g:\WorkSpace\WineProject\wine-android"
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & gradle assembleDebug --no-daemon --console=plain 2>&1 | Tee-Object -FilePath "build-output.log"
    $buildExit = $LASTEXITCODE
    Write-Host "Build exit code: $buildExit"
} finally {
    $ErrorActionPreference = $prevEAP
    Pop-Location
}

Write-Host "=== 7. 检查 APK 产物 ==="
$apkPath = "g:\WorkSpace\WineProject\wine-android\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    $apkSize = [math]::Round((Get-Item $apkPath).Length/1MB, 2)
    Write-Host "  APK OK: $apkPath ($apkSize MB)"
} else {
    Write-Host "  APK NOT FOUND at $apkPath"
}

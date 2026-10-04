# 在本机（受限沙箱）环境中构建本插件的辅助脚本。
#
# 该环境有若干限制，普通 Gradle 构建会失败，脚本把它们一次性处理掉：
#   1. Java 信任库无法验证 services.gradle.org，故 wrapper 使用本地 Gradle 发行包；
#   2. 沙箱不允许 Java 写系统临时目录，故把 TMP/TEMP 指到工作区内；
#   3. 沙箱不允许写 C:\Users\<user>，故 GRADLE_USER_HOME 与 ANDROID_USER_HOME 也放在工作区；
#   4. sdkmanager 不可用，SDK 由 tools/fetch_android_sdk.py 直接下载。
#
# 用法：
#   pwsh -File tools/build-local.ps1                  # 构建 debug 插件
#   pwsh -File tools/build-local.ps1 -Variant Release
#   pwsh -File tools/build-local.ps1 -Clean           # 先清理
[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')][string]$Variant = 'Debug',
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tools = Get-AndroidToolchainDir -PluginRoot $root

$env:JAVA_HOME = Join-Path $tools 'jdk'
$env:ANDROID_HOME = Join-Path $tools 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $tools 'gradle-home'
$env:ANDROID_USER_HOME = Join-Path $tools 'android-user-home'
$env:TMP = Join-Path $tools 'tmp'
$env:TEMP = $env:TMP
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH

foreach ($d in @($env:TMP, $env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    throw "缺少 JDK。请先运行 tools/setup-and-build.ps1，或手动放置到 $env:JAVA_HOME"
}
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms'))) {
    throw "缺少 Android SDK。请先运行: python tools/fetch_android_sdk.py --dest `"$env:ANDROID_HOME`" --api 37.2 --build-tools 37.0.0"
}

# Gradle 只读取 settings.gradle.kts 同级的 local.properties。
$sdkEscaped = $env:ANDROID_HOME.Replace('\', '\\')
Set-Content -Path (Join-Path $root 'local.properties') -Value "sdk.dir=$sdkEscaped" -Encoding ASCII

# 本机 Java 信任库验证不了 services.gradle.org 时，可改用已下载到本地的发行包。
# 这只在 zip 存在时才生效；gradle-wrapper.properties 保持官方地址不变，
# 因此不会影响其他环境的构建。
$localZip = Join-Path $tools 'downloads\gradle-9.6.1-bin.zip'
if (Test-Path $localZip) {
    $uri = 'file\:///' + ($localZip.Replace('\', '/'))
    $propsPath = Join-Path $root 'gradle\wrapper\gradle-wrapper.properties'
    $props = Get-Content $propsPath -Raw
    $patched = [regex]::Replace($props, '(?m)^distributionUrl=.*$', "distributionUrl=$uri")
    if ($patched -ne $props) {
        Set-Content -Path $propsPath -Value $patched -NoNewline -Encoding ASCII
        Write-Host "已把 wrapper 指向本地 Gradle 发行包（仅本次本机构建使用）"
    }
}

$tasks = @()
if ($Clean) { $tasks += 'clean' }
$tasks += ":plugin:assemble$Variant"

Write-Host "JAVA_HOME        = $env:JAVA_HOME"
Write-Host "ANDROID_HOME     = $env:ANDROID_HOME"
Write-Host "GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
Write-Host "TMP              = $env:TMP"
Write-Host ""

Push-Location $root
try {
    & (Join-Path $root 'gradlew.bat') '--no-daemon' '-Dorg.gradle.workers.max=1' @tasks
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) { throw "Gradle 构建失败，退出码 $code" }

$outDir = Join-Path $root "plugin\build\outputs\apk\$($Variant.ToLower())"
if (Test-Path $outDir) {
    Write-Host ""
    Write-Host "构建产物：" -ForegroundColor Green
    Get-ChildItem -Recurse $outDir -File | ForEach-Object {
        Write-Host ("  {0}  ({1:N0} 字节)" -f $_.FullName, $_.Length) -ForegroundColor Green
    }
}

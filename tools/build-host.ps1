# 在本机构建 LightNovelReader 宿主 App。
#
# 背景：宿主 App 并不在本工作区里预先构建好，只有源码。要在本机跑插件就必须先把宿主编出来。
# 这个脚本处理与本机环境相关的全部问题：
#   1. Java 信任库验证不了 services.gradle.org → 用已下载到本地的 Gradle 发行包；
#   2. Java 默认临时/缓存目录不可写 → GRADLE_USER_HOME、TMP 全部放工作区；
#   3. 宿主自带 gradle wrapper 指向 9.5.1，这里会用本地 zip 覆盖并留备份；
#   4. **宿主用 gradle/gradle-daemon-jvm.properties 强制以 JetBrains Runtime 21 运行 daemon，
#      而 JBR 21 的 File.canWrite() 在本机对这些目录返回 false。**
#      实测（同机、同目录、同一探测程序）：Temurin 17 true、Temurin 21 true、JBR 21 false。
#      Gradle 因此在配置阶段抛出「目录不存在或不可写」，构建完全无法进行。
#      解法：用 Temurin 21 作 JAVA_HOME 并让 Gradle 把它当工具链，禁用自动下载 JBR。
#      宿主的 gradle-daemon-jvm.properties 无需改动，仓库保持干净。
#
# 用法：
#   pwsh -File tools/build-host.ps1                 # 构建 debug 宿主
#   pwsh -File tools/build-host.ps1 -Clean
#   pwsh -File tools/build-host.ps1 -RestoreWrapper # 还原 wrapper 与 daemon JVM 配置
[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$RestoreWrapper,
    [string]$Variant = 'Debug'
)

$ErrorActionPreference = 'Stop'

# 本脚本放在插件仓库的 tools/ 下，宿主源码在 ../../app
$pluginRoot = Split-Path -Parent $PSScriptRoot
$lnrRoot = Split-Path -Parent $pluginRoot
$hostRoot = Join-Path $lnrRoot 'app'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tools = Get-AndroidToolchainDir -PluginRoot $pluginRoot

if (-not (Test-Path $hostRoot)) {
    throw "找不到宿主源码目录：$hostRoot（应为 ../../app，即 LightNovelReader 仓库）"
}

$wrapperProps = Join-Path $hostRoot 'gradle\wrapper\gradle-wrapper.properties'
$wrapperBackup = "$wrapperProps.orig"
$daemonJvm = Join-Path $hostRoot 'gradle\gradle-daemon-jvm.properties'
$daemonJvmBackup = "$daemonJvm.disabled"

if ($RestoreWrapper) {
    if (Test-Path $wrapperBackup) {
        Move-Item -Force $wrapperBackup $wrapperProps
        Write-Host "已把宿主 wrapper 还原为官方地址"
    } else {
        Write-Host "没有找到 wrapper 备份，无需还原"
    }
    if (Test-Path $daemonJvmBackup) {
        Move-Item -Force $daemonJvmBackup $daemonJvm
        Write-Host "已还原 gradle-daemon-jvm.properties"
    }
    return
}

# ---------------------------------------------------------------- 环境
# 宿主用 jvmToolchain(21) + JavaVersion.VERSION_21，因此必须用 JDK 21。
#
# 但**不能用宿主自己指定的那个**：宿主的 gradle/gradle-daemon-jvm.properties 要求
# JetBrains Runtime 21，而 JBR 21 的 File.canWrite() 在本机对这些目录返回 false
# （同一台机器、同一个目录、同一个探测程序：Temurin 17 和 Temurin 21 都得 true，
#   只有 JBR 21 得 false）。Gradle 于是认为项目目录/缓存目录不可写，在配置阶段就失败。
#
# 解法是用 Temurin 21 同时满足「JDK 21」与「正确的 canWrite」两个条件：
#   - JAVA_HOME 指向 Temurin 21；
#   - 让 Gradle 把它作为工具链探测路径，并禁止自动下载 JBR。
# 这样宿主的 gradle-daemon-jvm.properties 无需改动，仓库保持干净。
$toolchainJdk = Join-Path $tools 'jdk21'
$jdk21 = Get-ChildItem $toolchainJdk -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $jdk21) {
    throw @"
缺少 JDK 21（$toolchainJdk）。
宿主需要 JDK 21，但宿主要求的 JetBrains Runtime 21 在本机无法工作（见文件头注释）。
请下载 Temurin 21 并解压到该目录：
  https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse
"@
}

$env:JAVA_HOME = $jdk21
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
    throw "缺少 JDK：$env:JAVA_HOME"
}

# Gradle 只读取 settings.gradle.kts 同级的 local.properties。
$sdkEscaped = $env:ANDROID_HOME.Replace('\', '\\')
Set-Content -Path (Join-Path $hostRoot 'local.properties') -Value "sdk.dir=$sdkEscaped" -Encoding ASCII

# ------------------------------------------------- 把 wrapper 指向本地发行包
# 宿主自带 9.5.1，与插件用的 9.6.1 不同，因此单独下载。
$hostGradle = '9.5.1'
$localZip = Join-Path $tools "downloads\gradle-$hostGradle-bin.zip"
if (-not (Test-Path $localZip)) {
    throw "缺少本地的 gradle-$hostGradle-bin.zip：$localZip`n请先下载 https://services.gradle.org/distributions/gradle-$hostGradle-bin.zip 放到该路径（PowerShell 能访问，Java 不行）。"
}

if (-not (Test-Path $wrapperBackup)) {
    Copy-Item $wrapperProps $wrapperBackup
}
$uri = 'file\:///' + ($localZip.Replace('\', '/'))
$props = Get-Content $wrapperProps -Raw
$patched = [regex]::Replace($props, '(?m)^distributionUrl=.*$', "distributionUrl=$uri")
if ($patched -ne $props) {
    Set-Content -Path $wrapperProps -Value $patched -NoNewline -Encoding ASCII
    Write-Host "已把宿主 wrapper 指向本地 gradle-$hostGradle（原文件备份为 .orig）"
}

# ---------------------------------------------------------------- 构建
Write-Host "host             = $hostRoot"
Write-Host "JAVA_HOME        = $env:JAVA_HOME"
Write-Host "ANDROID_HOME     = $env:ANDROID_HOME"
Write-Host "GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
Write-Host "TMP              = $env:TMP"
Write-Host ""

$tasks = @()
if ($Clean) { $tasks += 'clean' }
$tasks += ":app:assemble$Variant"

# 把 Temurin 21 作为工具链探测路径，并禁止 Gradle 自动下载 JBR。
# gradle-daemon-jvm.properties 保持原样，宿主的仓库不会被改脏。
$jdk21Uri = $env:JAVA_HOME.Replace('\', '/')
$gradleArgs = @(
    '--no-daemon'
    '-Dorg.gradle.workers.max=1'
    "-Dorg.gradle.java.installations.paths=$jdk21Uri"
    '-Dorg.gradle.java.installations.auto-download=false'
)

Push-Location $hostRoot
try {
    & (Join-Path $hostRoot 'gradlew.bat') @gradleArgs @tasks
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) { throw "宿主构建失败，退出码 $code" }

Write-Host ""
Write-Host "构建产物：" -ForegroundColor Green
Get-ChildItem (Join-Path $hostRoot "app\build\outputs\apk\$($Variant.ToLower())") -Recurse -File -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host ("  {0}  ({1:N0} 字节)" -f $_.FullName, $_.Length) -ForegroundColor Green }

Write-Host ""
Write-Host "提示：宿主 wrapper 已被改为本地路径，提交前请运行 -RestoreWrapper 还原。" -ForegroundColor Yellow

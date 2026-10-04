# 一键准备构建环境并编译 WenkuKeeper 插件。
#
# 本仓库不包含 JDK 与 Android SDK：它们体积很大，且许可证允许再分发但没必要随仓库走。
# 该脚本会下载便携版 JDK 17 与 Android 命令行工具到**共享工具链目录**
# （默认 <工作区>\project\vm，旧检出回退到 <插件仓库>\.build-tools），
# 安装编译所需的 SDK 组件，然后构建插件。解析逻辑见 tools/toolchain.ps1。
#
# 用法：
#   pwsh -File tools/setup-and-build.ps1                 # 构建 debug 插件
#   pwsh -File tools/setup-and-build.ps1 -Variant Release
#   pwsh -File tools/setup-and-build.ps1 -BuildOnly      # 跳过下载，直接用已有环境构建
#
# 注意：compileSdk / targetSdk 为 37，需要较新的 Android SDK 命令行工具。

[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')][string]$Variant = 'Debug',
    [string]$JdkVersion = '17.0.12',
    [string]$JdkBuild = '7',
    [switch]$BuildOnly,
    [switch]$InstallSdkPackages
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$toolsDir = Get-AndroidToolchainDir -PluginRoot $repoRoot
$jdkDir = Join-Path $toolsDir 'jdk'
$sdkDir = Join-Path $toolsDir 'android-sdk'
$dlDir = Join-Path $toolsDir 'downloads'

function Write-Step([string]$text) {
    Write-Host ""
    Write-Host "==> $text" -ForegroundColor Cyan
}

function Get-File([string]$url, [string]$outFile) {
    if (Test-Path $outFile) {
        Write-Host "   已缓存: $(Split-Path -Leaf $outFile)"
        return
    }
    Write-Host "   下载: $url"
    $partial = "$outFile.partial"
    Invoke-WebRequest -Uri $url -OutFile $partial -UseBasicParsing -TimeoutSec 900
    Move-Item -Force $partial $outFile
}

New-Item -ItemType Directory -Force -Path $toolsDir, $dlDir | Out-Null

if (-not $BuildOnly) {
    # ---------------------------------------------------------------- JDK 17
    if (-not (Test-Path (Join-Path $jdkDir 'bin\javac.exe'))) {
        Write-Step "准备 JDK $JdkVersion"
        # Adoptium Temurin 便携版：解压即用，无需管理员权限。
        $jdkUrl = "https://github.com/adoptium/temurin17-binaries/releases/download/" +
                  "jdk-$JdkVersion%2B$JdkBuild/OpenJDK17U-jdk_x64_windows_hotspot_" +
                  "${JdkVersion}_$JdkBuild.zip"
        $jdkZip = Join-Path $dlDir "temurin-jdk-$JdkVersion.zip"
        Get-File $jdkUrl $jdkZip

        $extract = Join-Path $toolsDir 'jdk-extract'
        if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
        Expand-Archive -Path $jdkZip -DestinationPath $extract -Force
        $inner = Get-ChildItem $extract -Directory | Select-Object -First 1
        if (-not $inner) { throw "JDK 压缩包结构异常：$jdkZip" }
        if (Test-Path $jdkDir) { Remove-Item -Recurse -Force $jdkDir }
        Move-Item $inner.FullName $jdkDir
        Remove-Item -Recurse -Force $extract
        Write-Host "   JDK 就绪: $jdkDir"
    } else {
        Write-Host "JDK 已就绪: $jdkDir"
    }

    $env:JAVA_HOME = $jdkDir
    $env:PATH = (Join-Path $jdkDir 'bin') + ';' + $env:PATH

    # ------------------------------------------------------------ Android SDK
    $sdkManager = Join-Path $sdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path $sdkManager)) {
        Write-Step "准备 Android 命令行工具"
        # Google 未提供固定版本号链接，这里取当前最新的命令行工具包。
        $cmdlineZip = Join-Path $dlDir 'commandlinetools-win.zip'
        Get-File 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' $cmdlineZip

        $extract = Join-Path $toolsDir 'cmdline-extract'
        if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
        Expand-Archive -Path $cmdlineZip -DestinationPath $extract -Force
        # 命令行工具要求位于 cmdline-tools/latest/ 下才能被 sdkmanager 正确识别。
        $latest = Join-Path $sdkDir 'cmdline-tools\latest'
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $latest) | Out-Null
        if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
        Move-Item (Join-Path $extract 'cmdline-tools') $latest
        Remove-Item -Recurse -Force $extract
        Write-Host "   命令行工具就绪: $latest"
    } else {
        Write-Host "Android 命令行工具已就绪"
    }

    $env:ANDROID_HOME = $sdkDir
    $env:ANDROID_SDK_ROOT = $sdkDir

    if (-not $InstallSdkPackages) {
        Write-Step "安装 SDK 组件（platform-tools / platforms;android-37 / build-tools）"
        $sdkArgs = @(
            ("--sdk_root=" + $sdkDir),
            'platform-tools',
            'platforms;android-37',
            'build-tools;36.0.0'
        )
        # sdkmanager 在首次运行时询问许可证，这里一次性接受。
        $yes = ("y`n" * 50)
        $yes | & $sdkManager @sdkArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "sdkmanager 返回 $LASTEXITCODE。若缺少 android-37，请在 Android Studio 的 SDK Manager 中安装后重试。"
        }
    }

    # 让 Gradle 找到 SDK，而不必写入用户级 local.properties。
    # 注意：必须放在 Gradle 的根项目目录（settings.gradle.kts 所在处），
    # 放在 plugin/ 子目录里 Gradle 不会读取。
    $localProps = Join-Path $repoRoot 'local.properties'
    $escaped = $sdkDir.Replace('\', '\\')
    Set-Content -Path $localProps -Value "sdk.dir=$escaped" -Encoding ASCII
    Write-Host "   已写入 $localProps"
}

if (-not $env:JAVA_HOME) {
    if (Test-Path (Join-Path $jdkDir 'bin\javac.exe')) {
        $env:JAVA_HOME = $jdkDir
        $env:PATH = (Join-Path $jdkDir 'bin') + ';' + $env:PATH
    } else {
        throw "未找到 JDK。请先不带 -BuildOnly 运行一次，或自行设置 JAVA_HOME。"
    }
}
if (-not $env:ANDROID_HOME -and (Test-Path $sdkDir)) { $env:ANDROID_HOME = $sdkDir }
if (-not $env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }

Write-Step "构建插件（$Variant）"
Write-Host "   JAVA_HOME    = $env:JAVA_HOME"
Write-Host "   ANDROID_HOME = $env:ANDROID_HOME"

Push-Location $repoRoot
try {
    & (Join-Path $repoRoot 'gradlew.bat') ":plugin:assemble$Variant" --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Step "构建产物"
$outDir = Join-Path $repoRoot "plugin\build\outputs\apk\$($Variant.ToLower())"
if (Test-Path $outDir) {
    Get-ChildItem -Recurse $outDir -File | ForEach-Object {
        Write-Host ("   {0}  ({1:N0} 字节)" -f $_.FullName, $_.Length) -ForegroundColor Green
    }
    Write-Host ""
    Write-Host "把 .apk.lnrp 安装到 LightNovelReader 即可（设置 → 插件管理 → 从文件安装）。"
} else {
    Write-Warning "未找到输出目录：$outDir"
}

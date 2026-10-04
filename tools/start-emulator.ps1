# 启动本机模拟器（AVD 名称默认 lnrtest）。
#
# 关键点（缺一个都起不来）：
#   * ANDROID_EMULATOR_HOME 必须指向工作区 —— 模拟器默认要写
#     C:\Users\<用户>\.android\emu-last-feature-flags.protobuf.lock，在本机被拒（error 5），
#     然后它会刷屏重试，qemu 永远停在几十 MB 起不来。
#   * ANDROID_AVD_HOME 指向工作区里的 AVD 目录（AVD 由 tools/create-avd.ps1 手工构造）。
#   * TMP/TEMP 也放工作区。
#   * LOCALAPPDATA 也必须指向工作区 —— netsimd 启动时会写 %LOCALAPPDATA%\Temp\netsim.ini，
#     在本机被拒（os error 5），netsimd 反复重启，qemu 停在约 100 MB、adb 一直 offline。
#
# 用法：
#   pwsh -File tools/start-emulator.ps1                 # 后台启动，无窗口
#   pwsh -File tools/start-emulator.ps1 -Window         # 带窗口（需要能看到界面时）
#   pwsh -File tools/start-emulator.ps1 -Wait           # 启动并等开机完成
[CmdletBinding()]
param(
    [string]$AvdName = 'lnrtest',
    [switch]$Window,
    [switch]$Wait
)

$ErrorActionPreference = 'Stop'

$pluginRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tools = Get-AndroidToolchainDir -PluginRoot $pluginRoot
$sdk = Join-Path $tools 'android-sdk'

$env:ANDROID_EMULATOR_HOME = Join-Path $tools 'android-emulator-home'
$env:ANDROID_AVD_HOME = Join-Path $tools 'android-user-home\avd'
$env:ANDROID_USER_HOME = Join-Path $tools 'android-user-home'
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
$env:TMP = Join-Path $tools 'tmp'
$env:TEMP = $env:TMP
$env:LOCALAPPDATA = Join-Path $tools 'localappdata'
$env:PATH = "$sdk\platform-tools;$sdk\emulator;$env:PATH"

foreach ($d in @($env:ANDROID_EMULATOR_HOME, $env:ANDROID_AVD_HOME, $env:TMP, (Join-Path $env:LOCALAPPDATA 'Temp'))) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

$emulator = Join-Path $sdk 'emulator\emulator.exe'
if (-not (Test-Path $emulator)) { throw "找不到模拟器：$emulator" }

Write-Host "AVD        = $AvdName"
Write-Host "EMU_HOME   = $env:ANDROID_EMULATOR_HOME"
Write-Host "AVD_HOME   = $env:ANDROID_AVD_HOME"

$args = @('-avd', $AvdName, '-no-snapshot', '-no-audio', '-no-boot-anim',
          '-gpu', 'swiftshader_indirect')
if (-not $Window) { $args += '-no-window' }

Start-Process -FilePath $emulator -ArgumentList $args -WorkingDirectory $sdk -WindowStyle Minimized | Out-Null
Write-Host "已启动模拟器，等待 adb 识别..."

$adb = Join-Path $sdk 'platform-tools\adb.exe'
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 5
    $devices = & $adb devices 2>&1 | Select-String -Pattern 'emulator-\d+\s+device'
    if ($devices) {
        Write-Host "设备已就绪：$($devices.Line.Trim())" -ForegroundColor Green
        if (-not $Wait) { return }
        Write-Host "等待开机完成（sys.boot_completed）..."
        for ($j = 0; $j -lt 90; $j++) {
            Start-Sleep -Seconds 5
            $boot = (& $adb shell getprop sys.boot_completed 2>&1) -join ''
            if ($boot.Trim() -eq '1') {
                Write-Host "开机完成。" -ForegroundColor Green
                return
            }
        }
        throw "设备已连接但 7 分钟内未完成开机"
    }
}
throw "5 分钟内 adb 未识别到模拟器；检查 $env:ANDROID_EMULATOR_HOME 下是否有错误日志"

# 在本机启动 Android 模拟器并安装「宿主 App + 本插件」。
#
# 这些坑都是实测踩出来的，缺一个模拟器就起不来（或起得来但 guest 不执行）：
#
#   1. **提交内存（commit charge）必须够。** 模拟器要预留约 2.5 GB 提交量。
#      本机曾因 ChatGPT 单进程占 14 GB 提交量、系统只剩 37 MB 可用，
#      导致 qemu 在 VirtualAlloc 阶段静默退出——日志只有 1~50 行、无任何报错。
#      症状极易误判为「镜像损坏 / 虚拟化冲突」。
#      先运行 -CheckMemory 确认，必要时关闭占内存的程序。
#
#   2. **必须用目录联接把 %TEMP%\avd 指到工作区。** 否则模拟器写
#      `%TEMP%\avd\running\<pid>\jwks\...` 被拒（error 13），
#      抛出 FATAL 并退出（崩溃转储里能查到 `Failed to create jwk directory`）。
#      GetTempPath() 读的是 TEMP，而 Start-Process 不一定把它传下去，用联接最可靠。
#
#   3. **PATH 里要包含 emulator\lib64。** qemu 依赖其中的库（如 libandroid-emu-tracing.dll）。
#
#   4. **必须让进程一直活着。** 从 PowerShell 前台运行、并用工具的后台作业托管；
#      用 `Start-Process` 启动的实例会随发起命令结束一起被收走。
#
#   5. 软件渲染（swiftshader）下开机很慢，第一次要等几分钟；系统会弹
#      「System UI isn't responding」，那是慢不是挂，点 Wait 即可。
#
#   6. **netsimd 起不来会让 qemu 直接卡死。** netsimd 负责模拟蓝牙 / UWB / Wi-Fi，
#      它需要在启动时改 Windows 防火墙（或绑定端口），**没有管理员权限会失败**：
#
#          Netsim daemon failed to start: 拒绝访问。 (os error 5)
#
#      此时 qemu 会卡在 `-chardev netsim,id=uwb` / `id=bluetooth` 上：进程存在、
#      CPU 冻结在 ~0.6 秒不再增长、**没有任何内核输出**（加 -show-kernel 也一样），
#      `adb devices` 里表现为 `emulator-5554 offline`。极易误判成"镜像坏了"或"虚拟化冲突"。
#
#      排查与修复：
#        * 复现：直接运行 `<SDK>\emulator\netsimd.exe -l`，正常情况应该**一直不退出**；
#          它若立刻退出并打印上面的错误，就是这个坑。换目录、换 TEMP、走计划任务都一样，
#          说明是系统级拒绝，与工作区路径无关（实测已排除路径、DSH 沙箱、虚拟化、
#          快照、AVD 锁、内存等因素）。
#        * 修复：**必须以管理员身份**运行本脚本。实测对照——
#            不提权：qemu 存在但 CPU 冻结在 ~0.6 s，永远不启动，无任何内核输出；
#            提权后：netsimd 正常存活，同一个 AVD、同一个路径，**30 秒内 boot_completed=1**。
#        * 「只需提权一次、之后普通权限也能跑」**尚未验证**，保险起见每次都提权运行。
#          另外注意：提权启动的模拟器，普通权限的终端**杀不掉**它（访问被拒），
#          需要关掉那个管理员控制台窗口，或用管理员终端收尾。
#
# 用法：
#   pwsh -File tools/run-on-emulator.ps1 -CheckMemory
#   pwsh -File tools/run-on-emulator.ps1                 # 只启动模拟器
#   pwsh -File tools/run-on-emulator.ps1 -InstallHost    # 并安装宿主 App
#   pwsh -File tools/run-on-emulator.ps1 -InstallPlugin  # 并安装插件
[CmdletBinding()]
param(
    [string]$AvdName = 'lnrtest',
    [switch]$CheckMemory,
    [switch]$InstallHost,
    [switch]$InstallPlugin,
    [switch]$Window
)

$ErrorActionPreference = 'Stop'

$pluginRoot = Split-Path -Parent $PSScriptRoot
$lnrRoot = Split-Path -Parent $pluginRoot
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$tools = Get-AndroidToolchainDir -PluginRoot $pluginRoot
$sdk = Join-Path $tools 'android-sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$avdHome = Join-Path $tools 'android-user-home\avd'
$tempAvd = Join-Path $env:LOCALAPPDATA 'Temp\avd'

function Get-CommitInfo {
    $os = Get-CimInstance Win32_OperatingSystem
    [pscustomobject]@{
        FreePhysicalMB = [int]($os.FreePhysicalMemory / 1KB)
        FreeCommitMB   = [int]($os.FreeVirtualMemory / 1KB)
    }
}

if ($CheckMemory) {
    $m = Get-CommitInfo
    Write-Host ("物理可用 {0:N0} MB   提交可用 {1:N0} MB" -f $m.FreePhysicalMB, $m.FreeCommitMB)
    if ($m.FreeCommitMB -lt 3000) {
        Write-Host "提交内存不足（需 ≈2500 MB）：模拟器会在无任何报错的情况下静默退出。" -ForegroundColor Red
        Write-Host "请关闭占内存的程序后重试。占用最高的进程：" -ForegroundColor Yellow
        Get-Process | Group-Object ProcessName |
            ForEach-Object { [pscustomobject]@{ N = $_.Name; C = $_.Count
                CommitMB = [int](($_.Group | Measure-Object -Property PagedMemorySize64 -Sum).Sum / 1MB) } } |
            Sort-Object CommitMB -Descending | Select-Object -First 8 |
            ForEach-Object { Write-Host ("  {0,-26} x{1,-3} 提交 {2,7} MB" -f $_.N, $_.C, $_.CommitMB) }
        exit 1
    }
    Write-Host "提交内存充足。" -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------- 环境
$env:ANDROID_EMULATOR_HOME = Join-Path $tools 'android-emulator-home'
$env:ANDROID_AVD_HOME = $avdHome
$env:ANDROID_USER_HOME = Join-Path $tools 'android-user-home'
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
$env:TMP = Join-Path $tools 'tmp'
$env:TEMP = $env:TMP
$env:PATH = "$sdk\emulator\lib64;$sdk\platform-tools;$sdk\emulator;$env:PATH"
foreach ($d in @($env:ANDROID_EMULATOR_HOME, $avdHome, $env:TMP)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# 坑 2：目录联接
if (-not (Test-Path $tempAvd)) {
    New-Item -ItemType Directory -Force -Path (Join-Path $env:TMP 'avd') | Out-Null
    New-Item -ItemType Junction -Path $tempAvd -Target (Join-Path $env:TMP 'avd') | Out-Null
    Write-Host "已创建联接 $tempAvd -> $env:TMP\avd"
}

$m = Get-CommitInfo
if ($m.FreeCommitMB -lt 3000) {
    throw "提交内存不足（$($m.FreeCommitMB) MB < 3000 MB）。先运行 -CheckMemory 查看占用者。"
}

$emu = Join-Path $sdk 'emulator\emulator.exe'
if (-not (Test-Path $emu)) { throw "找不到模拟器：$emu" }

# ---------------------------------------------------------------- 启动
if ($InstallHost -or $InstallPlugin) {
    Write-Host "请先保持本脚本运行（它负责让模拟器存活），另开一个终端执行安装步骤。" -ForegroundColor Yellow
}

$emuArgs = @('-avd', $AvdName, '-no-snapshot', '-no-audio', '-no-boot-anim',
             '-gpu', 'swiftshader_indirect', '-no-metrics')
if (-not $Window) { $emuArgs += '-no-window' }

Write-Host "启动模拟器 $AvdName（软件渲染，首次开机需数分钟）..."
Push-Location $sdk
try {
    & $emu @emuArgs
} finally {
    Pop-Location
}

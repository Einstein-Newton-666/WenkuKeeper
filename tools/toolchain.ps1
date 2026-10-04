# 解析共享的 Android 工具链目录（Android SDK / AVD / 模拟器 / 便携 JDK / Gradle 缓存）。
#
# 工具链不放在插件仓库里，而是集中放在工作区的 project\vm 下，供同一工作区的多个项目共用：
#
#     <工作区>\project\vm\
#         android-sdk\        android-user-home\   android-emulator-home\
#         jdk\  jdk21\        gradle-home\         tmp\  downloads\
#
# 早期版本放在 <插件仓库>\.build-tools，这里保留回退，旧检出仍然可用。
#
# 用法（本文件与调用方同在 <插件仓库>\tools\ 下）：
#
#     . "$PSScriptRoot\toolchain.ps1"
#     $tools = Get-AndroidToolchainDir -PluginRoot $pluginRoot
function Get-AndroidToolchainDir {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$PluginRoot
    )

    # <插件仓库>\..\..\vm  =>  <工作区>\project\vm
    $shared = Join-Path (Split-Path -Parent (Split-Path -Parent $PluginRoot)) 'vm'
    $legacy = Join-Path $PluginRoot '.build-tools'

    foreach ($candidate in @($shared, $legacy)) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'android-sdk')) { return $candidate }
    }

    throw "找不到 Android 工具链目录。已尝试：`n  $shared`n  $legacy"
}

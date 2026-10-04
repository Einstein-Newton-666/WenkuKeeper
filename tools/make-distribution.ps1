<#
.SYNOPSIS
    构建插件，并把上架分发包更新到 distribution 分支。

.DESCRIPTION
    插件市场要求分发文件（plugin.toml / plugin.lnrp / icon.png / 截图）位于某个仓库或
    分支的**根目录**，不能放进源码目录，因此本仓库用一条独立的 distribution 分支承载，
    详见 README 的「发版流程」。

    这个脚本把那套流程机械化，重点在于**保证 plugin.toml 里的 sha1 与 plugin.lnrp 一致**
    —— 手工更新时最容易漏掉这一步，而一旦漏了，商店装出来的包装不上（校验不过）。

    做四件事：
      1. 干净构建（增量构建会累积陈旧 dex，发布前必须清掉 build 目录）；
      2. 从源码读出当前版本号，回写 plugin.toml 的 version_name / version_code；
      3. 用新产物的 sha1 覆盖 plugin.toml 的 [release.download] sha1；
      4. 打印待提交的差异；加 -Push 才真的提交并推送。

.PARAMETER Push
    提交并推送 distribution 分支。不加时只在本地工作树里改好并显示差异。

.PARAMETER SkipBuild
    跳过构建，直接用当前已有的产物（调试脚本自身时用）。

.EXAMPLE
    .\tools\make-distribution.ps1
    .\tools\make-distribution.ps1 -Push
#>
[CmdletBinding()]
param(
    [switch]$Push,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$constants = Join-Path $repoRoot 'plugin\src\main\kotlin\io\github\lnrplugin\wenkukeeper\PluginConstants.kt'
$gradleFile = Join-Path $repoRoot 'plugin\build.gradle.kts'
$artifact = Join-Path $repoRoot 'plugin\build\outputs\apk\debug\plugin-debug.apk.lnrp'

# --- 1. 版本号：从源码读，避免脚本里再写一份 ---
$versionName = [regex]::Match((Get-Content $constants -Raw), 'PLUGIN_VERSION_NAME\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match((Get-Content $gradleFile -Raw), 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $versionName -or -not $versionCode) { throw "读不到版本号（$constants / $gradleFile）" }
Write-Output "版本: $versionName ($versionCode)"

# --- 2. 干净构建 ---
if (-not $SkipBuild) {
    foreach ($dir in @((Join-Path $repoRoot 'plugin\build'), (Join-Path $repoRoot 'build'))) {
        if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
    }
    Write-Output "干净构建中…"
    & (Join-Path $PSScriptRoot 'build-local.ps1') -Variant Debug | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
}
if (-not (Test-Path -LiteralPath $artifact)) { throw "找不到产物：$artifact" }
$size = (Get-Item -LiteralPath $artifact).Length
$sha1 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA1).Hash.ToLower()
$sha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLower()
Write-Output "产物: $size 字节"
Write-Output "  sha1   $sha1"
Write-Output "  sha256 $sha256"

# --- 3. 取出 distribution 分支的工作树 ---
Set-Location $repoRoot
& git fetch origin distribution:distribution --force 2>&1 | Out-Null
$worktree = Join-Path ([System.IO.Path]::GetTempPath()) 'wenkukeeper-dist'

# 已经挂过就先卸掉，保证每次都是分支的最新状态
& git worktree remove $worktree --force 2>&1 | Out-Null
if (Test-Path -LiteralPath $worktree) { Remove-Item -LiteralPath $worktree -Recurse -Force }
& git worktree add $worktree distribution 2>&1 | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $worktree 'plugin.toml'))) { throw "工作树里没有 plugin.toml" }

# --- 4. 回写 plugin.toml 与产物 ---
$tomlPath = Join-Path $worktree 'plugin.toml'
$toml = Get-Content -LiteralPath $tomlPath -Raw
$toml = [regex]::Replace($toml, '(?m)^version_name = ".*"$', "version_name = `"$versionName`"")
$toml = [regex]::Replace($toml, '(?m)^version_code = \d+$', "version_code = $versionCode")
$toml = [regex]::Replace($toml, '(?m)^sha1 = ".*"$', "sha1 = `"$sha1`"")
Set-Content -LiteralPath $tomlPath -Value $toml -Encoding UTF8 -NoNewline
Copy-Item -LiteralPath $artifact -Destination (Join-Path $worktree 'plugin-debug.apk.lnrp') -Force

# --- 5. 自检：toml 里声明的 sha1 必须与同目录的 lnrp 相符 ---
$declared = [regex]::Match((Get-Content -LiteralPath $tomlPath -Raw), 'sha1 = "([0-9a-f]+)"').Groups[1].Value
$actual = (Get-FileHash -LiteralPath (Join-Path $worktree 'plugin-debug.apk.lnrp') -Algorithm SHA1).Hash.ToLower()
if ($declared -ne $actual) { throw "sha1 不一致：toml=$declared 实际=$actual" }
Write-Output "自检通过: plugin.toml 的 sha1 与 lnrp 一致"

# --- 6. 提交 / 推送 ---
Set-Location $worktree
$changes = & git status --short
if (-not $changes) {
    Write-Output "distribution 分支无变化（版本与产物都与分支上的一致）"
} else {
    Write-Output "待提交："
    $changes | ForEach-Object { "  $_" }
    if ($Push) {
        & git add -A
        & git -c user.name='Einstein-Newton-666' -c user.email='gao87136715@qq.com' `
            commit -q -m "distribution: 文库管家 v$versionName" 2>&1 | Out-Null
        & git push origin distribution 2>&1 | Select-Object -Last 1 | ForEach-Object { "  $_" }
        Write-Output "已推送 distribution 分支"
    } else {
        Write-Output "（未加 -Push，未提交；确认无误后重新运行并加 -Push）"
    }
}
Set-Location $repoRoot

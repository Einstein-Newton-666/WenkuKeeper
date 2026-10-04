package io.github.lnrplugin.wenkukeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lnrplugin.wenkukeeper.PluginConstants
import io.github.lnrplugin.wenkukeeper.PluginSettings
import io.nightfish.lightnovelreader.api.ui.components.SettingsBasicEntry
import io.nightfish.lightnovelreader.api.ui.components.SettingsClickableEntry
import io.nightfish.lightnovelreader.api.ui.components.SettingsSwitchEntry
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 项目主页。
 *
 * 指向插件自己的发布仓库, 定义在 [PluginConstants.PROJECT_URL]; 更新检查也读同一个仓库的
 * Releases, 因此两处不会写出不同地址。
 */
private const val PROJECT_URL = PluginConstants.PROJECT_URL

/** 时间戳的显示格式。 */
private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"

/**
 * 插件详情页的页面内容。
 *
 * 页面分为"数据源""云端同步""书库迁移""关于"四段, 所有设置项都直接读写 [PluginSettings] 中
 * 定义的用户数据路径; 云端操作交给 [SyncViewModel], 迁移操作交给 [MigrationViewModel]。
 *
 * @param paddingValues 宿主给出的系统内边距, 必须应用到最外层容器上
 * @param userDataRepository 宿主提供的用户数据仓库, 用于读写插件设置
 * @param syncViewModel 云端同步的状态持有者, 由插件入口类构造
 * @param migrationViewModel 书库迁移的状态持有者, 由插件入口类构造
 * @param siteSyncViewModel 站点账号同步的状态持有者, 由插件入口类构造
 * @param updateViewModel 更新检查的状态持有者, 由插件入口类构造
 */
@Composable
fun WenkuKeeperPage(
    paddingValues: PaddingValues,
    userDataRepository: UserDataRepositoryApi,
    syncViewModel: SyncViewModel,
    migrationViewModel: MigrationViewModel,
    siteSyncViewModel: SiteSyncViewModel,
    updateViewModel: UpdateViewModel
) {
    val uiState by syncViewModel.uiState.collectAsState()
    val siteUiState by siteSyncViewModel.uiState.collectAsState()
    val updateUiState by updateViewModel.uiState.collectAsState()

    val enableExplore by rememberBooleanSetting(
        userDataRepository, PluginSettings.ENABLE_EXPLORE, PluginSettings.DEFAULT_ENABLE_EXPLORE
    )
    val preferredHost by rememberStringSetting(
        userDataRepository, PluginSettings.PREFERRED_HOST, PluginSettings.DEFAULT_PREFERRED_HOST
    )
    val wenku8Cookie by rememberStringSetting(
        userDataRepository, PluginSettings.WENKU8_COOKIE, PluginSettings.DEFAULT_WENKU8_COOKIE
    )
    val webdavUrl by rememberStringSetting(
        userDataRepository, PluginSettings.WEBDAV_URL, PluginSettings.DEFAULT_WEBDAV_URL
    )
    val webdavUsername by rememberStringSetting(
        userDataRepository, PluginSettings.WEBDAV_USERNAME, PluginSettings.DEFAULT_WEBDAV_USERNAME
    )
    val webdavPassword by rememberStringSetting(
        userDataRepository, PluginSettings.WEBDAV_PASSWORD, PluginSettings.DEFAULT_WEBDAV_PASSWORD
    )
    val webdavDirectory by rememberStringSetting(
        userDataRepository, PluginSettings.WEBDAV_DIRECTORY, PluginSettings.DEFAULT_WEBDAV_DIRECTORY
    )
    val cloudBackend by rememberStringSetting(
        userDataRepository, PluginSettings.CLOUD_BACKEND, PluginSettings.DEFAULT_CLOUD_BACKEND
    )
    val githubRepository by rememberStringSetting(
        userDataRepository, PluginSettings.GITHUB_REPOSITORY, PluginSettings.DEFAULT_GITHUB_REPOSITORY
    )
    val githubToken by rememberStringSetting(
        userDataRepository, PluginSettings.GITHUB_TOKEN, PluginSettings.DEFAULT_GITHUB_TOKEN
    )
    val githubBranch by rememberStringSetting(
        userDataRepository, PluginSettings.GITHUB_BRANCH, PluginSettings.DEFAULT_GITHUB_BRANCH
    )
    val githubDirectory by rememberStringSetting(
        userDataRepository, PluginSettings.GITHUB_DIRECTORY, PluginSettings.DEFAULT_GITHUB_DIRECTORY
    )
    val writeReadingLog by rememberBooleanSetting(
        userDataRepository, PluginSettings.WRITE_READING_LOG, PluginSettings.DEFAULT_WRITE_READING_LOG
    )
    val siteSyncEnabled by rememberBooleanSetting(
        userDataRepository, PluginSettings.SITE_SYNC_ENABLED, PluginSettings.DEFAULT_SITE_SYNC_ENABLED
    )
    val autoUpload by rememberBooleanSetting(
        userDataRepository, PluginSettings.AUTO_UPLOAD, PluginSettings.DEFAULT_AUTO_UPLOAD
    )
    val autoDownload by rememberBooleanSetting(
        userDataRepository, PluginSettings.AUTO_DOWNLOAD, PluginSettings.DEFAULT_AUTO_DOWNLOAD
    )
    val keepSnapshots by rememberIntSetting(
        userDataRepository, PluginSettings.KEEP_SNAPSHOTS, PluginSettings.DEFAULT_KEEP_SNAPSHOTS
    )

    // 开关设置项需要 BooleanUserData 对象才能自动写回用户数据, 因此按仓库实例缓存。
    val enableExploreUserData = remember(userDataRepository) {
        userDataRepository.booleanUserData(PluginSettings.ENABLE_EXPLORE)
    }
    val autoUploadUserData = remember(userDataRepository) {
        userDataRepository.booleanUserData(PluginSettings.AUTO_UPLOAD)
    }
    val autoDownloadUserData = remember(userDataRepository) {
        userDataRepository.booleanUserData(PluginSettings.AUTO_DOWNLOAD)
    }
    val writeReadingLogUserData = remember(userDataRepository) {
        userDataRepository.booleanUserData(PluginSettings.WRITE_READING_LOG)
    }
    val siteSyncEnabledUserData = remember(userDataRepository) {
        userDataRepository.booleanUserData(PluginSettings.SITE_SYNC_ENABLED)
    }

    var dialog by remember { mutableStateOf<WenkuKeeperDialog?>(null) }

    // 当前生效的后端：只有明确选了 github 才走 GitHub，与引擎的归一化规则保持一致。
    val isGitHub = cloudBackend.trim().equals(PluginSettings.BACKEND_GITHUB, ignoreCase = true)

    // 先清除密码按钮只在已经设置过密码时有意义。
    val clearPassword: (() -> Unit)? = if (webdavPassword.isEmpty()) {
        null
    } else {
        { writeString(userDataRepository, PluginSettings.WEBDAV_PASSWORD, "") }
    }

    // 同理，令牌的清除按钮。
    val clearToken: (() -> Unit)? = if (githubToken.isEmpty()) {
        null
    } else {
        { writeString(userDataRepository, PluginSettings.GITHUB_TOKEN, "") }
    }

    val statusText = when {
        uiState.running -> uiState.runningLabel
        uiState.message.isNotBlank() -> uiState.message
        uiState.lastSyncStatus.isNotBlank() -> uiState.lastSyncStatus
        else -> "尚未同步过"
    }

    LaunchedEffect(syncViewModel) {
        syncViewModel.refreshStatus()
    }

    // 打开插件页面时自动查一次更新（本次进程内只跑一次，之后靠用户点「检查更新」）。
    LaunchedEffect(updateViewModel) {
        updateViewModel.checkOnce()
    }

    Column(
        modifier = Modifier
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
    ) {
        SectionHeader(text = "数据源")
        SettingsSwitchEntry(
            modifier = settingsEntryModifier(),
            title = "启用探索页",
            description = "注册 wenku8 的探索页与分类浏览入口(开关会随数据源轮询自动生效)",
            checked = enableExplore,
            booleanUserData = enableExploreUserData
        )
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "首选镜像",
            description = "留空表示自动探测可用镜像, 例如 https://www.wenku8.net (需重启宿主生效)",
            option = preferredHost.ifBlank { "自动" },
            onClick = { dialog = WenkuKeeperDialog.PreferredHost }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        SectionHeader(text = "wenku8 账号 (可选)")

        // 站点现在要求请求带登录 Cookie，它同时携带 Cloudflare 的放行凭证；
        // 不带时站点返回 403，控制器里看到的就是"数据源不可用"。留空即维持无凭据模式。
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "会话 Cookie",
            description = "在浏览器登录 www.wenku8.net 后复制完整 Cookie (形如 PHPSESSID=...; jieqiUserInfo=...)。" +
                    "留空表示不带任何凭据; 只保存在本机, 不会写入云端快照",
            option = if (wenku8Cookie.isNotBlank()) "已设置" else "未设置",
            onClick = { dialog = WenkuKeeperDialog.Wenku8Cookie }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        SectionHeader(text = "wenku8 站点同步 (可选)")

        // 与上面的云端同步是两件事: 这里写的是用户的站点账号(书架与书签),
        // 云端同步写的是用户自己的 WebDAV / GitHub 空间。默认关闭, 且只在按下按钮时才发请求。
        SettingsSwitchEntry(
            modifier = settingsEntryModifier(),
            title = "允许写入站点账号",
            description = "关闭时下面的按钮不会发出任何请求; 打开后才允许把书架与书签写进你的 wenku8 账号",
            checked = siteSyncEnabled,
            booleanUserData = siteSyncEnabledUserData
        )
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "推送到站点书架",
            description = "逐本加入站点书架, 并把书签写到本机记录的阅读章节; 站点侧有限流, 会比较慢",
            onClick = siteSyncViewModel::pushShelf
        )
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "读取站点书架",
            description = "列出站点书架上每本书的书签; 站点用重定向表达结果, 这是确认推送真的生效的方式",
            onClick = siteSyncViewModel::loadSiteShelf
        )
        SettingsBasicEntry(
            modifier = settingsEntryModifier(),
            title = "站点同步状态",
            description = siteUiState.message.ifBlank { "尚未执行过站点操作" },
            trailingContent = {
                if (siteUiState.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            extraBelowContent = {
                if (siteUiState.running && siteUiState.runningLabel.isNotBlank()) {
                    Text(
                        text = siteUiState.runningLabel,
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall
                    )
                }
                siteUiState.siteShelfCount?.let { count ->
                    Text(
                        text = "站点书架：$count 本，其中 ${siteUiState.siteBookmarkCount ?: 0} 本有书签",
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall
                    )
                }
                siteUiState.preview.forEach { line ->
                    Text(
                        text = "· $line",
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall
                    )
                }
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        SectionHeader(text = "云端同步")

        // 后端选择：切换后下面只显示该后端需要的配置项，避免两套字段混在一起。
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "存储后端",
            description = "快照存到哪里; WebDAV 与 GitHub 共用同一套快照格式与合并策略",
            option = if (isGitHub) "GitHub 仓库" else "WebDAV",
            onClick = { dialog = WenkuKeeperDialog.CloudBackend }
        )

        if (isGitHub) {
            SettingsClickableEntry(
                modifier = settingsEntryModifier(),
                title = "仓库",
                description = "格式 用户名/仓库名, 建议使用私有仓库",
                option = githubRepository.ifBlank { "未设置" },
                onClick = { dialog = WenkuKeeperDialog.GitHubRepository }
            )
            SettingsClickableEntry(
                modifier = settingsEntryModifier(),
                title = "访问令牌",
                description = "Fine-grained PAT, 需要该仓库的 Contents 读写权限; 未加密保存在宿主数据库中",
                option = if (githubToken.isNotEmpty()) "已设置" else "未设置",
                onClick = { dialog = WenkuKeeperDialog.GitHubToken }
            )
            SettingsClickableEntry(
                modifier = settingsEntryModifier(),
                title = "分支",
                description = "提交到哪个分支",
                option = githubBranch.ifBlank { PluginSettings.DEFAULT_GITHUB_BRANCH },
                onClick = { dialog = WenkuKeeperDialog.GitHubBranch }
            )
            SettingsClickableEntry(
                modifier = settingsEntryModifier(),
                title = "目录",
                description = "仓库内存放快照的目录, 留空表示仓库根目录",
                option = githubDirectory.ifBlank { "仓库根目录" },
                onClick = { dialog = WenkuKeeperDialog.GitHubDirectory }
            )
        } else {
            SettingsClickableEntry(
                modifier = settingsEntryModifier(),
                title = "服务器地址",
                description = "WebDAV 服务地址, 例如 https://dav.jianguoyun.com/dav/",
                option = webdavUrl.ifBlank { "未设置" },
                onClick = { dialog = WenkuKeeperDialog.WebDavUrl }
            )
            SettingsClickableEntry(
                modifier = settingsEntryModifier(),
                title = "用户名",
                description = "WebDAV 账号名",
                option = webdavUsername.ifBlank { "未设置" },
                onClick = { dialog = WenkuKeeperDialog.WebDavUsername }
            )
            SettingsClickableEntry(
                modifier = settingsEntryModifier(),
                title = "密码",
                description = "未加密保存在宿主数据库中, 建议使用应用专用密钥; 不会写入上传的快照",
                option = if (webdavPassword.isNotEmpty()) "已设置" else "未设置",
                onClick = { dialog = WenkuKeeperDialog.WebDavPassword }
            )
            SettingsClickableEntry(
                modifier = settingsEntryModifier(),
                title = "远程目录",
                description = "快照存放的远端目录, 相对于服务器地址",
                option = webdavDirectory.ifBlank { "默认 (${PluginSettings.DEFAULT_WEBDAV_DIRECTORY})" },
                onClick = { dialog = WenkuKeeperDialog.WebDavDirectory }
            )
        }

        SettingsSwitchEntry(
            modifier = settingsEntryModifier(),
            title = "写入阅读记录",
            description = "额外上传一份 READING_LOG.md, 在 GitHub 上可直接查看",
            checked = writeReadingLog,
            booleanUserData = writeReadingLogUserData
        )
        SettingsSwitchEntry(
            modifier = settingsEntryModifier(),
            title = "自动上传",
            description = "每 30 分钟自动上传一次备份(需重启宿主生效)",
            checked = autoUpload,
            booleanUserData = autoUploadUserData
        )
        SettingsSwitchEntry(
            modifier = settingsEntryModifier(),
            title = "自动下载",
            description = "启动时自动拉取并恢复最新备份(需重启宿主生效)",
            checked = autoDownload,
            booleanUserData = autoDownloadUserData
        )
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "保留快照数",
            description = "上传成功后自动删除更旧的远端快照",
            option = "$keepSnapshots 个",
            onClick = { dialog = WenkuKeeperDialog.KeepSnapshots }
        )
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "立即上传到云端",
            description = "先把云端最新备份合并进来，再上传书架、阅读进度与插件设置",
            onClick = syncViewModel::upload
        )
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "从云端恢复最新备份",
            description = "下载最新的快照并合并到本机, 不会删除已有书架",
            onClick = syncViewModel::restoreLatest
        )
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "测试连接",
            description = "校验当前后端的配置是否可用（地址/仓库、账号/令牌、目录）",
            onClick = syncViewModel::testConnection
        )
        SettingsBasicEntry(
            modifier = settingsEntryModifier(),
            title = "同步状态",
            description = statusText,
            trailingContent = {
                if (uiState.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            extraBelowContent = {
                Text(
                    text = "上次上传：${formatTime(uiState.lastUploadTime)}",
                    color = colorScheme.onSurfaceVariant,
                    style = typography.bodySmall
                )
                Text(
                    text = "上次恢复：${formatTime(uiState.lastRestoreTime)}",
                    color = colorScheme.onSurfaceVariant,
                    style = typography.bodySmall
                )
                uiState.remoteSnapshotCount?.let { count ->
                    Text(
                        text = "云端备份：$count 个",
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall
                    )
                }
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        SectionHeader(text = "书库迁移")
        MigrationSection(
            userDataRepository = userDataRepository,
            viewModel = migrationViewModel
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        SectionHeader(text = "关于")
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "项目主页",
            description = "在浏览器中打开项目主页",
            openUrl = PROJECT_URL
        )
        SettingsBasicEntry(
            modifier = settingsEntryModifier(),
            title = "插件版本",
            description = "${PluginConstants.SOURCE_NAME} " +
                "${PluginConstants.PLUGIN_VERSION_NAME}（Api ${PluginConstants.API_VERSION}）"
        )
        // 宿主的更新检查只查官方插件商店, 未上架的插件永远不会被提示, 所以这里自己查
        // GitHub Releases。做成可点的一项: 点一次重查, 有新版本时下方多出一行跳转入口。
        SettingsClickableEntry(
            modifier = settingsEntryModifier(),
            title = "检查更新",
            description = updateUiState.message,
            option = if (updateUiState.latestTag.isNotBlank()) "新版本 ${updateUiState.latestTag}" else null,
            trailingContent = {
                if (updateUiState.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            onClick = updateViewModel::check
        )
        updateUiState.latestUrl?.let { url ->
            SettingsClickableEntry(
                modifier = settingsEntryModifier(),
                title = "下载新版本",
                description = "在浏览器中打开发布页面",
                openUrl = url
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    val openDialog = dialog
    if (openDialog != null) {
        when (openDialog) {
            WenkuKeeperDialog.CloudBackend -> BackendChooserDialog(
                current = cloudBackend,
                onSelect = { selected ->
                    writeString(userDataRepository, PluginSettings.CLOUD_BACKEND, selected)
                    dialog = null
                },
                onDismissRequest = { dialog = null }
            )

            WenkuKeeperDialog.GitHubRepository -> SimpleTextDialog(
                title = "仓库",
                description = "填写 用户名/仓库名, 例如 octocat/my-reading-log",
                initialText = githubRepository,
                confirmText = "保存",
                validate = { value ->
                    val text = value.trim()
                    when {
                        text.isEmpty() -> "请填写仓库"
                        text.count { it == '/' } != 1 -> "格式应为 用户名/仓库名"
                        text.substringBefore('/').isBlank() -> "用户名不能为空"
                        text.substringAfter('/').isBlank() -> "仓库名不能为空"
                        else -> null
                    }
                },
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.GITHUB_REPOSITORY, value.trim())
                    dialog = null
                }
            )

            WenkuKeeperDialog.GitHubToken -> SimpleTextDialog(
                title = "访问令牌",
                description = "Fine-grained PAT, 需要该仓库的 Contents 读写权限; 点击清除按钮可删除已保存的令牌",
                initialText = githubToken,
                masked = true,
                keyboardType = KeyboardType.Password,
                confirmText = "保存",
                onClear = clearToken,
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.GITHUB_TOKEN, value)
                    dialog = null
                }
            )

            WenkuKeeperDialog.GitHubBranch -> SimpleTextDialog(
                title = "分支",
                description = "留空表示使用 ${PluginSettings.DEFAULT_GITHUB_BRANCH}",
                initialText = githubBranch,
                confirmText = "保存",
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.GITHUB_BRANCH, value.trim())
                    dialog = null
                }
            )

            WenkuKeeperDialog.GitHubDirectory -> SimpleTextDialog(
                title = "目录",
                description = "留空表示仓库根目录",
                initialText = githubDirectory,
                confirmText = "保存",
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.GITHUB_DIRECTORY, value.trim())
                    dialog = null
                }
            )

            WenkuKeeperDialog.PreferredHost -> SimpleTextDialog(
                title = "首选镜像",
                description = "留空表示自动探测可用镜像, 请填写完整地址",
                initialText = preferredHost,
                keyboardType = KeyboardType.Uri,
                confirmText = "保存",
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.PREFERRED_HOST, value.trim())
                    dialog = null
                }
            )

            WenkuKeeperDialog.Wenku8Cookie -> SimpleTextDialog(
                title = "wenku8 会话 Cookie",
                description = "在浏览器登录 www.wenku8.net 后, 从开发者工具里复制完整 Cookie " +
                        "(如 PHPSESSID=...; jieqiUserInfo=...)。留空表示不带凭据。" +
                        "只保存在本机, 不会写入云端快照; 站点会话过期后需要重新复制",
                initialText = wenku8Cookie,
                confirmText = "保存",
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.WENKU8_COOKIE, value.trim())
                    dialog = null
                }
            )

            WenkuKeeperDialog.WebDavUrl -> SimpleTextDialog(
                title = "服务器地址",
                description = "WebDAV 服务地址, 需要以 / 结尾",
                initialText = webdavUrl,
                keyboardType = KeyboardType.Uri,
                confirmText = "保存",
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.WEBDAV_URL, value.trim())
                    dialog = null
                }
            )

            WenkuKeeperDialog.WebDavUsername -> SimpleTextDialog(
                title = "用户名",
                description = "WebDAV 账号名",
                initialText = webdavUsername,
                confirmText = "保存",
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.WEBDAV_USERNAME, value.trim())
                    dialog = null
                }
            )

            WenkuKeeperDialog.WebDavPassword -> SimpleTextDialog(
                title = "密码",
                description = "输入内容以密文显示; 点击清除按钮可以删除已保存的密码",
                initialText = webdavPassword,
                masked = true,
                keyboardType = KeyboardType.Password,
                confirmText = "保存",
                onClear = clearPassword,
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.WEBDAV_PASSWORD, value)
                    dialog = null
                }
            )

            WenkuKeeperDialog.WebDavDirectory -> SimpleTextDialog(
                title = "远程目录",
                description = "留空表示使用默认目录 ${PluginSettings.DEFAULT_WEBDAV_DIRECTORY}",
                initialText = webdavDirectory,
                confirmText = "保存",
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    writeString(userDataRepository, PluginSettings.WEBDAV_DIRECTORY, value.trim())
                    dialog = null
                }
            )

            WenkuKeeperDialog.KeepSnapshots -> SimpleTextDialog(
                title = "保留快照数",
                description = "上传成功后, 更旧的远端快照会被删除",
                initialText = keepSnapshots.toString(),
                keyboardType = KeyboardType.Number,
                confirmText = "保存",
                validate = { value -> validateNumber(value, min = 1, max = 100) },
                onDismissRequest = { dialog = null },
                onConfirm = { value ->
                    value.trim().toIntOrNull()?.let {
                        writeInt(userDataRepository, PluginSettings.KEEP_SNAPSHOTS, it)
                    }
                    dialog = null
                }
            )
        }
    }
}

/** 页面中会打开的对话框。 */
private enum class WenkuKeeperDialog {
    /** 数据源的首选镜像。 */
    PreferredHost,

    /** 可选的 wenku8 会话 Cookie。 */
    Wenku8Cookie,

    /** 云端存储后端（WebDAV / GitHub）。 */
    CloudBackend,

    /** WebDAV 服务器地址。 */
    WebDavUrl,

    /** WebDAV 用户名。 */
    WebDavUsername,

    /** WebDAV 密码。 */
    WebDavPassword,

    /** 快照存放的远端目录。 */
    WebDavDirectory,

    /** GitHub 仓库（用户名/仓库名）。 */
    GitHubRepository,

    /** GitHub 访问令牌。 */
    GitHubToken,

    /** GitHub 分支。 */
    GitHubBranch,

    /** GitHub 仓库内存放快照的目录。 */
    GitHubDirectory,

    /** 远端保留的快照数量。 */
    KeepSnapshots
}

/** 分段标题; 同一模块内的其他分段文件也会复用。 */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
        text = text,
        color = colorScheme.primary,
        style = typography.titleSmall
    )
}

/** 统一的设置项修饰符, 与宿主设置页保持一致的外观; 同一模块内的其他分段文件也会复用。 */
@Composable
internal fun settingsEntryModifier(): Modifier =
    Modifier
        .fillMaxWidth()
        .background(colorScheme.surfaceContainer)

/**
 * 观察一个字符串用户数据; 数据变化时自动触发重组。
 *
 * 这里必须记住 [UserDataRepositoryApi.stringUserData] 返回的对象及其数据流, 否则每次重组
 * 都会创建新的流实例, 导致收集被反复重启。
 */
@Composable
internal fun rememberStringSetting(
    userDataRepository: UserDataRepositoryApi,
    path: String,
    default: String
): State<String> =
    remember(userDataRepository, path) {
        userDataRepository.stringUserData(path).getFlowWithDefault(default)
    }.collectAsState(initial = default)

/** 观察一个整数用户数据; 数据变化时自动触发重组。 */
@Composable
private fun rememberIntSetting(
    userDataRepository: UserDataRepositoryApi,
    path: String,
    default: Int
): State<Int> =
    remember(userDataRepository, path) {
        userDataRepository.intUserData(path).getFlowWithDefault(default)
    }.collectAsState(initial = default)

/** 观察一个布尔用户数据; 数据变化时自动触发重组。 */
@Composable
private fun rememberBooleanSetting(
    userDataRepository: UserDataRepositoryApi,
    path: String,
    default: Boolean
): State<Boolean> =
    remember(userDataRepository, path) {
        userDataRepository.booleanUserData(path).getFlowWithDefault(default)
    }.collectAsState(initial = default)

/** 异步写入一条字符串设置, 与宿主设置页使用同一条写入路径。 */
private fun writeString(userDataRepository: UserDataRepositoryApi, path: String, value: String) {
    userDataRepository.stringUserData(path).asynchronousSet(value)
}

/** 异步写入一条整数设置。 */
private fun writeInt(userDataRepository: UserDataRepositoryApi, path: String, value: Int) {
    userDataRepository.intUserData(path).asynchronousSet(value)
}

/** 校验一个整数输入, 返回 null 表示合法。 */
private fun validateNumber(value: String, min: Int, max: Int): String? {
    val parsed = value.trim().toIntOrNull() ?: return "请输入整数"
    if (parsed < min || parsed > max) return "请输入 $min 到 $max 之间的整数"
    return null
}

/** 把时间戳格式化为本地时间; 为 null 时显示"从未"。 */
private fun formatTime(millis: Long?): String {
    if (millis == null || millis <= 0L) return "从未"
    return SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(millis))
}

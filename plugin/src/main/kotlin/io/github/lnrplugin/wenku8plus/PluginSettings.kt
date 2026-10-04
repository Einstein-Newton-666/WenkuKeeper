package io.github.lnrplugin.wenku8plus

/**
 * Every user-data path used by this plugin.
 *
 * Values are persisted through the host's [io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi],
 * which means they are stored in the host database and are therefore included in the host's own
 * local export/import. Keeping all paths in one object guarantees that the settings screen and the
 * sync engine never disagree about a key.
 */
object PluginSettings {

    private const val ROOT = "plugin.wenku8plus"

    // ---------------------------------------------------------------------
    // Data source
    // ---------------------------------------------------------------------

    /** Preferred host; empty means "auto-detect from [PluginConstants.HOSTS]". */
    const val PREFERRED_HOST = "$ROOT.source.preferredHost"

    /** Whether to follow the whole catalogue through the explore pages. */
    const val ENABLE_EXPLORE = "$ROOT.source.enableExplore"

    /**
     * 可选的 wenku8 会话 Cookie（用户从浏览器复制）。
     *
     * 站点现在对绝大多数请求都要求带登录 Cookie，且该 Cookie 里同时携带 Cloudflare 的放行
     * 凭证——不带它时站点直接返回 403 challenge，这也是本插件曾经"数据源不可用"的原因。
     *
     * 设计取舍：**不做用户名密码登录，也不内置任何凭据**。用户自己登录站点、自己复制 Cookie，
     * 插件只负责带上它。留空时行为与从前完全一致（无凭据，可能被 Cloudflare 挡）。
     *
     * 属于敏感项：只保存在宿主数据库，**绝不写入云端快照**，见 `CloudSyncEngine.SENSITIVE_SETTING_PATHS`。
     */
    const val WENKU8_COOKIE = "$ROOT.source.cookie"

    // ---------------------------------------------------------------------
    // Cloud sync — backend selection
    // ---------------------------------------------------------------------

    /** Which remote backend to use: [BACKEND_WEBDAV] or [BACKEND_GITHUB]. */
    const val CLOUD_BACKEND = "$ROOT.cloud.backend"

    /** Whether to also write a human-readable reading-history Markdown file. */
    const val WRITE_READING_LOG = "$ROOT.cloud.writeReadingLog"

    // ---------------------------------------------------------------------
    // Cloud sync (WebDAV)
    // ---------------------------------------------------------------------

    /** Base URL of the WebDAV collection, e.g. `https://dav.jianguoyun.com/dav/`. */
    const val WEBDAV_URL = "$ROOT.cloud.webdavUrl"

    /** WebDAV account name. */
    const val WEBDAV_USERNAME = "$ROOT.cloud.webdavUsername"

    /** WebDAV password or app-specific token. Stored in the host database, not encrypted. */
    const val WEBDAV_PASSWORD = "$ROOT.cloud.webdavPassword"

    /** Remote folder the snapshots are written into, relative to [WEBDAV_URL]. */
    const val WEBDAV_DIRECTORY = "$ROOT.cloud.webdavDirectory"

    // ---------------------------------------------------------------------
    // Cloud sync (GitHub)
    // ---------------------------------------------------------------------

    /**
     * Target repository in `owner/repo` form.
     *
     * A private repository is strongly recommended: a reading-history Markdown file names the
     * books you read, which is personal data even though it contains no copyrighted text.
     */
    const val GITHUB_REPOSITORY = "$ROOT.cloud.githubRepository"

    /**
     * Fine-grained personal access token with `Contents: read and write` on that repository.
     *
     * Must never be uploaded inside a snapshot — see `SENSITIVE_SETTING_PATHS` in
     * `CloudSyncEngine`, which already excludes both this and the WebDAV password.
     */
    const val GITHUB_TOKEN = "$ROOT.cloud.githubToken"

    /** Branch the snapshots are committed to. */
    const val GITHUB_BRANCH = "$ROOT.cloud.githubBranch"

    /** Directory inside the repository the snapshots are written into. */
    const val GITHUB_DIRECTORY = "$ROOT.cloud.githubDirectory"

    /** Whether the plugin uploads automatically after a reading session ends. */
    const val AUTO_UPLOAD = "$ROOT.cloud.autoUpload"

    /** Whether the plugin downloads and applies the newest snapshot on startup. */
    const val AUTO_DOWNLOAD = "$ROOT.cloud.autoDownload"

    /** Number of snapshots kept remotely; older ones are deleted after a successful upload. */
    const val KEEP_SNAPSHOTS = "$ROOT.cloud.keepSnapshots"

    /** Timestamp (epoch millis) of the last successful upload, as a string. */
    const val LAST_UPLOAD_TIME = "$ROOT.cloud.lastUploadTime"

    /** Timestamp (epoch millis) of the last successful restore, as a string. */
    const val LAST_RESTORE_TIME = "$ROOT.cloud.lastRestoreTime"

    /** Human readable result of the most recent sync, shown on the plugin page. */
    const val LAST_SYNC_STATUS = "$ROOT.cloud.lastSyncStatus"

    /** All paths this plugin owns, used by "clear plugin data". */
    val ALL: List<String> = listOf(
        PREFERRED_HOST,
        ENABLE_EXPLORE,
        WENKU8_COOKIE,
        CLOUD_BACKEND,
        WRITE_READING_LOG,
        WEBDAV_URL,
        WEBDAV_USERNAME,
        WEBDAV_PASSWORD,
        WEBDAV_DIRECTORY,
        GITHUB_REPOSITORY,
        GITHUB_TOKEN,
        GITHUB_BRANCH,
        GITHUB_DIRECTORY,
        AUTO_UPLOAD,
        AUTO_DOWNLOAD,
        KEEP_SNAPSHOTS,
        LAST_UPLOAD_TIME,
        LAST_RESTORE_TIME,
        LAST_SYNC_STATUS
    )

    // ---------------------------------------------------------------------
    // Backend identifiers
    // ---------------------------------------------------------------------

    /** Backend id for a WebDAV server. */
    const val BACKEND_WEBDAV = "webdav"

    /** Backend id for a GitHub repository. */
    const val BACKEND_GITHUB = "github"

    // ---------------------------------------------------------------------
    // Defaults
    // ---------------------------------------------------------------------

    const val DEFAULT_PREFERRED_HOST = ""
    const val DEFAULT_ENABLE_EXPLORE = true
    const val DEFAULT_WENKU8_COOKIE = ""
    const val DEFAULT_CLOUD_BACKEND = BACKEND_WEBDAV
    const val DEFAULT_WRITE_READING_LOG = true
    const val DEFAULT_WEBDAV_URL = ""
    const val DEFAULT_WEBDAV_USERNAME = ""
    const val DEFAULT_WEBDAV_PASSWORD = ""
    const val DEFAULT_WEBDAV_DIRECTORY = "LightNovelReader"
    const val DEFAULT_GITHUB_REPOSITORY = ""
    const val DEFAULT_GITHUB_TOKEN = ""
    const val DEFAULT_GITHUB_BRANCH = "main"
    const val DEFAULT_GITHUB_DIRECTORY = "LightNovelReader"
    const val DEFAULT_AUTO_UPLOAD = false
    const val DEFAULT_AUTO_DOWNLOAD = false
    const val DEFAULT_KEEP_SNAPSHOTS = 10
}

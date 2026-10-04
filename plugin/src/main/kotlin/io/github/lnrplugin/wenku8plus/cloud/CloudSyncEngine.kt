package io.github.lnrplugin.wenku8plus.cloud

import android.content.Context
import android.os.Build
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import io.github.lnrplugin.wenku8plus.PluginConstants
import io.github.lnrplugin.wenku8plus.PluginSettings
import io.github.lnrplugin.wenku8plus.cloud.remote.GitHubStore
import io.github.lnrplugin.wenku8plus.cloud.remote.RemoteEntry
import io.github.lnrplugin.wenku8plus.cloud.remote.RemoteError
import io.github.lnrplugin.wenku8plus.cloud.remote.RemoteStore
import io.github.lnrplugin.wenku8plus.cloud.remote.toCloudSyncError
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.userdata.UserDataDaoApi
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** 本插件写入宿主用户数据时使用的组名，与该路径前缀天然一致。 */
private const val CLOUD_SETTINGS_GROUP = "plugin.wenku8plus.cloud"

/** 阅读记录在云端的文件名；刻意不匹配快照命名规则，因此不会被当作快照清理。 */
private const val READING_LOG_FILE_NAME = "READING_LOG.md"

/** 保留快照数量的合法区间。 */
private const val MIN_KEEP_SNAPSHOTS = 1

/** 保留快照数量的上限，避免用户误填极大的值导致一次删除过多文件。 */
private const val MAX_KEEP_SNAPSHOTS = 100

/**
 * 把后端 id 归一化：只有明确写了 `github` 才走 GitHub，其余（含空值与无法识别的值）
 * 都按 WebDAV 处理，与 [PluginSettings.DEFAULT_CLOUD_BACKEND] 保持一致。
 */
private fun normalizeBackend(raw: String): String =
    if (raw.trim().equals(PluginSettings.BACKEND_GITHUB, ignoreCase = true)) {
        PluginSettings.BACKEND_GITHUB
    } else {
        PluginSettings.BACKEND_WEBDAV
    }

/**
 * 尚未配置当前后端时的提示。
 *
 * @param backend 归一化后的后端 id
 */
private fun notConfiguredMessage(backend: String): String = when (backend) {
    PluginSettings.BACKEND_GITHUB ->
        "尚未配置 GitHub：请在插件设置中填写仓库（owner/repo 形式）、" +
                "具备该仓库 Contents 读写权限的令牌与分支"

    else ->
        "尚未配置 WebDAV：请先在插件设置中填写地址（例如 https://dav.jianguoyun.com/dav/）、账号与密码"
}

/**
 * 只描述“本机状态”的设置项：它们会随快照上传，但恢复时不会写回本机。
 *
 * 否则用另一台设备的快照恢复后，界面上的“上次上传时间 / 上次同步结果”会变成那台设备的值，
 * 与本机实际发生过的同步对不上。
 *
 * [PluginSettings.CLOUD_BACKEND] 也属于这一类：它是**本机选择**。凭据（WebDAV 密码 / GitHub
 * 令牌）本来就不进快照，如果后端选择跟着快照走，把 GitHub 备份恢复到一台只配了 WebDAV 的设备上
 * 就会把后端切成 GitHub 而令牌为空，下次同步直接报「尚未配置」。因此后端选择同样只留本机。
 */
private val LOCAL_ONLY_SETTING_PATHS: Set<String> = setOf(
    PluginSettings.CLOUD_BACKEND,
    PluginSettings.LAST_UPLOAD_TIME,
    PluginSettings.LAST_RESTORE_TIME,
    PluginSettings.LAST_SYNC_STATUS
)

/**
 * 绝不写入快照的敏感设置项。
 *
 * WebDAV 账号密码与 GitHub 令牌都是**本机凭据**：快照本身就以明文存放在云端（只有 gzip 压缩，
 * 没有加密），如果把它们写进去，等于把凭据又明文存了一份到服务器上，而且用别的设备恢复时会
 * 把本机的凭据悄悄换掉。因此这些项既不采集、也不应用。
 *
 * 需要在新设备上使用云端备份时，请在新设备上重新填写一次凭据。
 */
private val SENSITIVE_SETTING_PATHS: Set<String> = setOf(
    PluginSettings.WEBDAV_USERNAME,
    PluginSettings.WEBDAV_PASSWORD,
    PluginSettings.GITHUB_TOKEN,
    // wenku8 会话 Cookie 等同于账号凭据：带上它就等于登录了用户的文库账号，也能读 VIP 章节。
    // 快照是明文存放的，写进去等于把会话泄露到云端，而且用别的设备恢复会顶掉那台的登录态。
    PluginSettings.WENKU8_COOKIE
)

/**
 * 恢复时不得覆盖的本机设置项：设备本机状态 + 敏感凭据。
 */
private val RESTORE_PROTECTED_PATHS: Set<String> =
    LOCAL_ONLY_SETTING_PATHS + SENSITIVE_SETTING_PATHS

/**
 * 剥离快照中的敏感设置项后再上传。
 *
 * [buildLocalSnapshot] 本身已经不采集凭据，但 [SnapshotCodec.merge] 会把远端快照的条目并进来
 * ——如果云端存在早期版本留下的、含凭据的快照，合并结果又会把它们带回新文件。上传前统一过滤
 * 一次，既避免密码被反复写入云端，也让旧快照中的凭据不再向后传播。
 *
 * @return 不含敏感设置项的快照
 */
private fun CloudSnapshot.withoutSensitiveSettings(): CloudSnapshot =
    if (userData.none { it.path in SENSITIVE_SETTING_PATHS }) {
        this
    } else {
        copy(userData = userData.filterNot { it.path in SENSITIVE_SETTING_PATHS })
    }

/**
 * 云端同步失败的分类，便于界面区分“改配置”“改密码”“稍后重试”三种处理方式。
 */
enum class CloudSyncErrorKind {
    /** 还没有配置 WebDAV 地址。 */
    NOT_CONFIGURED,

    /** 配置存在但不合法（地址错误、文件名非法等）。 */
    INVALID_CONFIGURATION,

    /** 认证失败。 */
    UNAUTHORIZED,

    /** 网络层失败：连不上、超时、TLS 错误等。 */
    NETWORK,

    /** 目标不存在。 */
    NOT_FOUND,

    /** 服务器返回了非预期状态码。 */
    HTTP,

    /** 协议层失败：响应无法解析、重定向无法跟随等。 */
    PROTOCOL,

    /** 快照内容无法解析。 */
    DECODE,

    /** 本机读写失败。 */
    IO
}

/**
 * 云端同步失败。
 *
 * [message] 是可直接展示给用户的中文说明，保证不含用户名与密码；界面通常只需要读
 * [message]，需要区分处理方式时再读 [kind]（或按子类匹配）。
 *
 * @property kind 失败分类
 * @property message 面向用户的说明
 * @property cause 原始异常，便于排查；可能为 null
 */
sealed class CloudSyncError(
    val kind: CloudSyncErrorKind,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /** 尚未配置 WebDAV。 */
    class NotConfigured(message: String) :
        CloudSyncError(CloudSyncErrorKind.NOT_CONFIGURED, message)

    /** 配置不合法。 */
    class InvalidConfiguration(message: String) :
        CloudSyncError(CloudSyncErrorKind.INVALID_CONFIGURATION, message)

    /** 认证失败。 */
    class Unauthorized(message: String) :
        CloudSyncError(CloudSyncErrorKind.UNAUTHORIZED, message)

    /** 网络层失败。 */
    class Network(message: String, cause: Throwable? = null) :
        CloudSyncError(CloudSyncErrorKind.NETWORK, message, cause)

    /** 目标不存在。 */
    class NotFound(message: String) :
        CloudSyncError(CloudSyncErrorKind.NOT_FOUND, message)

    /** 服务器返回了非预期状态码。 */
    class Http(val status: Int, message: String, cause: Throwable? = null) :
        CloudSyncError(CloudSyncErrorKind.HTTP, message, cause)

    /** 协议层失败。 */
    class Protocol(message: String, cause: Throwable? = null) :
        CloudSyncError(CloudSyncErrorKind.PROTOCOL, message, cause)

    /** 快照解析失败。 */
    class Decode(message: String, cause: Throwable? = null) :
        CloudSyncError(CloudSyncErrorKind.DECODE, message, cause)

    /** 本机读写失败。 */
    class Io(message: String, cause: Throwable? = null) :
        CloudSyncError(CloudSyncErrorKind.IO, message, cause)
}

/**
 * 云端上的一个快照文件。
 *
 * 名字刻意不叫 `WebDav...`：WebDAV 与 GitHub 两个后端共用同一个类型。
 *
 * @property name 文件名（形如 `lnr-20261003-195731.json.gz`），可直接传给
 * [CloudSyncEngine.download]
 * @property size 文件大小（字节），服务器未返回时为 0
 * @property lastModifiedEpochMillis 最后修改时间（Unix 毫秒），无法解析时为 null
 */
data class SnapshotInfo(
    val name: String,
    val size: Long,
    val lastModifiedEpochMillis: Long?
)

/**
 * 一次恢复操作的统计结果，供界面展示“应用 N 项 / 跳过 N 项 / 失败 N 项”。
 *
 * @property sourceName 快照文件名；恢复内存中的快照时为 null
 * @property bookshelvesApplied 成功写入的书架数
 * @property bookshelvesSkipped 与本地一致、无需写入的书架数
 * @property bookshelvesFailed 写入失败的书架数
 * @property readingApplied 成功更新且确实发生变化的阅读进度数
 * @property readingSkipped 与本地一致（或本身为空）而未更新的阅读进度数
 * @property readingFailed 更新失败的阅读进度数
 * @property userDataApplied 成功写入且值发生变化的设置项数
 * @property userDataSkipped 值与本地一致、无需写入的设置项数
 * @property userDataFailed 写入失败的设置项数
 */
data class RestoreReport(
    val sourceName: String? = null,
    val bookshelvesApplied: Int = 0,
    val bookshelvesSkipped: Int = 0,
    val bookshelvesFailed: Int = 0,
    val readingApplied: Int = 0,
    val readingSkipped: Int = 0,
    val readingFailed: Int = 0,
    val userDataApplied: Int = 0,
    val userDataSkipped: Int = 0,
    val userDataFailed: Int = 0
) {
    /** 实际写入的条目总数。 */
    val applied: Int get() = bookshelvesApplied + readingApplied + userDataApplied

    /** 因与本地一致而跳过的条目总数。 */
    val skipped: Int get() = bookshelvesSkipped + readingSkipped + userDataSkipped

    /** 写入失败的条目总数。 */
    val failed: Int get() = bookshelvesFailed + readingFailed + userDataFailed

    /** 快照中参与恢复的条目总数。 */
    val total: Int get() = applied + skipped + failed
}

/**
 * 一次“先合并再上传”同步的结果。
 *
 * @property uploaded 本次上传的快照文件
 * @property mergedRemoteName 本次合并过的云端快照名；没有可合并的远端快照时为 null
 * @property prunedSnapshots 本次清理掉的旧快照数量
 * @property bookshelfCount 上传内容中的书架数
 * @property readingCount 上传内容中的阅读进度数
 * @property userDataCount 上传内容中的设置项数
 */
data class SyncReport(
    val uploaded: SnapshotInfo,
    val mergedRemoteName: String? = null,
    val prunedSnapshots: Int = 0,
    val bookshelfCount: Int = 0,
    val readingCount: Int = 0,
    val userDataCount: Int = 0
)

/**
 * 云端备份与恢复的编排器。
 *
 * ## 后端
 * 存到哪里由 [PluginSettings.CLOUD_BACKEND] 决定：WebDAV（[WebDavClient]）或 GitHub 仓库
 * （[GitHubStore]）。两者都实现 [RemoteStore]，所以快照格式、合并策略、保留数量与恢复流程
 * 完全共用，引擎只负责「读配置 → 选后端 → 翻译错误」。
 *
 * ## 备份范围（重要，请勿夸大）
 * 插件只能通过 `io.nightfish.lightnovelreader.api` 读写数据。宿主**没有**向插件开放
 * “枚举任意用户数据行”“遍历已缓存书籍信息”“读取章节正文”的能力，因此本引擎的快照**不是**
 * 完整数据库备份，只包含三类内容：
 * 1. 书架（[BookshelfRepositoryApi.getAllBookshelves]）；
 * 2. 阅读进度（[BookRepositoryApi.getAllUserReadingData]）；
 * 3. 本插件自己的设置项（[PluginSettings.ALL] 中的每一个 path，凭据除外）。
 *
 * 书架的“书本元数据/更新时间”、章节缓存、阅读统计、其它插件的设置等都不在快照内，
 * 恢复后这些内容仍以本机现有数据为准。
 *
 * ## 阅读记录
 * [PluginSettings.WRITE_READING_LOG] 打开时，上传还会在快照旁边额外写一份 `READING_LOG.md`
 * （可读的 Markdown 阅读记录）。它不匹配快照命名规则，因此永远不会被保留数量策略清理；
 * 写失败只会记入 [lastUploadWarnings]，不会让本次快照上传失败。
 *
 * ## 线程与异常
 * 所有公开方法内部都切到 [Dispatchers.IO]，返回 `kotlin-result` 的 [Result]，
 * 不会向调用方抛异常（协程取消 [CancellationException] 除外，它必须继续向上传播）。
 * [buildLocalSnapshot] 因为签名返回非空快照而无法返回失败：它按数据段分别吞掉异常并把
 * 说明记录到 [lastBuildWarnings]，同时返回“尽可能完整”的快照。
 *
 * ## 凭据
 * WebDAV 账号密码与 GitHub 令牌只从宿主用户数据里读取，只用于 `Authorization` 头，
 * 并且被 [SENSITIVE_SETTING_PATHS] 排除在快照之外；任何错误信息、状态文本与日志都不会包含凭据。
 *
 * @param context 宿主注入的 Application Context，仅用于生成不含敏感信息的环境标识
 * @param userDataRepository 读取/写入插件设置项的宿主 Api
 * @param userDataDao 直接读写用户数据行，恢复设置项时使用
 * @param bookshelfRepository 书架的读取与写入
 * @param bookRepository 阅读进度的读取与写入
 * @param bookTitleProvider 读取书名：入参是书本 id，返回书名；取不到时返回 null。
 * 只有阅读记录需要书名，因此引擎只在 [PluginSettings.WRITE_READING_LOG] 打开时调用它，
 * 且每个 id 最多调用一次；实现方（插件入口）自己负责缓存与降级，异常按「取不到」处理。
 */
class CloudSyncEngine(
    private val context: Context,
    private val userDataRepository: UserDataRepositoryApi,
    private val userDataDao: UserDataDaoApi,
    private val bookshelfRepository: BookshelfRepositoryApi,
    private val bookRepository: BookRepositoryApi,
    private val bookTitleProvider: suspend (String) -> String? = { null },
) {

    /** 保护 [cachedStore] 的锁；只在替换客户端时短暂持有。 */
    private val clientLock = Any()

    /** 缓存的远端客户端（WebDAV 或 GitHub），避免每次同步都新建 HTTP 客户端。 */
    private var cachedStore: RemoteStore? = null

    /** 缓存客户端对应的配置；后端、地址或凭据变化时重建客户端。 */
    private var cachedConfiguration: RemoteConfiguration? = null

    /**
     * 最近一次 [buildLocalSnapshot] 中被静默跳过的数据段说明。
     *
     * 正常情况下为空列表；非空表示快照不完整（例如宿主某个读取接口抛了异常），
     * 界面可据此提示用户。本字段不参与快照内容。
     */
    var lastBuildWarnings: List<String> = emptyList()
        private set

    /**
     * 最近一次上传中被降级处理的步骤说明（目前只有「阅读记录写入失败」）。
     *
     * 正常情况下为空列表；非空表示快照本身上传成功，但附带内容有问题，界面可据此提示用户。
     */
    var lastUploadWarnings: List<String> = emptyList()
        private set

    /**
     * 采集本机数据，生成一份可以上传的快照。
     *
     * 只包含书架、阅读进度与本插件设置项，范围限制见类说明。任意一段读取失败都不会抛出异常：
     * 该段退化为空列表，失败原因写入 [lastBuildWarnings]。
     *
     * @return 本机快照（可能是不完整的，检查 [lastBuildWarnings] 可知）
     */
    suspend fun buildLocalSnapshot(): CloudSnapshot = withContext(Dispatchers.IO) {
        val warnings = ArrayList<String>()

        val bookshelves = guard(warnings, "读取书架失败") {
            bookshelfRepository.getAllBookshelves().map { it.toEntry() }
        }.orEmpty()

        val readingData = guard(warnings, "读取阅读进度失败") {
            bookRepository.getAllUserReadingData().map { it.toEntry() }
        }.orEmpty()

        val userData = ArrayList<UserDataEntry>(PluginSettings.ALL.size)
        for (path in PluginSettings.ALL) {
            // 凭据不采集，见 SENSITIVE_SETTING_PATHS 的说明。
            if (path in SENSITIVE_SETTING_PATHS) continue
            val value = guard(warnings, "读取设置失败：$path") {
                userDataRepository.stringUserData(path).get()
            } ?: continue
            userData += UserDataEntry(
                path = path,
                group = CLOUD_SETTINGS_GROUP,
                type = USER_DATA_TYPE_STRING,
                value = value
            )
        }

        lastBuildWarnings = warnings
        CloudSnapshot(
            version = CLOUD_SNAPSHOT_FORMAT_VERSION,
            createdAtEpochMillis = System.currentTimeMillis(),
            deviceLabel = currentDeviceLabel(),
            bookshelves = bookshelves,
            readingData = readingData,
            userData = userData
        )
    }

    /**
     * 把快照上传到云端。
     *
     * 流程：确保目标存在 → 写入 `lnr-YYYYMMDD-HHmmss.json.gz` → 按
     * [PluginSettings.WRITE_READING_LOG] 决定是否额外写一份 `READING_LOG.md` →
     * 按 [PluginSettings.KEEP_SNAPSHOTS] 删除最旧的快照 → 写入
     * [PluginSettings.LAST_UPLOAD_TIME] 与 [PluginSettings.LAST_SYNC_STATUS]。
     *
     * @param snapshot 需要上传的快照，通常来自 [buildLocalSnapshot]
     *
     * @return 成功时返回刚上传文件的信息
     */
    suspend fun upload(snapshot: CloudSnapshot): Result<SnapshotInfo, CloudSyncError> {
        val outcome = uploadWithPruneCount(snapshot).asCloudOutcome()
        val failure = outcome.failure
        if (failure != null) return Err(failure)
        val uploadResult = outcome.value ?: return Err(CloudSyncError.Io("上传结果缺失"))
        return Ok(uploadResult.info)
    }

    /** 上传的真正实现：除了文件信息，还报告本次清理掉多少旧快照。 */
    private suspend fun uploadWithPruneCount(
        snapshot: CloudSnapshot
    ): Result<UploadOutcome, CloudSyncError> = withContext(Dispatchers.IO) {
        try {
            val configuration = readConfiguration()
                ?: return@withContext Err(
                    CloudSyncError.NotConfigured(notConfiguredMessage(selectedBackend()))
                )
            val store = remoteStoreFor(configuration)
            val fileName = SnapshotCodec.snapshotFileName(LocalDateTime.now())
            val payload = SnapshotCodec.encode(snapshot.withoutSensitiveSettings())
            val warnings = ArrayList<String>()

            val directory = store.ensureDirectory().asRemoteOutcome()
            val directoryFailure = directory.failure
            if (directoryFailure != null) return@withContext Err(directoryFailure)

            val put = store.put(fileName, payload).asRemoteOutcome()
            val putFailure = put.failure
            if (putFailure != null) return@withContext Err(putFailure)

            val readingLogWritten = writeReadingLog(store, snapshot, warnings)

            val entries = listSnapshotEntries(store)
            val pruned = pruneOldSnapshots(store, entries, keepSnapshotCount())
            writeStatus(PluginSettings.LAST_UPLOAD_TIME, System.currentTimeMillis().toString())
            writeStatus(
                PluginSettings.LAST_SYNC_STATUS,
                buildUploadStatus(fileName, snapshot, pruned, readingLogWritten, warnings)
            )
            lastUploadWarnings = warnings

            val uploaded = entries.firstOrNull { it.name == fileName }
            Ok(
                UploadOutcome(
                    info = SnapshotInfo(
                        name = fileName,
                        size = uploaded?.size ?: payload.size.toLong(),
                        lastModifiedEpochMillis = uploaded?.lastModifiedEpochMillis
                    ),
                    pruned = pruned
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(CloudSyncError.Io("上传失败：${describe(error)}", error))
        }
    }

    /**
     * 列出云端的全部快照，最新在前。
     *
     * 目录还不存在时（第一次使用）返回空列表，而不是失败。
     *
     * @return 快照信息列表
     */
    suspend fun listSnapshots(): Result<List<SnapshotInfo>, CloudSyncError> =
        withContext(Dispatchers.IO) {
            try {
                val configuration = readConfiguration()
                    ?: return@withContext Err(
                        CloudSyncError.NotConfigured(notConfiguredMessage(selectedBackend()))
                    )
                val store = remoteStoreFor(configuration)
                val listed = store.list().asRemoteOutcome()
                val listedFailure = listed.failure
                if (listedFailure != null) {
                    return@withContext if (listedFailure.kind == CloudSyncErrorKind.NOT_FOUND) {
                        Ok(emptyList<SnapshotInfo>())
                    } else {
                        Err(listedFailure)
                    }
                }
                Ok(snapshotInfos(listed.value.orEmpty()))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Err(CloudSyncError.Io("列出云端快照失败：${describe(error)}", error))
            }
        }

    /**
     * 下载并解析指定快照。
     *
     * @param name 文件名，来自 [listSnapshots]；传入路径时会只取最后一段并校验命名规则
     *
     * @return 解析后的快照
     */
    suspend fun download(name: String): Result<CloudSnapshot, CloudSyncError> =
        withContext(Dispatchers.IO) {
            try {
                val fileName = name.trim().substringAfterLast('/')
                if (!SnapshotCodec.SNAPSHOT_FILE_NAME_PATTERN.matches(fileName)) {
                    return@withContext Err(
                        CloudSyncError.InvalidConfiguration("不是合法的快照文件名：$name")
                    )
                }
                val configuration = readConfiguration()
                    ?: return@withContext Err(
                        CloudSyncError.NotConfigured(notConfiguredMessage(selectedBackend()))
                    )
                val store = remoteStoreFor(configuration)

                val downloaded = store.get(fileName).asRemoteOutcome()
                val downloadFailure = downloaded.failure
                if (downloadFailure != null) return@withContext Err(downloadFailure)
                val bytes = downloaded.value
                    ?: return@withContext Err(CloudSyncError.Decode("云端快照为空：$fileName"))

                val snapshot = try {
                    SnapshotCodec.decode(bytes)
                } catch (formatError: SnapshotFormatException) {
                    return@withContext Err(
                        CloudSyncError.Decode(
                            formatError.message ?: "快照解析失败：$fileName",
                            formatError
                        )
                    )
                }
                Ok(snapshot)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Err(CloudSyncError.Io("下载快照失败：${describe(error)}", error))
            }
        }

    /**
     * 把快照写回本机。
     *
     * 语义（与宿主的本地导入保持一致，只增不减、只进不退）：
     * - 书架：`overwrite = true` 时先删除同 id 书架再写入；`false` 时与本地同 id 书架合并
     *   （名称等描述字段取快照值，书本 id 列表取并集）；
     * - 阅读进度：通过 [BookRepositoryApi.updateUserReadingData] 逐本合并，进度只取较大值，
     *   永远不会让本地进度倒退；
     * - 设置项：写入前先与本地值合并，`StringList` 类型按宿主的并集规则处理；只描述本机状态的
     *   三个字段（[PluginSettings.LAST_UPLOAD_TIME]、[PluginSettings.LAST_RESTORE_TIME]、
     *   [PluginSettings.LAST_SYNC_STATUS]）不会被快照覆盖；
     * - 每一项都单独捕获异常，单项失败不会中断整次恢复。
     *
     * 结束后写入 [PluginSettings.LAST_RESTORE_TIME] 与 [PluginSettings.LAST_SYNC_STATUS]。
     *
     * @param snapshot 需要恢复的快照
     * @param overwrite 书架是否覆盖式恢复
     * @param sourceName 快照来源文件名，仅用于界面展示与状态文本
     *
     * @return 各项计数
     */
    suspend fun restore(
        snapshot: CloudSnapshot,
        overwrite: Boolean,
        sourceName: String? = null
    ): Result<RestoreReport, CloudSyncError> = withContext(Dispatchers.IO) {
        try {
            var bookshelvesApplied = 0
            var bookshelvesSkipped = 0
            var bookshelvesFailed = 0
            for (entry in snapshot.bookshelves) {
                try {
                    if (overwrite) bookshelfRepository.deleteBookshelf(entry.id)
                    val incoming = entry.toBookshelf()
                    val existing = if (overwrite) null else bookshelfRepository.getBookshelf(entry.id)
                    val merged = if (existing == null) {
                        incoming
                    } else {
                        SnapshotCodec.mergeBookshelfEntry(existing.toEntry(), entry).toBookshelf()
                    }
                    if (existing != null && merged == existing) {
                        bookshelvesSkipped++
                    } else {
                        bookshelfRepository.addBookshelf(merged)
                        bookshelvesApplied++
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    bookshelvesFailed++
                }
            }

            var readingApplied = 0
            var readingSkipped = 0
            var readingFailed = 0
            for (entry in snapshot.readingData) {
                if (entry.isEmptyEntry()) {
                    readingSkipped++
                    continue
                }
                try {
                    var changed = false
                    bookRepository.updateUserReadingData(entry.id) { current ->
                        val currentEntry = current.toEntry()
                        val mergedEntry = SnapshotCodec.mergeReadingEntry(currentEntry, entry)
                        changed = mergedEntry != currentEntry
                        mergedEntry.toReadingData()
                    }
                    if (changed) readingApplied++ else readingSkipped++
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    readingFailed++
                }
            }

            var userDataApplied = 0
            var userDataSkipped = 0
            var userDataFailed = 0
            for (entry in snapshot.userData) {
                if (entry.path.isBlank() || entry.path in RESTORE_PROTECTED_PATHS) {
                    userDataSkipped++
                    continue
                }
                try {
                    val type = entry.type.ifBlank { USER_DATA_TYPE_STRING }
                    val group = entry.group.ifBlank { CLOUD_SETTINGS_GROUP }
                    val existingValue = userDataDao.get(entry.path)
                    val mergedValue = SnapshotCodec.mergeUserDataEntry(
                        UserDataEntry(
                            path = entry.path,
                            group = group,
                            type = type,
                            value = existingValue.orEmpty()
                        ),
                        entry.copy(type = type, group = group)
                    ).value
                    when {
                        existingValue == null -> {
                            userDataDao.insert(entry.path, group, type, mergedValue)
                            userDataApplied++
                        }

                        existingValue == mergedValue -> userDataSkipped++
                        else -> {
                            userDataDao.insert(entry.path, group, type, mergedValue)
                            userDataApplied++
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    userDataFailed++
                }
            }

            val report = RestoreReport(
                sourceName = sourceName,
                bookshelvesApplied = bookshelvesApplied,
                bookshelvesSkipped = bookshelvesSkipped,
                bookshelvesFailed = bookshelvesFailed,
                readingApplied = readingApplied,
                readingSkipped = readingSkipped,
                readingFailed = readingFailed,
                userDataApplied = userDataApplied,
                userDataSkipped = userDataSkipped,
                userDataFailed = userDataFailed
            )
            writeStatus(PluginSettings.LAST_RESTORE_TIME, System.currentTimeMillis().toString())
            writeStatus(
                PluginSettings.LAST_SYNC_STATUS,
                "恢复完成（来源：${sourceName ?: "内存快照"}）：应用 ${report.applied}，" +
                        "跳过 ${report.skipped}，失败 ${report.failed}"
            )
            Ok(report)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(CloudSyncError.Io("恢复失败：${describe(error)}", error))
        }
    }

    /**
     * 一键同步：先与云端最新快照合并，再上传合并结果。
     *
     * 合并（[SnapshotCodec.merge]）只会增加或取较大值，不会丢掉任何一方的数据，因此在两台设备
     * 之间来回同步是安全的。云端还没有快照、或最新快照损坏时，退化为直接上传本机快照。
     *
     * @return 同步结果统计
     */
    suspend fun syncNow(): Result<SyncReport, CloudSyncError> = withContext(Dispatchers.IO) {
        try {
            val configuration = readConfiguration()
                ?: return@withContext Err(
                    CloudSyncError.NotConfigured(notConfiguredMessage(selectedBackend()))
                )
            val store = remoteStoreFor(configuration)

            var snapshot = buildLocalSnapshot()
            var mergedRemoteName: String? = null

            val listed = store.list().asRemoteOutcome()
            val listedFailure = listed.failure
            if (listedFailure != null && listedFailure.kind != CloudSyncErrorKind.NOT_FOUND) {
                return@withContext Err(listedFailure)
            }
            val newest = listed.value.orEmpty()
                .filter { !it.isCollection && SnapshotCodec.SNAPSHOT_FILE_NAME_PATTERN.matches(it.name) }
                .maxWithOrNull(
                    compareBy<RemoteEntry> { it.lastModifiedEpochMillis ?: 0L }.thenBy { it.name }
                )
            if (newest != null) {
                val remote = store.get(newest.name).asRemoteOutcome()
                val remoteFailure = remote.failure
                val remoteBytes = if (remoteFailure == null) remote.value else null
                if (remoteBytes != null) {
                    val remoteSnapshot = try {
                        SnapshotCodec.decode(remoteBytes)
                    } catch (_: SnapshotFormatException) {
                        null
                    }
                    if (remoteSnapshot != null) {
                        snapshot = SnapshotCodec.merge(snapshot, remoteSnapshot)
                        mergedRemoteName = newest.name
                    }
                }
            }

            val uploaded = uploadWithPruneCount(snapshot).asCloudOutcome()
            val uploadFailure = uploaded.failure
            if (uploadFailure != null) return@withContext Err(uploadFailure)
            val uploadResult = uploaded.value
                ?: return@withContext Err(CloudSyncError.Io("上传结果缺失"))

            Ok(
                SyncReport(
                    uploaded = uploadResult.info,
                    mergedRemoteName = mergedRemoteName,
                    prunedSnapshots = uploadResult.pruned,
                    bookshelfCount = snapshot.bookshelves.size,
                    readingCount = snapshot.readingData.size,
                    userDataCount = snapshot.userData.size
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(CloudSyncError.Io("同步失败：${describe(error)}", error))
        }
    }

    /**
     * 拉取云端最新快照并合并回本机（不会覆盖删除本机数据）。
     *
     * 等价于 [listSnapshots] 取第一个 → [download] → [restore]`(overwrite = false)`。
     *
     * @return 恢复统计；云端没有快照时返回 [CloudSyncErrorKind.NOT_FOUND] 失败
     */
    suspend fun pullLatest(): Result<RestoreReport, CloudSyncError> = withContext(Dispatchers.IO) {
        try {
            val snapshots = listSnapshots().asCloudOutcome()
            val snapshotsFailure = snapshots.failure
            if (snapshotsFailure != null) return@withContext Err(snapshotsFailure)
            val newest = snapshots.value.orEmpty().firstOrNull()
                ?: return@withContext Err(CloudSyncError.NotFound("云端还没有任何快照"))

            val snapshot = download(newest.name).asCloudOutcome()
            val downloadFailure = snapshot.failure
            if (downloadFailure != null) return@withContext Err(downloadFailure)
            val decoded = snapshot.value
                ?: return@withContext Err(CloudSyncError.Decode("云端快照为空：${newest.name}"))

            restore(decoded, overwrite = false, sourceName = newest.name)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(CloudSyncError.Io("拉取云端快照失败：${describe(error)}", error))
        }
    }

    /**
     * 测试当前后端的配置是否可用。
     *
     * 先做一次探测：WebDAV 用 Depth:0 的 PROPFIND 校验基础地址，GitHub 校验仓库与分支，
     * 这样「地址写错 / 仓库写错 / 分支写错 / 凭据无效」都能给出各自的提示，而不会被
     * 「目录还没建」掩盖；通过后再列一次目录统计快照数量（目录不存在也算配置正确）。
     * 可用于设置页的“测试连接”。
     *
     * @return 成功时返回一句可直接展示的中文说明
     */
    suspend fun testConnection(): Result<String, CloudSyncError> = withContext(Dispatchers.IO) {
        try {
            val configuration = readConfiguration()
                ?: return@withContext Err(
                    CloudSyncError.NotConfigured(notConfiguredMessage(selectedBackend()))
                )
            val store = remoteStoreFor(configuration)

            val probed = store.probe().asRemoteOutcome()
            val probeFailure = probed.failure
            if (probeFailure != null) return@withContext Err(probeFailure)
            if (probed.value != true) {
                return@withContext Err(CloudSyncError.NotFound(probeNotFoundMessage(configuration)))
            }

            val listed = store.list().asRemoteOutcome()
            val listedFailure = listed.failure
            if (listedFailure != null && listedFailure.kind != CloudSyncErrorKind.NOT_FOUND) {
                return@withContext Err(listedFailure)
            }
            val count = snapshotInfos(listed.value.orEmpty()).size
            val hint = if (listedFailure != null) "（快照目录尚未创建，会在首次上传时自动创建）" else ""
            Ok("连接成功：${connectionTarget(configuration)}，云端已有 $count 个快照$hint")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(CloudSyncError.Io("测试连接失败：${describe(error)}", error))
        }
    }

    /** 测试连接成功时用来描述「连到哪里」：GitHub 显示仓库@分支，WebDAV 显示地址。 */
    private fun connectionTarget(configuration: RemoteConfiguration): String =
        if (configuration.backend == PluginSettings.BACKEND_GITHUB) {
            "${configuration.repository}@${configuration.branch}"
        } else {
            configuration.url
        }

    /** 探测返回「目标不存在」时，按后端给出可操作的提示。 */
    private fun probeNotFoundMessage(configuration: RemoteConfiguration): String =
        if (configuration.backend == PluginSettings.BACKEND_GITHUB) {
            "GitHub 仓库或分支不可用：${configuration.repository}@${configuration.branch}；" +
                    "请检查仓库名是否写对、令牌是否有权访问它、分支是否存在"
        } else {
            "地址可以连通，但不存在该 WebDAV 集合：${configuration.url}；" +
                    "请确认地址指向 WebDAV 根目录（通常以 /dav/ 之类结尾）"
        }

    /**
     * 关闭内部缓存的远端客户端（WebDAV 或 GitHub）。
     *
     * 插件卸载或页面销毁时调用；关闭后再次调用同步方法会自动重建客户端。
     */
    fun close() {
        synchronized(clientLock) {
            cachedStore?.close()
            cachedStore = null
            cachedConfiguration = null
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 远端配置；一个后端只用到其中一部分字段。
     *
     * [toString] 刻意不输出账号、密码与令牌，避免凭据随异常信息或日志外泄。
     */
    private data class RemoteConfiguration(
        val backend: String,
        val url: String,
        val username: String,
        val password: String,
        val directory: String,
        val repository: String,
        val token: String,
        val branch: String,
        val githubDirectory: String
    ) {
        /** 不包含任何凭据，避免它出现在日志或异常信息里。 */
        override fun toString(): String = if (backend == PluginSettings.BACKEND_GITHUB) {
            "RemoteConfiguration(backend=github, repository=$repository, branch=$branch, " +
                    "directory=$githubDirectory)"
        } else {
            "RemoteConfiguration(backend=webdav, url=$url, directory=$directory)"
        }
    }

    /** `kotlin-result` 结果的内部视图：成功值与失败原因二选一。 */
    private class Outcome<T>(val value: T?, val failure: CloudSyncError?)

    /** 一次上传的完整结果：文件信息 + 本次清理的旧快照数量。 */
    private class UploadOutcome(val info: SnapshotInfo, val pruned: Int)

    /** 把任意失败类型的结果折叠成 [Outcome]，避免对 captured var 做智能转换。 */
    private fun <T, E> Result<T, E>.asOutcome(convert: (E) -> CloudSyncError): Outcome<T> {
        var value: T? = null
        var failure: CloudSyncError? = null
        onOk { value = it }
        onErr { failure = convert(it) }
        return Outcome(value, failure)
    }

    /** [RemoteStore] 结果 → [Outcome]；后端差异在 [RemoteError.toCloudSyncError] 里被抹平。 */
    private fun <T> Result<T, RemoteError>.asRemoteOutcome(): Outcome<T> =
        asOutcome { it.toCloudSyncError() }

    /** 本引擎结果 → [Outcome]。 */
    private fun <T> Result<T, CloudSyncError>.asCloudOutcome(): Outcome<T> = asOutcome { it }

    /**
     * 运行一段可能失败的本机读写，失败时记录说明并返回 null。
     *
     * 协程取消必须继续向上抛，否则会破坏取消语义。
     */
    private inline fun <T> guard(
        warnings: MutableList<String>,
        label: String,
        block: () -> T
    ): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        warnings += "$label：${describe(error)}"
        null
    }

    /** 读取插件设置项：未设置过时返回 [default]，已设置（即使是空串）则原样返回。 */
    private suspend fun stringSetting(path: String, default: String): String =
        runCatching { userDataRepository.stringUserData(path).get() }.getOrNull() ?: default

    /**
     * 读取布尔设置项。
     *
     * 宿主用字符串 `"true"`/`"false"` 存布尔值（见 `BooleanUserData`），未设置或值非法时返回
     * [default]。
     */
    private suspend fun booleanSetting(path: String, default: Boolean): Boolean {
        val raw = runCatching { userDataRepository.stringUserData(path).get() }.getOrNull()
            ?: return default
        val text = raw.trim()
        if (text.isEmpty()) return default
        return text.equals("true", ignoreCase = true)
    }

    /** 当前选中的后端 id；无法识别的值按 WebDAV 处理。 */
    private suspend fun selectedBackend(): String = normalizeBackend(
        stringSetting(PluginSettings.CLOUD_BACKEND, PluginSettings.DEFAULT_CLOUD_BACKEND)
    )

    /**
     * 组装远端配置。
     *
     * 只读取**当前后端**需要的字段：WebDAV 需要地址，GitHub 需要仓库与令牌；缺少必需字段时返回
     * null，调用方据此给出「未配置」提示。另一个后端的凭据不会被读取。
     */
    private suspend fun readConfiguration(): RemoteConfiguration? {
        val backend = selectedBackend()
        if (backend == PluginSettings.BACKEND_GITHUB) {
            val repository = stringSetting(
                PluginSettings.GITHUB_REPOSITORY,
                PluginSettings.DEFAULT_GITHUB_REPOSITORY
            )
            val token = stringSetting(PluginSettings.GITHUB_TOKEN, PluginSettings.DEFAULT_GITHUB_TOKEN)
            if (repository.isBlank() || token.isBlank()) return null
            return RemoteConfiguration(
                backend = backend,
                url = "",
                username = "",
                password = "",
                directory = "",
                repository = repository,
                token = token,
                branch = stringSetting(
                    PluginSettings.GITHUB_BRANCH,
                    PluginSettings.DEFAULT_GITHUB_BRANCH
                ).trim().ifBlank { PluginSettings.DEFAULT_GITHUB_BRANCH },
                githubDirectory = stringSetting(
                    PluginSettings.GITHUB_DIRECTORY,
                    PluginSettings.DEFAULT_GITHUB_DIRECTORY
                )
            )
        }

        val url = stringSetting(PluginSettings.WEBDAV_URL, PluginSettings.DEFAULT_WEBDAV_URL)
        if (url.isBlank()) return null
        return RemoteConfiguration(
            backend = backend,
            url = url,
            username = stringSetting(
                PluginSettings.WEBDAV_USERNAME,
                PluginSettings.DEFAULT_WEBDAV_USERNAME
            ),
            password = stringSetting(
                PluginSettings.WEBDAV_PASSWORD,
                PluginSettings.DEFAULT_WEBDAV_PASSWORD
            ),
            directory = stringSetting(
                PluginSettings.WEBDAV_DIRECTORY,
                PluginSettings.DEFAULT_WEBDAV_DIRECTORY
            ),
            repository = "",
            token = "",
            branch = "",
            githubDirectory = ""
        )
    }

    /**
     * 取（必要时重建）与当前配置匹配的远端客户端。
     *
     * 配置里带了后端 id，因此切换后端、换地址或换凭据都会重建客户端；重建时旧客户端会被关闭。
     */
    private fun remoteStoreFor(configuration: RemoteConfiguration): RemoteStore =
        synchronized(clientLock) {
            val existing = cachedStore
            if (existing != null && cachedConfiguration == configuration) {
                return@synchronized existing
            }
            existing?.close()
            val created = when (configuration.backend) {
                PluginSettings.BACKEND_GITHUB -> GitHubStore(
                    repository = configuration.repository,
                    token = configuration.token,
                    branch = configuration.branch,
                    directory = configuration.githubDirectory
                )

                else -> WebDavClient(
                    baseUrl = configuration.url,
                    username = configuration.username,
                    password = configuration.password,
                    directory = configuration.directory
                )
            }
            cachedStore = created
            cachedConfiguration = configuration
            created
        }

    /** 读取保留数量设置，越界值会被收敛到合法区间。 */
    private suspend fun keepSnapshotCount(): Int {
        val raw = stringSetting(
            PluginSettings.KEEP_SNAPSHOTS,
            PluginSettings.DEFAULT_KEEP_SNAPSHOTS.toString()
        )
        val parsed = raw.trim().toIntOrNull() ?: PluginSettings.DEFAULT_KEEP_SNAPSHOTS
        return parsed.coerceIn(MIN_KEEP_SNAPSHOTS, MAX_KEEP_SNAPSHOTS)
    }

    /** 列出远端目录并过滤出快照文件（最新在前）；列目录失败时返回空列表。 */
    private suspend fun listSnapshotEntries(store: RemoteStore): List<RemoteEntry> {
        val listed = store.list().asRemoteOutcome()
        return snapshotEntries(listed.value.orEmpty())
    }

    /** 从已列出的快照中删除超出保留数量的最旧部分，返回实际删除数量。 */
    private suspend fun pruneOldSnapshots(
        store: RemoteStore,
        entries: List<RemoteEntry>,
        keep: Int
    ): Int {
        var pruned = 0
        for (entry in entries.drop(keep)) {
            val deleted = store.delete(entry.name).asRemoteOutcome()
            if (deleted.failure == null) pruned++
        }
        return pruned
    }

    /**
     * 过滤出快照文件，最新在前。
     *
     * 只保留匹配 [SnapshotCodec.SNAPSHOT_FILE_NAME_PATTERN] 的文件，因此阅读记录等附带文件
     * 既不会被误认为快照，也不会被清理。
     */
    private fun snapshotEntries(entries: List<RemoteEntry>): List<RemoteEntry> = entries
        .filter { !it.isCollection && SnapshotCodec.SNAPSHOT_FILE_NAME_PATTERN.matches(it.name) }
        .sortedWith(
            compareByDescending<RemoteEntry> { it.lastModifiedEpochMillis ?: 0L }
                .thenByDescending { it.name }
        )

    /** 过滤并转换成对外的快照信息，最新在前。 */
    private fun snapshotInfos(entries: List<RemoteEntry>): List<SnapshotInfo> =
        snapshotEntries(entries).map {
            SnapshotInfo(
                name = it.name,
                size = it.size ?: 0L,
                lastModifiedEpochMillis = it.lastModifiedEpochMillis
            )
        }

    /**
     * 按设置决定是否写一份阅读记录 Markdown。
     *
     * 阅读记录是「附带产物」：写失败只记录 [warnings] 并返回 false，绝不影响快照本身的上传。
     *
     * @return 本次是否成功写入了阅读记录
     */
    private suspend fun writeReadingLog(
        store: RemoteStore,
        snapshot: CloudSnapshot,
        warnings: MutableList<String>
    ): Boolean {
        if (!booleanSetting(PluginSettings.WRITE_READING_LOG, PluginSettings.DEFAULT_WRITE_READING_LOG)) {
            return false
        }
        val content = ReadingLogBuilder.build(
            readingData = snapshot.readingData,
            bookshelves = snapshot.bookshelves,
            bookTitles = collectBookTitles(snapshot.readingData),
            generatedAt = LocalDateTime.now()
        )
        val result = store.put(READING_LOG_FILE_NAME, content.toByteArray(Charsets.UTF_8))
            .asRemoteOutcome()
        val failure = result.failure
        if (failure != null) {
            warnings += "阅读记录写入失败：${failure.message}"
            return false
        }
        return true
    }

    /**
     * 逐本解析书名。
     *
     * 每个书本 id 最多调用一次 [bookTitleProvider]；取不到或抛异常都按「没有书名」处理，
     * 由 [ReadingLogBuilder] 退回显示书本 id。
     */
    private suspend fun collectBookTitles(readingData: List<ReadingEntry>): Map<String, String> {
        if (readingData.isEmpty()) return emptyMap()
        val titles = LinkedHashMap<String, String>(readingData.size)
        val seen = HashSet<String>(readingData.size)
        for (entry in readingData) {
            if (!seen.add(entry.id)) continue
            val title = runCatching { bookTitleProvider(entry.id) }.getOrNull()
            if (!title.isNullOrBlank()) {
                titles[entry.id] = title
            }
        }
        return titles
    }

    /** 组装上传成功的状态文本，把阅读记录与降级警告一并告诉用户。 */
    private fun buildUploadStatus(
        fileName: String,
        snapshot: CloudSnapshot,
        pruned: Int,
        readingLogWritten: Boolean,
        warnings: List<String>
    ): String {
        val log = if (readingLogWritten) "，已写入 $READING_LOG_FILE_NAME" else ""
        val warning = if (warnings.isEmpty()) "" else "，注意：${warnings.joinToString("；")}"
        return "上传成功：$fileName（书架 ${snapshot.bookshelves.size}，" +
                "阅读进度 ${snapshot.readingData.size}，设置 ${snapshot.userData.size}，" +
                "清理旧快照 $pruned）$log$warning"
    }

    /** 写入一条本插件自己的状态字段；失败不影响同步结果，因此静默忽略（取消除外）。 */
    private suspend fun writeStatus(path: String, value: String) {
        try {
            userDataDao.insert(path, CLOUD_SETTINGS_GROUP, USER_DATA_TYPE_STRING, value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // 状态文本只用于界面展示，写失败不应让同步本身失败
        }
    }

    /** 生成不含任何凭据的环境标识，仅用于快照来源展示。 */
    private fun currentDeviceLabel(): String {
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "Android" }
        val hostPackage = runCatching { context.packageName }.getOrNull().orEmpty()
        val plugin = "wenku8plus ${PluginConstants.PLUGIN_VERSION_NAME}"
        return if (hostPackage.isEmpty()) "$model · $plugin" else "$model · $hostPackage · $plugin"
    }

    /** [Bookshelf] → 快照条目。 */
    private fun Bookshelf.toEntry(): BookshelfEntry = BookshelfEntry(
        id = id,
        name = name,
        sortType = sortType.key,
        sortReversed = sortReversed,
        autoCache = autoCache,
        systemUpdateReminder = systemUpdateReminder,
        allBookIds = allBookIds,
        pinnedBookIds = pinnedBookIds,
        updatedBookIds = updatedBookIds
    )

    /** 快照条目 → [Bookshelf]；无法识别的排序 key 由 [BookshelfSortType.map] 退化为默认排序。 */
    private fun BookshelfEntry.toBookshelf(): Bookshelf = Bookshelf(
        id = id,
        name = name,
        sortType = BookshelfSortType.map(sortType),
        sortReversed = sortReversed,
        autoCache = autoCache,
        systemUpdateReminder = systemUpdateReminder,
        allBookIds = allBookIds,
        pinnedBookIds = pinnedBookIds,
        updatedBookIds = updatedBookIds
    )

    /**
     * [UserReadingData] → 快照条目。
     *
     * 宿主 `getAllUserReadingData()` 不会把 `LocalDateTime.MIN`（表示“从未阅读”）转换成 null，
     * 这里统一转换，避免把 MIN 写进快照后在另一台设备上显示成公元元年。
     */
    private fun UserReadingData.toEntry(): ReadingEntry = ReadingEntry(
        id = id,
        lastReadTime = lastReadTime?.takeIf { it.isAfter(LocalDateTime.MIN) }?.toString(),
        totalReadTime = totalReadTime,
        readingProgress = readingProgress,
        lastReadChapterId = lastReadChapterId,
        lastReadChapterTitle = lastReadChapterTitle,
        currentChapterReadingProgressMap = currentChapterReadingProgressMap,
        maxChapterReadingProgressMap = maxChapterReadingProgressMap
    )

    /** 快照条目 → [UserReadingData]；时间解析失败按“未读过”处理，数值做安全收敛。 */
    private fun ReadingEntry.toReadingData(): UserReadingData = UserReadingData(
        id = id,
        lastReadTime = parseIsoDateTime(lastReadTime),
        totalReadTime = totalReadTime.coerceAtLeast(0),
        readingProgress = readingProgress.coerceIn(0f, 1f),
        lastReadChapterId = lastReadChapterId,
        lastReadChapterTitle = lastReadChapterTitle,
        currentChapterReadingProgressMap = currentChapterReadingProgressMap,
        maxChapterReadingProgressMap = maxChapterReadingProgressMap
    )

    /** 解析 ISO-8601 本地时间；空值与非法值都返回 null。 */
    private fun parseIsoDateTime(value: String?): LocalDateTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { LocalDateTime.parse(text) }.getOrNull()
    }

    /** 判断一条阅读进度是否完全为空：为空时跳过写库，避免给没读过的书创建空记录。 */
    private fun ReadingEntry.isEmptyEntry(): Boolean =
        totalReadTime <= 0 &&
                readingProgress <= 0f &&
                lastReadTime.isNullOrBlank() &&
                lastReadChapterId.isNullOrBlank() &&
                currentChapterReadingProgressMap.isEmpty() &&
                maxChapterReadingProgressMap.isEmpty()

    /** 取一段可读的异常说明；不含任何凭据。 */
    private fun describe(error: Throwable): String {
        val text = error.message?.trim().orEmpty()
        return if (text.isEmpty()) error.javaClass.simpleName else text
    }
}

package io.github.lnrplugin.wenku8plus.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 云端快照的格式版本。
 *
 * 该版本号同时写入文件头的第 5 个字节和 [CloudSnapshot.version]：文件头用于在解压之前就
 * 拒绝外来文件，JSON 中的字段用于合并时判断新旧。
 */
const val CLOUD_SNAPSHOT_FORMAT_VERSION: Int = 1

/**
 * 用户数据的类型名：普通字符串。
 *
 * 取值与宿主 `UserDataEntity.type` 一致，快照按该字符串决定合并规则。
 */
const val USER_DATA_TYPE_STRING: String = "String"

/**
 * 用户数据的类型名：字符串列表（宿主以英文逗号拼接存储）。
 *
 * 宿主 `UserDataEntity.merge` 对该类型做并集去重，本文件的 [SnapshotCodec.mergeUserDataEntry]
 * 复刻同一规则。
 */
const val USER_DATA_TYPE_STRING_LIST: String = "StringList"

/** 文件头魔数：ASCII 字符 `LNRW`（LightNovelReader WebDAV）。 */
private val SNAPSHOT_MAGIC: ByteArray = byteArrayOf(0x4C, 0x4E, 0x52, 0x57)

/** 文件头长度：4 字节魔数 + 1 字节格式版本。 */
private const val SNAPSHOT_HEADER_SIZE: Int = 5

/** gzip 数据的最小长度：10 字节头 + 8 字节尾。 */
private const val MIN_GZIP_SIZE: Int = 18

/** 快照文件名中的时间戳格式。 */
private val FILE_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

/**
 * 快照专用的 Json 配置。
 *
 * - `encodeDefaults = true`：字段即使等于默认值也写出，保证不同版本的插件读到确定的字段集合；
 * - `ignoreUnknownKeys = true`：新版本写入的额外字段不会让旧版本解析失败；
 * - `prettyPrint = false`：随后要 gzip，无需浪费体积。
 */
private val SNAPSHOT_JSON: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
}

/**
 * 快照文件格式错误。
 *
 * 只在解码阶段抛出，[SnapshotCodec.decode] 保证抛出的一定是本类型；调用方
 * （[CloudSyncEngine]）会把它转换成 [CloudSyncError]。
 *
 * @param message 面向用户的中文说明
 * @param cause 原始异常，可能为 null
 */
class SnapshotFormatException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 一份云端快照。
 *
 * 快照只覆盖插件通过 `io.nightfish.lightnovelreader.api` 能读到的三类数据：书架、阅读进度、
 * 以及本插件自己写入宿主的设置项。宿主没有向插件开放“枚举任意用户数据行 / 已缓存书籍信息 /
 * 章节正文”的 Api，因此这**不是**完整数据库备份，具体限制见 [CloudSyncEngine] 的类说明。
 *
 * @property version 快照格式版本，当前为 [CLOUD_SNAPSHOT_FORMAT_VERSION]
 * @property createdAtEpochMillis 生成快照时的 Unix 时间戳（毫秒）
 * @property deviceLabel 生成快照的设备标识，仅用于展示，不含任何凭据
 * @property bookshelves 书架列表
 * @property readingData 阅读进度列表
 * @property userData 插件设置项列表
 */
@Serializable
data class CloudSnapshot(
    val version: Int = CLOUD_SNAPSHOT_FORMAT_VERSION,
    val createdAtEpochMillis: Long = 0L,
    val deviceLabel: String = "",
    val bookshelves: List<BookshelfEntry> = emptyList(),
    val readingData: List<ReadingEntry> = emptyList(),
    val userData: List<UserDataEntry> = emptyList()
)

/**
 * 快照中的一条书架记录，字段与
 * [io.nightfish.lightnovelreader.api.bookshelf.Bookshelf] 一一对应。
 *
 * @property id 书架 id（宿主数据库主键，恢复时原样写回，因此两台设备的书架 id 需要一致）
 * @property name 书架名称
 * @property sortType 排序方式的 key 字符串，取值见
 * [io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType]
 * @property sortReversed 是否反向排序
 * @property autoCache 是否自动缓存
 * @property systemUpdateReminder 是否通过系统通知提醒更新
 * @property allBookIds 书架中的全部书本 id
 * @property pinnedBookIds 置顶的书本 id
 * @property updatedBookIds 有新章节更新的书本 id
 */
@Serializable
data class BookshelfEntry(
    val id: Int,
    val name: String = "",
    val sortType: String = "default",
    val sortReversed: Boolean = false,
    val autoCache: Boolean = false,
    val systemUpdateReminder: Boolean = false,
    val allBookIds: List<String> = emptyList(),
    val pinnedBookIds: List<String> = emptyList(),
    val updatedBookIds: List<String> = emptyList()
)

/**
 * 快照中的一条阅读进度记录，字段与
 * [io.nightfish.lightnovelreader.api.book.UserReadingData] 完全一致。
 *
 * 时间刻意使用 ISO-8601 字符串，而不是宿主实体里的 `java.time` 类型：这样快照的字节内容
 * 不依赖 kotlinx.serialization 对 `java.time` 的支持程度，跨版本更稳。
 *
 * @property id 书本 id
 * @property lastReadTime 最后阅读时间（ISO-8601，未读过时为 null）
 * @property totalReadTime 累计阅读时长（秒）
 * @property readingProgress 整本书的阅读进度（0.0~1.0）
 * @property lastReadChapterId 最后阅读的章节 id
 * @property lastReadChapterTitle 最后阅读的章节标题
 * @property currentChapterReadingProgressMap 各章节当前进度，key 为章节 id
 * @property maxChapterReadingProgressMap 各章节历史最高进度，key 为章节 id
 */
@Serializable
data class ReadingEntry(
    val id: String,
    val lastReadTime: String? = null,
    val totalReadTime: Int = 0,
    val readingProgress: Float = 0f,
    val lastReadChapterId: String? = null,
    val lastReadChapterTitle: String? = null,
    val currentChapterReadingProgressMap: Map<String, Float> = emptyMap(),
    val maxChapterReadingProgressMap: Map<String, Float> = emptyMap()
)

/**
 * 快照中的一条用户数据，字段与宿主 `UserDataEntity` 的四个列一一对应。
 *
 * 这里只描述线格式；插件代码不得引用宿主实体类本身，宿主实体不在插件类路径上。
 *
 * @property path 数据完整路径，如 `plugin.wenku8plus.cloud.webdavUrl`
 * @property group 数据所属组，本插件的设置固定为 `plugin.wenku8plus.cloud`
 * @property type 数据类型名，见 [USER_DATA_TYPE_STRING] / [USER_DATA_TYPE_STRING_LIST]
 * @property value 序列化后的字符串值
 */
@Serializable
data class UserDataEntry(
    val path: String,
    val group: String = "",
    val type: String = USER_DATA_TYPE_STRING,
    val value: String = ""
)

/**
 * 云端快照的编解码与合并规则。
 *
 * 线格式为：`"LNRW"`（4 字节） + 格式版本（1 字节） + gzip(UTF-8 JSON)。
 * 先校验文件头再解压，外来文件会以明确的中文错误被拒绝。
 *
 * 合并规则整体对齐宿主自己的导入逻辑（`UserDataEntity.merge`、`BookshelfEntity.merge`、
 * `UserReadingDataEntity.merge`）：只增不减、只进不退。
 */
object SnapshotCodec {

    /**
     * 云端快照文件名必须匹配的模式：`lnr-YYYYMMDD-HHmmss.json.gz`。
     *
     * 列出目录时用它把快照与目录里其它文件区分开。
     */
    val SNAPSHOT_FILE_NAME_PATTERN: Regex = Regex("^lnr-\\d{8}-\\d{6}\\.json\\.gz$")

    /**
     * 按约定生成快照文件名。
     *
     * @param timestamp 快照生成时间
     *
     * @return 形如 `lnr-20261003-195731.json.gz` 的文件名
     */
    fun snapshotFileName(timestamp: LocalDateTime): String =
        "lnr-" + timestamp.format(FILE_NAME_FORMATTER) + ".json.gz"

    /**
     * 把快照编码为可上传的字节数组。
     *
     * @param snapshot 需要编码的快照
     *
     * @return 带 `LNRW` 文件头的 gzip 压缩字节
     */
    fun encode(snapshot: CloudSnapshot): ByteArray {
        val jsonBytes = SNAPSHOT_JSON
            .encodeToString(CloudSnapshot.serializer(), snapshot)
            .encodeToByteArray()
        val output = ByteArrayOutputStream(jsonBytes.size + 256)
        output.write(SNAPSHOT_MAGIC)
        output.write(CLOUD_SNAPSHOT_FORMAT_VERSION)
        GZIPOutputStream(output).use { gzip ->
            gzip.write(jsonBytes)
        }
        return output.toByteArray()
    }

    /**
     * 解码云端快照。
     *
     * @param bytes 从 WebDAV 下载到的原始字节
     *
     * @return 解析出的快照
     *
     * @throws SnapshotFormatException 文件头不正确、版本不支持、gzip 损坏或 JSON 非法时抛出，
     * 异常信息可直接展示给用户
     */
    fun decode(bytes: ByteArray): CloudSnapshot {
        if (bytes.size < SNAPSHOT_HEADER_SIZE + MIN_GZIP_SIZE) {
            throw SnapshotFormatException("文件太小，不是本插件生成的快照")
        }
        for (index in SNAPSHOT_MAGIC.indices) {
            if (bytes[index] != SNAPSHOT_MAGIC[index]) {
                throw SnapshotFormatException("文件缺少 LNRW 标识，不是本插件生成的快照")
            }
        }
        val version = bytes[SNAPSHOT_MAGIC.size].toInt() and 0xFF
        if (version != CLOUD_SNAPSHOT_FORMAT_VERSION) {
            throw SnapshotFormatException(
                "不支持的快照格式版本：$version（本插件支持 $CLOUD_SNAPSHOT_FORMAT_VERSION）"
            )
        }
        val jsonBytes = try {
            GZIPInputStream(
                ByteArrayInputStream(bytes, SNAPSHOT_HEADER_SIZE, bytes.size - SNAPSHOT_HEADER_SIZE)
            ).use { input -> input.readBytes() }
        } catch (error: Exception) {
            throw SnapshotFormatException("快照数据解压失败，文件可能已损坏", error)
        }
        if (jsonBytes.isEmpty()) {
            throw SnapshotFormatException("快照内容为空")
        }
        return try {
            SNAPSHOT_JSON.decodeFromString(CloudSnapshot.serializer(), jsonBytes.decodeToString())
        } catch (error: Exception) {
            throw SnapshotFormatException("快照内容无法解析，文件可能已损坏", error)
        }
    }

    /**
     * 合并两份快照，结果可以安全地再上传。
     *
     * 规则：
     * - 书架按 id 对齐：名称/排序等描述性字段以 [remote] 为准，三个书本 id 列表取并集去重；
     * - 阅读进度按书本 id 对齐：`totalReadTime`、`readingProgress` 取较大值，
     *   `maxChapterReadingProgressMap` 逐 key 取较大值，最后章节信息取时间较晚的一方；
     * - 用户数据按 path 对齐：`StringList` 走并集（复刻宿主规则），其它类型以 [remote] 为准；
     * - 只在一边出现的条目原样保留，因此合并不会删除任何一方的数据。
     *
     * @param local 本机快照
     * @param remote 云端快照
     *
     * @return 合并后的快照
     */
    fun merge(local: CloudSnapshot, remote: CloudSnapshot): CloudSnapshot = CloudSnapshot(
        version = maxOf(local.version, remote.version),
        createdAtEpochMillis = maxOf(local.createdAtEpochMillis, remote.createdAtEpochMillis),
        deviceLabel = remote.deviceLabel.ifBlank { local.deviceLabel },
        bookshelves = mergeByKey(
            local.bookshelves,
            remote.bookshelves,
            { entry -> entry.id },
            { localEntry, remoteEntry -> mergeBookshelfEntry(localEntry, remoteEntry) }
        ),
        readingData = mergeByKey(
            local.readingData,
            remote.readingData,
            { entry -> entry.id },
            { localEntry, remoteEntry -> mergeReadingEntry(localEntry, remoteEntry) }
        ),
        userData = mergeByKey(
            local.userData,
            remote.userData,
            { entry -> entry.path },
            { localEntry, remoteEntry -> mergeUserDataEntry(localEntry, remoteEntry) }
        )
    )

    /**
     * 合并两条书架记录。
     *
     * 与宿主 `BookshelfEntity.merge` 一致：[remote] 覆盖名称与各项开关，书本 id 列表取并集去重。
     *
     * @param local 本机记录
     * @param remote 云端记录
     *
     * @return 合并后的记录
     */
    fun mergeBookshelfEntry(local: BookshelfEntry, remote: BookshelfEntry): BookshelfEntry =
        remote.copy(
            allBookIds = (local.allBookIds + remote.allBookIds).distinct(),
            pinnedBookIds = (local.pinnedBookIds + remote.pinnedBookIds).distinct(),
            updatedBookIds = (local.updatedBookIds + remote.updatedBookIds).distinct()
        )

    /**
     * 合并两条阅读进度记录，保证进度只进不退。
     *
     * 与宿主 `UserReadingDataEntity.merge` 的差别只有一处：宿主无条件采用新记录的最后章节信息，
     * 这里改成“最后阅读时间较晚的一方”，避免用旧快照恢复时把当前阅读位置倒退回去。
     *
     * @param local 本机记录
     * @param remote 云端记录
     *
     * @return 合并后的记录
     */
    fun mergeReadingEntry(local: ReadingEntry, remote: ReadingEntry): ReadingEntry {
        val remoteIsNewer = compareIsoDateTime(local.lastReadTime, remote.lastReadTime) <= 0
        val winner = if (remoteIsNewer) remote else local
        val loser = if (remoteIsNewer) local else remote

        val currentProgress = LinkedHashMap<String, Float>()
        currentProgress.putAll(local.currentChapterReadingProgressMap)
        for ((chapterId, progress) in remote.currentChapterReadingProgressMap) {
            val existing = currentProgress[chapterId]
            // 与 maxChapterReadingProgressMap 一样逐章取较大值：当前章节进度同样是
            // “读到哪儿了”，若采用时间较晚一方的值，较新的云端快照反而可能让本机的
            // 当前进度倒退（例如在另一台设备上往回翻了几页）。
            currentProgress[chapterId] = if (existing == null) progress else maxOf(existing, progress)
        }

        val maxProgress = LinkedHashMap<String, Float>()
        maxProgress.putAll(local.maxChapterReadingProgressMap)
        for ((chapterId, progress) in remote.maxChapterReadingProgressMap) {
            val existing = maxProgress[chapterId]
            maxProgress[chapterId] = if (existing == null) progress else maxOf(existing, progress)
        }

        return ReadingEntry(
            id = winner.id,
            lastReadTime = if (remoteIsNewer) {
                remote.lastReadTime ?: local.lastReadTime
            } else {
                local.lastReadTime ?: remote.lastReadTime
            },
            totalReadTime = maxOf(local.totalReadTime, remote.totalReadTime),
            readingProgress = maxOf(local.readingProgress, remote.readingProgress),
            lastReadChapterId = winner.lastReadChapterId ?: loser.lastReadChapterId,
            lastReadChapterTitle = winner.lastReadChapterTitle ?: loser.lastReadChapterTitle,
            currentChapterReadingProgressMap = currentProgress,
            maxChapterReadingProgressMap = maxProgress
        )
    }

    /**
     * 合并两条用户数据，复刻宿主 `UserDataEntity.merge` 的规则。
     *
     * `StringList` 类型做并集去重（逗号拼接，过滤空串），其它类型以 [remote] 为准；
     * 两边类型不一致时也以 [remote] 为准，避免把非列表值当列表拼接。
     *
     * @param local 本机记录（只用于读取类型与旧值，`path` 必须与 [remote] 相同）
     * @param remote 云端记录
     *
     * @return 合并后的记录
     */
    fun mergeUserDataEntry(local: UserDataEntry, remote: UserDataEntry): UserDataEntry =
        if (local.type == USER_DATA_TYPE_STRING_LIST && remote.type == USER_DATA_TYPE_STRING_LIST) {
            local.copy(value = unionStringList(local.value, remote.value))
        } else {
            remote
        }

    /**
     * 按 key 对齐合并两个列表：两边都有的交给 [merge]，只有一边的原样保留。
     *
     * @param local 本机列表，顺序被保留
     * @param remote 云端列表，只追加本机没有的 key
     * @param key 取 key 的函数
     * @param merge 同 key 条目的合并函数
     *
     * @return 去重后的合并列表
     */
    private fun <T, K> mergeByKey(
        local: List<T>,
        remote: List<T>,
        key: (T) -> K,
        merge: (T, T) -> T
    ): List<T> {
        val remoteByKey = remote.associateBy(key)
        val seen = HashSet<K>()
        val merged = ArrayList<T>(local.size + remote.size)
        for (item in local) {
            if (!seen.add(key(item))) continue
            val other = remoteByKey[key(item)]
            merged += if (other == null) item else merge(item, other)
        }
        for (item in remote) {
            if (seen.add(key(item))) merged += item
        }
        return merged
    }

    /** 复刻宿主对 `StringList` 的处理：逗号切分、去空、去重、再拼回。 */
    private fun unionStringList(local: String, remote: String): String =
        (local.split(",") + remote.split(","))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")

    /**
     * 比较两个 ISO-8601 本地时间字符串。
     *
     * `LocalDateTime.toString()` 会省略为零的秒/纳秒（`2026-10-03T19:57` 与
     * `2026-10-03T19:57:31`），直接比字符串在个别情况下会出错，因此优先解析成时间再比较；
     * 解析失败时退回字符串比较，保证坏数据不会让合并抛异常。
     */
    private fun compareIsoDateTime(local: String?, remote: String?): Int {
        if (local == remote) return 0
        val localText = local.orEmpty()
        val remoteText = remote.orEmpty()
        if (localText.isBlank()) return -1
        if (remoteText.isBlank()) return 1
        val localTime = runCatching { LocalDateTime.parse(localText) }.getOrNull()
        val remoteTime = runCatching { LocalDateTime.parse(remoteText) }.getOrNull()
        return if (localTime != null && remoteTime != null) {
            localTime.compareTo(remoteTime)
        } else {
            localText.compareTo(remoteText)
        }
    }
}

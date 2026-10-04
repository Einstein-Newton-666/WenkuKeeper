package io.github.lnrplugin.wenku8plus.cloud.remote

import com.github.michaelbull.result.Result
import io.github.lnrplugin.wenku8plus.cloud.CloudSyncError
import io.github.lnrplugin.wenku8plus.cloud.CloudSyncErrorKind

/**
 * 云端存储的抽象。
 *
 * 云同步引擎只依赖这个接口，因此「存到 WebDAV」和「存到 GitHub 仓库」是同一套逻辑的两种
 * 后端实现，快照格式、合并策略、保留数量策略都完全共用。
 *
 * 约定：
 * - 所有方法都是挂起函数，内部自行切到 IO 线程，调用方不需要再包一层；
 * - 所有方法都不抛异常，失败通过 `Result` 返回 [RemoteError]；
 * - 文件路径一律是相对根目录的**文件名**，不含目录分隔符；目录由远端配置决定。
 *
 * @since 1.1.0
 */
interface RemoteStore {

    /**
     * 确保远端目录存在，不存在则创建。
     *
     * 对不支持目录概念的后端（例如 GitHub 的 Contents API）而言，这一步是校验仓库与分支
     * 是否可写，因此仍然必须实现。
     *
     * @return 成功时不返回内容，失败时返回带用户可读说明的错误
     */
    suspend fun ensureDirectory(): Result<Unit, RemoteError>

    /**
     * 写入（覆盖）一个文件。
     *
     * @param name 相对根目录的文件名
     * @param bytes 文件内容
     *
     * @return 成功时不返回内容，失败时返回错误
     */
    suspend fun put(name: String, bytes: ByteArray): Result<Unit, RemoteError>

    /**
     * 读取一个文件。
     *
     * @param name 相对根目录的文件名
     *
     * @return 文件字节；文件不存在时返回 [RemoteErrorKind.NOT_FOUND] 失败
     */
    suspend fun get(name: String): Result<ByteArray, RemoteError>

    /**
     * 列出根目录下的条目。
     *
     * 只需要返回文件，不需要递归；不支持目录的后端返回仓库根目录下的文件即可。
     *
     * @return 条目列表；目录尚不存在时返回 [RemoteErrorKind.NOT_FOUND] 失败
     */
    suspend fun list(): Result<List<RemoteEntry>, RemoteError>

    /**
     * 删除一个文件。
     *
     * @param name 相对根目录的文件名
     *
     * @return 成功时不返回内容，失败时返回错误
     */
    suspend fun delete(name: String): Result<Unit, RemoteError>

    /**
     * 探测后端是否可访问，用于「测试连接」。
     *
     * @return 可访问时返回 true
     */
    suspend fun probe(): Result<Boolean, RemoteError>

    /** 释放底层网络资源。关闭后再次调用其它方法应当能自动重建。 */
    fun close()
}

/**
 * 云端条目的通用表示。
 *
 * @property name 文件名（不含目录）
 * @property isCollection 是否为目录/文件夹
 * @property size 字节数，未知时为 null
 * @property lastModifiedEpochMillis 最后修改时间（epoch 毫秒），未知时为 null
 */
data class RemoteEntry(
    val name: String,
    val isCollection: Boolean,
    val size: Long?,
    val lastModifiedEpochMillis: Long?
)

/**
 * 远端错误分类。
 *
 * 与 `WebDavErrorKind` 的取值一一对应，这样两个后端可以映射到同一套
 * [CloudSyncError]，界面上的提示与重试策略也就不必区分后端。
 */
enum class RemoteErrorKind {
    /** 配置为空或格式非法。 */
    INVALID_URL,

    /** 认证失败。 */
    UNAUTHORIZED,

    /** 目标不存在。 */
    NOT_FOUND,

    /** 网络层失败：无法连接、超时、TLS 错误等。 */
    NETWORK,

    /** 服务器返回了非预期状态码。 */
    HTTP,

    /** 协议层失败：响应格式不符合预期。 */
    PROTOCOL
}

/**
 * 远端错误。
 *
 * @property kind 错误分类
 * @property message 面向用户的说明，**不得包含凭据**
 * @property status HTTP 状态码，非 HTTP 错误时为 null
 * @property cause 原始异常，便于排查
 */
data class RemoteError(
    val kind: RemoteErrorKind,
    val message: String,
    val status: Int? = null,
    val cause: Throwable? = null
)

/**
 * 把 [RemoteErrorKind] 映射为云同步的错误分类。
 *
 * 有了它，[io.github.lnrplugin.wenku8plus.cloud.CloudSyncEngine] 就不必知道后端是哪一种：
 * WebDAV 与 GitHub 两个实现都把自己的失败折叠成 [RemoteError]，引擎只认这一套。
 *
 * @return 对应的云同步错误
 */
fun RemoteError.toCloudSyncError(): CloudSyncError = when (kind) {
    RemoteErrorKind.INVALID_URL -> CloudSyncError.InvalidConfiguration(message)
    RemoteErrorKind.UNAUTHORIZED -> CloudSyncError.Unauthorized(message)
    RemoteErrorKind.NOT_FOUND -> CloudSyncError.NotFound(message)
    RemoteErrorKind.NETWORK -> CloudSyncError.Network(message, cause)
    RemoteErrorKind.HTTP -> status?.let { CloudSyncError.Http(it, message, cause) }
        ?: CloudSyncError.Protocol(message, cause)

    RemoteErrorKind.PROTOCOL -> CloudSyncError.Protocol(message, cause)
}

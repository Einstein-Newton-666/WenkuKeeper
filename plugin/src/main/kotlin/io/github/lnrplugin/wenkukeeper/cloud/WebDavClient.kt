package io.github.lnrplugin.wenkukeeper.cloud

import android.util.Base64
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import com.github.michaelbull.result.mapError
import io.github.lnrplugin.wenkukeeper.PluginConstants
import io.github.lnrplugin.wenkukeeper.cloud.remote.RemoteEntry
import io.github.lnrplugin.wenkukeeper.cloud.remote.RemoteError
import io.github.lnrplugin.wenkukeeper.cloud.remote.RemoteErrorKind
import io.github.lnrplugin.wenkukeeper.cloud.remote.RemoteStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** WebDAV 请求中的 `Depth` 头名称（RFC 4918，非 HTTP 标准头）。 */
private const val DEPTH_HEADER = "Depth"

/** `Depth: 0`，只取资源自身。 */
private const val DEPTH_ZERO = "0"

/** `Depth: 1`，取集合自身与直接子节点，用于列目录。 */
private const val DEPTH_ONE = "1"

/** PROPFIND 请求体与响应的 Content-Type。 */
private const val XML_CONTENT_TYPE = "application/xml; charset=utf-8"

/** 上传快照时使用的 Content-Type。 */
private const val OCTET_STREAM_CONTENT_TYPE = "application/octet-stream"

/** 只读请求最多跟随的重定向次数。 */
private const val MAX_REDIRECTS = 3

/** 需要跟随/需要报错的 HTTP 重定向状态码。 */
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/** 客户端 UA；带上插件版本，便于服务端排查。不包含任何账号信息。 */
private val USER_AGENT = "LightNovelReader-WenkuKeeper/${PluginConstants.PLUGIN_VERSION_NAME} (Android)"

/** 目录列表中请求的属性集合：仅取判断快照所需的三个属性，响应体最小。 */
private val PROPFIND_BODY = (
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
                "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/>" +
                "</D:prop></D:propfind>"
        ).encodeToByteArray()

/**
 * 已经过百分号编码的路径片段。
 *
 * 用来避免把用户已经写好的 `%20` 再编码成 `%2520`。
 */
private val ENCODED_SEGMENT = Regex("%[0-9A-Fa-f]{2}")

/**
 * 匹配一个 `<D:response>…</D:response>` 块，同时兼容带命名空间前缀与不带前缀两种写法。
 *
 * 之所以使用正则而不是 XML 解析器：服务端使用的命名空间前缀无法预知（`D:`、`d:`、`lp1:`…），
 * 而 jsoup 的 CSS 命名空间选择器对前缀的处理并不等价，正则对这两种形态都稳定。
 */
private val RESPONSE_BLOCK = Regex(
    "<(?:[A-Za-z0-9._-]+:)?response\\b[^>]*>.*?</(?:[A-Za-z0-9._-]+:)?response>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** 从 `<D:response>` 块中取出 `href`。 */
private val HREF_TAG = Regex(
    "<(?:[A-Za-z0-9._-]+:)?href[^>]*>(.*?)</(?:[A-Za-z0-9._-]+:)?href>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** 从 `<D:response>` 块中取出 `getcontentlength`。 */
private val CONTENT_LENGTH_TAG = Regex(
    "<(?:[A-Za-z0-9._-]+:)?getcontentlength[^>]*>(.*?)</(?:[A-Za-z0-9._-]+:)?getcontentlength>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** 从 `<D:response>` 块中取出 `getlastmodified`。 */
private val LAST_MODIFIED_TAG = Regex(
    "<(?:[A-Za-z0-9._-]+:)?getlastmodified[^>]*>(.*?)</(?:[A-Za-z0-9._-]+:)?getlastmodified>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** 判断 `<D:response>` 块里的 `resourcetype` 是否声明了 `<D:collection>`。 */
private val COLLECTION_TAG = Regex(
    "<(?:[A-Za-z0-9._-]+:)?collection(?:\\s[^>]*)?/?>",
    setOf(RegexOption.IGNORE_CASE)
)

/** CDATA 起始标记。 */
private const val CDATA_START = "<![CDATA["

/** CDATA 结束标记。 */
private const val CDATA_END = "]]>"

/**
 * WebDAV 调用失败的分类。
 *
 * 供上层决定是提示用户改配置、改密码，还是稍后重试。
 */
enum class WebDavErrorKind {
    /** 地址为空或格式非法。 */
    INVALID_URL,

    /** 认证失败（HTTP 401）。 */
    UNAUTHORIZED,

    /** 目标不存在（HTTP 404）。 */
    NOT_FOUND,

    /** 网络层失败：无法连接、超时、TLS 错误等。 */
    NETWORK,

    /** 服务器返回了非预期状态码。 */
    HTTP,

    /** 协议层失败：响应无法解析、重定向无法跟随等。 */
    PROTOCOL
}

/**
 * WebDAV 操作的失败结果。
 *
 * [message] 是可直接展示给用户的中文描述，且保证不含用户名与密码。
 *
 * @property kind 失败分类
 * @property message 面向用户的说明
 * @property status HTTP 状态码，非 HTTP 层失败时为 null
 * @property cause 原始异常，便于排查；可能为 null
 */
data class WebDavError(
    val kind: WebDavErrorKind,
    val message: String,
    val status: Int? = null,
    val cause: Throwable? = null
)

/**
 * WebDAV 目录中的一项。
 *
 * @property name 资源名（URL 解码后的最后一段路径）
 * @property href 服务器返回的原始 `href`，可能已做百分号编码
 * @property isCollection 是否为集合（目录）
 * @property size 文件大小（字节），服务器未提供时为 null
 * @property lastModifiedEpochMillis 最后修改时间（Unix 毫秒），无法解析时为 null
 */
data class WebDavEntry(
    val name: String,
    val href: String,
    val isCollection: Boolean,
    val size: Long?,
    val lastModifiedEpochMillis: Long?
)

/**
 * 极简 WebDAV 客户端，覆盖快照同步所需的六个动作。
 *
 * 设计要点：
 * - 基于 Ktor + CIO 引擎，全部方法都在 [Dispatchers.IO] 上执行，可直接从协程调用；
 * - 默认发送 HTTP Basic 认证头；若服务器返回 401 且带 `WWW-Authenticate: Digest`，
 *   按 RFC 2617 计算 MD5/qop=auth 摘要并重试一次（部分 WebDAV 服务商仍只支持 Digest）；
 * - `PUT` 绝不跟随 301/302：避免把快照写到未知地址，遇到重定向直接报错；
 *   列目录/下载/探测等只读请求会自行跟随重定向（最多 [MAX_REDIRECTS] 次）；
 * - 用户名与密码只存在于内存和 `Authorization` 头中，不会出现在日志、异常信息或 URL 里。
 *
 * @param baseUrl WebDAV 根地址，例如 `https://dav.jianguoyun.com/dav/`；不允许内嵌账号密码
 * @param username 账号名，可为空（匿名访问）
 * @param password 密码或应用专用密码，可为空
 * @param directory 相对 [baseUrl] 的快照目录，可多级，例如 `LightNovelReader/backup`
 */
class WebDavClient(
    baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    directory: String = ""
) : RemoteStore {

    /** 解析后的根地址；地址非法时为 null，此时所有操作都返回 [invalidUrlError]。 */
    private val root: WebDavRoot? = parseBaseUrl(baseUrl)

    /** 地址非法时统一返回该错误，避免在构造函数里抛异常。 */
    private val invalidUrlError = WebDavError(
        kind = WebDavErrorKind.INVALID_URL,
        message = "WebDAV 地址无效：请填写以 http:// 或 https:// 开头、且不要把用户名密码写进地址的完整地址"
    )

    /** 快照目录的各级片段，均已做百分号编码。 */
    private val directorySegments: List<String> = directory
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { encodePathSegment(it) }

    /** 预计算的 Basic 认证头；未配置账号时不发送该头。 */
    private val basicAuthorization: String? =
        if (username.isEmpty() && password.isEmpty()) {
            null
        } else {
            val token = "$username:$password".toByteArray(Charsets.UTF_8)
            "Basic " + Base64.encodeToString(token, Base64.NO_WRAP)
        }

    /**
     * 共享的 Ktor 客户端。
     *
     * `expectSuccess = false`：401/404 等状态码需要自行读取响应（Digest 挑战就在 401 响应里），
     * 不能让 Ktor 直接抛异常。`followRedirects = false`：只读请求由 [send] 手动跟随，
     * `PUT` 则严格不跟随。
     */
    private val client: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        followRedirects = false
        install(UserAgent) {
            agent = USER_AGENT
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    /**
     * 列出快照目录下的直接子项（PROPFIND，Depth: 1）。
     *
     * @param path 相对配置目录的子路径，留空表示目录本身
     *
     * @return 子项列表；目录不存在时返回 [WebDavErrorKind.NOT_FOUND] 失败
     */
    private suspend fun listEntries(path: String): Result<List<WebDavEntry>, WebDavError> =
        withContext(Dispatchers.IO) {
            val webDavRoot = root ?: return@withContext Err(invalidUrlError)
            if (!isSafeRelativePath(path)) {
                return@withContext Err(invalidNameError(path))
            }
            val target = collectionTarget(webDavRoot, path)
            val response = send(
                method = METHOD_PROPFIND,
                target = target,
                body = PROPFIND_BODY,
                depth = DEPTH_ONE,
                contentType = XML_CONTENT_TYPE,
                followRedirect = true
            )
            when {
                response.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
                response.status == HTTP_FORBIDDEN -> Err(httpError(response, "服务器拒绝访问该目录"))
                response.status == HTTP_NOT_FOUND -> Err(
                    WebDavError(
                        kind = WebDavErrorKind.NOT_FOUND,
                        message = "云端目录不存在：${target.uriPath}",
                        status = HTTP_NOT_FOUND
                    )
                )

                response.status in REDIRECT_STATUSES -> Err(redirectError(target, response.status))
                response.status.isSuccessCode() -> Ok(parseEntries(response.body, target.uriPath))
                else -> Err(httpError(response, "读取云端目录失败"))
            }
        }

    /**
     * 上传一个文件。
     *
     * 301/302/303/307/308 一律视为失败：这类重定向通常意味着地址填错了，静默跟随可能把数据
     * 写到意料之外的位置。
     *
     * @param name 相对配置目录的文件名（可含 `/`，不存在时会失败）
     * @param bytes 文件内容
     *
     * @return 成功时返回 [Unit]
     */
    private suspend fun putInternal(name: String, bytes: ByteArray): Result<Unit, WebDavError> =
        withContext(Dispatchers.IO) {
            val webDavRoot = root ?: return@withContext Err(invalidUrlError)
            if (name.isBlank() || !isSafeRelativePath(name)) {
                return@withContext Err(invalidNameError(name))
            }
            val target = resourceTarget(webDavRoot, name)
            val response = send(
                method = METHOD_PUT,
                target = target,
                body = bytes,
                contentType = OCTET_STREAM_CONTENT_TYPE,
                followRedirect = false
            )
            when {
                response.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
                response.status in REDIRECT_STATUSES -> Err(putRedirectError(target, response.status))
                response.status.isSuccessCode() -> Ok(Unit)
                else -> Err(httpError(response, "上传失败"))
            }
        }

    /**
     * 下载一个文件。
     *
     * @param name 相对配置目录的文件名
     *
     * @return 文件原始字节
     */
    private suspend fun getInternal(name: String): Result<ByteArray, WebDavError> = withContext(Dispatchers.IO) {
        val webDavRoot = root ?: return@withContext Err(invalidUrlError)
        if (name.isBlank() || !isSafeRelativePath(name)) {
            return@withContext Err(invalidNameError(name))
        }
        val target = resourceTarget(webDavRoot, name)
        val response = send(
            method = METHOD_GET,
            target = target,
            followRedirect = true
        )
        when {
            response.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
            response.status == HTTP_NOT_FOUND -> Err(
                WebDavError(
                    kind = WebDavErrorKind.NOT_FOUND,
                    message = "云端文件不存在：$name",
                    status = HTTP_NOT_FOUND
                )
            )

            response.status in REDIRECT_STATUSES -> Err(redirectError(target, response.status))
            response.status.isSuccessCode() -> Ok(response.body)
            else -> Err(httpError(response, "下载失败"))
        }
    }

    /**
     * 创建集合（MKCOL）。
     *
     * 返回 405 表示集合已存在，同样视为成功。多级目录请使用 [ensureDirectory]。
     *
     * @param name 相对配置目录的集合路径
     *
     * @return 成功时返回 [Unit]
     */
    private suspend fun mkcolInternal(name: String): Result<Unit, WebDavError> = withContext(Dispatchers.IO) {
        val webDavRoot = root ?: return@withContext Err(invalidUrlError)
        if (name.isBlank() || !isSafeRelativePath(name)) {
            return@withContext Err(invalidNameError(name))
        }
        val target = resourceTarget(webDavRoot, name)
        val response = send(
            method = METHOD_MKCOL,
            target = target,
            followRedirect = true
        )
        when {
            response.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
            response.status.isSuccessCode() -> Ok(Unit)
            response.status == HTTP_METHOD_NOT_ALLOWED -> Ok(Unit)
            response.status in REDIRECT_STATUSES -> Err(redirectError(target, response.status))
            else -> Err(httpError(response, "创建云端目录失败：${target.uriPath}"))
        }
    }

    /**
     * 删除资源。
     *
     * 404 视为成功（目标已经不在了），便于“清理旧快照”这类操作重复执行。
     *
     * @param name 相对配置目录的资源路径
     *
     * @return 成功时返回 [Unit]
     */
    private suspend fun deleteInternal(name: String): Result<Unit, WebDavError> = withContext(Dispatchers.IO) {
        val webDavRoot = root ?: return@withContext Err(invalidUrlError)
        if (name.isBlank() || !isSafeRelativePath(name)) {
            return@withContext Err(invalidNameError(name))
        }
        val target = resourceTarget(webDavRoot, name)
        val response = send(
            method = METHOD_DELETE,
            target = target,
            followRedirect = false
        )
        when {
            response.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
            response.status.isSuccessCode() -> Ok(Unit)
            response.status == HTTP_NOT_FOUND -> Ok(Unit)
            response.status in REDIRECT_STATUSES -> Err(redirectError(target, response.status))
            else -> Err(httpError(response, "删除云端文件失败：$name"))
        }
    }

    /**
     * 探测资源是否存在。
     *
     * 优先用 HEAD；部分服务器不支持对集合做 HEAD（405/501），此时退回 PROPFIND（Depth: 0）。
     *
     * @param name 相对配置目录的资源路径
     *
     * @return 存在返回 true，明确不存在返回 false
     */
    private suspend fun existsInternal(name: String): Result<Boolean, WebDavError> = withContext(Dispatchers.IO) {
        val webDavRoot = root ?: return@withContext Err(invalidUrlError)
        if (name.isBlank() || !isSafeRelativePath(name)) {
            return@withContext Err(invalidNameError(name))
        }
        val target = resourceTarget(webDavRoot, name)
        val head = send(method = METHOD_HEAD, target = target, followRedirect = true)
        when {
            head.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
            head.status.isSuccessCode() -> Ok(true)
            head.status == HTTP_NOT_FOUND || head.status == HTTP_GONE -> Ok(false)
            head.status in REDIRECT_STATUSES -> Err(redirectError(target, head.status))
            head.status == HTTP_METHOD_NOT_ALLOWED || head.status == HTTP_NOT_IMPLEMENTED -> {
                val probe = send(
                    method = METHOD_PROPFIND,
                    target = target,
                    body = PROPFIND_BODY,
                    depth = DEPTH_ZERO,
                    contentType = XML_CONTENT_TYPE,
                    followRedirect = true
                )
                when {
                    probe.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
                    probe.status.isSuccessCode() -> Ok(hasHref(probe.body))
                    probe.status == HTTP_NOT_FOUND -> Ok(false)
                    else -> Err(httpError(probe, "探测云端文件失败：$name"))
                }
            }

            else -> Err(httpError(head, "探测云端文件失败：$name"))
        }
    }

    /**
     * 逐级创建配置的快照目录，已存在的层级会被跳过。
     *
     * 每一级单独 MKCOL：WebDAV 不会自动创建父集合。基础地址本身（[baseUrl]）被视为已存在，
     * 不会被创建。
     *
     * @return 全部层级可用时返回 [Unit]
     */
    private suspend fun ensureDirectoryInternal(): Result<Unit, WebDavError> = withContext(Dispatchers.IO) {
        val webDavRoot = root ?: return@withContext Err(invalidUrlError)
        if (directorySegments.isEmpty()) return@withContext Ok(Unit)
        for (index in directorySegments.indices) {
            val relativePath = directorySegments.subList(0, index + 1).joinToString("/")
            val target = resourceTarget(webDavRoot, relativePath)
            val response = send(
                method = METHOD_MKCOL,
                target = target,
                followRedirect = true
            )
            when {
                response.status == HTTP_UNAUTHORIZED -> return@withContext Err(unauthorizedError())
                response.status.isSuccessCode() -> Unit
                response.status == HTTP_METHOD_NOT_ALLOWED -> Unit
                response.status in REDIRECT_STATUSES ->
                    return@withContext Err(redirectError(target, response.status))

                else -> return@withContext Err(
                    httpError(response, "创建云端目录失败：${target.uriPath}")
                )
            }
        }
        Ok(Unit)
    }

    /**
     * 探测基础地址本身是否存在且可访问（PROPFIND，Depth: 0）。
     *
     * 与 [list] 不同，它不带配置的快照目录，因此可以区分“账号或密码不对”“基础地址写错”
     * 与“只是目录还没建”三种情况，适合给“测试连接”使用。
     *
     * @return 基础地址是可达的 WebDAV 集合时返回 true；明确是 404 时返回 false
     */
    private suspend fun probeInternal(): Result<Boolean, WebDavError> = withContext(Dispatchers.IO) {
        val webDavRoot = root ?: return@withContext Err(invalidUrlError)
        val target = collectionTarget(webDavRoot, relative = null, includeDirectory = false)
        val response = send(
            method = METHOD_PROPFIND,
            target = target,
            body = PROPFIND_BODY,
            depth = DEPTH_ZERO,
            contentType = XML_CONTENT_TYPE,
            followRedirect = true
        )
        when {
            response.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
            response.status.isSuccessCode() -> Ok(response.body.isNotEmpty())
            response.status == HTTP_NOT_FOUND || response.status == HTTP_GONE -> Ok(false)
            response.status == HTTP_FORBIDDEN -> Err(httpError(response, "服务器拒绝访问该地址"))
            response.status in REDIRECT_STATUSES -> Err(redirectError(target, response.status))
            else -> Err(httpError(response, "探测 WebDAV 基础地址失败"))
        }
    }

    /**
     * 关闭底层 HTTP 客户端。
     *
     * 插件卸载或引擎销毁时调用；关闭后本实例不应再被使用。
     */
    override fun close() {
        runCatching { client.close() }
    }

    // ------------------------------------------------------------------
    // RemoteStore 实现
    //
    // 实际逻辑仍然使用 WebDAV 特有的 [WebDavError]（错误分类与提示都更精确），只在这一层
    // 转换成后端无关的 [RemoteError]。云同步引擎因此不需要知道当前用的是哪种后端。
    // ------------------------------------------------------------------

    /**
     * 逐级创建配置的快照目录。
     *
     * @return 全部层级可用时返回成功
     */
    override suspend fun ensureDirectory(): Result<Unit, RemoteError> =
        ensureDirectoryInternal().mapWebDavError()

    /**
     * 上传一个文件。
     *
     * @param name 相对配置目录的文件名
     * @param bytes 文件内容
     *
     * @return 成功时不返回内容
     */
    override suspend fun put(name: String, bytes: ByteArray): Result<Unit, RemoteError> =
        putInternal(name, bytes).mapWebDavError()

    /**
     * 下载一个文件。
     *
     * @param name 相对配置目录的文件名
     *
     * @return 文件原始字节
     */
    override suspend fun get(name: String): Result<ByteArray, RemoteError> =
        getInternal(name).mapWebDavError()

    /**
     * 列出配置目录下的直接子项（PROPFIND，Depth: 1）。
     *
     * @return 条目列表；目录不存在时返回 [RemoteErrorKind.NOT_FOUND] 失败
     */
    override suspend fun list(): Result<List<RemoteEntry>, RemoteError> =
        listEntries("")
            .mapWebDavError()
            .map { entries -> entries.map { it.toRemoteEntry() } }

    /**
     * 删除一个文件；目标已不存在时同样视为成功。
     *
     * @param name 相对配置目录的文件名
     *
     * @return 成功时不返回内容
     */
    override suspend fun delete(name: String): Result<Unit, RemoteError> =
        deleteInternal(name).mapWebDavError()

    /**
     * 探测基础地址是否可达（PROPFIND，Depth: 0）。
     *
     * @return 基础地址是可达的 WebDAV 集合时返回 true；明确是 404 时返回 false
     */
    override suspend fun probe(): Result<Boolean, RemoteError> =
        probeInternal().mapWebDavError()

    /**
     * 创建单个集合（WebDAV 专有操作，[RemoteStore] 没有对应方法）。
     *
     * @param name 相对配置目录的集合路径
     *
     * @return 成功时不返回内容；405 表示集合已存在，同样视为成功
     */
    suspend fun mkcol(name: String): Result<Unit, RemoteError> = mkcolInternal(name).mapWebDavError()

    /**
     * 探测某个资源是否存在（WebDAV 专有操作）。
     *
     * @param name 相对配置目录的资源路径
     *
     * @return 存在返回 true，明确不存在返回 false
     */
    suspend fun exists(name: String): Result<Boolean, RemoteError> =
        existsInternal(name).mapWebDavError()

    /** 把 WebDAV 特有的失败结果折叠成后端无关的结果。 */
    private fun <T> Result<T, WebDavError>.mapWebDavError(): Result<T, RemoteError> =
        mapError { it.toRemoteError() }

    /** [WebDavError] → [RemoteError]：两套分类的成员一一对应。 */
    private fun WebDavError.toRemoteError(): RemoteError = RemoteError(
        kind = when (kind) {
            WebDavErrorKind.INVALID_URL -> RemoteErrorKind.INVALID_URL
            WebDavErrorKind.UNAUTHORIZED -> RemoteErrorKind.UNAUTHORIZED
            WebDavErrorKind.NOT_FOUND -> RemoteErrorKind.NOT_FOUND
            WebDavErrorKind.NETWORK -> RemoteErrorKind.NETWORK
            WebDavErrorKind.HTTP -> RemoteErrorKind.HTTP
            WebDavErrorKind.PROTOCOL -> RemoteErrorKind.PROTOCOL
        },
        message = message,
        status = status,
        cause = cause
    )

    /** [WebDavEntry] → [RemoteEntry]，字段一一对应。 */
    private fun WebDavEntry.toRemoteEntry(): RemoteEntry = RemoteEntry(
        name = name,
        isCollection = isCollection,
        size = size,
        lastModifiedEpochMillis = lastModifiedEpochMillis
    )

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 解析后的根地址：协议 + 主机，以及基础路径（均已编码，无结尾斜杠）。 */
    private data class WebDavRoot(val origin: String, val basePath: String)

    /** 一次具体请求的地址：完整 URL 与用于 Digest 摘要的 request-uri。 */
    private data class Target(val url: String, val uriPath: String)

    /** 一次性读取完的响应，避免重复消费 Ktor 的响应体。 */
    private class RawResponse(
        val status: Int,
        val headers: Headers,
        val body: ByteArray
    )

    /** 解析用户填写的根地址；非法时返回 null。 */
    private fun parseBaseUrl(raw: String): WebDavRoot? {
        val text = raw.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = text.substring(0, schemeEnd).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val rest = text.substring(schemeEnd + 3)
        val firstSlash = rest.indexOf('/')
        val authority = (if (firstSlash < 0) rest else rest.substring(0, firstSlash)).trim()
        if (authority.isEmpty()) return null
        // 凭据只能走用户名/密码输入框，禁止写在 URL 里：URL 会出现在错误提示中。
        if (authority.contains('@')) return null
        val rawPath = if (firstSlash < 0) "" else rest.substring(firstSlash)
        val encodedPath = rawPath
            .split('/')
            .joinToString("/") { encodePathSegment(it) }
            .trimEnd('/')
        return WebDavRoot(origin = "$scheme://$authority", basePath = encodedPath)
    }

    /** 对单个路径片段做百分号编码；已经编码过的片段原样保留。 */
    private fun encodePathSegment(segment: String): String {
        if (segment.isEmpty()) return segment
        if (ENCODED_SEGMENT.containsMatchIn(segment)) return segment
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    /** 拒绝 `..`、`.`、反斜杠等会逃出目录的写法。 */
    private fun isSafeRelativePath(path: String): Boolean =
        path.split('/').none { segment ->
            val trimmed = segment.trim()
            trimmed == ".." || trimmed == "." || trimmed.contains('\\')
        }

    /** 构造资源地址（集合不带结尾斜杠）。 */
    private fun resourceTarget(
        root: WebDavRoot,
        relative: String?,
        includeDirectory: Boolean = true
    ): Target {
        val extraPath = buildExtraPath(relative, includeDirectory)
        return Target(
            url = root.origin + root.basePath + extraPath,
            uriPath = root.basePath + extraPath
        )
    }

    /** 构造集合地址：结尾补 `/`，避免服务器用 301 把集合地址规范化。 */
    private fun collectionTarget(
        root: WebDavRoot,
        relative: String?,
        includeDirectory: Boolean = true
    ): Target {
        val target = resourceTarget(root, relative, includeDirectory)
        if (target.url.endsWith("/")) return target
        return Target(url = target.url + "/", uriPath = target.uriPath + "/")
    }

    /**
     * 拼接“配置目录 + 相对路径”的请求路径，返回以 `/` 开头（或为空）的编码后路径。
     *
     * @param includeDirectory 是否带上配置的快照目录；探测基础地址时传 false
     */
    private fun buildExtraPath(relative: String?, includeDirectory: Boolean): String {
        val segments = ArrayList<String>(directorySegments.size + 2)
        if (includeDirectory) {
            segments += directorySegments
        }
        relative.orEmpty()
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { segments += encodePathSegment(it) }
        if (segments.isEmpty()) return ""
        return "/" + segments.joinToString("/")
    }

    /**
     * 发送请求：先 Basic，遇到 Digest 挑战重试一次，再按需跟随重定向。
     *
     * @param method HTTP 方法（自定义方法由 [HttpMethod] 构造）
     * @param target 目标地址
     * @param body 请求体，可为 null
     * @param depth `Depth` 头，可为 null
     * @param contentType 请求体类型，可为 null
     * @param followRedirect 是否允许跟随重定向（写操作为 false）
     * @param redirectBudget 剩余可跟随次数
     */
    private suspend fun send(
        method: HttpMethod,
        target: Target,
        body: ByteArray? = null,
        depth: String? = null,
        contentType: String? = null,
        followRedirect: Boolean = false,
        redirectBudget: Int = MAX_REDIRECTS
    ): RawResponse {
        var response = execute(method, target, body, depth, contentType, null)
        if (response.status == HTTP_UNAUTHORIZED) {
            val challenge = findDigestChallenge(response.headers)
            val digest = challenge?.let { buildDigestAuthorization(method.value, target.uriPath, it) }
            if (digest != null) {
                response = execute(method, target, body, depth, contentType, digest)
            }
        }
        if (followRedirect && redirectBudget > 0 && response.status in REDIRECT_STATUSES) {
            val location = response.headers[HttpHeaders.Location]
            if (!location.isNullOrBlank()) {
                val next = resolveTarget(target, location)
                if (next != null) {
                    return send(
                        method = method,
                        target = next,
                        body = body,
                        depth = depth,
                        contentType = contentType,
                        followRedirect = true,
                        redirectBudget = redirectBudget - 1
                    )
                }
            }
        }
        return response
    }

    /** 真正执行一次 HTTP 请求，并把响应体一次性读完。 */
    private suspend fun execute(
        method: HttpMethod,
        target: Target,
        body: ByteArray?,
        depth: String?,
        contentType: String?,
        authorization: String?
    ): RawResponse = withContext(Dispatchers.IO) {
        val authorizationHeader = authorization ?: basicAuthorization
        val response = client.request(target.url) {
            this.method = method
            if (authorizationHeader != null) {
                header(HttpHeaders.Authorization, authorizationHeader)
            }
            if (depth != null) {
                header(DEPTH_HEADER, depth)
            }
            if (contentType != null) {
                header(HttpHeaders.ContentType, contentType)
            }
            header(HttpHeaders.Accept, "*/*")
            if (body != null) {
                setBody(body)
            }
        }
        RawResponse(
            status = response.status.value,
            headers = response.headers,
            body = response.bodyAsBytes()
        )
    }

    /** 把相对 `Location` 换算成新的请求地址；无法换算时返回 null。 */
    private fun resolveTarget(current: Target, location: String): Target? = runCatching {
        val resolved = URI(current.url).resolve(location.trim())
        val path = resolved.rawPath ?: return@runCatching null
        val query = resolved.rawQuery
        Target(
            url = resolved.toString(),
            uriPath = if (query.isNullOrEmpty()) path else "$path?$query"
        )
    }.getOrNull()

    /** 解析 `WWW-Authenticate: Digest …` 挑战；没有 Digest 挑战时返回 null。 */
    private fun findDigestChallenge(headers: Headers): Map<String, String>? {
        val challenges = headers.getAll(HttpHeaders.WWWAuthenticate).orEmpty()
        for (challenge in challenges) {
            val text = challenge.trim()
            if (!text.startsWith("Digest", ignoreCase = true)) continue
            val params = parseAuthParams(text.substring("Digest".length))
            if (params["realm"] != null && params["nonce"] != null) return params
        }
        return null
    }

    /** 解析 `key="value", key2=value2` 形式的认证参数。 */
    private fun parseAuthParams(input: String): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        AUTH_PARAM.findAll(input).forEach { match ->
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            if (!params.containsKey(name)) params[name] = value
        }
        return params
    }

    /**
     * 计算 RFC 2617 摘要认证头。
     *
     * 只实现 MD5（含 `qop=auth`）；服务器要求 `MD5-sess`、`SHA-256` 或只提供 `auth-int` 时返回
     * null，此时调用方会把 401 原样报给用户，而不是发出一个注定失败的请求。
     */
    private fun buildDigestAuthorization(
        method: String,
        uriPath: String,
        params: Map<String, String>
    ): String? {
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val opaque = params["opaque"]
        val algorithm = params["algorithm"]
        if (algorithm != null && !algorithm.equals("MD5", ignoreCase = true)) return null
        val qop = params["qop"]
        val useQop = qop?.split(',')?.any { it.trim().equals("auth", ignoreCase = true) } == true
        if (qop != null && !useQop) return null

        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uriPath")

        val header = StringBuilder("Digest ")
        header.append("username=\"").append(escapeQuoted(username)).append("\", ")
        header.append("realm=\"").append(escapeQuoted(realm)).append("\", ")
        header.append("nonce=\"").append(escapeQuoted(nonce)).append("\", ")
        header.append("uri=\"").append(escapeQuoted(uriPath)).append("\", ")
        if (useQop) {
            val nonceCount = "00000001"
            val clientNonce = UUID.randomUUID().toString().replace("-", "")
            val digest = md5Hex("$ha1:$nonce:$nonceCount:$clientNonce:auth:$ha2")
            header.append("qop=auth, ")
            header.append("nc=").append(nonceCount).append(", ")
            header.append("cnonce=\"").append(clientNonce).append("\", ")
            header.append("response=\"").append(digest).append("\"")
        } else {
            header.append("response=\"").append(md5Hex("$ha1:$nonce:$ha2")).append("\"")
        }
        if (opaque != null) {
            header.append(", opaque=\"").append(escapeQuoted(opaque)).append("\"")
        }
        if (algorithm != null) {
            header.append(", algorithm=MD5")
        }
        return header.toString()
    }

    /** 计算字符串的 MD5 十六进制摘要。仅用于满足服务器的 Digest 要求。 */
    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    /** 转义 Digest 头里引号包裹的值。 */
    private fun escapeQuoted(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 解析 PROPFIND 的 207 响应体，抽出目录项。 */
    private fun parseEntries(body: ByteArray, collectionPath: String): List<WebDavEntry> {
        if (body.isEmpty()) return emptyList()
        val text = String(body, Charsets.UTF_8)
        val selfPath = stripTrailingSlash(percentDecode(collectionPath))
        val entries = ArrayList<WebDavEntry>()
        for (block in RESPONSE_BLOCK.findAll(text)) {
            val blockText = block.value
            val hrefMatch = HREF_TAG.find(blockText) ?: continue
            val rawHref = unescapeXml(stripCdata(hrefMatch.groupValues[1])).trim()
            if (rawHref.isEmpty()) continue
            val decodedPath = percentDecode(hrefToPath(rawHref))
            val normalized = stripTrailingSlash(decodedPath)
            val isCollection = COLLECTION_TAG.containsMatchIn(blockText)
            // 目录自身也会出现在 Depth:1 的结果里，跳过它；只按完整路径比较，
            // 避免把同名的子目录误判成目录自身。
            if (normalized.equals(selfPath, ignoreCase = true)) continue
            val name = normalized.substringAfterLast('/')
            if (name.isEmpty()) continue
            entries += WebDavEntry(
                name = name,
                href = rawHref,
                isCollection = isCollection,
                size = CONTENT_LENGTH_TAG.find(blockText)
                    ?.groupValues?.get(1)?.trim()?.toLongOrNull(),
                lastModifiedEpochMillis = parseHttpDate(
                    LAST_MODIFIED_TAG.find(blockText)?.groupValues?.get(1)
                )
            )
        }
        return entries
    }

    /** 响应体里是否含至少一个 `href`，用于 Depth:0 的存在性探测。 */
    private fun hasHref(body: ByteArray): Boolean =
        body.isNotEmpty() && HREF_TAG.containsMatchIn(String(body, Charsets.UTF_8))

    /** 把绝对形式的 `href` 还原成路径，相对形式原样返回。 */
    private fun hrefToPath(href: String): String = runCatching {
        val uri = URI(href)
        if (uri.isAbsolute) uri.rawPath ?: href else href
    }.getOrDefault(href)

    /** 去掉结尾斜杠（保留根路径 `/`）。 */
    private fun stripTrailingSlash(path: String): String =
        if (path.length > 1) path.trimEnd('/') else path

    /** 解开 XML 基本实体；`&amp;` 必须最后处理。 */
    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /** 去掉 CDATA 包裹。 */
    private fun stripCdata(value: String): String {
        var text = value.trim()
        if (text.startsWith(CDATA_START)) text = text.removePrefix(CDATA_START)
        if (text.endsWith(CDATA_END)) text = text.removeSuffix(CDATA_END)
        return text.trim()
    }

    /** 按 UTF-8 还原百分号编码；`+` 在路径中是普通字符，不做转换。 */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val buffer = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (decoded != null) {
                    buffer.write(decoded)
                    index += 3
                    continue
                }
            }
            val encoded = char.toString().toByteArray(Charsets.UTF_8)
            buffer.write(encoded, 0, encoded.size)
            index++
        }
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    /** 解析 HTTP 日期；常见三种格式都试一遍，全部失败返回 null。 */
    private fun parseHttpDate(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        runCatching { return Instant.parse(text).toEpochMilli() }
        runCatching {
            return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        }
        runCatching {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant().toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return null
    }

    /** HTTP 状态码是否表示成功（含 WebDAV 的 207）。 */
    private fun Int.isSuccessCode(): Boolean = this in 200..299

    /** 认证失败的统一错误，绝不回显服务器挑战或任何凭据。 */
    private fun unauthorizedError(): WebDavError = WebDavError(
        kind = WebDavErrorKind.UNAUTHORIZED,
        message = "认证失败（HTTP 401）：请检查 WebDAV 用户名与密码/应用专用密码",
        status = HTTP_UNAUTHORIZED
    )

    /** 非预期状态码的通用错误，附带截断后的响应体便于排查。 */
    private fun httpError(response: RawResponse, prefix: String): WebDavError {
        val snippet = responseSnippet(response)
        val suffix = if (snippet.isEmpty()) "" else "：$snippet"
        return WebDavError(
            kind = if (response.status == HTTP_NOT_FOUND) WebDavErrorKind.NOT_FOUND else WebDavErrorKind.HTTP,
            message = "$prefix（HTTP ${response.status}）$suffix",
            status = response.status
        )
    }

    /** 取响应体摘要；401/403 不回显内容，避免把服务器的鉴权细节带到界面上。 */
    private fun responseSnippet(response: RawResponse): String {
        if (response.status == HTTP_UNAUTHORIZED || response.status == HTTP_FORBIDDEN) return ""
        if (response.body.isEmpty()) return ""
        val text = String(response.body, Charsets.UTF_8)
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (text.length <= 200) text else text.take(200) + "…"
    }

    /** 只读请求无法跟随的重定向。 */
    private fun redirectError(target: Target, status: Int): WebDavError = WebDavError(
        kind = WebDavErrorKind.PROTOCOL,
        message = "服务器返回了无法跟随的重定向（HTTP $status）：${target.uriPath}。" +
                "请把 WebDAV 地址改成重定向后的最终地址",
        status = status
    )

    /** `PUT` 被重定向时的专用错误。 */
    private fun putRedirectError(target: Target, status: Int): WebDavError = WebDavError(
        kind = WebDavErrorKind.PROTOCOL,
        message = "服务器要求把上传重定向到其它地址（HTTP $status）：${target.uriPath}。" +
                "为避免把快照写到未知位置，插件不会跟随 PUT 重定向，请把 WebDAV 地址改成最终地址",
        status = status
    )

    /** 文件名不合法时的错误。 */
    private fun invalidNameError(name: String): WebDavError = WebDavError(
        kind = WebDavErrorKind.PROTOCOL,
        message = "非法的云端路径：$name"
    )

    private companion object {
        /** HTTP 状态码常量。 */
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_GONE = 410
        const val HTTP_NOT_IMPLEMENTED = 501

        /** 十六进制字符表，用于手工拼 MD5 摘要。 */
        const val HEX_DIGITS = "0123456789abcdef"

        /** WebDAV 自定义方法。用字符串构造，避免依赖具体 Ktor 版本是否提供同名常量。 */
        val METHOD_PROPFIND = HttpMethod("PROPFIND")
        val METHOD_PUT = HttpMethod("PUT")
        val METHOD_GET = HttpMethod("GET")
        val METHOD_HEAD = HttpMethod("HEAD")
        val METHOD_MKCOL = HttpMethod("MKCOL")
        val METHOD_DELETE = HttpMethod("DELETE")

        /** Digest 挑战里的参数，形如 `realm="x", nonce="y"`。 */
        val AUTH_PARAM = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))")
    }
}

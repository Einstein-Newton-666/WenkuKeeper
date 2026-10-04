package io.github.lnrplugin.wenku8plus.cloud.remote

import android.util.Base64
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import io.github.lnrplugin.wenku8plus.PluginConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** GitHub REST API 的根地址。 */
private const val GITHUB_API_BASE = "https://api.github.com"

/** GitHub 推荐的媒体类型。 */
private const val GITHUB_ACCEPT = "application/vnd.github+json"

/** GitHub 要求的 API 版本头。 */
private const val GITHUB_API_VERSION = "2022-11-28"

/** API 版本头的名称（非 HTTP 标准头）。 */
private const val API_VERSION_HEADER = "X-GitHub-Api-Version"

/** 提交 JSON 请求体时使用的 Content-Type。 */
private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

/** 提交信息前缀；后面会拼上具体动作与文件名。 */
private const val COMMIT_MESSAGE_PREFIX = "LightNovelReader 云端备份"

/** 客户端 UA；GitHub 会拒绝没有 User-Agent 的请求。不含 token。 */
private val USER_AGENT = "LightNovelReader-Wenku8Plus/${PluginConstants.PLUGIN_VERSION_NAME} (Android)"

/** 已经过百分号编码的路径片段，避免把用户写好的 `%20` 再编码一次。 */
private val ENCODED_SEGMENT = Regex("%[0-9A-Fa-f]{2}")

/**
 * 把云端数据存到 GitHub 仓库的 [RemoteStore] 实现。
 *
 * 使用 GitHub 官方的 Contents API：
 * - 每个文件对应仓库里的一个文件，快照文件名保持不变；
 * - GitHub 没有空目录，因此 [ensureDirectory] 只校验仓库可达，目录会随第一个文件自动出现；
 * - Contents API 不提供每个文件的提交时间，[RemoteEntry.lastModifiedEpochMillis] 一律为 null，
 *   排序由调用方按文件名（`lnr-YYYYMMDD-HHmmss.json.gz` 天然按时间递增）完成；
 * - 更新与删除都需要先读到文件当前的 `sha`，因此这两个操作各多一次 GET。
 *
 * 凭据只有 [token] 一个，只出现在 `Authorization` 头里：不出现在 URL、错误信息、状态文本或
 * [toString] 中。建议使用仅对该仓库有 `Contents: read and write` 权限的细粒度令牌，并配合
 * **私有**仓库——阅读记录 Markdown 会列出书名。
 *
 * @param repository `owner/repo` 形式的目标仓库，例如 `your-name/lnr-backup`
 * @param token 具备该仓库 Contents 读写权限的令牌
 * @param branch 提交到哪个分支，例如 `main`
 * @param directory 仓库内用于存放快照的目录，可多级；留空表示仓库根目录
 */
class GitHubStore(
    repository: String,
    private val token: String,
    private val branch: String,
    directory: String
) : RemoteStore {

    /** 解析后的 owner/repo；写法非法时为 null，此时所有操作都返回 [configurationError]。 */
    private val coordinates: RepoCoordinates? = parseRepository(repository)

    /** 仓库写法非法时的统一错误。 */
    private val configurationError = RemoteError(
        kind = RemoteErrorKind.INVALID_URL,
        message = "GitHub 仓库写法无效：请填写 owner/repo 形式（例如 your-name/lnr-backup），不要带 https:// 前缀"
    )

    /** 目标目录的各级片段，均已做百分号编码。 */
    private val directorySegments: List<String> = directory
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { encodePathSegment(it) }

    /** 保护 [httpClient] 的锁；只在创建/关闭客户端时短暂持有。 */
    private val clientLock = Any()

    /** 惰性创建的 Ktor 客户端；[close] 之后会重新创建。 */
    private var httpClient: HttpClient? = null

    /**
     * 校验仓库是否可达。
     *
     * GitHub 没有空目录的概念，因此这里不做「建目录」，只确认仓库存在且令牌可用；
     * 目标目录会随第一个文件一起出现。
     *
     * 注意：这一步只能证明**可读**。是否可写要等真正的上传，GitHub 会在 [put] 时以 403 拒绝。
     *
     * @return 仓库可访问时返回成功
     */
    override suspend fun ensureDirectory(): Result<Unit, RemoteError> = withContext(Dispatchers.IO) {
        try {
            val coordinates = coordinates ?: return@withContext Err(configurationError)
            val response = send(METHOD_GET, repositoryUrl(coordinates)).unwrap()
            val failure = response.failure
            if (failure != null) return@withContext Err(failure)
            val api = response.value ?: return@withContext Err(emptyResponseError())
            when {
                api.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
                api.status == HTTP_FORBIDDEN -> Err(forbiddenError(api))
                api.status == HTTP_NOT_FOUND -> Err(repositoryNotFoundError(coordinates))
                api.status.isSuccessCode() -> Ok(Unit)
                else -> Err(httpError(api, "校验 GitHub 仓库失败"))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(protocolError("校验 GitHub 仓库失败", error))
        }
    }

    /**
     * 写入（覆盖）一个文件：先取现有 `sha`，再带 `sha` 提交（新建时不带）。
     *
     * @param name 相对配置目录的文件名
     * @param bytes 文件内容
     *
     * @return 成功时不返回内容
     */
    override suspend fun put(name: String, bytes: ByteArray): Result<Unit, RemoteError> =
        withContext(Dispatchers.IO) {
            try {
                val coordinates = coordinates ?: return@withContext Err(configurationError)
                val path = remotePath(name) ?: return@withContext Err(invalidNameError(name))

                val sha = existingSha(coordinates, path).unwrap()
                val shaFailure = sha.failure
                if (shaFailure != null) return@withContext Err(shaFailure)

                val requestBody = buildJsonObject {
                    put("message", "$COMMIT_MESSAGE_PREFIX：写入 $name")
                    put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    put("branch", branch)
                    sha.value?.let { put("sha", it) }
                }.toString()

                val response = send(
                    method = METHOD_PUT,
                    url = contentsUrl(coordinates, path, withRef = false),
                    body = requestBody,
                    contentType = JSON_CONTENT_TYPE
                ).unwrap()
                val failure = response.failure
                if (failure != null) return@withContext Err(failure)
                val api = response.value ?: return@withContext Err(emptyResponseError())
                when {
                    api.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
                    api.status == HTTP_FORBIDDEN -> Err(forbiddenError(api))
                    api.status == HTTP_NOT_FOUND -> Err(repositoryNotFoundError(coordinates))
                    api.status == HTTP_CONFLICT || api.status == HTTP_UNPROCESSABLE ->
                        Err(httpError(api, "GitHub 拒绝了这次提交（分支或内容有冲突）"))

                    api.status.isSuccessCode() -> Ok(Unit)
                    else -> Err(httpError(api, "上传 GitHub 文件失败"))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Err(protocolError("上传 GitHub 文件失败", error))
            }
        }

    /**
     * 读取一个文件并解码其中的 base64 内容。
     *
     * @param name 相对配置目录的文件名
     *
     * @return 文件字节；文件不存在时返回 [RemoteErrorKind.NOT_FOUND] 失败
     */
    override suspend fun get(name: String): Result<ByteArray, RemoteError> =
        withContext(Dispatchers.IO) {
            try {
                val coordinates = coordinates ?: return@withContext Err(configurationError)
                val path = remotePath(name) ?: return@withContext Err(invalidNameError(name))

                val response = send(
                    method = METHOD_GET,
                    url = contentsUrl(coordinates, path, withRef = true)
                ).unwrap()
                val failure = response.failure
                if (failure != null) return@withContext Err(failure)
                val api = response.value ?: return@withContext Err(emptyResponseError())
                when {
                    api.status == HTTP_UNAUTHORIZED -> return@withContext Err(unauthorizedError())
                    api.status == HTTP_FORBIDDEN -> return@withContext Err(forbiddenError(api))
                    api.status == HTTP_NOT_FOUND -> return@withContext Err(fileNotFoundError(name))
                    !api.status.isSuccessCode() ->
                        return@withContext Err(httpError(api, "下载 GitHub 文件失败"))
                }

                val element = parseJson(api.body)
                    ?: return@withContext Err(protocolError("GitHub 返回的内容无法解析为 JSON"))
                val entry = element as? JsonObject
                    ?: return@withContext Err(protocolError("GitHub 路径 $name 是目录，不能当作文件下载"))

                val encoding = entry.string("encoding")
                val content = entry.string("content").orEmpty()
                if (content.isBlank() || (encoding != null && encoding != "base64")) {
                    return@withContext Err(
                        protocolError(
                            "GitHub 没有内联返回文件内容（通常是文件超过 1 MB，Contents API 不再直接给出内容）"
                        )
                    )
                }
                val bytes = try {
                    Base64.decode(content.filterNot { it.isWhitespace() }, Base64.DEFAULT)
                } catch (error: IllegalArgumentException) {
                    return@withContext Err(protocolError("GitHub 返回的 base64 内容无法解码", error))
                }
                Ok(bytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Err(protocolError("下载 GitHub 文件失败", error))
            }
        }

    /**
     * 列出配置目录下的条目。
     *
     * @return 条目列表；目录不存在时返回 [RemoteErrorKind.NOT_FOUND] 失败
     */
    override suspend fun list(): Result<List<RemoteEntry>, RemoteError> = withContext(Dispatchers.IO) {
        try {
            val coordinates = coordinates ?: return@withContext Err(configurationError)
            val directoryPath = directorySegments.joinToString("/")
            val response = send(
                method = METHOD_GET,
                url = contentsUrl(coordinates, directoryPath, withRef = true)
            ).unwrap()
            val failure = response.failure
            if (failure != null) return@withContext Err(failure)
            val api = response.value ?: return@withContext Err(emptyResponseError())
            when {
                api.status == HTTP_UNAUTHORIZED -> return@withContext Err(unauthorizedError())
                api.status == HTTP_FORBIDDEN -> return@withContext Err(forbiddenError(api))
                api.status == HTTP_NOT_FOUND ->
                    return@withContext Err(directoryNotFoundError(directoryPath))

                !api.status.isSuccessCode() -> return@withContext Err(httpError(api, "列出 GitHub 目录失败"))
            }

            val element = parseJson(api.body)
                ?: return@withContext Err(protocolError("GitHub 返回的内容无法解析为 JSON"))
            if (element !is JsonArray) {
                return@withContext Err(
                    protocolError("GitHub 路径 ${displayDirectory()} 是一个文件，不是目录")
                )
            }
            Ok(element.mapNotNull { parseEntry(it) })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(protocolError("列出 GitHub 目录失败", error))
        }
    }

    /**
     * 删除一个文件：先取 `sha`，再提交删除。
     *
     * @param name 相对配置目录的文件名
     *
     * @return 成功时不返回内容；文件不存在时返回 [RemoteErrorKind.NOT_FOUND] 失败
     */
    override suspend fun delete(name: String): Result<Unit, RemoteError> =
        withContext(Dispatchers.IO) {
            try {
                val coordinates = coordinates ?: return@withContext Err(configurationError)
                val path = remotePath(name) ?: return@withContext Err(invalidNameError(name))

                val sha = existingSha(coordinates, path).unwrap()
                val shaFailure = sha.failure
                if (shaFailure != null) return@withContext Err(shaFailure)
                val currentSha = sha.value ?: return@withContext Err(fileNotFoundError(name))

                val requestBody = buildJsonObject {
                    put("message", "$COMMIT_MESSAGE_PREFIX：删除 $name")
                    put("sha", currentSha)
                    put("branch", branch)
                }.toString()

                val response = send(
                    method = METHOD_DELETE,
                    url = contentsUrl(coordinates, path, withRef = false),
                    body = requestBody,
                    contentType = JSON_CONTENT_TYPE
                ).unwrap()
                val failure = response.failure
                if (failure != null) return@withContext Err(failure)
                val api = response.value ?: return@withContext Err(emptyResponseError())
                when {
                    api.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
                    api.status == HTTP_FORBIDDEN -> Err(forbiddenError(api))
                    api.status == HTTP_NOT_FOUND -> Err(fileNotFoundError(name))
                    api.status.isSuccessCode() -> Ok(Unit)
                    else -> Err(httpError(api, "删除 GitHub 文件失败"))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Err(protocolError("删除 GitHub 文件失败", error))
            }
        }

    /**
     * 探测仓库与分支是否可达。
     *
     * 先 `GET /repos/{owner}/{repo}`，再（分支名非空时）`GET .../branches/{branch}`，
     * 这样「仓库写错」「令牌无权访问」「分支名写错」三种情况能给出不同的提示。
     *
     * @return 仓库与分支都可达时返回 true；仓库明确不存在（404）时返回 false
     */
    override suspend fun probe(): Result<Boolean, RemoteError> = withContext(Dispatchers.IO) {
        try {
            val coordinates = coordinates ?: return@withContext Err(configurationError)

            val repositoryResponse = send(METHOD_GET, repositoryUrl(coordinates)).unwrap()
            val repositoryFailure = repositoryResponse.failure
            if (repositoryFailure != null) return@withContext Err(repositoryFailure)
            val repository = repositoryResponse.value ?: return@withContext Err(emptyResponseError())
            when {
                repository.status == HTTP_UNAUTHORIZED -> return@withContext Err(unauthorizedError())
                repository.status == HTTP_NOT_FOUND -> return@withContext Ok(false)
                repository.status == HTTP_FORBIDDEN -> return@withContext Err(forbiddenError(repository))
                !repository.status.isSuccessCode() ->
                    return@withContext Err(httpError(repository, "校验 GitHub 仓库失败"))
            }

            if (branch.isBlank()) return@withContext Ok(true)

            val branchResponse = send(METHOD_GET, branchUrl(coordinates)).unwrap()
            val branchFailure = branchResponse.failure
            if (branchFailure != null) return@withContext Err(branchFailure)
            val branchProbe = branchResponse.value ?: return@withContext Err(emptyResponseError())
            when {
                branchProbe.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
                branchProbe.status.isSuccessCode() -> Ok(true)
                branchProbe.status == HTTP_NOT_FOUND -> Err(
                    RemoteError(
                        kind = RemoteErrorKind.NOT_FOUND,
                        message = "GitHub 分支不存在：$branch（请检查分支名，或先在仓库里创建它）",
                        status = HTTP_NOT_FOUND
                    )
                )

                else -> Err(httpError(branchProbe, "校验 GitHub 分支失败"))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(protocolError("校验 GitHub 仓库失败", error))
        }
    }

    /**
     * 关闭底层 HTTP 客户端并释放连接池。
     *
     * 关闭后再次调用其它方法会自动重建客户端；实例本身可以继续使用。
     */
    override fun close() {
        synchronized(clientLock) {
            httpClient?.close()
            httpClient = null
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 解析后的仓库坐标。 */
    private class RepoCoordinates(val owner: String, val repo: String)

    /** 一次 GitHub API 响应：状态码 + 已读完的响应体。 */
    private class ApiResponse(val status: Int, val body: String)

    /** `Result` 的内部视图：成功值与失败原因二选一。 */
    private class Unwrapped<T>(val value: T?, val failure: RemoteError?)

    /** 取（必要时创建）Ktor 客户端；不在锁内做网络操作。 */
    private fun client(): HttpClient = synchronized(clientLock) {
        val existing = httpClient
        if (existing != null) {
            return@synchronized existing
        }
        val created = HttpClient(CIO) {
            expectSuccess = false
            followRedirects = true
            install(UserAgent) {
                agent = USER_AGENT
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
        httpClient = created
        created
    }

    /** 发送一次请求；网络异常折叠成 [RemoteErrorKind.NETWORK]。 */
    private suspend fun send(
        method: HttpMethod,
        url: String,
        body: String? = null,
        contentType: String? = null
    ): Result<ApiResponse, RemoteError> = withContext(Dispatchers.IO) {
        try {
            val response = client().request(url) {
                this.method = method
                // 令牌只出现在这里：不进 URL，不进日志，不进异常信息。
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, GITHUB_ACCEPT)
                header(API_VERSION_HEADER, GITHUB_API_VERSION)
                if (contentType != null) {
                    header(HttpHeaders.ContentType, contentType)
                }
                if (body != null) {
                    setBody(body.toByteArray(Charsets.UTF_8))
                }
            }
            Ok(ApiResponse(response.status.value, String(response.bodyAsBytes(), Charsets.UTF_8)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(
                RemoteError(
                    kind = RemoteErrorKind.NETWORK,
                    message = "无法访问 GitHub：${describe(error)}",
                    cause = error
                )
            )
        }
    }

    /** 把 `Result` 折叠成可空视图，避免对捕获变量做智能转换。 */
    private fun <T> Result<T, RemoteError>.unwrap(): Unwrapped<T> {
        var value: T? = null
        var failure: RemoteError? = null
        onOk { value = it }
        onErr { failure = it }
        return Unwrapped(value, failure)
    }

    /** 取现有文件的 `sha`；文件不存在（404）时返回 Ok(null) 表示「新建」。 */
    private suspend fun existingSha(
        coordinates: RepoCoordinates,
        path: String
    ): Result<String?, RemoteError> {
        val response = send(
            method = METHOD_GET,
            url = contentsUrl(coordinates, path, withRef = true)
        ).unwrap()
        val failure = response.failure
        if (failure != null) return Err(failure)
        val api = response.value ?: return Err(emptyResponseError())
        return when {
            api.status == HTTP_UNAUTHORIZED -> Err(unauthorizedError())
            api.status == HTTP_FORBIDDEN -> Err(forbiddenError(api))
            api.status == HTTP_NOT_FOUND -> Ok(null)
            !api.status.isSuccessCode() -> Err(httpError(api, "读取 GitHub 文件信息失败"))
            else -> {
                val entry = parseJson(api.body) as? JsonObject
                if (entry == null) {
                    Err(protocolError("GitHub 路径 $path 是目录，不能当作文件写入"))
                } else {
                    Ok(entry.string("sha"))
                }
            }
        }
    }

    /** 校验并编码 `owner/repo` 写法；非法时返回 null。 */
    private fun parseRepository(raw: String): RepoCoordinates? {
        val text = raw.trim().removeSuffix(".git")
        if (text.isEmpty() || text.contains("://")) return null
        val parts = text.split('/')
        if (parts.size != 2) return null
        val owner = parts[0].trim()
        val repo = parts[1].trim()
        if (owner.isEmpty() || repo.isEmpty()) return null
        return RepoCoordinates(owner, repo)
    }

    /** 把文件名拼成仓库内的相对路径（目录 + 文件名，逐段编码）；非法时返回 null。 */
    private fun remotePath(name: String): String? {
        val clean = name.trim().trim('/')
        if (clean.isEmpty()) return null
        val relative = clean
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (relative.any { it == ".." || it.contains('\\') }) return null
        val segments = directorySegments + relative.map { encodePathSegment(it) }
        return segments.joinToString("/")
    }

    /** 对单个路径片段做百分号编码；已编码的片段原样保留。 */
    private fun encodePathSegment(segment: String): String {
        if (segment.isEmpty()) return segment
        if (ENCODED_SEGMENT.containsMatchIn(segment)) return segment
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    /** 对查询参数做百分号编码（`ref=` 用）。 */
    private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** `GET /repos/{owner}/{repo}`。 */
    private fun repositoryUrl(coordinates: RepoCoordinates): String =
        "$GITHUB_API_BASE/repos/${encodePathSegment(coordinates.owner)}/" +
                encodePathSegment(coordinates.repo)

    /** `GET /repos/{owner}/{repo}/branches/{branch}`。 */
    private fun branchUrl(coordinates: RepoCoordinates): String =
        "${repositoryUrl(coordinates)}/branches/${encodePathSegment(branch)}"

    /** `GET|PUT|DELETE /repos/{owner}/{repo}/contents/{path}`。 */
    private fun contentsUrl(
        coordinates: RepoCoordinates,
        path: String,
        withRef: Boolean
    ): String {
        val base = "${repositoryUrl(coordinates)}/contents"
        val full = if (path.isEmpty()) base else "$base/$path"
        return if (withRef && branch.isNotBlank()) {
            "$full?ref=${encodeQueryValue(branch)}"
        } else {
            full
        }
    }

    /** 目录在界面上的显示形式（未编码，便于用户对照设置项）。 */
    private fun displayDirectory(): String =
        directorySegments.joinToString("/").ifEmpty { "（仓库根目录）" }

    /** 解析 JSON；失败时返回 null，由调用方转成协议错误。 */
    private fun parseJson(text: String): JsonElement? =
        runCatching { Json.parseToJsonElement(text) }.getOrNull()

    /** 从目录条目 JSON 里取一个 [RemoteEntry]；字段缺失时返回 null。 */
    private fun parseEntry(element: JsonElement): RemoteEntry? {
        val entry = element as? JsonObject ?: return null
        val name = entry.string("name") ?: return null
        val size = entry.string("size")?.toLongOrNull()
        return RemoteEntry(
            name = name,
            isCollection = entry.string("type") == "dir",
            size = size,
            // Contents API 不提供每个文件的提交时间，也不值得为它多发一次请求。
            lastModifiedEpochMillis = null
        )
    }

    /** 读一个 JSON 字段的字符串内容。 */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    /** HTTP 状态码是否表示成功。 */
    private fun Int.isSuccessCode(): Boolean = this in 200..299

    /** 认证失败：不区分「令牌无效」与「令牌过期」，都提示用户重新填写。 */
    private fun unauthorizedError(): RemoteError = RemoteError(
        kind = RemoteErrorKind.UNAUTHORIZED,
        message = "GitHub 认证失败（HTTP 401）：请检查令牌是否有效、是否已过期",
        status = HTTP_UNAUTHORIZED
    )

    /** 403：GitHub 用它表示权限不足、也用来表示触发限流。 */
    private fun forbiddenError(response: ApiResponse): RemoteError = RemoteError(
        kind = RemoteErrorKind.UNAUTHORIZED,
        message = "GitHub 拒绝访问（HTTP 403）：请确认令牌具备该仓库 Contents 的读写权限，" +
                "并且没有触发 API 限流",
        status = response.status
    )

    /** 仓库 404：仓库名写错，或令牌无权访问（GitHub 对两者都回 404）。 */
    private fun repositoryNotFoundError(coordinates: RepoCoordinates): RemoteError = RemoteError(
        kind = RemoteErrorKind.NOT_FOUND,
        message = "找不到 GitHub 仓库 ${coordinates.owner}/${coordinates.repo}：" +
                "请检查仓库名是否正确、令牌是否有权访问它",
        status = HTTP_NOT_FOUND
    )

    /** 文件 404。 */
    private fun fileNotFoundError(name: String): RemoteError = RemoteError(
        kind = RemoteErrorKind.NOT_FOUND,
        message = "云端文件不存在：$name",
        status = HTTP_NOT_FOUND
    )

    /** 目录 404：与 WebDAV 保持同样的语义，让「目录还没建」这条既有分支继续生效。 */
    private fun directoryNotFoundError(path: String): RemoteError = RemoteError(
        kind = RemoteErrorKind.NOT_FOUND,
        message = "云端目录不存在：${path.ifEmpty { "（仓库根目录）" }}",
        status = HTTP_NOT_FOUND
    )

    /** 非预期状态码：附带 GitHub 的 `message` 字段，便于排查。 */
    private fun httpError(response: ApiResponse, prefix: String): RemoteError {
        val detail = apiMessage(response.body)
        val suffix = if (detail.isEmpty()) "" else "：$detail"
        return RemoteError(
            kind = if (response.status == HTTP_NOT_FOUND) {
                RemoteErrorKind.NOT_FOUND
            } else {
                RemoteErrorKind.HTTP
            },
            message = "$prefix（HTTP ${response.status}）$suffix",
            status = response.status
        )
    }

    /** 取 GitHub 错误响应里的 `message` 字段（截断）。 */
    private fun apiMessage(body: String): String {
        val element = parseJson(body) ?: return ""
        val message = (element as? JsonObject)?.string("message").orEmpty().trim()
        if (message.isEmpty()) return ""
        val flattened = message.replace(Regex("\\s+"), " ")
        return if (flattened.length <= 200) flattened else flattened.take(200) + "…"
    }

    /** 解析/编码阶段的协议错误。 */
    private fun protocolError(message: String, cause: Throwable? = null): RemoteError =
        RemoteError(kind = RemoteErrorKind.PROTOCOL, message = message, cause = cause)

    /** 响应体为空的兜底错误。 */
    private fun emptyResponseError(): RemoteError =
        protocolError("GitHub 返回了空响应")

    /** 文件名非法。 */
    private fun invalidNameError(name: String): RemoteError = protocolError("非法的云端文件名：$name")

    /** 取一段可读的异常说明；不含任何凭据。 */
    private fun describe(error: Throwable): String {
        val text = error.message?.trim().orEmpty()
        return if (text.isEmpty()) error.javaClass.simpleName else text
    }

    private companion object {
        /** HTTP 状态码常量。 */
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_UNPROCESSABLE = 422

        /** 用字符串构造方法，避免依赖具体 Ktor 版本是否提供同名常量。 */
        val METHOD_GET = HttpMethod("GET")
        val METHOD_PUT = HttpMethod("PUT")
        val METHOD_DELETE = HttpMethod("DELETE")
    }
}

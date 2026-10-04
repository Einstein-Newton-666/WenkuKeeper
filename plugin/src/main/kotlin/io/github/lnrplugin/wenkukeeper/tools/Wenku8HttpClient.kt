package io.github.lnrplugin.wenkukeeper.tools

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.runCatching
import io.github.lnrplugin.wenkukeeper.PluginConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.EOFException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.ConnectException
import java.nio.charset.Charset

/**
 * wenku8 页面所使用的字符集。
 *
 * 站点声明为 GBK，但实际以 GB18030 输出：`•`、`〜` 等字符不在 GBK 范围内，会以 GB18030
 * 独有的四字节序列传输，若按 GBK 解码会碎成乱码。GB18030 是 GBK 的严格超集，因此统一按
 * GB18030 解码不会影响原本正确的内容。
 */
private val WENKU8_CHARSET: Charset = Charset.forName(PluginConstants.WENKU8_CHARSET)

/** 桌面端 UA。站点对非常规 UA 会返回降级页面。 */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

/** 镜像探测使用的超时（毫秒）。探测发生在加载路径上，必须远短于常规请求超时。 */
private const val PROBE_TIMEOUT_MILLIS = 3_000L

/**
 * 插件专用的 wenku8 网络访问层。
 *
 * 与宿主内置数据源不同，插件不会携带任何账号 Cookie：文库里不需要登录即可阅读的章节
 * 都能正常获取，而 VIP 章节在未登录时本来就会返回受限页面，因此插件选择不内置凭据，
 * 避免把他人账号信息分发出去。
 *
 * 该类同时负责：
 * - 以 GB18030 正确解码响应体；
 * - 对请求做限流，避免触发站点的访问频率限制；
 * - 解析为关闭了格式化输出的 XML 语法 [Document]，以便 XPath 选择器稳定命中。
 */
class Wenku8HttpClient(
    private val hosts: List<String> = PluginConstants.HOSTS
) {
    private val semaphore = Semaphore(PluginConstants.MAX_CONCURRENT_REQUESTS)

    /**
     * 共享的 Ktor 客户端。
     *
     * 使用 CIO 引擎而不是 OkHttp：宿主的 PluginClassLoader 对插件内的类是子加载器优先，
     * 插件若自带 OkHttp 就会实际运行这一份，在新版 Android 上会因框架存根而崩溃。
     *
     * 实测（模拟器 + 本机网络）：CIO 与 `Android`（HttpURLConnection/Conscrypt）引擎访问
     * `www.wenku8.net` 都拿到 **HTTP 403**（Cloudflare challenge），宿主内置的
     * OkHttp+固定 Cookie 数据源同样取不到页面。也就是说站点在此网络下对所有客户端都不可用，
     * 不是引擎选择的问题，插件把数据源标记为离线是正确行为。
     */
    val ktorClient: HttpClient = HttpClient(CIO) {
        install(UserAgent) {
            agent = DESKTOP_USER_AGENT
        }
        install(DefaultRequest) {
            headers {
                append(
                    HttpHeaders.Accept,
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                append(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
                append("Upgrade-Insecure-Requests", "1")
            }
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            exponentialDelay()
            retryOnExceptionIf { _, cause ->
                cause is EOFException || cause is ConnectException
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
    }

    /**
     * 当前选中的站点主机。
     *
     * 初始为 [hosts] 中的第一项；[selectHost]、[isOffLine] 在探测到可用镜像后会更新它。
     * 需要在每次请求时读取实时值，不要在构造阶段缓存。
     */
    var host: String = hosts.firstOrNull() ?: PluginConstants.HOSTS.first()
        private set

    /** 当前主机的根地址，语义上等同于 [host]，供调用方表达「取实时值」的意图。 */
    val baseUrl: String get() = host

    /**
     * 当前使用的 wenku8 会话 Cookie；空串表示不带任何凭据。
     *
     * 站点现在对无 Cookie 的请求直接返回 403 challenge，带上它之后才可能拿到内容。
     * 值由 [io.github.lnrplugin.wenkukeeper.source.WenkuKeeperDataSource] 的轮询循环写入——
     * 读取用户设置是挂起操作，不能在本类里同步读，所以这里只保存一个快照值。
     */
    @Volatile
    private var sessionCookie: String = ""

    /**
     * 更新会话 Cookie。
     *
     * @param value 从插件设置读到的 Cookie；空白表示关闭，退回无凭据请求
     */
    fun updateCookie(value: String) {
        sessionCookie = value.trim()
    }

    /**
     * 给请求附上可选的会话 Cookie。
     *
     * 只在非空时添加请求头，避免给站点送去一个空的 `Cookie:`。
     */
    private fun HttpRequestBuilder.withSessionCookie() {
        if (sessionCookie.isNotEmpty()) header(HttpHeaders.Cookie, sessionCookie)
    }

    /**
     * 依据用户偏好与可用性选择站点主机。
     *
     * 用户明确指定了镜像时只尝试该镜像：若不可用则保持当前值不变，而不是静默改用别的镜像
     * ——那样会让用户误以为自己的设置在生效。未指定时才按 [hosts] 顺序探测。
     *
     * @param preferred 用户指定的主机；传 null 或空字符串表示自动探测
     */
    suspend fun selectHost(preferred: String?) {
        val normalized = preferred?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (normalized != null) {
            if (probe(normalized)) host = normalized
            return
        }
        for (candidate in hosts) {
            if (probe(candidate)) {
                host = candidate
                return
            }
        }
    }

    /**
     * 探测单个主机是否可用。
     *
     * 使用远短于常规请求的超时：探测发生在插件加载与离线轮询路径上，若沿用 20 秒的请求超时，
     * 三个镜像都不可达时会把加载阻塞近一分钟。探测只关心「能不能连上」，因此 3 秒足够。
     *
     * @param baseUrl 需要探测的站点根地址
     *
     * @return 返回 2xx 时视为可用
     */
    private suspend fun probe(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ktorClient.get(baseUrl) {
                withSessionCookie()
                timeout {
                    requestTimeoutMillis = PROBE_TIMEOUT_MILLIS
                    connectTimeoutMillis = PROBE_TIMEOUT_MILLIS
                    socketTimeoutMillis = PROBE_TIMEOUT_MILLIS
                }
            }.status.isSuccess()
        }.getOr(false)
    }

    /**
     * 判断站点整体是否处于离线状态。
     *
     * 会依次探测 [hosts]，只要有一个可用就认为在线，并在成功时把 [host] 切到可用镜像。
     *
     * @return 全部镜像都不可用时返回 true
     */
    suspend fun isOffLine(): Boolean {
        for (candidate in hosts) {
            if (probe(candidate)) {
                host = candidate
                return false
            }
        }
        return true
    }

    /**
     * 请求页面并解析为 [Document]。
     *
     * 不依赖响应头声明的字符集：wenku8 的头部信息并不可靠，因此这里读取原始字节后按
     * [WENKU8_CHARSET] 自行解码。解析结果关闭了格式化输出并切换为 XML 语法，使空白节点
     * 不会混入文本内容，XPath 也能按元素层级稳定匹配。
     *
     * @param pathOrUrl 相对 [host] 的路径，或以 `http` 开头的完整地址
     *
     * @return 解析后的文档，或包含原始异常的失败结果
     */
    suspend fun getDocument(pathOrUrl: String): Result<Document, Throwable> = withContext(Dispatchers.IO) {
        semaphore.withPermit {
            runCatching {
                val url = absoluteUrl(pathOrUrl)
                val bytes = ktorClient.get(url) { withSessionCookie() }.bodyAsBytes()
                buildDocument(bytes)
            }
        }
    }

    /**
     * 请求页面并解析为 [Document]，**非 2xx 视为失败**。
     *
     * 与 [getDocument] 的区别只在这一点：[getDocument] 不检查状态码，因为它服务于数据源
     * ——那里站点有时会用非 2xx 返回可解析的正文。但站点同步不行：Cloudflare 的挑战页、
     * 未登录提示页都是 403/200，正文里没有书架表格，若不检查状态码，调用方会把「被拦截」
     * 误读成「站点书架上没有书」。
     *
     * @param pathOrUrl 相对 [host] 的路径，或以 `http` 开头的完整地址
     *
     * @return 解析后的文档；状态码非 2xx 时为失败
     */
    suspend fun getDocumentChecked(pathOrUrl: String): Result<Document, Throwable> = withContext(Dispatchers.IO) {
        semaphore.withPermit {
            runCatching {
                val url = absoluteUrl(pathOrUrl)
                val response = ktorClient.get(url) { withSessionCookie() }
                val status = response.status
                check(status.isSuccess()) {
                    "站点返回 HTTP ${status.value}（可能未登录或未通过 Cloudflare 校验）"
                }
                buildDocument(response.bodyAsBytes())
            }
        }
    }

    /**
     * 按 [WENKU8_CHARSET] 解码并解析 HTML。
     *
     * 统一在这里设置输出参数：关闭格式化输出并切换为 XML 语法，使空白节点不会混入文本内容。
     */
    private fun buildDocument(bytes: ByteArray): Document = Jsoup.parse(String(bytes, WENKU8_CHARSET))
        .outputSettings(
            Document.OutputSettings()
                .prettyPrint(false)
                .syntax(Document.OutputSettings.Syntax.xml)
        )

    /**
     * 下载原始字节（用于封面等二进制资源）。
     *
     * @param url 完整的资源地址
     *
     * @return 响应字节，或包含原始异常的失败结果
     */
    suspend fun getBytes(url: String): Result<ByteArray, Throwable> = withContext(Dispatchers.IO) {
        semaphore.withPermit {
            runCatching {
                ktorClient.get(absoluteUrl(url)) {
                    withSessionCookie()
                    header(HttpHeaders.Referrer, host)
                }.bodyAsBytes()
            }
        }
    }

    /**
     * 把相对路径补全为绝对地址。
     *
     * @param pathOrUrl 相对路径或完整地址
     *
     * @return 绝对地址
     */
    fun absoluteUrl(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("http://", ignoreCase = true) ||
                pathOrUrl.startsWith("https://", ignoreCase = true) -> pathOrUrl

        pathOrUrl.startsWith("//") -> "https:$pathOrUrl"
        pathOrUrl.startsWith("/") -> host + pathOrUrl
        else -> "$host/$pathOrUrl"
    }

    /** 关闭底层网络资源。插件卸载时由插件入口调用。 */
    fun close() {
        runCatching { ktorClient.close() }
    }
}

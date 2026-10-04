package io.github.lnrplugin.wenku8plus.source

import android.content.Context
import android.net.Uri
import androidx.navigation3.runtime.NavKey
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.runCatching
import io.github.lnrplugin.wenku8plus.PluginConstants
import io.github.lnrplugin.wenku8plus.PluginSettings
import io.github.lnrplugin.wenku8plus.source.explore.PageFetcher
import io.github.lnrplugin.wenku8plus.source.explore.Wenku8PlusExplorePageProvider
import io.github.lnrplugin.wenku8plus.source.search.Wenku8PlusSearchProvider
import io.github.lnrplugin.wenku8plus.tools.Wenku8HttpClient
import io.github.lnrplugin.wenku8plus.tools.attrOrNull
import io.github.lnrplugin.wenku8plus.tools.cleanText
import io.github.lnrplugin.wenku8plus.tools.normalizeUrl
import io.github.lnrplugin.wenku8plus.tools.selectSingleXPath
import io.github.lnrplugin.wenku8plus.tools.selectXPathOrEmpty
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.buildContent
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.paragraph
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.mapAsWebRequestError
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

/**
 * 由本插件提供的 wenku8 网络数据源。
 *
 * 该数据源与宿主内置的 wenku8 数据源使用**不同的标识符**（`wenku8plus:wenku8_plus`），
 * 因为书本 id 会按数据源命名空间区分；若两者同名，书架中的条目将无法区分。独立命名也
 * 让用户可以分别启用/禁用两者。
 *
 * 设计要点：
 * - **不内置任何账号凭据。** 免登录即可阅读的章节都能正常获取；VIP 章节在未登录时本就
 *   返回受限页面，插件不会为此分发他人账号信息。
 * - **按 GB18030 解码。** 站点声明为 GBK，但 `•`、`〜` 等字符以 GB18030 四字节序列传输，
 *   交给响应头声明的字符集解码会产生乱码。
 * - **多镜像自动切换。** [PluginConstants.HOSTS] 中的镜像会依次探测，优先使用可用者。
 *
 * 构造器保持无参，以便宿主 [io.nightfish.lightnovelreader.data.plugin.injector.PluginInjector]
 * 稳定地实例化它；插件设置经 [PluginSettingsRegistry] 读取。
 */
@Suppress("unused")
@WebDataSource(
    name = PluginConstants.SOURCE_NAME,
    provider = PluginConstants.SOURCE_PROVIDER
)
class Wenku8PlusDataSource : WebBookDataSource {

    companion object {
        private const val TAG = "Wenku8PlusDataSource"

        /** 书本详情页标题形如「书名 (副标题)」，副标题可能不存在。 */
        private val TITLE_REGEX = Regex("(.*?)\\s*[（(](.*)[)）]\\s*$")

        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    /** 共享网络层：负责解码、限流与镜像探测。 */
    private val httpClient = Wenku8HttpClient()

    /** 离线状态热流，供宿主展示与重试策略使用。 */
    private val offLineStateFlow = MutableStateFlow(true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 插件设置仓库。宿主在实例化数据源之前就已由插件入口登记，见 [PluginSettingsRegistry]。 */
    private val userDataRepository: UserDataRepositoryApi? get() = PluginSettingsRegistry.getRepository()

    override val id = PluginConstants.WEB_DATA_SOURCE

    override val permits: Int = PluginConstants.MAX_CONCURRENT_REQUESTS

    /**
     * 是否启用探索页。
     *
     * 由 [onLoad] 启动的循环定期刷新，因此不需要在构造阶段做任何阻塞读取 —— 宿主会在主线程上
     * 实例化数据源，而 Api 明确要求不要在初始化阶段读取用户数据。
     */
    @Volatile
    private var exploreEnabled: Boolean = PluginSettings.DEFAULT_ENABLE_EXPLORE

    /**
     * 数据源缓存。
     *
     * 目录与书本详情变动不频繁，缓存可以显著减少请求量；章节正文同样走这份缓存，
     * 避免用户在章节间来回翻动时重复抓取。有效期取 [PluginConstants.CACHE_TIMEOUT_MINUTES]，
     * 与宿主内置的 wenku8 数据源一致。
     */
    override val cache: Cache = Cache(
        maxCountEachType = 32,
        timeout = PluginConstants.CACHE_TIMEOUT_MINUTES * 60 * 1000
    )

    override val offLine: Boolean get() = offLineStateFlow.value

    override val isOffLineFlow: StateFlow<Boolean> = offLineStateFlow

    override suspend fun isOffLine(): Boolean = httpClient.isOffLine()

    /**
     * 图片请求头。
     *
     * 站点的封面与插图有防盗链校验，缺少 Referer 会返回占位图；这里返回对应镜像的地址。
     * 该属性会在每次展示图片时被读取，因此镜像切换后能自动跟随。
     */
    override val imageHeader: Map<String, String>
        get() = mapOf("Referer" to httpClient.host)

    /** 章节页所需的外部抓取函数，同时交给探索页数据源复用。 */
    private val pageFetcher: PageFetcher = object : PageFetcher {
        override suspend fun fetch(pathOrUrl: String) = httpClient.getDocument(pathOrUrl).getOr(null)
    }

    override val searchProvider: SearchProvider by lazy {
        Wenku8PlusSearchProvider(
            host = httpClient.host,
            fetchPage = pageFetcher,
            // 搜索提供器在首次访问时构造，之后镜像可能切换；每次请求都读实时主机。
            hostProvider = { httpClient.baseUrl }
        )
    }

    override val explorePageProvider: ExplorePageProvider by lazy {
        Wenku8PlusExplorePageProvider(
            host = httpClient.host,
            fetchPage = pageFetcher,
            // 同上：探索页也要跟随实时镜像，避免固定在构造时的旧镜像上。
            hostProvider = { httpClient.baseUrl },
            enabled = { exploreEnabled }
        )
    }

    override fun onLoad() {
        scope.launch {
            // 先取一次会话 Cookie 再探测镜像：站点现在对无 Cookie 的请求直接返回 403，
            // 不带 Cookie 探测会把所有镜像都误判成不可用。
            httpClient.updateCookie(readCookie())
            // 优先使用用户指定的镜像；未指定时按内置顺序自动探测。
            // selectHost 内部每个候选只做一次带 3 秒超时的探测，因此这里最多阻塞数秒。
            httpClient.selectHost(readPreferredHost())
            while (currentCoroutineContext().isActive) {
                val offLine = httpClient.isOffLine()
                offLineStateFlow.value = offLine
                // 顺带刷新探索页开关，让用户在插件页面里的改动无需重启宿主即可生效。
                exploreEnabled = readExploreEnabled()
                // 同理刷新会话 Cookie：站点会话会过期，改了也应当尽快生效。
                httpClient.updateCookie(readCookie())
                // 离线时更快重试，在线时降低探测频率。
                delay((if (offLine) 5_000L else 120_000L).milliseconds)
            }
        }
    }

    /**
     * 读取用户填写的 wenku8 会话 Cookie。
     *
     * 留空表示不带凭据（此时站点可能返回 Cloudflare 挑战页）。读取失败按留空处理：
     * 设置读取问题不应该影响数据源本身。
     *
     * @return 去空白后的 Cookie；未设置或读取失败时为空串
     */
    private suspend fun readCookie(): String = runCatching {
        userDataRepository
            ?.stringUserData(PluginSettings.WENKU8_COOKIE)
            ?.get()
            ?.trim()
    }.getOr(null).orEmpty()

    /**
     * 读取「启用探索页」设置。
     *
     * 读取失败时退回默认值：设置读取问题不应该影响数据源本身。
     *
     * @return 用户是否启用探索页
     */
    private suspend fun readExploreEnabled(): Boolean = runCatching {
        userDataRepository
            ?.booleanUserData(PluginSettings.ENABLE_EXPLORE)
            ?.get()
    }.getOr(null) ?: PluginSettings.DEFAULT_ENABLE_EXPLORE

    /** 读取用户在插件页面中选择的站点；未设置时返回 null 表示自动探测。 */
    private suspend fun readPreferredHost(): String? =
        userDataRepository
            ?.stringUserData(PluginSettings.PREFERRED_HOST)
            ?.get()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    // -----------------------------------------------------------------
    // 书本详情
    // -----------------------------------------------------------------

    override suspend fun getBookInformation(id: String): Result<BookInformation, WebRequestError> {
        val document = httpClient.getDocument("book/$id.htm")
            .mapAsWebRequestError("网络请求失败", "请求书本详情时失败(id=$id)")
            .getOrElse { return Err(it) }

        val titleNode = firstMatch(
            document,
            "//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[1]/td/table/tbody/tr/td[1]/span/b",
            "//*[@id=\"content\"]/div[1]/table[1]/tr[1]/td/table/tr/td[1]/span/b",
            "//*[@id=\"content\"]//table[1]//tr[1]//td[1]/span/b"
        )
        val rawTitle = titleNode?.cleanText().orEmpty()
        if (rawTitle.isEmpty()) {
            return Err(WebRequestError("解析错误", "无法解析书本「$id」的标题，站点结构可能已变更。"))
        }

        // 站点会以「因版权问题」替换受限书本的正文，此时没有可展示的数据。
        if (document.text().contains("因版权问题")) {
            return Err(
                WebRequestError("版权受限", "由于数据源方面的版权原因，无法加载书本「$rawTitle」的信息。")
            )
        }

        return runCatching {
            val titleGroup = TITLE_REGEX.find(rawTitle)
            val cover = firstMatch(
                document,
                "//*[@id=\"content\"]/div[1]/table[2]/tbody/tr/td[1]/img",
                "//*[@id=\"content\"]/div[1]/table[2]/tr/td[1]/img",
                "//*[@id=\"content\"]//table[2]//img"
            )?.attrOrNull("src")?.let { normalizeUrl(httpClient.host, it) }.orEmpty()

            BookInformation(
                id = id,
                title = titleGroup?.groupValues?.getOrNull(1)?.trim().orEmpty().ifEmpty { rawTitle },
                subtitle = titleGroup?.groupValues?.getOrNull(2)?.trim().orEmpty(),
                coverUri = Uri.parse(cover),
                author = firstMatch(
                    document,
                    "//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[2]",
                    "//*[@id=\"content\"]/div[1]/table[1]/tr[2]/td[2]"
                )?.cleanText()?.removePrefix("小说作者：").orEmpty(),
                description = firstMatch(
                    document,
                    "//*[@id=\"content\"]/div[1]/table[2]/tbody/tr/td[2]/span[6]",
                    "//*[@id=\"content\"]/div[1]/table[2]/tr/td[2]/span[6]"
                )?.cleanText().orEmpty(),
                tags = firstMatch(
                    document,
                    "//*[@id=\"content\"]/div[1]/table[2]/tbody/tr/td[2]/span[1]/b",
                    "//*[@id=\"content\"]/div[1]/table[2]/tr/td[2]/span[1]/b"
                )?.cleanText()
                    ?.removePrefix("作品Tags：")
                    ?.split(" ", "\u3000")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                publishingHouse = firstMatch(
                    document,
                    "//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[1]",
                    "//*[@id=\"content\"]/div[1]/table[1]/tr[2]/td[1]"
                )?.cleanText()?.removePrefix("文库分类：").orEmpty(),
                wordCount = WordCount(
                    firstMatch(
                        document,
                        "//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[5]",
                        "//*[@id=\"content\"]/div[1]/table[1]/tr[2]/td[5]"
                    )?.cleanText()
                        ?.removePrefix("全文长度：")
                        ?.removeSuffix("字")
                        ?.trim()
                        ?.toIntOrNull()
                        ?: -1
                ),
                lastUpdated = firstMatch(
                    document,
                    "//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[4]",
                    "//*[@id=\"content\"]/div[1]/table[1]/tr[2]/td[4]"
                )?.cleanText()
                    ?.removePrefix("最后更新：")
                    ?.trim()
                    ?.let { kotlin.runCatching { LocalDate.parse(it, DATE_FORMATTER).atStartOfDay() }.getOrNull() }
                    ?: LocalDateTime.MIN,
                isComplete = firstMatch(
                    document,
                    "//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[3]",
                    "//*[@id=\"content\"]/div[1]/table[1]/tr[2]/td[3]"
                )?.cleanText()?.contains("已完结") ?: false
            )
        }.mapError { throwable ->
            WebRequestError("解析错误", "解析书本「$id」的信息时失败：${throwable.message}", throwable)
        }
    }

    // -----------------------------------------------------------------
    // 卷与章节目录
    // -----------------------------------------------------------------

    override suspend fun getBookVolumes(id: String): Result<BookVolumes, WebRequestError> {
        val numericId = id.toIntOrNull()
            ?: return Err(WebRequestError("解析错误", "书本 id 非法：$id"))

        val document = httpClient.getDocument("novel/${numericId / 1000}/$id/index.htm")
            .mapAsWebRequestError("网络请求失败", "请求书本目录时失败(id=$id)")
            .getOrElse { return Err(it) }

        return runCatching {
            val volumes = mutableListOf<Volume>()
            var currentTitle: String? = null
            var currentId: String? = null
            var chapters = mutableListOf<ChapterInformation>()

            fun flushVolume() {
                val volumeTitle = currentTitle ?: return
                volumes += Volume(
                    volumeId = currentId ?: "${id}_${volumes.size}",
                    volumeTitle = volumeTitle,
                    chapters = chapters.toList()
                )
                chapters = mutableListOf()
            }

            // 目录页的每一行 tr 要么是卷标题行（td.vcss），要么是若干章节链接。
            // 镜像之间可能省略 tbody，因此两种层级都尝试。
            val rows = document.selectXPathOrEmpty("/html/body/table/tbody/tr")
                .ifEmpty { document.selectXPathOrEmpty("/html/body/table/tr") }
            for (row in rows) {
                val firstCell = row.selectFirst("td")
                if (firstCell?.attr("class") == "vcss") {
                    flushVolume()
                    currentTitle = firstCell.cleanText().ifEmpty { "正文" }
                    currentId = firstCell.attrOrNull("vid")
                    continue
                }
                for (anchor in row.select("td > a")) {
                    val chapterId = anchor.attrOrNull("href")
                        ?.substringBeforeLast(".")
                        ?.substringAfterLast("/")
                        ?.takeIf { it.isNotEmpty() }
                        ?: continue
                    val chapterTitle = anchor.cleanText().ifEmpty { chapterId }
                    chapters += ChapterInformation(chapterId, chapterTitle)
                }
            }
            flushVolume()

            // 少数书本没有卷标题行，此时把全部章节放进一个默认卷，避免目录为空。
            if (volumes.isEmpty() && chapters.isNotEmpty()) {
                volumes += Volume("${id}_0", "正文", chapters.toList())
            }

            BookVolumes(bookId = id, volumes = volumes)
        }.mapError { throwable ->
            WebRequestError("解析错误", "解析书本「$id」的目录时失败：${throwable.message}", throwable)
        }
    }

    // -----------------------------------------------------------------
    // 章节正文
    // -----------------------------------------------------------------

    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String
    ): Result<ChapterContent, WebRequestError> {
        val numericId = bookId.toIntOrNull()
            ?: return Err(WebRequestError("解析错误", "书本 id 非法：$bookId"))

        val document = httpClient
            .getDocument("novel/${numericId / 1000}/$bookId/$chapterId.htm")
            .mapAsWebRequestError("网络请求失败", "请求章节内容时失败(chapterId=$chapterId)")
            .getOrElse { return Err(it) }

        val title = document.selectSingleXPath("//*[@id=\"title\"]")?.cleanText().orEmpty()

        if (document.text().contains("因版权问题")) {
            return Err(
                WebRequestError(
                    "版权受限",
                    "由于数据源方面的版权原因，无法加载「${title.ifEmpty { chapterId }}」的章节内容。"
                )
            )
        }

        val contentNode = document.selectSingleXPath("//*[@id=\"content\"]")
            ?: return Err(WebRequestError("解析错误", "无法解析章节「$chapterId」的正文"))

        val content = runCatching {
            buildContent {
                // 站点把段落直接写成文本节点；连续的空行用于分隔场景，需要保留少量留白。
                var pendingBlankLines = 0
                for (node in contentNode.childNodes()) {
                    when {
                        node is TextNode -> {
                            val text = node.nodeValue().replace('\u00a0', ' ').trimEnd()
                            if (text.isBlank()) {
                                pendingBlankLines++
                                continue
                            }
                            if (pendingBlankLines >= 3) {
                                repeat(pendingBlankLines) {
                                    paragraph { text("\n") }
                                }
                            }
                            pendingBlankLines = 0
                            paragraph { text(text) }
                        }

                        node is Element && node.`is`("div.divimage") -> {
                            node.selectFirst("img")
                                ?.attrOrNull("src")
                                ?.let { normalizeUrl(httpClient.host, it) }
                                ?.let { image(Uri.parse(it)) }
                        }
                    }
                }
            }
        }.getOrElse { throwable ->
            return Err(
                WebRequestError("解析错误", "解析章节「$chapterId」的正文时失败：${throwable.message}", throwable)
            )
        }

        return Ok(
            ChapterContent(
                id = chapterId,
                title = title.ifEmpty { chapterId },
                content = content,
                prevChapter = chapterLinkAt(document, "//*[@id=\"foottext\"]/a[3]"),
                nextChapter = chapterLinkAt(document, "//*[@id=\"foottext\"]/a[4]")
            )
        )
    }

    /**
     * 解析章节页底部的上一章/下一章链接。
     *
     * 链接可能指向目录页（`index.htm`）或站点公告（`article`），这两种情况都不算相邻章节。
     *
     * @param document 章节页文档
     * @param xpath 链接所在的 XPath
     *
     * @return 相邻章节 id，不存在时返回 null
     */
    private fun chapterLinkAt(document: org.jsoup.nodes.Document, xpath: String): String? {
        val href = document.selectSingleXPath(xpath)?.attrOrNull("href") ?: return null
        if (href == "index.htm" || href.contains("article") || href.startsWith("index")) return null
        return href.substringBeforeLast(".").substringAfterLast("/").takeIf { it.isNotEmpty() }
    }

    // -----------------------------------------------------------------
    // 封面（EPUB 分卷导出用）
    // -----------------------------------------------------------------

    override suspend fun getCoverUriInVolume(
        bookId: String,
        volume: Volume,
        volumeChapterContentMap: MutableMap<String, ChapterContent>,
        context: Context
    ): Uri? = null

    // -----------------------------------------------------------------
    // 标签跳转
    // -----------------------------------------------------------------

    /**
     * 处理书本标签的点击事件。
     *
     * 只有当标签属于站点的标签体系、且用户没有关闭探索页时才跳转到探索展开页；否则交由宿主
     * 按默认行为处理。关闭探索页时必须一并拒绝跳转：此时展开页只会返回空结果，而宿主的展开页
     * 在结果为空期间会一直显示加载指示器，跳过去反而像卡死。
     *
     * @param tag 被点击的标签文本
     *
     * @return 探索展开页路由，标签未注册或探索页已关闭时返回 null
     */
    override fun progressBookTagClick(tag: String): NavKey? {
        if (!exploreEnabled) return null
        val normalized = tag.trim().removePrefix("#")
        if (normalized !in PluginConstants.TAGS) return null
        return Route.Main.Explore.Expanded(normalized)
    }

    /**
     * 尝试多个 XPath，返回第一个命中的节点。
     *
     * 站点在不同镜像上会省略 `tbody` 层级（浏览器解析与源码不一致），把两种写法都列出并
     * 依次尝试，可以避免因镜像差异导致整本书解析失败。
     *
     * @param document 待查询的文档
     * @param xpaths 候选 XPath，按优先级排列
     *
     * @return 第一个命中的元素，全部落空时返回 null
     */
    private fun firstMatch(document: Document, vararg xpaths: String): Element? {
        for (xpath in xpaths) {
            document.selectSingleXPath(xpath)?.let { return it }
        }
        return null
    }
}

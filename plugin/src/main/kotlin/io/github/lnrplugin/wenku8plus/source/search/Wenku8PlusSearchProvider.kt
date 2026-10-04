package io.github.lnrplugin.wenku8plus.source.search

import com.github.michaelbull.result.get
import io.github.lnrplugin.wenku8plus.PluginConstants
import io.github.lnrplugin.wenku8plus.source.explore.PageFetcher
import io.github.lnrplugin.wenku8plus.source.explore.extractWenku8BookId
import io.github.lnrplugin.wenku8plus.tools.Wenku8HttpClient
import io.github.lnrplugin.wenku8plus.tools.selectSingleXPath
import io.github.lnrplugin.wenku8plus.tools.selectXPathOrEmpty
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.search.AbstractSearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.URLEncoder

/** 按书名搜索的搜索类型标识，直接作为站点的 `searchtype` 参数。 */
private const val SEARCH_TYPE_ARTICLE_NAME = "articlename"

/** 按作者搜索的搜索类型标识，直接作为站点的 `searchtype` 参数。 */
private const val SEARCH_TYPE_AUTHOR = "author"

/**
 * 站点在两次搜索间隔不足 5 秒时返回的提示。
 *
 * 只匹配去掉空白后的核心片段：站点在不同镜像上可能在句子里插入半角或全角空格，
 * 逐字匹配整句容易漏判。
 */
private const val SEARCH_INTERVAL_HINT = "两次搜索的间隔时间不得少于"

/** 唯一搜索结果中「小说目录」入口所在的 XPath，与宿主内置实现一致。 */
private const val SINGLE_BOOK_MENU_XPATH =
    "//*[@id=\"content\"]/div[1]/div[4]/div/span[1]/fieldset/div/a"

/** 「小说目录」入口的文本，用于判断搜索结果是否只剩一本书。 */
private const val SINGLE_BOOK_MENU_TEXT = "小说目录"

/** 多结果页面中每张书本卡片的 XPath，与宿主内置实现一致。 */
private const val SEARCH_RESULT_CARDS_XPATH = "//*[@id=\"content\"]/table/tbody/tr/td/div"

/** 分页信息所在的 XPath，其文本形如 `当前页/总页数`。 */
private const val PAGE_LINK_XPATH = "//*[@id=\"pagelink\"]/em"

/** 卡片内指向书本详情页的链接，用于解析书本 id。 */
private const val CARD_LINK_SELECTOR = "div > div:nth-child(1) > a"

/**
 * 单次搜索最多翻页数。
 *
 * 总页数来自页面解析结果，一旦站点结构变化导致解析出异常大的数字，无上限的翻页会变成
 * 一次持续数小时、每 5 秒一次的请求风暴；这里给出一个远高于真实结果数的安全上限。
 */
private const val SEARCH_MAX_PAGES = 50

/**
 * wenku8 搜索提供器。
 *
 * 支持按书名与按作者两种搜索类型，行为与宿主内置的 wenku8 数据源保持一致：
 * - 关键词按 GB2312 编码后拼接到 `modules/article/search.php`，不能使用 UTF-8，
 *   否则站点无法识别中文关键词；
 * - 站点限制两次搜索至少间隔 5 秒，命中提示后会等待
 *   [PluginConstants.SEARCH_INTERVAL_MILLIS] 并重试同一页，不推进页码；
 * - 只有一个结果时站点直接给出「小说目录」入口，此时返回
 *   [SearchResult.SingleBook]，由宿主直接跳转；
 * - 其余情况按卡片逐个返回 [SearchResult.MultipleBook]，并按 `#pagelink` 的
 *   总页数翻页，结束时返回 [SearchResult.End]。
 *
 * 该类自身不持有网络客户端，页面抓取由数据源注入的 [PageFetcher] 完成。
 *
 * @param host 站点主机，例如 `https://www.wenku8.net`；仅在未提供 [hostProvider] 时作为默认值
 * @param fetchPage 页面抓取函数，由数据源注入
 * @param hostProvider 实时主机读取器；每次拼请求地址时调用，镜像切换后无需重建本对象
 */
class Wenku8PlusSearchProvider(
    private val host: String,
    private val fetchPage: PageFetcher,
    private val hostProvider: () -> String = { host },
) : AbstractSearchProvider() {

    /**
     * 使用插件共享的 [Wenku8HttpClient] 创建搜索提供器。
     *
     * 主机通过 `client.baseUrl` 实时读取，因此镜像切换后无需重建提供器。
     *
     * @param client 插件共享的网络访问层
     */
    constructor(client: Wenku8HttpClient) : this(
        host = client.host,
        fetchPage = object : PageFetcher {
            override suspend fun fetch(pathOrUrl: String) = client.getDocument(pathOrUrl).get()
        },
        hostProvider = { client.baseUrl }
    )

    init {
        // searchTypes 由父类的私有列表支撑，必须在 init 阶段完成注册。
        registerSearchType(SEARCH_TYPE_ARTICLE_NAME, "按书名搜索".local(), "请输入书本名称".local())
        registerSearchType(SEARCH_TYPE_AUTHOR, "按作者名搜索".local(), "请输入作者名称".local())
    }

    /**
     * 执行一次搜索。
     *
     * 结果以冷数据流的形式逐个返回；请求失败、关键词编码失败或唯一结果无法解析书本 id 时，
     * 返回 [SearchResult.Error] 并结束数据流。
     *
     * @param searchType 搜索类别，取自 [searchTypes]
     * @param keyword 用户输入的关键词
     *
     * @return 搜索结果的数据流
     */
    override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> = flow {
        // 站点按 GB2312 解析 searchkey；用 UTF-8 编码会得到乱码关键词。
        val encodedKeyword = kotlin.runCatching { URLEncoder.encode(keyword, "gb2312") }.getOrNull()
        if (encodedKeyword == null) {
            emit(SearchResult.Error("搜索失败：关键词「$keyword」无法按 GB2312 编码"))
            return@flow
        }

        var targetPage = 1
        var presentPage = 1
        var emittedBook = false

        while (presentPage <= targetPage) {
            val soup = fetchPage.fetch(searchUrl(searchType.type, encodedKeyword, presentPage))
            if (soup == null) {
                emit(SearchResult.Error("网络请求失败：无法加载搜索结果第 $presentPage 页"))
                return@flow
            }

            // 站点限制两次搜索至少间隔 5 秒：等待后重试同一页，不推进页码。
            val plainText = soup.text().replace(" ", "").replace("\u00a0", "")
            if (plainText.contains(SEARCH_INTERVAL_HINT)) {
                delay(PluginConstants.SEARCH_INTERVAL_MILLIS)
                continue
            }

            // 只有一个结果时站点直接给出目录入口，此时无需分页。
            val menu = soup.selectSingleXPath(SINGLE_BOOK_MENU_XPATH)
            if (menu != null && menu.text().contains(SINGLE_BOOK_MENU_TEXT)) {
                val id = extractWenku8BookId(menu.attr("href"))
                if (id == null) {
                    emit(SearchResult.Error("解析失败：无法解析唯一搜索结果的书本 id"))
                    return@flow
                }
                emit(SearchResult.SingleBook(id))
                return@flow
            }

            val cards = soup.selectXPathOrEmpty(SEARCH_RESULT_CARDS_XPATH)
            if (targetPage == 1) {
                val totalPages = soup.selectSingleXPath(PAGE_LINK_XPATH)
                    ?.text()
                    ?.split("/")
                    ?.getOrNull(1)
                    ?.trim()
                    ?.toIntOrNull()
                // 既没有分页信息也没有卡片，说明站点没有匹配到任何书本。
                if (totalPages == null && cards.isEmpty()) {
                    emit(SearchResult.Empty())
                    return@flow
                }
                if (totalPages != null && totalPages > 0) {
                    targetPage = totalPages.coerceAtMost(SEARCH_MAX_PAGES)
                }
            }

            for (card in cards) {
                val id = extractWenku8BookId(card.selectFirst(CARD_LINK_SELECTOR)?.attr("href"))
                    ?: continue
                emittedBook = true
                emit(SearchResult.MultipleBook(id))
            }

            presentPage++
            // 仅当确实还有下一页时才等待，避免把最后一次请求后的空等算进返回时间。
            if (presentPage <= targetPage) delay(PluginConstants.SEARCH_INTERVAL_MILLIS)
        }

        emit(if (emittedBook) SearchResult.End() else SearchResult.Empty())
    }.flowOn(Dispatchers.IO)

    /**
     * 拼接搜索请求地址。
     *
     * 主机取自 [hostProvider]，因此镜像切换后下一次搜索即会生效。
     *
     * @param searchType 站点 `searchtype` 参数
     * @param encodedKeyword 已按 GB2312 编码的关键词
     * @param page 页码，从 1 开始
     *
     * @return 完整的请求地址
     */
    private fun searchUrl(searchType: String, encodedKeyword: String, page: Int): String =
        "${hostProvider()}/modules/article/search.php" +
                "?searchtype=$searchType&searchkey=$encodedKeyword&page=$page"
}

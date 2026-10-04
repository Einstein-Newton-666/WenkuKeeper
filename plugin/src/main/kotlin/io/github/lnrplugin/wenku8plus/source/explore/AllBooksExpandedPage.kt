package io.github.lnrplugin.wenku8plus.source.explore

import android.net.Uri
import io.github.lnrplugin.wenku8plus.tools.normalizeUrl
import io.github.lnrplugin.wenku8plus.tools.selectSingleXPath
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.explore.filter.LocalFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 书本列表页默认的内容容器选择器（轻小说列表、完结全本）。 */
internal const val DEFAULT_BOOK_LIST_CONTENT_SELECTOR =
    "#content > table.grid > tbody > tr > td > div"

/** 排行榜页的内容容器选择器，比默认选择器少一层 `table.grid`。 */
internal const val TOP_LIST_CONTENT_SELECTOR = "#content > table > tbody > tr > td > div"

/** 标签页的内容容器选择器，书本列表位于表格的第二行。 */
internal const val TAGS_CONTENT_SELECTOR =
    "#content > table > tbody > tr:nth-child(2) > td > div"

/** 卡片页每行最多展示的书本数，与宿主内置实现一致。 */
internal const val ROW_BOOK_LIMIT = 6

/**
 * 等待 [AllBooksExpandedPageDataSource.loadMore] 的轮询间隔。
 *
 * 接口没有提供唤醒回调，只能轮询 `targetPage` 是否被放行；50ms 对用户点击「加载更多」
 * 完全无感，同时避免宿主内置实现 1ms 轮询带来的高频定时器唤醒。
 */
private val LOAD_MORE_POLL_INTERVAL = 50.milliseconds

/** 唯一结果页面中「小说目录」入口所在的 XPath，与宿主内置实现一致。 */
private const val SINGLE_BOOK_MENU_XPATH =
    "//*[@id=\"content\"]/div[1]/div[4]/div/span[1]/fieldset/div/a"

/** 「小说目录」入口的文本，用于判断列表是否退化成单本书。 */
private const val SINGLE_BOOK_MENU_TEXT = "小说目录"

/** 分页信息所在的 XPath，其文本形如 `当前页/总页数`。 */
private const val PAGE_LINK_XPATH = "//*[@id=\"pagelink\"]/em"

/** 卡片内指向书本详情页的链接。 */
private const val CARD_LINK_SELECTOR = "div > div:nth-child(1) > a"

/** 卡片内的书名链接。 */
private const val CARD_TITLE_SELECTOR = "div > div:nth-child(2) > b > a"

/** 卡片内的作者与文库信息行，形如 `小说作者：xxx / 文库分类：yyy`。 */
private const val CARD_AUTHOR_SELECTOR = "div > div:nth-child(2) > p:nth-child(2)"

/** 卡片内的封面图。 */
private const val CARD_COVER_SELECTOR = "div > div:nth-child(1) > a > img"

/** 形如 `/book/1234.htm` 的书本详情页链接。 */
private val BOOK_LINK_ID_REGEX = Regex("""(\d+)\.htm""", RegexOption.IGNORE_CASE)

/** 兜底使用：整串中最后一段连续数字。 */
private val TRAILING_DIGITS_REGEX = Regex("""(\d+)""")

/** 文库分类的可选项，取值与站点「文库分类」字段一致。 */
private val PUBLISHING_HOUSES: List<String> = listOf(
    "全部轻小说",
    "电击文库",
    "富士见文库",
    "角川文库",
    "MF文库J",
    "Fami通文库",
    "GA文库",
    "HJ文库",
    "一迅社",
    "集英社",
    "小学馆",
    "讲谈社",
    "少女文库",
    "其他文库",
    "游戏剧本"
)

/**
 * 通用书本列表展开页数据源。
 *
 * 同一份实现同时服务宿主内置数据源的多个入口，行为与宿主内置实现保持一致：
 * - 列表页与排行榜页的翻页参数为 `?page=<页码>`，附加参数由 [extendedParameters] 与
 *   [arg] 拼在后面；
 * - 首屏会从 `#pagelink` 读出总页数，之后由 [loadMore] 逐页放行；
 * - 站点在结果只剩一本书时会直接给出「小说目录」入口，此时返回
 *   [SearchResult.SingleBook]，由宿主直接跳转；
 * - 已把已知页数读完后，数据流保持活跃等待 [loadMore]，直到最后一页读完才返回
 *   [SearchResult.End]。
 *
 * 该类不持有网络客户端，页面抓取由数据源注入的 [PageFetcher] 完成。
 *
 * @param host 站点主机，例如 `https://www.wenku8.net`；仅在未提供 [hostProvider] 时作为默认值
 * @param fetchPage 页面抓取函数，由数据源注入
 * @param hostProvider 实时主机读取器；每次拼请求地址时调用，镜像切换后无需重建本对象
 * @param enabled 探索页开关读取器；返回 false 时只发射 [SearchResult.Empty]，不发起任何请求
 * @param baseUrl 列表页路径，相对站点主机（以 `/` 开头）；排行榜与标签页会传入各自的路径
 * @param extendedParameters 固定附加参数，例如 `&fullflag=1`、`&sort=allvisit`
 * @param contentSelector 书本卡片容器的选择器，不同列表页的层级不一致
 * @param title 展开页的显示标题
 * @param filtersBuilder 过滤器构造器；在构造时调用一次，可用它读取 `this` 绑定额外状态
 */
class AllBooksExpandedPageDataSource(
    private val host: String,
    private val fetchPage: PageFetcher,
    private val hostProvider: () -> String = { host },
    private val enabled: () -> Boolean = { true },
    private val baseUrl: String = "/modules/article/articlelist.php",
    private val extendedParameters: String = "",
    private val contentSelector: String = DEFAULT_BOOK_LIST_CONTENT_SELECTOR,
    override val title: String,
    filtersBuilder: AllBooksExpandedPageDataSource.() -> List<Filter<*>>,
) : ExploreExpandedPageDataSource {

    private var maxPage = 1
    private var targetPage = 1
    private var currentPage = 1

    /**
     * 由过滤器写入的附加请求参数。
     *
     * 目前只有标签页的排序过滤器会修改它，取值形如 `&v=1`；为空表示使用站点默认排序。
     */
    var arg: String = ""

    /** 展开页的过滤器列表；由构造时传入的 [filtersBuilder] 构建一次。 */
    override val filters: List<Filter<*>> = filtersBuilder(this)

    /**
     * 逐页加载书本列表。
     *
     * 数据流会一直保持活跃：首屏读完后不会结束，而是等待 [loadMore] 放行下一页；
     * 只有把已知的最后一页读完（或站点没有给出分页信息）时才返回 [SearchResult.End]。
     * 探索页被关闭时只发射一次 [SearchResult.Empty] 就结束，不会调用任何取页函数。
     *
     * @return 展开页结果的数据流
     */
    override fun getResultFlow(): Flow<SearchResult> = flow {
        if (!enabled()) {
            emit(SearchResult.Empty())
            return@flow
        }
        maxPage = 1
        targetPage = 1
        currentPage = 1
        while (targetPage <= maxPage) {
            if (targetPage < currentPage) {
                // 已知页数已读完，保持数据流活跃，等待 loadMore() 放行下一页。
                // 这里用轮询而非信号量：接口没有提供唤醒回调，50ms 的间隔对点击「加载更多」
                // 完全无感，又比宿主内置实现的 1ms 轮询少两个数量级的定时器唤醒。
                delay(LOAD_MORE_POLL_INTERVAL)
                continue
            }

            val soup = fetchPage.fetch("${hostProvider()}$baseUrl?page=$currentPage$arg$extendedParameters")
            if (soup == null) {
                emit(SearchResult.Error("网络请求失败：无法加载第 $currentPage 页"))
                return@flow
            }

            // 结果只剩一本书时站点直接给出目录入口，此时无需分页。
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

            if (maxPage == 1) {
                val totalPages = soup.selectSingleXPath(PAGE_LINK_XPATH)
                    ?.text()
                    ?.split("/")
                    ?.getOrNull(1)
                    ?.trim()
                    ?.toIntOrNull()
                // 站点没有给出分页信息时按「只有一页」处理，避免数据流空转。
                if (totalPages != null && totalPages > 0) maxPage = totalPages
            }

            val cards = soup.select(contentSelector)
            for (book in parseWenku8BookCards(cards, hostProvider())) {
                emit(SearchResult.MultipleBook(book.id))
            }

            currentPage++
            // 已知页数全部读完：结束本轮，宿主据此收起「加载更多」。
            if (currentPage > maxPage && targetPage >= maxPage) {
                emit(SearchResult.End())
                return@flow
            }
            delay(1.seconds)
        }
        emit(SearchResult.End())
    }.flowOn(Dispatchers.IO)

    /**
     * 放行下一页。
     *
     * 总页数未知或已到最后一页时不会越界，页码上限为已读出的总页数。
     */
    override fun loadMore() {
        targetPage = maxPage.coerceAtMost(targetPage + 1)
    }
}

/**
 * 文库分类过滤器。
 *
 * 属于本地过滤器：站点返回的书本卡片信息里已包含「文库分类」字段，因此由宿主在本地
 * 过滤，不需要额外的网络请求。
 */
class PublishingHouseSingleChoiceFilter : SingleChoiceFilter(
    title = "文库".local(),
    dialogTitle = "文库筛选".local(),
    description = "根据小说的文库筛选".local(),
    choices = PUBLISHING_HOUSES,
    defaultChoice = PUBLISHING_HOUSES.first()
), LocalFilter {

    /**
     * 判断一本书是否命中当前选择。
     *
     * @param bookInformation 待判断的书本信息
     *
     * @return 选择「全部轻小说」或文库一致时返回 true
     */
    override fun filter(bookInformation: BookInformation): Boolean =
        value == getDefaultChoice() || bookInformation.publishingHouse == value
}

/**
 * 从 wenku8 的链接中提取书本 id。
 *
 * 站点在不同页面给出的链接形式并不统一（`/book/1234.htm`、
 * `/novel/12/12345/index.htm`、或直接是数字），这里统一归一化为纯数字 id，
 * 以便交给数据源继续请求；无法识别时返回 null，由调用方降级处理，绝不抛异常。
 *
 * @param href 链接地址，允许为 null
 *
 * @return 书本 id，无法识别时返回 null
 */
internal fun extractWenku8BookId(href: String?): String? {
    val value = href?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleaned = value.substringBefore('?').substringBefore('#').trimEnd('/')

    // 形如 /book/1234.htm
    BOOK_LINK_ID_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.let { return it }

    // 形如 /novel/12/12345/index.htm 或 /novel/12/12345/
    val directory = cleaned.removeSuffix("/index.htm").removeSuffix("index.htm").trimEnd('/')
    val lastSegment = directory.substringAfterLast('/')
    if (lastSegment.isNotEmpty() && lastSegment.all(Char::isDigit)) return lastSegment

    // 兜底：整串中最后一段连续数字。
    TRAILING_DIGITS_REGEX.findAll(cleaned).lastOrNull()
        ?.groupValues?.getOrNull(1)
        ?.let { return it }

    // 最后沿用宿主内置实现的下标取法，保证行为不会比内置实现更差。
    return cleaned.split('/').getOrNull(3)?.takeIf { it.isNotEmpty() }
}

/**
 * 把书本卡片容器解析为探索页展示用的书本列表。
 *
 * 站点各列表页的容器层级不同，但卡片内部结构一致，因此选择器都相对卡片本身书写。
 * 单张卡片缺少关键节点时会被跳过，不会中断整页解析。
 *
 * @param cards 书本卡片容器列表
 * @param host 站点主机，用于把封面地址补全为绝对地址
 * @param limit 最多解析的卡片数量
 *
 * @return 解析成功的书本列表，顺序与页面一致
 */
internal fun parseWenku8BookCards(
    cards: List<Element>,
    host: String,
    limit: Int = Int.MAX_VALUE,
): List<ExploreDisplayBook> =
    cards.asSequence()
        .take(limit)
        .mapNotNull { card -> parseWenku8BookCard(card, host) }
        .toList()

/**
 * 解析单张书本卡片。
 *
 * @param card 卡片容器
 * @param host 站点主机
 *
 * @return 解析出的书本，缺少书本 id 或链接时返回 null
 */
private fun parseWenku8BookCard(card: Element, host: String): ExploreDisplayBook? {
    val id = extractWenku8BookId(card.selectFirst(CARD_LINK_SELECTOR)?.attr("href"))
        ?: return null
    return ExploreDisplayBook(
        id = id,
        title = card.selectFirst(CARD_TITLE_SELECTOR)
            ?.text()
            ?.split("(")
            ?.getOrNull(0)
            ?.trim()
            .orEmpty(),
        author = card.selectFirst(CARD_AUTHOR_SELECTOR)
            ?.text()
            ?.split("/")
            ?.getOrNull(0)
            ?.split(":")
            ?.getOrNull(1)
            ?.trim()
            .orEmpty(),
        coverUri = normalizeUrl(host, card.selectFirst(CARD_COVER_SELECTOR)?.attr("src"))
            ?.let { Uri.parse(it) }
            ?: Uri.EMPTY
    )
}

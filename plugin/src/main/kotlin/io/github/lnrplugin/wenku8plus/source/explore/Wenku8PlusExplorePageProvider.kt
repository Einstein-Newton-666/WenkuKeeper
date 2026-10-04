package io.github.lnrplugin.wenku8plus.source.explore

import com.github.michaelbull.result.get
import io.github.lnrplugin.wenku8plus.PluginConstants
import io.github.lnrplugin.wenku8plus.tools.Wenku8HttpClient
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.explore.AbstractDefaultExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.explore.filter.IsCompletedSwitchFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.WordCountFilter
import java.net.URLEncoder

/** 展开页数据源 id：轻小说列表。卡片页与该 id 必须保持一致，否则展开无内容。 */
internal const val EXPANDED_PAGE_ID_ALL_BOOKS = "allBook"

/** 展开页数据源 id：完结全本。 */
internal const val EXPANDED_PAGE_ID_COMPLETED_BOOKS = "allCompletedBook"

/**
 * 拼接排行榜展开页的数据源 id。
 *
 * 规则为 `<排序标识>Book`，卡片页引用的 id 必须与注册时完全一致。
 *
 * @param sort 站点排序标识，例如 `allvisit`
 *
 * @return 展开页数据源 id
 */
internal fun topListExpandedPageId(sort: String): String = "${sort}Book"

/** 排行榜入口的名称与站点排序标识，顺序与宿主内置实现一致。 */
internal val TOP_LISTS: List<Pair<String, String>> = listOf(
    "热门轻小说" to "allvisit",
    "动画化作品" to "anime",
    "今日更新" to "lastupdate",
    "新书一览" to "postdate"
)

/** 标签页排序过滤器的可选项。 */
private val TAG_SORT_CHOICES: List<String> = listOf(
    "默认",
    "按更新时间排序",
    "按热度排序",
    "仅动画化"
)

/** 标签页排序选项对应的请求参数；「默认」与「按更新时间排序」都使用站点默认排序。 */
private val TAG_SORT_PARAMETERS: Map<String, String> = mapOf(
    "默认" to "",
    "按更新时间排序" to "",
    "按热度排序" to "&v=1",
    "仅动画化" to "&v=3"
)

/**
 * wenku8 探索页提供器。
 *
 * 提供三个卡片页与一组展开页，结构与宿主内置的 wenku8 数据源一致：
 * - 卡片页：「首页」（站点首页的三块推荐位）、「全部」（轻小说列表、四个排行榜与完结全本）、
 *   「分类」（站点标签页中的标签，逐个展示该标签下的书本）；
 * - 展开页：轻小说列表、完结全本、四个排行榜，以及 [PluginConstants.TAGS] 中的每个标签；
 * - 过滤器：是否完结、文库分类、字数限制；标签展开页另有排序过滤器。
 *
 * 卡片页引用的展开页 id 与这里注册的 id 一一对应，改动其一必须同步另一处。
 * 该类不持有网络客户端，页面抓取由数据源注入的 [PageFetcher] 完成。
 *
 * @param host 站点主机，例如 `https://www.wenku8.net`；仅在未提供 [hostProvider] 时作为默认值
 * @param fetchPage 页面抓取函数，由数据源注入
 * @param hostProvider 实时主机读取器；子页面与展开页每次拼请求地址时调用，镜像切换后无需重建
 * @param enabled 探索页开关读取器；返回 false 时卡片页与展开页都不再发起任何请求
 */
class Wenku8PlusExplorePageProvider(
    private val host: String,
    private val fetchPage: PageFetcher,
    private val hostProvider: () -> String = { host },
    private val enabled: () -> Boolean = { true },
) : AbstractDefaultExplorePageProvider() {

    /**
     * 使用插件共享的 [Wenku8HttpClient] 创建探索页提供器。
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
        // 卡片页：注册顺序即界面上的显示顺序。
        // 注册本身不发起任何请求；能否取页由 enabled 在 getRowsFlow()/getResultFlow() 中决定。
        registerTapPage(Wenku8PlusHomeTapPage(host, fetchPage, hostProvider, enabled))
        registerTapPage(Wenku8PlusAllTapPage(host, fetchPage, hostProvider, enabled))
        registerTapPage(Wenku8PlusTagsTapPage(host, fetchPage, hostProvider, enabled))

        registerExpandedPageDataSource(
            id = EXPANDED_PAGE_ID_ALL_BOOKS,
            exploreExpandedPageDataSource = AllBooksExpandedPageDataSource(
                host = host,
                fetchPage = fetchPage,
                hostProvider = hostProvider,
                enabled = enabled,
                title = "轻小说列表",
                filtersBuilder = { defaultBookFilters() }
            )
        )
        registerExpandedPageDataSource(
            id = EXPANDED_PAGE_ID_COMPLETED_BOOKS,
            exploreExpandedPageDataSource = AllBooksExpandedPageDataSource(
                host = host,
                fetchPage = fetchPage,
                hostProvider = hostProvider,
                enabled = enabled,
                title = "完结全本",
                filtersBuilder = { defaultBookFilters() },
                extendedParameters = "&fullflag=1"
            )
        )
        for ((name, sort) in TOP_LISTS) {
            registerExpandedPageDataSource(
                id = topListExpandedPageId(sort),
                exploreExpandedPageDataSource = AllBooksExpandedPageDataSource(
                    host = host,
                    fetchPage = fetchPage,
                    hostProvider = hostProvider,
                    enabled = enabled,
                    baseUrl = "/modules/article/toplist.php",
                    title = name,
                    filtersBuilder = { defaultBookFilters() },
                    extendedParameters = "&sort=$sort",
                    contentSelector = TOP_LIST_CONTENT_SELECTOR
                )
            )
        }
        for (tag in PluginConstants.TAGS) {
            registerExpandedPageDataSource(
                id = tag,
                exploreExpandedPageDataSource = AllBooksExpandedPageDataSource(
                    host = host,
                    fetchPage = fetchPage,
                    hostProvider = hostProvider,
                    enabled = enabled,
                    baseUrl = "/modules/article/tags.php",
                    title = tag,
                    filtersBuilder = {
                        listOf(
                            IsCompletedSwitchFilter(),
                            tagSortFilter(this),
                            PublishingHouseSingleChoiceFilter(),
                            WordCountFilter()
                        )
                    },
                    extendedParameters = "&t=${encodeTag(tag)}",
                    contentSelector = TAGS_CONTENT_SELECTOR
                )
            )
        }
    }
}

/**
 * 书本列表展开页共用的过滤器组。
 *
 * @return 是否完结、文库分类与字数限制三个过滤器
 */
private fun defaultBookFilters(): List<Filter<*>> = listOf(
    IsCompletedSwitchFilter(),
    PublishingHouseSingleChoiceFilter(),
    WordCountFilter()
)

/**
 * 构造标签展开页的排序过滤器。
 *
 * 该过滤器不参与本地过滤，只把用户的选择写回数据源的
 * [AllBooksExpandedPageDataSource.arg]，由下一次取页把排序参数拼进请求地址。
 *
 * @param dataSource 接收排序参数的展开页数据源
 *
 * @return 排序过滤器
 */
private fun tagSortFilter(dataSource: AllBooksExpandedPageDataSource): Filter<*> =
    SingleChoiceFilter(
        title = "排序".local(),
        dialogTitle = "排序方式".local(),
        description = "选择标签页内书本的排序方式。".local(),
        choices = TAG_SORT_CHOICES,
        defaultChoice = TAG_SORT_CHOICES.first()
    ).apply {
        addOnChangeListener { choice ->
            dataSource.arg = TAG_SORT_PARAMETERS[choice.trim()] ?: ""
        }
    }

/**
 * 按 GB2312 编码标签名。
 *
 * 站点按 GB2312 解析 `t` 参数，使用 UTF-8 编码会得到乱码标签。标签卡片页与标签展开页
 * 都需要该编码，因此在这里统一定义。
 *
 * @param tag 标签名
 *
 * @return 编码后的文本；编码不可用时退回原文
 */
internal fun encodeTag(tag: String): String =
    runCatching { URLEncoder.encode(tag, "gb2312") }.getOrDefault(tag)

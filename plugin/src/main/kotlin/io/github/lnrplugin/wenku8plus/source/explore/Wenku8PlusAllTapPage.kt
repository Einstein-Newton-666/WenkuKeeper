package io.github.lnrplugin.wenku8plus.source.explore

import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * wenku8「全部」卡片页。
 *
 * 对应宿主内置数据源的「全部」：依次展示轻小说列表、四个排行榜与完结全本，
 * 每行都支持点击展开，展开页的数据源 id 与 [Wenku8PlusExplorePageProvider]
 * 中注册的 id 一一对应：
 * - 轻小说列表 → [EXPANDED_PAGE_ID_ALL_BOOKS]
 * - 排行榜 → [topListExpandedPageId]
 * - 完结全本 → [EXPANDED_PAGE_ID_COMPLETED_BOOKS]
 *
 * 每解析出一行就发射一次，宿主可以边加载边展示。某一行请求失败时仍会发出该行，
 * 只是书本列表为空，用户依然可以点进展开页查看完整列表。
 *
 * 该类不持有网络客户端，页面抓取由数据源注入的 [PageFetcher] 完成。
 *
 * @param host 站点主机，例如 `https://www.wenku8.net`；仅在未提供 [hostProvider] 时作为默认值
 * @param fetchPage 页面抓取函数，由数据源注入
 * @param hostProvider 实时主机读取器；每次请求与封面补全时调用，镜像切换后无需重建本对象
 * @param enabled 探索页开关读取器；返回 false 时本页不发起任何请求
 */
class Wenku8PlusAllTapPage(
    private val host: String,
    private val fetchPage: PageFetcher,
    private val hostProvider: () -> String = { host },
    private val enabled: () -> Boolean = { true },
) : ExploreTapPageDataSource {

    /** 卡片页标题。 */
    override val title: String = "全部"

    /**
     * 获取全部入口的数据流。
     *
     * 行顺序为轻小说列表、热门轻小说、动画化作品、今日更新、新书一览、完结全本，
     * 与宿主内置实现一致。探索页被关闭时直接返回空的数据流，不发起任何请求。
     *
     * @return 探索页行列表的数据流
     */
    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> {
        if (!enabled()) return flowOf(emptyList())
        return flow {
            val rows = mutableListOf<ExploreBooksRow>()
            rows.add(allBooksRow())
            emit(rows.toList())
            for ((name, sort) in TOP_LISTS) {
                rows.add(topListRow(name, sort))
                emit(rows.toList())
            }
            rows.add(completedBooksRow())
            emit(rows.toList())
        }.flowOn(Dispatchers.IO)
    }

    /**
     * 轻小说列表行。
     *
     * @return 可展开到 [EXPANDED_PAGE_ID_ALL_BOOKS] 的展示行
     */
    private suspend fun allBooksRow(): ExploreBooksRow =
        booksRow(fetchPage.fetch("${hostProvider()}/modules/article/articlelist.php"), "轻小说列表")
            .copy(expandable = true, expandedPageDataSourceId = EXPANDED_PAGE_ID_ALL_BOOKS)

    /**
     * 排行榜行。
     *
     * @param name 行的显示标题
     * @param sort 站点排序标识，例如 `allvisit`
     *
     * @return 可展开到对应排行榜展开页的展示行
     */
    private suspend fun topListRow(name: String, sort: String): ExploreBooksRow =
        booksRow(fetchPage.fetch("${hostProvider()}/modules/article/toplist.php?sort=$sort"), name)
            .copy(expandable = true, expandedPageDataSourceId = topListExpandedPageId(sort))

    /**
     * 完结全本行。
     *
     * @return 可展开到 [EXPANDED_PAGE_ID_COMPLETED_BOOKS] 的展示行
     */
    private suspend fun completedBooksRow(): ExploreBooksRow =
        booksRow(fetchPage.fetch("${hostProvider()}/modules/article/articlelist.php?fullflag=1"), "完结全本")
            .copy(expandable = true, expandedPageDataSourceId = EXPANDED_PAGE_ID_COMPLETED_BOOKS)

    /**
     * 解析一页书本列表的前几本书。
     *
     * @param soup 列表页文档，请求失败时为 null
     * @param title 行的显示标题
     *
     * @return 展示行，`expandable` 由调用方补齐
     */
    private fun booksRow(soup: Document?, title: String): ExploreBooksRow {
        val cards: List<Element> = soup?.select(DEFAULT_BOOK_LIST_CONTENT_SELECTOR) ?: emptyList()
        return ExploreBooksRow(
            title = title,
            bookList = parseWenku8BookCards(cards, hostProvider(), limit = ROW_BOOK_LIMIT),
            expandable = false
        )
    }
}

package io.github.lnrplugin.wenkukeeper.source.explore

import android.net.Uri
import io.github.lnrplugin.wenkukeeper.tools.normalizeUrl
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 首页展示的推荐位数量：站点首页 `#centers` 下的第 2 至第 4 个子节点。 */
private const val HOME_ROW_COUNT = 3

/**
 * wenku8 首页卡片页。
 *
 * 对应宿主内置数据源的「首页」：从站点首页抓取三个推荐位（推荐、最近更新等），
 * 每个推荐位作为一行展示，逐行增量更新。首页推荐位的书没有作者信息，
 * 因此作者统一留空，与宿主内置实现一致。
 *
 * 该类不持有网络客户端，页面抓取由数据源注入的 [PageFetcher] 完成。
 *
 * @param host 站点主机，例如 `https://www.wenku8.net`；仅在未提供 [hostProvider] 时作为默认值
 * @param fetchPage 页面抓取函数，由数据源注入
 * @param hostProvider 实时主机读取器；每次请求与封面补全时调用，镜像切换后无需重建本对象
 * @param enabled 探索页开关读取器；返回 false 时本页不发起任何请求
 */
class WenkuKeeperHomeTapPage(
    private val host: String,
    private val fetchPage: PageFetcher,
    private val hostProvider: () -> String = { host },
    private val enabled: () -> Boolean = { true },
) : ExploreTapPageDataSource {

    /** 卡片页标题。 */
    override val title: String = "首页"

    /**
     * 获取首页推荐位的数据流。
     *
     * 每解析出一行就发射一次，宿主可以边加载边展示；首页请求失败时返回空的数据流，
     * 不会抛出异常。探索页被关闭时直接返回空的数据流，不发起任何请求。
     *
     * @return 探索页行列表的数据流
     */
    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> {
        if (!enabled()) return flowOf(emptyList())
        return flow {
            val rows = mutableListOf<ExploreBooksRow>()
            val soup = fetchPage.fetch(hostProvider())
            for (index in 0 until HOME_ROW_COUNT) {
                val row = getBooksRow(index, soup)
                // 站点结构变化时会出现既无标题也无书本的空行，直接跳过。
                if (row.title.isEmpty() && row.bookList.isEmpty()) continue
                rows.add(row)
                emit(rows.toList())
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * 解析一个推荐位。
     *
     * 书本 id、书名与封面在页面中是三个平行的节点列表，这里按相同的下标取值，
     * 下标越界时退化为空值，不会因为某个列表缺项而抛出异常。
     *
     * @param index 推荐位下标，从 0 开始
     * @param soup 首页文档，请求失败时为 null
     *
     * @return 该推荐位对应的展示行
     */
    private fun getBooksRow(index: Int, soup: Document?): ExploreBooksRow {
        val block = "#centers > div:nth-child(${index + 2})"
        val rowTitle = soup?.selectFirst("$block > div.blocktitle")
            ?.text()
            ?.split("(")
            ?.getOrNull(0)
            ?.trim()
            .orEmpty()
        val idLinks: List<Element> =
            soup?.select("$block > div.blockcontent > div > div > a:nth-child(1)") ?: emptyList()
        val titleLinks: List<Element> =
            soup?.select("$block > div.blockcontent > div > div > a:nth-child(3)") ?: emptyList()
        val coverImages: List<Element> =
            soup?.select("$block > div.blockcontent > div > div > a:nth-child(1) > img")
                ?: emptyList()

        val books = idLinks.indices.mapNotNull { position ->
            val id = extractWenku8BookId(idLinks[position].attr("href")) ?: return@mapNotNull null
            ExploreDisplayBook(
                id = id,
                title = titleLinks.getOrNull(position)
                    ?.text()
                    ?.split("(")
                    ?.getOrNull(0)
                    ?.trim()
                    .orEmpty(),
                author = "",
                coverUri = normalizeUrl(hostProvider(), coverImages.getOrNull(position)?.attr("src"))
                    ?.let { Uri.parse(it) }
                    ?: Uri.EMPTY
            )
        }
        return ExploreBooksRow(
            title = rowTitle,
            bookList = books,
            expandable = false
        )
    }
}

package io.github.lnrplugin.wenku8plus.source.explore

import io.github.lnrplugin.wenku8plus.PluginConstants
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import org.jsoup.nodes.Element
import java.net.URLDecoder

/** 标签索引页在站点中的路径。 */
private const val TAGS_PATH = "modules/article/tags.php"

/**
 * 标签索引页中指向单个标签的链接。
 *
 * 与宿主内置实现使用的选择器一致；`t` 参数是标签名。站点返回的链接形式并不统一
 * （可能已百分号编码，也可能直接是中文），因此标签名优先取链接文本。
 */
private const val TAG_LINK_SELECTOR = "a[href~=tags\\.php\\?t=.*]"

/** 最多展示的标签行数，与宿主内置实现的 `slice(0..48)` 一致。 */
private const val MAX_TAG_ROWS = 49

/**
 * wenku8「分类」卡片页。
 *
 * 对应宿主内置数据源的「分类」：先从标签索引页取出站点的标签，再逐个请求标签页，
 * 每取到一个标签的书本就发射一行，宿主可以边加载边展示。
 *
 * 注意：该页面会为每个标签各发一次请求（最多 [MAX_TAG_ROWS] 次），是探索页中请求量
 * 最大的一页，与宿主内置实现的行为一致。
 *
 * 行是否可以展开取决于该标签是否在 [PluginConstants.TAGS] 中登记过展开页；
 * 未登记的标签只展示但不能展开，避免宿主收到不存在的展开页 id。
 *
 * 该类不持有网络客户端，页面抓取由数据源注入的 [PageFetcher] 完成。
 *
 * @param host 站点主机，例如 `https://www.wenku8.net`；仅在未提供 [hostProvider] 时作为默认值
 * @param fetchPage 页面抓取函数，由数据源注入
 * @param hostProvider 实时主机读取器；每次请求与封面补全时调用，镜像切换后无需重建本对象
 * @param enabled 探索页开关读取器；返回 false 时本页不发起任何请求
 */
class Wenku8PlusTagsTapPage(
    private val host: String,
    private val fetchPage: PageFetcher,
    private val hostProvider: () -> String = { host },
    private val enabled: () -> Boolean = { true },
) : ExploreTapPageDataSource {

    /** 卡片页标题。 */
    override val title: String = "分类"

    /**
     * 获取标签卡片页的数据流。
     *
     * 标签索引页或某个标签页请求失败时会跳过该行，不会中断整个数据流。
     * 探索页被关闭时直接返回空的数据流，连标签索引页都不会请求。
     *
     * @return 探索页行列表的数据流
     */
    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> {
        if (!enabled()) return flowOf(emptyList())
        return flow {
            val rows = mutableListOf<ExploreBooksRow>()
            val index = fetchPage.fetch("${hostProvider()}/$TAGS_PATH")
            val links: List<Element> =
                index?.select(TAG_LINK_SELECTOR)?.take(MAX_TAG_ROWS) ?: emptyList()
            for (link in links) {
                val tag = tagNameOf(link) ?: continue
                val soup = fetchPage.fetch(tagUrl(tag))
                val cards: List<Element> = soup?.select(TAGS_CONTENT_SELECTOR) ?: emptyList()
                val books = parseWenku8BookCards(cards, hostProvider(), limit = ROW_BOOK_LIMIT)
                // 该标签下没有书本（或页面被站点拦截）时不生成空行，避免出现无内容的卡片。
                if (books.isEmpty()) continue

                val registered = tag in PluginConstants.TAGS
                rows.add(
                    ExploreBooksRow(
                        title = tag,
                        bookList = books,
                        expandable = registered,
                        expandedPageDataSourceId = tag.takeIf { registered }
                    )
                )
                emit(rows.toList())
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * 拼接标签页地址。
     *
     * 主机取自 [hostProvider]，站点按 GB2312 解析 `t` 参数，标签名必须先编码。
     *
     * @param tag 标签名
     *
     * @return 标签页的完整地址
     */
    private fun tagUrl(tag: String): String = "${hostProvider()}/$TAGS_PATH?t=${encodeTag(tag)}"

    /**
     * 解析链接对应的标签名。
     *
     * 优先取链接文本（站点展示的就是标签名）；文本为空时退回链接中的 `t` 参数，
     * 并按 GB2312 解码，避免把已编码的文本再次编码。
     *
     * @param link 标签索引页中的链接
     *
     * @return 标签名，无法解析时返回 null
     */
    private fun tagNameOf(link: Element): String? {
        link.text().trim().takeIf { it.isNotEmpty() }?.let { return it }
        val raw = link.attr("href").substringAfter("t=", "").substringBefore("&").trim()
        if (raw.isEmpty()) return null
        return runCatching { URLDecoder.decode(raw, "gb2312") }
            .getOrDefault(raw)
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}

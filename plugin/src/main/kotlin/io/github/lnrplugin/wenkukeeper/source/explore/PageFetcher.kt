package io.github.lnrplugin.wenkukeeper.source.explore

import org.jsoup.nodes.Document

/**
 * 章节页面抓取函数。
 *
 * 探索页数据源本身不持有网络客户端，只依赖这个函数完成取页，从而与主数据源解耦，
 * 也便于在测试中替换为离线实现。
 */
fun interface PageFetcher {
    /**
     * 抓取并解析一个页面。
     *
     * @param pathOrUrl 相对站点主机的路径，或完整地址
     *
     * @return 解析后的文档，请求或解析失败时返回 null
     */
    suspend fun fetch(pathOrUrl: String): Document?
}

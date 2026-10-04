package io.github.lnrplugin.wenku8plus.site

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import io.github.lnrplugin.wenku8plus.tools.Wenku8HttpClient

/**
 * 站点「我的书架」的读写。
 *
 * 站点**没有公开 API**，只有网页版的这几个入口（全部为 GET，靠会话 Cookie 认证），
 * 以下路径是从真实浏览器抓包得到的，不是推测：
 *
 * | 操作 | 请求 |
 * | --- | --- |
 * | 读取书架 | `GET /modules/article/bookcase.php` |
 * | 加入书架 / 更新书签 | `GET /modules/article/addbookcase.php?bid={aid}[&cid={cid}]` |
 * | 从书架移除 | `GET /modules/article/bookcase.php?delid={内部id}` |
 *
 * 两个要点：
 *
 * - **`addbookcase.php` 同时承担「加入书架」与「更新书签」两件事。** 带上 `cid` 时，
 *   站点会把该书在书架里的书签记到这一章；同一本书再调一次就是**移动书签**。
 *   这正是网页阅读器"读到哪就记到哪"的实现方式。
 * - `bid` 是**公开书本 id**（与宿主书本 id 相同），而移除用的 `delid` 是**站点内部 id**，
 *   两者不同，内部 id 只能从书架页解析得到（见 [SiteShelfEntry.internalId]）。
 *
 * @param http 已选好镜像并设置了会话 Cookie 的 HTTP 客户端
 */
class Wenku8SiteClient(private val http: Wenku8HttpClient) {

    /** 书架页路径。 */
    private val shelfPath = "modules/article/bookcase.php"

    /**
     * 读取站点书架。
     *
     * @return 每行一条；被 Cloudflare 拦下或未登录时返回失败而不是空列表
     */
    suspend fun fetchShelf(): Result<List<SiteShelfEntry>, Throwable> =
        http.getDocumentChecked(shelfPath).map { document ->
            Wenku8SiteShelfParser.parse(document)
        }

    /**
     * 把一本书加入站点书架，并把书签写到指定章节。
     *
     * @param aid 公开书本 id（宿主的书本 id）
     * @param chapterId 书签要指向的章节 id；为 null 时不带 `cid`，即只加书、不设书签
     *
     * @return 请求是否成功；**不代表站点一定接受了操作**（站点用重定向表达结果，
     *   是否生效请以 [fetchShelf] 的实际内容为准）
     */
    suspend fun addToShelf(aid: String, chapterId: String?): Result<Unit, Throwable> {
        val query = buildString {
            append("bid=").append(aid)
            if (!chapterId.isNullOrBlank()) append("&cid=").append(chapterId)
        }
        return http.getDocumentChecked("modules/article/addbookcase.php?$query").map { }
    }

    /**
     * 从站点书架移除一本书。
     *
     * @param internalId 站点内部书本 id，来自 [SiteShelfEntry.internalId]
     *
     * @return 请求是否成功
     */
    suspend fun removeFromShelf(internalId: String): Result<Unit, Throwable> =
        http.getDocumentChecked("$shelfPath?delid=$internalId").map { }

    /**
     * 探测站点是否可读写。
     *
     * 用读取书架代替探活：它同时验证了「网络可达」「Cookie 有效」「没有被 Cloudflare 拦」，
     * 而这三件事正是后续操作全部的前提。
     *
     * @return 书架条数；失败时返回带说明的错误
     */
    suspend fun probe(): Result<Int, Throwable> = fetchShelf().map { it.size }
}

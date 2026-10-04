package io.github.lnrplugin.wenkukeeper.site

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * wenku8 站点「我的书架」里的一条记录。
 *
 * 站点的书架表每行对应一本收藏的书，字段来自浏览器实测到的表结构
 * （`/modules/article/bookcase.php`）：
 *
 * ```
 * TD.odd   隐藏 checkbox  name="checkid[]"      value="11791091"   ← 站点内部 id，移除时用
 * TD.even  链接           readbookcase.php?aid=2964&bid=11791091  ← aid 与宿主的书本 id 相同
 * TD.odd   作者链接       authorarticle.php?author=...
 * TD.even  最新章节链接   readbookcase.php?...&cid=179553
 * TD.odd   ★ 书签         href="#" 或指向某个 cid                     ← 就是站点的"阅读记录"
 * TD.odd   更新日期       26-09-27
 * TD.even  「移除」        bookcase.php?delid=11791091
 * ```
 *
 * @property internalId 站点内部书本 id（`checkid[]` 的值），**移除时使用**；与 [aid] 不同
 * @property aid 公开书本 id，与宿主里的书本 id 一致，可直接用于 `addbookcase.php?bid=`
 * @property title 书名
 * @property bookmarkChapterId 书签指向的章节 id；该书没有书签时为 null
 * @property bookmarkTitle 书签显示的文字（章节名）；没有书签时为空
 */
data class SiteShelfEntry(
    val internalId: String,
    val aid: String,
    val title: String,
    val bookmarkChapterId: String? = null,
    val bookmarkTitle: String = ""
)

/**
 * 解析 `/modules/article/bookcase.php` 返回的书架表格。
 *
 * 解析策略刻意**按表头文字定位列**，而不是写死"第 5 格是书签"：站点是 PHP 模板，
 * 列顺序或有没有「新」标记都可能变。只有表头认不出来时才退回实测到的固定列号。
 */
object Wenku8SiteShelfParser {

    /**
     * 抓包里「名称」格与「书签」格的距离：名称在第 2 格、书签在第 5 格（从 0 数起是 1 与 4）。
     *
     * 只在页面上找不到表头时使用。
     */
    private const val FALLBACK_BOOKMARK_DISTANCE = 3

    private val AID_REGEX = Regex("""[?&]aid=(\d+)""")
    private val CID_REGEX = Regex("""[?&]cid=(\d+)""")
    private val DELID_REGEX = Regex("""[?&]delid=(\d+)""")

    /**
     * 解析书架页。
     *
     * @param document 已按 GB18030 解码并解析好的书架页
     *
     * @return 每行一条；页面上没有书架表格（例如未登录、被 Cloudflare 拦下）时返回空列表
     */
    fun parse(document: Document): List<SiteShelfEntry> {
        val rows = document.select("tr")
        if (rows.isEmpty()) return emptyList()

        val columns = headerColumns(rows)

        val result = mutableListOf<SiteShelfEntry>()
        for (row in rows) {
            // 只有数据行带 checkid[] 复选框；表头与分页行都没有。
            // 这里刻意用「遍历 + 比对属性」而不是 `input[name=checkid[]]`：方括号是 CSS 选择器的
            // 元字符，Jsoup 对该选择器的解析结果不稳定，写成选择器可能静默匹配不到任何行。
            val checkbox = row.select("input").firstOrNull { it.attr("name") == "checkid[]" } ?: continue
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.isEmpty()) continue

            // 名称列**按语义定位**：数据行里第一个挂着 readbookcase 链接的单元格就是书名格。
            // 它比表头下标可靠——抓包只取到了数据行，表头有没有「复选框」那一格是未知的，
            // 而两边的格数一旦不同，直接用表头下标就会整体偏移一位。偏移的后果不是报错而是
            // **静默读错**：书签列左边正是「最新章节」列，那里同样带 cid 参数。
            val titleIndex = cells.indexOfFirst { cell ->
                cell.select("a").any { it.attr("href").contains("readbookcase") }
            }.takeIf { it >= 0 } ?: continue

            // 表头里「名称」的位置与语义定位到的位置之差，就是整张表的列偏移。
            val offset = columns["名称"]?.let { titleIndex - it } ?: 0
            val bookmarkIndex = columns["书签"]?.let { it + offset }
                ?: (titleIndex + FALLBACK_BOOKMARK_DISTANCE)

            val removeLink = row.select("a").firstOrNull { it.attr("href").contains("delid=") }
            val internalId = checkbox.`val`().trim().takeIf { it.isNotEmpty() }
                ?: removeLink?.attr("href")?.let { DELID_REGEX.find(it)?.groupValues?.get(1) }
                ?: continue

            val titleAnchor = cells[titleIndex].select("a")
                .firstOrNull { it.attr("href").contains("readbookcase") }
            val aid = titleAnchor?.attr("href")?.let { AID_REGEX.find(it)?.groupValues?.get(1) }
                ?: continue
            // 用链接自身的文字，避免把单元格里的「新」角标读进书名。
            val title = (titleAnchor.text().ifBlank { cells[titleIndex].text() }).trim()

            // 书签格必须与名称格不同：偏移算错时宁可判定为"没有书签"，也不能把最新章节当书签。
            val bookmarkAnchor = if (bookmarkIndex != titleIndex) {
                cells.getOrNull(bookmarkIndex)?.selectFirst("a")
            } else {
                null
            }
            val bookmarkHref = bookmarkAnchor?.attr("href").orEmpty()
            val bookmarkCid = CID_REGEX.find(bookmarkHref)?.groupValues?.get(1)
            val bookmarkTitle = bookmarkAnchor?.text()?.trim().orEmpty()

            result += SiteShelfEntry(
                internalId = internalId,
                aid = aid,
                title = title,
                bookmarkChapterId = bookmarkCid,
                bookmarkTitle = bookmarkTitle
            )
        }
        return result
    }

    /**
     * 从表头行得到「列名 → 列号」的映射。
     *
     * @param rows 页面里所有 `tr`
     *
     * @return 列名到下标的映射；找不到表头时返回空映射
     */
    private fun headerColumns(rows: List<Element>): Map<String, Int> {
        for (row in rows) {
            val texts = row.children().filter { it.tagName() == "td" || it.tagName() == "th" }
                .map { it.text().trim() }
            // 表头至少要有「名称」和「书签」两列才认。
            if (texts.any { it == "名称" } && texts.any { it == "书签" }) {
                return texts.withIndex().associate { (index, name) -> name to index }
            }
        }
        return emptyMap()
    }
}

package io.github.lnrplugin.wenku8plus.cloud

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** 生成时间与「最后阅读」列使用的时间格式。 */
private val READING_LOG_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/**
 * 把本机阅读数据渲染成一份可读的 Markdown 阅读记录。
 *
 * 生成结果刻意只依赖入参：没有 I/O、没有 Android 依赖，也不读取任何设置，因此可以脱离宿主
 * 单独测试。书名由调用方通过 [build] 的 `bookTitles` 传入（引擎负责向宿主查询），取不到时
 * 退回显示书本 id。
 *
 * 输出的文档面向 GitHub 渲染：一级标题 + 一行统计信息 + 一张表格，末尾恰好一个换行。
 */
object ReadingLogBuilder {

    /**
     * 生成阅读记录 Markdown。
     *
     * @param readingData 阅读进度列表，通常来自快照
     * @param bookshelves 书架列表，用于把书本 id 解析成书架名
     * @param bookTitles 书本 id → 书名；缺失的 id 直接显示 id
     * @param generatedAt 生成时间，用于文档抬头
     *
     * @return Markdown 文本，末尾恰好一个换行
     */
    fun build(
        readingData: List<ReadingEntry>,
        bookshelves: List<BookshelfEntry>,
        bookTitles: Map<String, String>,
        generatedAt: LocalDateTime
    ): String {
        val finished = readingData.count { it.readingProgress >= 1f }
        val reading = readingData.size - finished
        val totalSeconds = readingData.sumOf { it.totalReadTime.coerceAtLeast(0) }

        val lines = ArrayList<String>(readingData.size + 8)
        lines += "# 阅读记录"
        lines += ""
        lines += "> 生成时间：${generatedAt.format(READING_LOG_TIME_FORMATTER)} ｜ " +
                "共记录 ${readingData.size} 本：已读完 $finished 本、在读 $reading 本 ｜ " +
                "累计阅读 ${formatTotalDuration(totalSeconds)}"
        lines += ""

        if (readingData.isEmpty()) {
            lines += "暂无阅读记录，读完一本书后这里就会出现内容。"
            return lines.joinToString("\n") + "\n"
        }

        lines += "| 书名 | 进度 | 阅读时长 | 最后阅读 | 书架 |"
        lines += "| --- | --- | --- | --- | --- |"
        for (entry in sortedForDisplay(readingData)) {
            lines += "| ${escapeCell(titleOf(entry, bookTitles))} " +
                    "| ${formatProgress(entry.readingProgress)} " +
                    "| ${formatDuration(entry.totalReadTime)} " +
                    "| ${formatLastRead(entry.lastReadTime)} " +
                    "| ${escapeCell(shelfNamesOf(entry.id, bookshelves))} |"
        }
        return lines.joinToString("\n") + "\n"
    }

    /** 按最后阅读时间倒序（最近读的在前）；没有时间的排在最后，同组内按 id 稳定排序。 */
    private fun sortedForDisplay(readingData: List<ReadingEntry>): List<ReadingEntry> =
        readingData.sortedWith(
            compareByDescending<ReadingEntry> { parseLastRead(it.lastReadTime) ?: LocalDateTime.MIN }
                .thenBy { it.id }
        )

    /** 书名：优先用宿主给的书名，缺失或空白时退回书本 id。 */
    private fun titleOf(entry: ReadingEntry, bookTitles: Map<String, String>): String =
        bookTitles[entry.id]?.takeIf { it.isNotBlank() } ?: entry.id

    /** 书本所属书架名，用 `、` 连接；不属于任何书架时返回 `—`。 */
    private fun shelfNamesOf(bookId: String, bookshelves: List<BookshelfEntry>): String {
        val names = bookshelves
            .filter { bookId in it.allBookIds }
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (names.isEmpty()) "—" else names.joinToString("、")
    }

    /** 进度：0.0~1.0 渲染成百分比，越界值先收敛到合法区间。 */
    private fun formatProgress(progress: Float): String =
        "${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%"

    /** 表格里的阅读时长：不足 1 分钟时给一句人话，未读过显示 `—`。 */
    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        if (safe <= 0) return "—"
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分钟"
            hours > 0 -> "$hours 小时"
            minutes > 0 -> "$minutes 分钟"
            else -> "不到 1 分钟"
        }
    }

    /** 统计行里的累计时长：固定 `X 小时 Y 分钟`，避免出现「—」这种不像统计的写法。 */
    private fun formatTotalDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return "${safe / 3600} 小时 ${(safe % 3600) / 60} 分钟"
    }

    /** 最后阅读时间：ISO 字符串 → `yyyy-MM-dd HH:mm`；缺失或无法解析时返回 `—`。 */
    private fun formatLastRead(value: String?): String {
        val parsed = parseLastRead(value) ?: return "—"
        return parsed.format(READING_LOG_TIME_FORMATTER)
    }

    /** 解析 ISO-8601 本地时间；空值与非法值都返回 null。 */
    private fun parseLastRead(value: String?): LocalDateTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { LocalDateTime.parse(text) }.getOrNull()
    }

    /** 单元格转义：竖线会破坏表格，换行会破坏行结构。 */
    private fun escapeCell(text: String): String =
        text.replace("|", "\\|").replace("\n", " ").replace("\r", " ")
}

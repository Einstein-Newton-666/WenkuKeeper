package io.github.lnrplugin.wenkukeeper.migrate

import kotlinx.serialization.Serializable

/**
 * 迁移计划：把一批书从「来源数据源」搬到「目标数据源」。
 *
 * 两个数据源的 id 体系不同，因此必须靠**书名与作者**做身份匹配。整个流程分三阶段：
 * 1. **导出**——在来源源激活时，把书架里的书目清单落到本文件（可离线完成）；
 * 2. **匹配**——在目标源激活时，用当前激活源的搜索接口逐本找候选，并把结果增量写回；
 * 3. **应用**——把用户确认的匹配结果写进新书架，并搬走能搬的阅读进度。
 *
 * 每个阶段都落盘，因此中途中断（切数据源、退出应用）后可以从上次的进度继续，不必从头再来。
 *
 * @property version 计划格式版本
 * @property createdAtEpochMillis 计划创建时间（epoch 毫秒）
 * @property sourceLabel 来源的显示名，仅用于界面提示
 * @property targetLabel 目标的显示名，仅用于界面提示
 * @property books 需要迁移的书目
 */
@Serializable
data class MigrationPlan(
    val version: Int = MIGRATION_PLAN_VERSION,
    val createdAtEpochMillis: Long,
    val sourceLabel: String = "",
    val targetLabel: String = "",
    val books: List<MigrationBook> = emptyList()
) {
    /** 已匹配到候选的书本数量。 */
    val matchedCount: Int get() = books.count { it.candidates.isNotEmpty() }

    /** 用户已确认的书本数量。 */
    val acceptedCount: Int get() = books.count { it.acceptedCandidateId != null }
}

/** 当前计划格式版本。字段变更时递增，旧版本会被拒绝而不是误读。 */
const val MIGRATION_PLAN_VERSION: Int = 1

/**
 * 计划中的一本书。
 *
 * @property sourceBook 来源侧的书目信息（导出阶段填充，之后不再变化）
 * @property candidates 目标侧的候选（匹配阶段填充）
 * @property searchedAt 上一次为此书执行搜索的时间；为 null 表示还没搜过
 * @property acceptedCandidateId 用户确认的目标书本 id；null 表示待确认或已跳过
 * @property note 上一次搜索失败或异常的说明，成功时为空
 */
@Serializable
data class MigrationBook(
    val sourceBook: SourceBook,
    val candidates: List<MigrationCandidate> = emptyList(),
    val searchedAt: Long? = null,
    val acceptedCandidateId: String? = null,
    val note: String = ""
) {
    /** 是否已经搜过（无论有无候选）。 */
    val searched: Boolean get() = searchedAt != null
}

/**
 * 来源侧的书目信息。
 *
 * 全部取自宿主本地缓存（[io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi]），
 * 因此导出阶段不需要联网，来源站即使之后下线也不影响已导出的计划。
 *
 * @property id 来源侧书本 id
 * @property title 书名
 * @property author 作者
 * @property subtitle 副标题
 * @property wordCount 字数；未知时为 -1
 * @property publishingHouse 文库分类
 * @property lastReadChapterTitle 最后阅读的章节标题，用于给候选打分时做交叉校验；没读过则为 null
 * @property readingProgress 整体阅读进度 0..1
 * @property totalReadTime 累计阅读时长（秒）
 * @property lastReadTime 最后阅读时间（ISO-8601 字符串）
 * @property shelves 该书所在的书架名，仅用于界面展示
 */
@Serializable
data class SourceBook(
    val id: String,
    val title: String,
    val author: String = "",
    val subtitle: String = "",
    val wordCount: Int = -1,
    val publishingHouse: String = "",
    val lastReadChapterTitle: String? = null,
    val readingProgress: Float = 0f,
    val totalReadTime: Int = 0,
    val lastReadTime: String? = null,
    val shelves: List<String> = emptyList()
)

/**
 * 目标侧的一个候选书。
 *
 * 搜索接口只返回书本 id，书名与作者需要再调用
 * [io.nightfish.lightnovelreader.api.book.BookRepositoryApi.getBookInformationFlow] 才能拿到，
 * 因此候选是在「搜索 + 取详情」之后才成形。
 *
 * @property targetId 目标侧书本 id
 * @property title 书名
 * @property author 作者
 * @property subtitle 副标题
 * @property wordCount 字数；未知时为 -1
 * @property publishingHouse 文库分类
 */
@Serializable
data class MigrationCandidate(
    val targetId: String,
    val title: String,
    val author: String = "",
    val subtitle: String = "",
    val wordCount: Int = -1,
    val publishingHouse: String = ""
)

/**
 * 匹配档位。
 *
 * 只有 [HIGH] 会在界面上默认勾选；[MEDIUM] 需要用户逐条确认；[LOW] 默认不勾选。
 */
enum class MatchLevel {
    /** 书名与作者都吻合，字数也接近——可以放心自动接受。 */
    HIGH,

    /** 书名吻合但作者缺失或字数差异较大——需要用户确认。 */
    MEDIUM,

    /** 只有部分相似——默认跳过。 */
    LOW,

    /** 没有任何候选项。 */
    NONE
}

/**
 * 一条打分结果。
 *
 * @property candidate 候选
 * @property score 总分 0..100，越大越可能是同一本书
 * @property level 档位
 * @property reasons 得分与扣分的可读说明，直接展示给用户帮助判断
 */
data class ScoredCandidate(
    val candidate: MigrationCandidate,
    val score: Int,
    val level: MatchLevel,
    val reasons: List<String>
)

/**
 * 候选打分与判定。
 *
 * 这是一个**纯函数模块**：不联网、不碰数据库、不依赖 Android。
 * 迁移里最容易出错的就是"匹错书"，把它隔离出来才能单独验证——
 * 否则一次误匹配就可能在用户书架上留下一条指向完全无关小说的记录。
 */
object MigrationMatcher {

    /** 判定为「高置信」的分数线。 */
    const val HIGH_THRESHOLD: Int = 80

    /** 判定为「需确认」的分数线，低于此值归为低置信。 */
    const val MEDIUM_THRESHOLD: Int = 50

    /** 字数相差超过这个比例就不再给字数分。 */
    private const val WORD_COUNT_TOLERANCE = 0.25

    /**
     * 给一组候选打分并排序。
     *
     * @param source 来源侧书目
     * @param candidates 目标侧候选
     *
     * @return 按分数从高到低排序的结果；候选为空时返回空列表
     */
    fun rank(source: SourceBook, candidates: List<MigrationCandidate>): List<ScoredCandidate> =
        candidates
            .map { score(source, it) }
            .sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { it.candidate.targetId })

    /**
     * 给单个候选打分。
     *
     * 打分规则（满分 100）：
     * - 书名完全一致 +55；去掉空格与常见标点后一致 +45；否则按规范化后的相似度给分，最高 +40；
     * - 作者一致 +25；两侧都缺作者则不计分；只有一侧缺作者 +10；
     * - 字数接近 +20（相差在 [WORD_COUNT_TOLERANCE] 以内），相差很大则 -10；
     * - 副标题一致 +5；
     * - 文库分类一致 +5。
     *
     * @param source 来源侧书目
     * @param candidate 目标侧候选
     *
     * @return 打分结果，含可读的加减分说明
     */
    fun score(source: SourceBook, candidate: MigrationCandidate): ScoredCandidate {
        var score = 0
        val reasons = mutableListOf<String>()

        // 简繁归一：来源与目标可能分属简体站与繁体站（例如 wenku8 → tw.linovelib.com），
        // 同一本书的书名会是「魔法剑士」与「魔法劍士」。若不先统一字形，逐字比对会把
        // 本该高置信的匹配压到低置信。
        val sourceTitleRaw = ChineseVariant.normalize(source.title)
        val candidateTitleRaw = ChineseVariant.normalize(candidate.title)
        val sourceTitle = normalizeTitle(sourceTitleRaw)
        val candidateTitle = normalizeTitle(candidateTitleRaw)
        if (sourceTitleRaw != source.title || candidateTitleRaw != candidate.title) {
            reasons += "已做简繁归一"
        }

        when {
            sourceTitleRaw.isNotBlank() && sourceTitleRaw == candidateTitleRaw -> {
                score += 55
                reasons += "书名完全一致"
            }

            sourceTitle.isNotBlank() && sourceTitle == candidateTitle -> {
                score += 45
                reasons += "书名一致（忽略空格/标点）"
            }

            else -> {
                val similarity = similarity(sourceTitle, candidateTitle)
                if (similarity > 0.0) {
                    val gained = (similarity * 40).toInt()
                    score += gained
                    reasons += "书名相似 ${(similarity * 100).toInt()}%（+$gained）"
                } else {
                    reasons += "书名不相似"
                }
            }
        }

        val sourceAuthor = normalizeTitle(ChineseVariant.normalize(source.author))
        val candidateAuthor = normalizeTitle(ChineseVariant.normalize(candidate.author))
        when {
            sourceAuthor.isBlank() && candidateAuthor.isBlank() -> {
                reasons += "两侧都缺作者信息，不计分"
            }

            // 只有一侧缺作者：给少量分而不是零分。目标站缺作者很常见，
            // 若按「作者不一致」处理，一本书名完全一致的书就永远进不了高置信档，
            // 用户得为数据缺失逐条点确认。给 10 分既能让书名吻合的书达到高置信，
            // 又不足以单独把一本书名相似的书推上去。
            sourceAuthor.isBlank() || candidateAuthor.isBlank() -> {
                score += 10
                reasons += "一侧缺作者信息（+10）"
            }

            sourceAuthor == candidateAuthor -> {
                score += 25
                reasons += "作者一致"
            }

            else -> {
                reasons += "作者不一致（${source.author} / ${candidate.author}）"
            }
        }

        if (source.wordCount > 0 && candidate.wordCount > 0) {
            val diff = kotlin.math.abs(source.wordCount - candidate.wordCount).toDouble() /
                    source.wordCount.toDouble()
            if (diff <= WORD_COUNT_TOLERANCE) {
                score += 20
                reasons += "字数接近（相差 ${(diff * 100).toInt()}%）"
            } else {
                score -= 10
                reasons += "字数相差 ${(diff * 100).toInt()}%"
            }
        }

        if (source.subtitle.isNotBlank() && normalizeTitle(source.subtitle) == normalizeTitle(candidate.subtitle)) {
            score += 5
            reasons += "副标题一致"
        }
        if (source.publishingHouse.isNotBlank() &&
            normalizeTitle(source.publishingHouse) == normalizeTitle(candidate.publishingHouse)
        ) {
            score += 5
            reasons += "文库分类一致"
        }

        val bounded = score.coerceIn(0, 100)
        return ScoredCandidate(
            candidate = candidate,
            score = bounded,
            level = levelOf(bounded),
            reasons = reasons
        )
    }

    /**
     * 由分数推导档位。
     *
     * @param score 总分
     *
     * @return 对应档位
     */
    fun levelOf(score: Int): MatchLevel = when {
        score >= HIGH_THRESHOLD -> MatchLevel.HIGH
        score >= MEDIUM_THRESHOLD -> MatchLevel.MEDIUM
        else -> MatchLevel.LOW
    }

    /**
     * 规范化书名：去掉空白、常见中英文标点与书名号，并统一大小写。
     *
     * @param value 原始文本
     *
     * @return 规范化后的文本
     */
    private fun normalizeTitle(value: String): String =
        value
            .lowercase()
            .filterNot { it.isWhitespace() }
            .filterNot { it in STRIPPED_CHARS }

    /**
     * 计算两个规范化字符串的相似度（0.0..1.0）。
     *
     * 用「最长公共子序列长度 / 较长串长度」衡量：整段包含关系（例如目标站书名多了卷号后缀）
     * 会得到接近 1.0 的分，完全无关则接近 0。这里刻意不用编辑距离——中文书名里增删一两个字
     * 往往就是另一本书，而"一个是另一个的前缀/主体"才是常见的同一本书情形。
     *
     * @param a 规范化后的文本
     * @param b 规范化后的文本
     *
     * @return 相似度；任一为空时返回 0.0
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val lcs = longestCommonSubsequenceLength(a, b)
        return lcs.toDouble() / maxOf(a.length, b.length).toDouble()
    }

    /**
     * 最长公共子序列长度（不要求连续）。
     *
     * 用两行滚动数组，空间 O(min(n, m))，避免长书名的二维数组开销。
     *
     * @param a 文本 a
     * @param b 文本 b
     *
     * @return 最长公共子序列长度
     */
    private fun longestCommonSubsequenceLength(a: String, b: String): Int {
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        var previous = IntArray(shorter.length + 1)
        var current = IntArray(shorter.length + 1)
        for (i in 1..longer.length) {
            for (j in 1..shorter.length) {
                current[j] = if (longer[i - 1] == shorter[j - 1]) {
                    previous[j - 1] + 1
                } else {
                    maxOf(previous[j], current[j - 1])
                }
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }
        return previous[shorter.length]
    }

    /** 规范化时被丢弃的标点与符号。 */
    private val STRIPPED_CHARS: Set<Char> = setOf(
        '《', '》', '〈', '〉', '「', '」', '『', '』', '【', '】',
        '（', '）', '(', ')', '［', '］', '[', ']',
        '，', ',', '。', '.', '、', '·', '・', '～', '~',
        '：', ':', '；', ';', '！', '!', '？', '?',
        '－', '-', '—', '_', '　', '·', '"', '\'', '“', '”', '‘', '’'
    )
}

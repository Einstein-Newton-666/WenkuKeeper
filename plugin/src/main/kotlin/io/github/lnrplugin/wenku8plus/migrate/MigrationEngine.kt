package io.github.lnrplugin.wenku8plus.migrate

import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.plugin.PluginContext
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchResult.*
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime

/**
 * 迁移过程中的失败。
 *
 * [message] 是可直接展示给用户的中文说明，不包含任何凭据。所有公开方法都通过
 * `kotlin-result` 返回它，不会向调用方抛异常。
 *
 * @property message 面向用户的说明
 * @property cause 原始异常，便于排查；可能为 null
 */
sealed class MigrationError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /** 书架上没有可迁移的书（或者是书架为空、或者是本地还没有任何书目缓存）。 */
    class NoBooks(message: String) : MigrationError(message)

    /** 拿不到当前激活的数据源，或它没有提供任何搜索类型。 */
    class NoSource(message: String, cause: Throwable? = null) : MigrationError(message, cause)

    /** 搜索阶段失败（数据源报错、结果无法解析等）。 */
    class SearchFailed(message: String, cause: Throwable? = null) : MigrationError(message, cause)

    /** 应用阶段失败（没有可确认的书、书架创建失败等）。 */
    class ApplyFailed(message: String, cause: Throwable? = null) : MigrationError(message, cause)

    /** 本地读写失败（计划文件无法写入等）。 */
    class Io(message: String, cause: Throwable? = null) : MigrationError(message, cause)
}

/**
 * 一次「导入到新书架」的结果统计。
 *
 * @property added 成功加入新书架的书本数
 * @property failed 失败的书本数
 * @property skipped 没有确认目标、因而被跳过的书本数
 * @property newBookshelfId 新建书架 id；一本书都没有成功加入时为 null
 * @property failures 每一条失败的简短说明（含书名），便于界面直接列出来
 */
data class ImportReport(
    val added: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val newBookshelfId: Int? = null,
    val failures: List<String> = emptyList()
)

/**
 * 书架迁移引擎：**把书架里任意来源的书导入到当前激活的数据源**。
 *
 * ## 为什么是「来源任意、目标只能是当前激活源」
 * 插件 Api 只能碰到**当前激活**的数据源：
 * - [WebBookDataSourceManagerApi] 只提供 `getWebDataSource()`，拿不到别的数据源；
 * - `SearchProvider` 只能从激活源拿到；
 * - [BookRepositoryApi.getBookInformationFlow] 也是按激活源解析 id 的。
 *
 * 而本地书目缓存是**与来源无关**的全局表（[LocalBookDataSourceApi.getBookInformation] 没有来源字段），
 * 所以插件可以读到书架里每一本书的元数据。于是这个功能就是：
 * **目标 = 当前激活的数据源；来源 = 书架里任意来源的书**。
 * 用户想从 wenku8 迁到 linovelib，就把 linovelib 设为激活源，然后运行导入。
 *
 * ## 三个阶段与可恢复性
 * 1. [exportShelf] 导出书目（离线，只读本地缓存），落盘；
 * 2. [matchTargets] 在激活源里逐本搜索候选，**每本书结束后立刻落盘**，
 *    已搜过的书（`searchedAt != null`）会被跳过，因此切数据源、杀进程之后可以从断点继续；
 * 3. [applyToNewBookshelf] 把用户确认的书写进一个新书架，并搬走阅读进度。
 *
 * 计划文件写入采用「先写临时文件再改名」，中断不会留下被截断的 JSON；文件里带有格式版本，
 * 版本不认识时按「没有计划」处理，而不是误读。
 *
 * ## 来源标签是尽力而为
 * Api 没有「读取激活源显示名」的接口，因此 [MigrationPlan.sourceLabel]/[MigrationPlan.targetLabel]
 * 由 [activeSourceLabel] 拼出来，优先级为：激活源自己的 `id`（Api 4 起 [io.nightfish.lightnovelreader.api.web.WebBookDataSource]
 * 暴露）、宿主记录在用户数据里的数据源 id、以及搜索类型名。这些**只是提示**，可能不精确。
 *
 * ## 线程与异常
 * 所有公开方法内部都切到 [Dispatchers.IO]，不会抛异常（协程取消 [CancellationException] 除外）。
 * 计划文件读写、搜索与详情请求都可能很慢——数据源普遍限流（例如 wenku8 要求两次搜索间隔 ≥5 秒），
 * 因此进度通过 [matchTargets] 的 `onProgress` 回调上报，供界面显示。
 *
 * @param context 宿主注入的 Application Context；仅在插件私有目录不可写时用于兜底存放计划文件
 * @param localBookDataSource 本地书目缓存，导出阶段读取书名与作者
 * @param bookRepository 激活源的书本仓库，用于取候选详情与阅读进度
 * @param bookshelfRepository 书架仓库，用于读书架、建新书架、把书加入书架
 * @param webBookDataSourceManager 取得当前激活的数据源（搜索入口）
 * @param userDataRepository 读取宿主记录的数据源 id，用于给来源打标签
 * @param pluginContext 插件运行时上下文，计划文件默认放在它的 `dataDir`
 */
class MigrationEngine(
    private val context: Context,
    private val localBookDataSource: LocalBookDataSourceApi,
    private val bookRepository: BookRepositoryApi,
    private val bookshelfRepository: BookshelfRepositoryApi,
    private val webBookDataSourceManager: WebBookDataSourceManagerApi,
    private val userDataRepository: UserDataRepositoryApi,
    private val pluginContext: PluginContext,
) {

    /**
     * 最近一次导出/应用过程中的提示（例如「N 本书没有本地缓存，已跳过」）。
     *
     * 计划模型本身没有字段放这些说明，所以由引擎暴露给界面；
     * 每次 [exportShelf] 与 [applyToNewBookshelf] 都会整体替换。
     */
    var lastWarnings: List<String> = emptyList()
        private set

    // ------------------------------------------------------------------
    // 计划的读写
    // ------------------------------------------------------------------

    /**
     * 读取已落盘的迁移计划。
     *
     * @return 计划；文件不存在、内容损坏或格式版本不认识时返回 null
     */
    suspend fun loadPlan(): MigrationPlan? = withContext(Dispatchers.IO) { readPlanFile() }

    /**
     * 删除已落盘的迁移计划（连同写入过程中的临时文件）。
     *
     * @return 成功时不返回内容
     */
    suspend fun clearPlan(): Result<Unit, MigrationError> = withContext(Dispatchers.IO) {
        try {
            planFile().delete()
            planTempFile().delete()
            Ok(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(MigrationError.Io("删除迁移计划失败：${describe(error)}", error))
        }
    }

    // ------------------------------------------------------------------
    // 阶段一：导出
    // ------------------------------------------------------------------

    /**
     * 导出书架，生成迁移计划。
     *
     * 只读本地缓存与宿主数据库，不联网；书目信息不完整的书会被跳过并计入 [lastWarnings]。
     * 生成的计划会立刻落盘，覆盖上一份计划。
     *
     * @param bookshelfId 只导出某一个书架；为 null 时导出全部书架里出现过的书（按 id 去重）
     *
     * @return 生成的计划；书架为空或所有书都没有本地缓存时返回 [MigrationError.NoBooks]
     */
    suspend fun exportShelf(bookshelfId: Int? = null): Result<MigrationPlan, MigrationError> =
        withContext(Dispatchers.IO) {
            try {
                val shelves = bookshelfRepository.getAllBookshelves()
                val bookIds = if (bookshelfId == null) {
                    shelves.flatMap { it.allBookIds }.distinct()
                } else {
                    shelves.firstOrNull { it.id == bookshelfId }
                        ?.allBookIds
                        .orEmpty()
                        .distinct()
                }
                if (bookIds.isEmpty()) {
                    return@withContext Err(
                        MigrationError.NoBooks(
                            if (bookshelfId == null) "书架里还没有书" else "这个书架里还没有书"
                        )
                    )
                }

                val warnings = ArrayList<String>()
                val books = ArrayList<MigrationBook>(bookIds.size)
                var missingMetadata = 0
                for (id in bookIds) {
                    val info = attempt { localBookDataSource.getBookInformation(id) }
                    if (info == null) {
                        // 没有本地缓存的书在目标源里也无从匹配，直接跳过。
                        missingMetadata++
                        continue
                    }
                    val reading = attempt { bookRepository.getUserReadingData(id) }
                        ?: UserReadingData(id)
                    books += MigrationBook(
                        sourceBook = SourceBook(
                            id = id,
                            title = info.title,
                            author = info.author,
                            subtitle = info.subtitle,
                            wordCount = info.wordCount.count,
                            publishingHouse = info.publishingHouse,
                            lastReadChapterTitle = reading.lastReadChapterTitle
                                ?.takeIf { it.isNotBlank() },
                            readingProgress = reading.readingProgress,
                            totalReadTime = reading.totalReadTime,
                            lastReadTime = normalizeLastReadTime(reading.lastReadTime),
                            shelves = shelves
                                .filter { id in it.allBookIds }
                                .map { it.name }
                                .filter { it.isNotBlank() }
                        )
                    )
                }
                if (missingMetadata > 0) {
                    warnings += "$missingMetadata 本书没有本地缓存信息，已跳过（先在原数据源里打开一次即可缓存）"
                }
                if (books.isEmpty()) {
                    return@withContext Err(
                        MigrationError.NoBooks("书架里的书都没有本地缓存信息，无法导出")
                    )
                }

                val plan = MigrationPlan(
                    version = MIGRATION_PLAN_VERSION,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    sourceLabel = activeSourceLabel(),
                    targetLabel = "",
                    books = books
                )
                persist(plan)
                lastWarnings = warnings
                Ok(plan)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Err(MigrationError.Io("导出书架失败：${describe(error)}", error))
            }
        }

    // ------------------------------------------------------------------
    // 阶段二：匹配
    // ------------------------------------------------------------------

    /**
     * 在当前激活的数据源里逐本搜索候选，并增量写回计划。
     *
     * 搜索策略（数据源之间差异很大，因此不写死搜索类型）：
     * - 遍历 `searchProvider.searchTypes`，逐个类型尝试；**第一个能给出候选的类型就采用**；
     * - 关键词先用 [ChineseVariant.normalize] 归一为繁体（来源简体、目标繁体时命中率更高），
     *   该关键词没有候选时再用原始书名重试一次；
     * - 每个类型/关键词最多保留 [MAX_CANDIDATES_PER_BOOK] 个候选 id：每个候选都要额外取一次
     *   详情，不封顶会在限流的数据源上等很久。
     *
     * 每本书处理完立刻落盘，因此中断后再调用会跳过 `searchedAt != null` 的书继续跑。
     * 失败的书会写入 `note`（并同样标记 `searchedAt`，避免坏数据源让流程永远循环），
     * 需要重试时调用 [resetSearch]。
     *
     * @param onProgress 进度回调：`(已处理数, 总数, 当前书名)`；在每本书开始与结束时各回调一次
     *
     * @return 更新后的计划；没有计划时返回 [MigrationError.NoBooks]，取不到数据源时返回 [MigrationError.NoSource]
     */
    suspend fun matchTargets(
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): Result<MigrationPlan, MigrationError> = withContext(Dispatchers.IO) {
        try {
            var plan = readPlanFile()
                ?: return@withContext Err(
                    MigrationError.NoBooks("还没有迁移计划：请先执行导出（导出只在本地进行，不需要联网）")
                )
            if (plan.books.isEmpty()) {
                return@withContext Err(MigrationError.NoBooks("迁移计划里没有书，请重新导出"))
            }

            val provider = try {
                webBookDataSourceManager.getWebDataSource().searchProvider
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                return@withContext Err(
                    MigrationError.NoSource("无法获取当前激活的数据源：${describe(error)}", error)
                )
            }
            val searchTypes = attempt { provider.searchTypes }.orEmpty()
            if (searchTypes.isEmpty()) {
                return@withContext Err(
                    MigrationError.NoSource("当前数据源没有提供任何搜索类型，无法匹配")
                )
            }

            val books = plan.books
            val total = books.size
            var done = books.count { it.searched }
            for (book in books) {
                if (book.searched) continue
                val sourceBook = book.sourceBook
                onProgress(done, total, sourceBook.title)

                val outcome = searchBook(provider, searchTypes, sourceBook)
                val updated = book.copy(
                    candidates = outcome.candidates,
                    searchedAt = System.currentTimeMillis(),
                    note = outcome.note
                )
                plan = plan.copy(
                    books = plan.books.map { if (it.sourceBook.id == sourceBook.id) updated else it }
                )
                // 每本书都落盘：数据源限流时一次匹配可能跑好几分钟，不能把进度攒在内存里。
                persist(plan)
                done++
                onProgress(done, total, sourceBook.title)
            }

            plan = plan.copy(targetLabel = activeSourceLabel())
            persist(plan)
            Ok(plan)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Err(MigrationError.SearchFailed("匹配候选失败：${describe(error)}", error))
        }
    }

    /**
     * 清除指定书本的匹配结果，让它们可以在下次 [matchTargets] 时重新搜索。
     *
     * 典型用法：数据源当时不可用、匹配结果明显不对、或者换了激活源之后想重来。
     *
     * @param bookIds 需要重搜的来源书本 id
     *
     * @return 更新后的计划
     */
    suspend fun resetSearch(bookIds: List<String>): Result<MigrationPlan, MigrationError> =
        withContext(Dispatchers.IO) {
            try {
                val plan = readPlanFile()
                    ?: return@withContext Err(MigrationError.NoBooks("还没有迁移计划"))
                if (bookIds.isEmpty()) return@withContext Ok(plan)
                val targets = bookIds.toHashSet()
                val updated = plan.copy(
                    books = plan.books.map { book ->
                        if (book.sourceBook.id in targets) {
                            book.copy(
                                candidates = emptyList(),
                                searchedAt = null,
                                acceptedCandidateId = null,
                                note = ""
                            )
                        } else {
                            book
                        }
                    }
                )
                persist(updated)
                Ok(updated)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Err(MigrationError.Io("重置匹配结果失败：${describe(error)}", error))
            }
        }

    // ------------------------------------------------------------------
    // 阶段三：确认与应用
    // ------------------------------------------------------------------

    /**
     * 记录用户对某本书的决定。
     *
     * @param bookId 来源书本 id
     * @param targetId 目标书本 id；传 null 表示这本书跳过（不导入）
     *
     * @return 更新后的计划；计划里没有这本书时返回 [MigrationError.NoBooks]
     */
    suspend fun accept(bookId: String, targetId: String?): Result<MigrationPlan, MigrationError> =
        withContext(Dispatchers.IO) {
            try {
                val plan = readPlanFile()
                    ?: return@withContext Err(MigrationError.NoBooks("还没有迁移计划"))
                if (plan.books.none { it.sourceBook.id == bookId }) {
                    return@withContext Err(MigrationError.NoBooks("计划里没有这本书：$bookId"))
                }
                val updated = plan.copy(
                    books = plan.books.map { book ->
                        if (book.sourceBook.id == bookId) {
                            book.copy(acceptedCandidateId = targetId)
                        } else {
                            book
                        }
                    }
                )
                persist(updated)
                Ok(updated)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Err(MigrationError.Io("保存确认结果失败：${describe(error)}", error))
            }
        }

    /**
     * 把所有「高置信」候选自动确认为目标。
     *
     * 判定用 [MigrationMatcher.score] 现算（计划里只存候选，不存分数），因此判定标准始终跟着
     * 匹配算法走。已经在界面上手动确认过的书不会被覆盖。
     *
     * 出错时不抛异常，返回 0 并把原因写入 [lastWarnings]。
     *
     * @return 本次自动确认的书本数量
     */
    suspend fun acceptAllHighConfidence(): Int = withContext(Dispatchers.IO) {
        try {
            val plan = readPlanFile() ?: return@withContext 0
            var accepted = 0
            val updated = plan.copy(
                books = plan.books.map { book ->
                    if (book.acceptedCandidateId != null) return@map book
                    val best = book.candidates
                        .map { MigrationMatcher.score(book.sourceBook, it) }
                        .maxByOrNull { it.score }
                        ?: return@map book
                    if (best.level != MatchLevel.HIGH) return@map book
                    accepted++
                    book.copy(acceptedCandidateId = best.candidate.targetId)
                }
            )
            if (accepted > 0) persist(updated)
            lastWarnings = if (accepted == 0) {
                listOf("没有新的高置信匹配可以自动确认")
            } else {
                listOf("已自动确认 $accepted 本高置信匹配")
            }
            accepted
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            lastWarnings = listOf("自动确认失败：${describe(error)}")
            0
        }
    }

    /**
     * 把所有已确认的书导入到一个**新建**的书架。
     *
     * 每个目标书本 id 都必须能从当前激活源取到 [BookInformation]：宿主会用它的
     * `lastUpdated` 维护更新提醒表，凭空造一个空对象会把那张表写坏，所以取不到就跳过这本书
     * 并计入失败。
     *
     * 阅读进度只搬「不依赖章节结构」的部分：整体进度、累计时长、最后阅读时间取较大值；
     * 章节级的进度 map 与最后阅读章节保持目标书自己的值不动——两个站的章节 id 不同，
     * 复制过去只会把进度写坏。
     *
     * @param name 新书架名称；为空白时使用 [ImportReport] 说明里的默认名
     *
     * @return 导入统计；没有任何已确认的书时返回 [MigrationError.ApplyFailed]
     */
    suspend fun applyToNewBookshelf(name: String): Result<ImportReport, MigrationError> =
        withContext(Dispatchers.IO) {
            try {
                val plan = readPlanFile()
                    ?: return@withContext Err(MigrationError.NoBooks("还没有迁移计划"))
                val accepted = plan.books.filter { it.acceptedCandidateId != null }
                if (accepted.isEmpty()) {
                    return@withContext Err(
                        MigrationError.ApplyFailed("还没有确认任何书：请先完成匹配并确认目标，再执行导入")
                    )
                }

                val warnings = ArrayList<String>()
                val failures = ArrayList<String>()
                var added = 0
                var failed = 0
                val skipped = plan.books.size - accepted.size

                val newShelfId = generateShelfId()
                bookshelfRepository.addBookshelf(
                    Bookshelf(
                        id = newShelfId,
                        name = name.trim().ifEmpty { DEFAULT_SHELF_NAME },
                        sortType = BookshelfSortType.Default,
                        sortReversed = false,
                        autoCache = false,
                        systemUpdateReminder = false,
                        allBookIds = emptyList(),
                        pinnedBookIds = emptyList(),
                        updatedBookIds = emptyList()
                    )
                )

                for (book in accepted) {
                    val targetId = book.acceptedCandidateId ?: continue
                    val title = book.sourceBook.title
                    val info = fetchBookInformation(targetId)
                    if (info == null) {
                        failed++
                        failures += "《$title》：无法从当前数据源取到书本详情"
                        continue
                    }
                    try {
                        bookshelfRepository.addBookIntoBookShelf(newShelfId, info)
                        added++
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        failed++
                        failures += "《$title》：加入书架失败（${describe(error)}）"
                        continue
                    }
                    try {
                        carryOverReadingData(targetId, book.sourceBook)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        // 书已经进书架了，进度没搬过去不算整体失败，但要让用户知道。
                        failures += "《$title》：阅读进度迁移失败（${describe(error)}）"
                    }
                }

                if (added == 0) {
                    // 一本书都没成功：把刚建的空书架删掉，别在用户书架上留垃圾。
                    attempt { bookshelfRepository.deleteBookshelf(newShelfId) }
                    failures += "一本书都没有导入成功，已删除空书架「${name.trim().ifEmpty { DEFAULT_SHELF_NAME }}」"
                }
                lastWarnings = warnings
                Ok(
                    ImportReport(
                        added = added,
                        failed = failed,
                        skipped = skipped,
                        newBookshelfId = if (added > 0) newShelfId else null,
                        failures = failures
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Err(MigrationError.ApplyFailed("导入到新书架失败：${describe(error)}", error))
            }
        }

    // ------------------------------------------------------------------
    // 内部实现：搜索
    // ------------------------------------------------------------------

    /** 单本书的搜索结果：候选（已按分数排序）+ 失败说明。 */
    private class SearchOutcome(val candidates: List<MigrationCandidate>, val note: String)

    /**
     * 为一本书搜索候选。
     *
     * 按搜索类型逐个尝试，第一个给出候选的类型就直接返回；同一类型内先用繁体归一后的书名，
     * 没有候选再用原始书名。
     */
    private suspend fun searchBook(
        provider: SearchProvider,
        searchTypes: List<SearchType>,
        sourceBook: SourceBook
    ): SearchOutcome {
        val keywords = LinkedHashSet<String>(2)
        val normalized = ChineseVariant.normalize(sourceBook.title).trim()
        if (normalized.isNotEmpty()) keywords += normalized
        val raw = sourceBook.title.trim()
        if (raw.isNotEmpty()) keywords += raw
        if (keywords.isEmpty()) {
            return SearchOutcome(emptyList(), "书名是空的，无法搜索")
        }

        var firstNote = ""
        for (searchType in searchTypes) {
            for (keyword in keywords) {
                val result = runSearch(provider, searchType, keyword)
                if (result.note.isNotEmpty() && firstNote.isEmpty()) firstNote = result.note
                if (result.ids.isEmpty()) continue
                val candidates = buildCandidates(result.ids)
                if (candidates.isEmpty()) continue
                // rank 负责排序；计划里只存候选本身，分数由界面用 score() 现算。
                return SearchOutcome(
                    candidates = MigrationMatcher.rank(sourceBook, candidates).map { it.candidate },
                    note = ""
                )
            }
        }
        return SearchOutcome(emptyList(), firstNote.ifEmpty { "没有找到候选" })
    }

    /** 一次搜索的原始结果：候选 id + 终止原因。 */
    private class SearchIds(val ids: List<String>, val note: String)

    /**
     * 执行一次搜索，取回最多 [MAX_CANDIDATES_PER_BOOK] 个候选 id。
     *
     * 数据流在 [SearchResult.End]、[SearchResult.Empty]、[SearchResult.Error] 处结束；
     * 另外用 `take` 再封一次顶，避免某个数据源永远不结束导致这里挂住。
     */
    private suspend fun runSearch(
        provider: SearchProvider,
        searchType: SearchType,
        keyword: String
    ): SearchIds {
        var terminalNote = ""
        val results = try {
            provider.search(searchType, keyword)
                .takeWhile { result ->
                    when (result) {
                        is SingleBook, is MultipleBook -> true
                        is Error -> {
                            terminalNote = result.error.message
                                ?.takeIf { it.isNotBlank() }
                                ?: "数据源返回了搜索错误"
                            false
                        }

                        is End, is Empty -> false
                    }
                }
                .take(MAX_CANDIDATES_PER_BOOK)
                .toList()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return SearchIds(emptyList(), "搜索失败：${describe(error)}")
        }
        val ids = results.mapNotNull { it.bookIdOrNull() }.distinct()
        return SearchIds(ids, terminalNote)
    }

    /**
     * 把候选 id 变成完整候选：搜索只给 id，书名作者必须再取一次详情。
     *
     * 取不到详情的候选直接丢弃——留着它只会让界面显示一个不知道是什么的 id。
     */
    private suspend fun buildCandidates(ids: List<String>): List<MigrationCandidate> {
        val candidates = ArrayList<MigrationCandidate>(ids.size)
        for (id in ids) {
            val info = fetchBookInformation(id) ?: continue
            candidates += MigrationCandidate(
                targetId = info.id,
                title = info.title,
                author = info.author,
                subtitle = info.subtitle,
                wordCount = info.wordCount.count,
                publishingHouse = info.publishingHouse
            )
        }
        return candidates
    }

    /**
     * 取一本书的详情。
     *
     * [BookRepositoryApi.getBookInformationFlow] 是「先本地后远端」的数据流：取其中最后一次
     * 成功的结果（远端结果通常更新），全都失败则返回 null。
     */
    private suspend fun fetchBookInformation(id: String): BookInformation? {
        val results = try {
            bookRepository.getBookInformationFlow(id).toList()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return null
        }
        return results.mapNotNull { it.get() }.lastOrNull()
    }

    // ------------------------------------------------------------------
    // 内部实现：数据搬运
    // ------------------------------------------------------------------

    /**
     * 把来源书的阅读进度合并到目标书上。
     *
     * 目标书自己的章节级数据（两个进度 map、最后阅读章节）保持不动：
     * 两个数据源的章节 id 体系不同，照搬会把目标书的阅读位置写到不存在的章节上。
     */
    private suspend fun carryOverReadingData(targetId: String, source: SourceBook) {
        val sourceLastRead = parseIsoDateTime(source.lastReadTime)
        bookRepository.updateUserReadingData(targetId) { current ->
            current.copy(
                lastReadTime = laterOf(current.lastReadTime, sourceLastRead),
                totalReadTime = maxOf(current.totalReadTime, source.totalReadTime),
                readingProgress = maxOf(current.readingProgress, source.readingProgress)
            )
        }
    }

    /** 生成一个当前不冲突的书架 id：以时间戳为种子，冲突则顺延。 */
    private suspend fun generateShelfId(): Int {
        val existing = attempt { bookshelfRepository.getAllBookshelfIds() }.orEmpty().toHashSet()
        var candidate = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
        var attempts = 0
        while (candidate in existing && attempts < MAX_SHELF_ID_ATTEMPTS) {
            candidate = (candidate + 1) and 0x7FFFFFFF
            attempts++
        }
        return candidate
    }

    // ------------------------------------------------------------------
    // 内部实现：来源标签
    // ------------------------------------------------------------------

    /**
     * 尽力而为地拼出当前激活数据源的标签。
     *
     * Api 没有「读取数据源显示名」的单个方法，所以这里按可靠性从高到低拼：
     * 1. 激活源类上的 [WebDataSource] 注解的 `name`/`provider`——宿主自己就是用它给数据源
     *    起显示名的（`WebBookDataSourceManager.loadWebDataSourceClass`），可读性最好；
     * 2. 激活源自己的 [io.nightfish.lightnovelreader.api.web.WebBookDataSource.id]（`命名空间:id`，
     *    Api 4 起的公开成员）；
     * 3. 宿主记录在 [UserDataPath.Settings.Data.WebDataSourceId] 里的 id 字符串
     *    （宿主写入的就是 `Identifier.toString()`）；
     * 4. 激活源注册的搜索类型名（站点风格的补充线索）。
     *
     * **纯数字片段会被丢掉**：Api 2 时代的数据源 id 是 `字符串.hashCode()`，例如 linovelib 是
     * `"linovelib_tw".hashCode()` = 282781674。把这种数字当标签展示对用户毫无意义，因此只保留
     * 可读部分；实在没有可读部分时才退回「当前激活的数据源（id …）」并附上搜索类型。
     *
     * 结果仅用于界面提示，**不代表数据源的正式名称**。
     */
    private suspend fun activeSourceLabel(): String {
        val displayName = activeSourceDisplayName()
        val identifier = attempt { webBookDataSourceManager.getWebDataSource().id }
        val storedId = attempt {
            userDataRepository
                .stringUserData(UserDataPath.Settings.Data.WebDataSourceId.path)
                .get()
        }?.takeIf { it.isNotBlank() }
        val searchTypes = attempt {
            webBookDataSourceManager.getWebDataSource()
                .searchProvider
                .searchTypes
                .joinToString("/") { it.type }
        }?.takeIf { it.isNotBlank() }

        val parts = ArrayList<String>(3)
        when {
            displayName != null -> parts += displayName
            else -> {
                val readable = readableIdentifier(identifier)
                when {
                    readable != null -> parts += readable
                    // 激活源 id 全是数字：退回宿主记录的那份 id（可读时才有用）
                    storedId != null && !storedId.looksLikeRawNumber() -> parts += storedId
                }
            }
        }
        searchTypes?.let { parts += "搜索类型 $it" }
        if (parts.isEmpty()) {
            val raw = identifier?.toString() ?: storedId
            parts += if (raw.isNullOrBlank()) {
                "当前激活的数据源（名称未知）"
            } else {
                "当前激活的数据源（id $raw）"
            }
        }
        return parts.joinToString(" ｜ ")
    }

    /**
     * 读激活源类上的 [WebDataSource] 注解，拼成 `显示名 · 提供方`。
     *
     * 宿主给数据源起显示名走的就是同一条路，因此这里拿到的通常是最可读的名字。
     *
     * @return 可读名；注解缺失或为空时返回 null
     */
    private suspend fun activeSourceDisplayName(): String? = attempt {
        val annotation = webBookDataSourceManager.getWebDataSource()
            .javaClass
            .getAnnotationsByType(WebDataSource::class.java)
            .firstOrNull()
            ?: return@attempt null
        listOf(annotation.name, annotation.provider)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
            .takeIf { it.isNotEmpty() }
    }

    /**
     * 把 [Identifier] 拼成可读标签；两段都是纯数字（老插件的哈希 id）时返回 null。
     *
     * @return 形如 `lightnovelreader:Wenku8` 的标签；没有任何可读片段时为 null
     */
    private fun readableIdentifier(identifier: Identifier?): String? {
        if (identifier == null) return null
        val namespace = identifier.namespace.trim()
            .takeIf { it.isNotEmpty() && !it.looksLikeRawNumber() }
        val id = identifier.id.trim()
            .takeIf { it.isNotEmpty() && !it.looksLikeRawNumber() }
        return when {
            namespace != null && id != null -> "$namespace:$id"
            id != null -> id
            namespace != null -> namespace
            else -> null
        }
    }

    /** 判断一个 id 片段是否只是哈希出来的数字（对用户没有意义）。 */
    private fun String.looksLikeRawNumber(): Boolean =
        isNotEmpty() && any { it.isDigit() } && all { it.isDigit() || it == '-' }

    // ------------------------------------------------------------------
    // 内部实现：计划文件
    // ------------------------------------------------------------------

    /** 计划文件所在目录；插件私有目录不可写时退回宿主的缓存目录。 */
    private fun planDirectory(): File {
        val dataDir = pluginContext.dataDir
        if (dataDir.isDirectory || dataDir.mkdirs()) return dataDir
        // 极少数设备上插件数据目录可能不存在且创建失败；退回宿主缓存目录，
        // 至少让「导出 → 匹配 → 应用」在本次会话里可用，而不是功能整体不可用。
        return File(context.cacheDir, PLAN_DIRECTORY_NAME).also { it.mkdirs() }
    }

    /** 计划文件。 */
    private fun planFile(): File = File(planDirectory(), PLAN_FILE_NAME)

    /** 写入计划时的临时文件，与计划文件同目录以保证改名是同一文件系统内的原子操作。 */
    private fun planTempFile(): File = File(planDirectory(), "$PLAN_FILE_NAME$PLAN_TEMP_SUFFIX")

    /** 读取并校验计划文件；任何异常都按「没有计划」处理。 */
    private fun readPlanFile(): MigrationPlan? {
        val file = planFile()
        if (!file.isFile) return null
        return try {
            val plan = MIGRATION_JSON.decodeFromString(MigrationPlan.serializer(), file.readText())
            if (plan.version == MIGRATION_PLAN_VERSION) plan else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 落盘计划：先写临时文件再改名，避免中断时留下被截断的 JSON。
     *
     * @throws Throwable 写盘失败时抛出，由调用方转成 [MigrationError.Io]
     */
    private fun persist(plan: MigrationPlan) {
        val json = MIGRATION_JSON.encodeToString(MigrationPlan.serializer(), plan)
        val temp = planTempFile()
        temp.writeText(json, Charsets.UTF_8)
        if (temp.renameTo(planFile())) return
        // 少数文件系统上 rename 可能失败：退回直接覆盖，再删掉临时文件。
        planFile().writeText(json, Charsets.UTF_8)
        temp.delete()
    }

    // ------------------------------------------------------------------
    // 内部实现：小工具
    // ------------------------------------------------------------------

    /** 运行一段可能抛异常的挂起调用；取消除外，失败返回 null。 */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    /** 解析 ISO-8601 本地时间；空值与非法值返回 null。 */
    private fun parseIsoDateTime(value: String?): LocalDateTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { LocalDateTime.parse(text) }.getOrNull()
    }

    /** 取两个时间中较晚的一个。 */
    private fun laterOf(current: LocalDateTime?, other: LocalDateTime?): LocalDateTime? = when {
        current == null -> other
        other == null -> current
        other.isAfter(current) -> other
        else -> current
    }

    /**
     * 把 [UserReadingData.lastReadTime] 转成 ISO-8601 字符串。
     *
     * 宿主用 `LocalDateTime.MIN` 表示「从未阅读」（批量读取时不会转成 null），这里统一转成 null。
     */
    private fun normalizeLastReadTime(value: LocalDateTime?): String? =
        value?.takeIf { it.isAfter(LocalDateTime.MIN) }?.toString()

    /** 取一段可读的异常说明。 */
    private fun describe(error: Throwable): String {
        val text = error.message?.trim().orEmpty()
        return if (text.isEmpty()) error.javaClass.simpleName else text
    }

    /** [SearchResult] → 书本 id；结束/错误结果没有 id。 */
    private fun SearchResult.bookIdOrNull(): String? = when (this) {
        is SingleBook -> bookId
        is MultipleBook -> bookId
        else -> null
    }

    companion object {
        /**
         * 新书架的默认名称：界面上的输入框留空时用它。
         *
         * 这是引擎与界面之间的约定，因此公开；其余常量与 Json 实例都是实现细节。
         */
        const val DEFAULT_SHELF_NAME = "迁移导入"

        /** 计划文件名。 */
        private const val PLAN_FILE_NAME = "migration-plan.json"

        /** 写入计划时的临时文件后缀。 */
        private const val PLAN_TEMP_SUFFIX = ".tmp"

        /** 插件数据目录不可用时，退回宿主缓存目录所用的子目录名。 */
        private const val PLAN_DIRECTORY_NAME = "lnr-wenku8plus-migration"

        /** 每本书最多保留的候选数量：每个候选都要多取一次详情，必须封顶。 */
        private const val MAX_CANDIDATES_PER_BOOK = 12

        /** 生成书架 id 时最多顺延多少次。 */
        private const val MAX_SHELF_ID_ATTEMPTS = 10_000

        /**
         * 计划文件用的 Json。
         *
         * - `ignoreUnknownKeys`：未来版本加字段时旧版本仍能读；
         * - `prettyPrint`：文件要能人工查看/手改；
         * - `encodeDefaults`：**必须开启**，否则带默认值的 `version` 不会写进文件——
         *   将来把 [MIGRATION_PLAN_VERSION] 提升到 2 时，旧文件会被解出「默认值 2」而被误读，
         *   而不是被版本闸门拒绝。
         */
        private val MIGRATION_JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

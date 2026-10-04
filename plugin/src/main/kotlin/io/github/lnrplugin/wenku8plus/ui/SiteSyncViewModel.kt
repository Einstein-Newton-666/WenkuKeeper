package io.github.lnrplugin.wenku8plus.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import io.github.lnrplugin.wenku8plus.PluginSettings
import io.github.lnrplugin.wenku8plus.site.SiteShelfEntry
import io.github.lnrplugin.wenku8plus.site.Wenku8SiteClient
import io.github.lnrplugin.wenku8plus.tools.Wenku8HttpClient
import io.nightfish.lightnovelreader.api.book.BookRepositoryApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 站点同步的状态。
 *
 * @property running 是否有站点操作正在执行
 * @property runningLabel 正在执行的操作描述, [running] 为 false 时为空串
 * @property message 最近一次操作的结果, 由本次操作直接产生
 * @property siteShelfCount 最近一次读到的站点书架条数, 未读取过时为 null
 * @property siteBookmarkCount 其中带有书签的条数, 未读取过时为 null
 * @property preview 站点书架前几本的摘要, 供页面展示
 */
data class SiteSyncUiState(
    val running: Boolean = false,
    val runningLabel: String = "",
    val message: String = "",
    val siteShelfCount: Int? = null,
    val siteBookmarkCount: Int? = null,
    val preview: List<String> = emptyList(),
)

/**
 * wenku8 站点账号同步的状态持有者。
 *
 * 与 [SyncViewModel] 一样不继承 `androidx.lifecycle.ViewModel`：宿主不向插件提供
 * `ViewModelStore`，因此状态由 [MutableStateFlow] 持有、后台任务跑在自带的协程作用域上。
 *
 * ## 为什么只做这两件事
 *
 * 站点的书架模型是「一本书一条记录，`cid` 就是书签」，因此：
 *
 * - **推送**：把宿主书架里的书逐本 `addbookcase.php?bid=&cid=`，一次调用同时完成
 *   「加入书架」与「把书签移到宿主记录的那一章」；
 * - **读取**：解析书架页，用来**验证推送是否真的生效**——站点用重定向表达结果，
 *   只看请求成功是不够的。
 *
 * 刻意**不做**「把站点书架的书加进宿主书架」：那需要为每本书取到 `BookInformation`
 * （宿主用它的 `lastUpdated` 维护更新提醒表），在站点不可达时会写坏那张表。
 *
 * @param context 宿主注入的应用上下文，用于操作结束后的 [Toast] 提示
 * @param userDataRepository 用户数据仓库，用于读取开关、Cookie 与首选镜像
 * @param bookshelfRepository 书架仓库，用于枚举要推送的书
 * @param bookRepository 书本仓库，用于读取每本书最后读到的章节
 */
class SiteSyncViewModel(
    private val context: Context,
    private val userDataRepository: UserDataRepositoryApi,
    private val bookshelfRepository: BookshelfRepositoryApi,
    private val bookRepository: BookRepositoryApi
) {

    companion object {
        /**
         * 逐本推送之间的等待时间。
         *
         * 站点对高频请求有限流（搜索路径要求间隔 5 秒，书架操作没有明确的公开限制，
         * 但同源请求密集同样会被 Cloudflare 拦），因此宁可慢一点也不要触发风控。
         */
        private const val PUSH_INTERVAL_MILLIS = 1_500L

        /** 页面预览的行数上限。 */
        private const val PREVIEW_LIMIT = 8

        /** 结果里最多列出几条失败原因。 */
        private const val FAILURE_SAMPLE_LIMIT = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(SiteSyncUiState())

    /** 页面观察的站点同步状态流。 */
    val uiState: StateFlow<SiteSyncUiState> = _uiState.asStateFlow()

    /**
     * 把宿主书架里的书推送到站点书架，并把书签同步到宿主记录的阅读位置。
     *
     * 只处理**书本 id 为纯数字**的书——那是 wenku8 的书本 id，可直接作为站点接口的 `bid`。
     * 来自其它数据源的书（id 带前缀）无法映射，会被计入「已跳过」而不是静默丢弃。
     */
    fun pushShelf() = run("正在推送到站点书架") {
        val client = openClient() ?: return@run

        val allIds = bookshelfRepository.getAllBookshelves()
            .flatMap { it.allBookIds }
            .distinct()
        val pushable = allIds.filter { it.toLongOrNull() != null }
        val skipped = allIds.size - pushable.size

        if (pushable.isEmpty()) {
            finish("书架里没有可推送的书（需要 wenku8 来源的书本 id）")
            return@run
        }

        // 宿主只在读到过某本书时才有一条阅读记录，没有记录的就是"还没读"，推送时不带 cid。
        val readingByBookId = bookRepository.getAllUserReadingData().associateBy { it.id }

        var ok = 0
        var withBookmark = 0
        val failures = mutableListOf<String>()
        pushable.forEachIndexed { index, bookId ->
            val chapterId = readingByBookId[bookId]?.lastReadChapterId
            if (!chapterId.isNullOrBlank()) withBookmark++
            client.addToShelf(bookId, chapterId)
                .onOk { ok++ }
                .onErr { error ->
                    // 只留前几条失败原因，避免一条超长消息塞满界面。
                    if (failures.size < FAILURE_SAMPLE_LIMIT) failures += "「$bookId」${describe(error)}"
                }
            progress("已推送 ${index + 1}/$pushable.size")
            if (index != pushable.lastIndex) delay(PUSH_INTERVAL_MILLIS)
        }

        val suffix = buildString {
            if (skipped > 0) append("，跳过 $skipped 本非 wenku8 来源的书")
            if (failures.isNotEmpty()) append("；失败示例：${failures.joinToString("；")}")
        }
        finish("已推送 $ok/${pushable.size} 本（其中 $withBookmark 本带书签）$suffix")
    }

    /**
     * 读取站点书架并展示摘要。
     *
     * 这是验证推送的唯一可靠方式：站点接口用重定向表达结果，请求成功不等于操作生效。
     */
    fun loadSiteShelf() = run("正在读取站点书架") {
        val client = openClient() ?: return@run
        client.fetchShelf()
            .onOk { entries ->
                val bookmarked = entries.count { !it.bookmarkChapterId.isNullOrBlank() }
                _uiState.update {
                    it.copy(
                        siteShelfCount = entries.size,
                        siteBookmarkCount = bookmarked,
                        preview = entries.take(PREVIEW_LIMIT).map { entry -> entry.describe() }
                    )
                }
                finish("站点书架共 ${entries.size} 本，其中 $bookmarked 本有书签")
            }
            .onErr { error -> finish(describe(error)) }
    }

    /** 释放协程作用域；页面不再使用时调用。 */
    fun dispose() {
        scope.cancel()
    }

    // -----------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------

    /**
     * 构造一个可用的站点客户端。
     *
     * 三道前置条件全部在这里检查，失败时给出**可操作**的说明而不是笼统的"失败"：
     * 开关没开、Cookie 没填、站点连不上，处理方式各不相同。
     *
     * @return 已选好镜像、带 Cookie 的客户端；前置条件不满足时为 null（已写好提示）
     */
    private suspend fun openClient(): Wenku8SiteClient? {
        val enabled = runCatching {
            userDataRepository.booleanUserData(PluginSettings.SITE_SYNC_ENABLED)
                .get() ?: PluginSettings.DEFAULT_SITE_SYNC_ENABLED
        }.getOrElse { PluginSettings.DEFAULT_SITE_SYNC_ENABLED }
        if (!enabled) {
            finish("站点同步未开启：请先打开上面的开关")
            return null
        }

        val cookie = runCatching {
            userDataRepository.stringUserData(PluginSettings.WENKU8_COOKIE).get()
        }.getOrNull()?.trim().orEmpty()
        if (cookie.isEmpty()) {
            finish("还没有填写会话 Cookie：请先在「wenku8 账号 (可选)」里粘贴浏览器的 Cookie")
            return null
        }

        val preferredHost = runCatching {
            userDataRepository.stringUserData(PluginSettings.PREFERRED_HOST).get()
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val http = Wenku8HttpClient()
        http.updateCookie(cookie)
        http.selectHost(preferredHost)
        return Wenku8SiteClient(http)
    }

    /**
     * 在 IO 协程里执行一次操作，统一处理进行中状态、异常与 [Toast]。
     *
     * @param label 进行中显示的文字
     * @param block 实际操作
     */
    private fun run(label: String, block: suspend () -> Unit) {
        if (_uiState.value.running) {
            toast("上一个操作还没有结束")
            return
        }
        _uiState.update { it.copy(running = true, runningLabel = label, message = "") }
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                finish("操作失败：${describe(throwable)}")
            } finally {
                _uiState.update { it.copy(running = false, runningLabel = "") }
            }
        }
    }

    /** 更新进行中的进度文字。 */
    private fun progress(text: String) {
        _uiState.update { it.copy(runningLabel = text) }
    }

    /** 写入结果并弹出提示。 */
    private fun finish(message: String) {
        _uiState.update { it.copy(message = message) }
        toast(message)
    }

    /** 在主线程序列上弹一个 Toast。 */
    private fun toast(message: String) {
        mainHandler.post {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }

    /** 把异常转成一句可展示的说明；优先用异常自带的中文 message。 */
    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    /** 站点书架一条记录的摘要文字。 */
    private fun SiteShelfEntry.describe(): String =
        if (bookmarkChapterId.isNullOrBlank()) {
            "$title（无书签）"
        } else {
            "$title · 书签：${bookmarkTitle.ifBlank { "第 $bookmarkChapterId 章" }}"
        }
}

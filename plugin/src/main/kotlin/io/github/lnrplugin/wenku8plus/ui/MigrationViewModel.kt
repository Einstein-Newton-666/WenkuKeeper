package io.github.lnrplugin.wenku8plus.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import io.github.lnrplugin.wenku8plus.migrate.ImportReport
import io.github.lnrplugin.wenku8plus.migrate.MigrationEngine
import io.github.lnrplugin.wenku8plus.migrate.MigrationPlan
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 书库迁移页面状态。
 *
 * @property plan 当前落盘的迁移计划; 为 null 表示本机还没有计划
 * @property shelves 宿主里的书架列表, 供"导出范围"选择
 * @property running 是否有迁移操作正在执行
 * @property runningLabel 正在执行的操作说明, [running] 为 false 时为空串
 * @property progressDone 匹配阶段已经处理完的书本数
 * @property progressTotal 匹配阶段需要处理的书本总数
 * @property progressCurrent 匹配阶段当前正在搜索的书名
 * @property message 最近一次操作的结果说明(可能是成功也可能是失败)
 * @property report 最近一次"导入到新书架"的结果统计
 * @property warnings 引擎给出的提示, 例如"N 本书没有本地缓存信息, 已跳过"
 */
data class MigrationUiState(
    val plan: MigrationPlan? = null,
    val shelves: List<Bookshelf> = emptyList(),
    val running: Boolean = false,
    val runningLabel: String = "",
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val progressCurrent: String = "",
    val message: String = "",
    val report: ImportReport? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * 书库迁移的状态持有者。
 *
 * 与 [SyncViewModel] 一样, 这里刻意不继承 `androidx.lifecycle.ViewModel`: 宿主不会给插件提供
 * `ViewModelStore`。所有耗时工作都交给 [MigrationEngine], 它们内部已经切到 IO 线程并且不抛异常,
 * 本类只负责串行化操作、把结果映射成界面状态, 并在主线程弹一次提示。
 *
 * @param context 宿主注入的应用上下文, 用于操作结束后的 [Toast] 提示
 * @param userDataRepository 宿主提供的用户数据仓库; 迁移只读写自己的计划文件,
 *   保留该参数是为了与 [SyncViewModel] 的构造方式保持一致, 并便于后续把用户的选择持久化
 * @param migrationEngine 迁移引擎, 由插件入口类构造后注入
 * @param bookshelfRepository 书架仓库, 仅用于列出书架供"导出范围"选择, 以及导入后刷新列表
 */
class MigrationViewModel(
    private val context: Context,
    private val userDataRepository: UserDataRepositoryApi,
    private val migrationEngine: MigrationEngine,
    private val bookshelfRepository: BookshelfRepositoryApi,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(MigrationUiState())

    /** 页面观察的迁移状态流。 */
    val uiState: StateFlow<MigrationUiState> = _uiState.asStateFlow()

    /**
     * 重新读取迁移计划与书架列表。
     *
     * 页面首次显示时调用; 也是"切回页面后看到最新进度"的入口。
     */
    fun refresh() {
        scope.launch {
            val plan = migrationEngine.loadPlan()
            val shelves = loadShelves()
            _uiState.update { it.copy(plan = plan, shelves = shelves) }
        }
    }

    /**
     * 阶段一: 导出书架, 生成迁移计划。
     *
     * 只读本地缓存, 不联网, 因此可以在切换数据源之前完成。
     *
     * @param bookshelfId 只导出某一个书架; 传 null 表示导出全部书架
     */
    fun exportShelf(bookshelfId: Int?) {
        launchOperation("正在导出书架…") {
            var status = "导出失败：没有返回结果"
            migrationEngine.exportShelf(bookshelfId)
                .onOk { plan ->
                    val warnings = migrationEngine.lastWarnings
                    _uiState.update {
                        it.copy(plan = plan, warnings = warnings, report = null)
                    }
                    status = "已导出 ${plan.books.size} 本书"
                }
                .onErr { error ->
                    status = error.message
                }
            status
        }
    }

    /**
     * 阶段二: 在**当前激活的数据源**里逐本搜索候选。
     *
     * 进度通过 [MigrationEngine.matchTargets] 的回调上报; 需要重搜时先调用 [resetSearch],
     * 否则已搜过的书会被跳过(这正是"中断后可以继续"的实现方式)。
     */
    fun matchTargets() {
        launchOperation("正在匹配候选…") {
            _uiState.update {
                it.copy(progressDone = 0, progressTotal = 0, progressCurrent = "")
            }
            var status = "匹配失败：没有返回结果"
            migrationEngine.matchTargets { done, total, current ->
                _uiState.update {
                    it.copy(progressDone = done, progressTotal = total, progressCurrent = current)
                }
            }
                .onOk { plan ->
                    _uiState.update { it.copy(plan = plan) }
                    status = "匹配完成：${plan.matchedCount}/${plan.books.size} 本找到候选"
                }
                .onErr { error ->
                    status = error.message
                }
            status
        }
    }

    /**
     * 确认某本书的目标, 或者把它标记为"跳过"。
     *
     * 这个操作由用户在列表里逐条点击触发, 状态本身就会立刻刷新, 因此不弹 [Toast]。
     *
     * @param bookId 来源书本 id
     * @param targetId 目标书本 id; 传 null 表示跳过这本书(不导入)
     */
    fun accept(bookId: String, targetId: String?) {
        launchOperation("正在保存确认结果…", toast = false) {
            var status = ""
            migrationEngine.accept(bookId, targetId)
                .onOk { plan ->
                    _uiState.update { it.copy(plan = plan) }
                    status = if (targetId == null) "已跳过这本书" else "已确认目标"
                }
                .onErr { error ->
                    status = error.message
                }
            status
        }
    }

    /** 把所有"高置信"候选一次性确认为目标; 已经手动确认过的书不会被覆盖。 */
    fun acceptAllHighConfidence() {
        launchOperation("正在采纳高置信匹配…") {
            val count = migrationEngine.acceptAllHighConfidence()
            val plan = migrationEngine.loadPlan()
            val warnings = migrationEngine.lastWarnings
            _uiState.update { it.copy(plan = plan, warnings = warnings) }
            "已采纳 $count 本高置信匹配"
        }
    }

    /**
     * 清除指定书的匹配结果, 让它们可以在下次 [matchTargets] 时重新搜索。
     *
     * 同样由列表里的按钮直接触发, 不弹 [Toast]。
     *
     * @param bookIds 需要重搜的来源书本 id
     */
    fun resetSearch(bookIds: List<String>) {
        if (bookIds.isEmpty()) return
        launchOperation("正在重置匹配结果…", toast = false) {
            var status = ""
            migrationEngine.resetSearch(bookIds)
                .onOk { plan ->
                    _uiState.update { it.copy(plan = plan) }
                    status = "已重置 ${bookIds.size} 本书的匹配结果"
                }
                .onErr { error ->
                    status = error.message
                }
            status
        }
    }

    /**
     * 阶段三: 把已确认的书导入到一个新建的书架, 并尽量搬运阅读进度。
     *
     * @param name 新书架的名称; 空白时由引擎使用它自己的默认名
     */
    fun apply(name: String) {
        launchOperation("正在导入到新书架…") {
            var status = "导入失败：没有返回结果"
            migrationEngine.applyToNewBookshelf(name)
                .onOk { report ->
                    val warnings = migrationEngine.lastWarnings
                    _uiState.update { it.copy(report = report, warnings = warnings) }
                    status = describe(report)
                }
                .onErr { error ->
                    status = error.message
                }
            // 新书架已经建好, 刷新列表让"导出范围"立刻能看到它。
            val shelves = loadShelves()
            _uiState.update { it.copy(shelves = shelves) }
            status
        }
    }

    /** 删除本机的迁移计划, 同时清空界面上的匹配结果与导入报告。 */
    fun clearPlan() {
        launchOperation("正在清除迁移计划…") {
            var status = "清除失败：没有返回结果"
            migrationEngine.clearPlan()
                .onOk {
                    _uiState.update {
                        it.copy(
                            plan = null,
                            report = null,
                            warnings = emptyList(),
                            progressDone = 0,
                            progressTotal = 0,
                            progressCurrent = ""
                        )
                    }
                    status = "已清除本机的迁移计划"
                }
                .onErr { error ->
                    status = error.message
                }
            status
        }
    }

    /**
     * 取消所有后台任务。
     *
     * 插件被卸载时应当调用; 取消之后本对象不应再被使用。
     */
    fun dispose() {
        scope.cancel()
    }

    /**
     * 以互斥的方式执行一次迁移操作。
     *
     * @param label 操作进行中的提示文字
     * @param toast 操作结束后是否弹出 [Toast]; 逐条确认之类的操作由界面自己给出反馈, 传 false
     * @param block 真正执行的操作, 返回本次操作的结果说明
     */
    private fun launchOperation(label: String, toast: Boolean = true, block: suspend () -> String) {
        if (_uiState.value.running) return
        // 所有调用都来自界面线程, 因此在启动协程之前就把状态置为"进行中", 避免连点触发两次操作。
        _uiState.update { it.copy(running = true, runningLabel = label, message = "") }
        scope.launch {
            val status = try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                "操作失败：${throwable.message ?: throwable::class.java.simpleName}"
            }
            _uiState.update { it.copy(running = false, runningLabel = "", message = status) }
            if (toast) showToast(status)
        }
    }

    /** 读取书架列表; 宿主接口失败时退化为空列表, 不影响迁移本身。 */
    private suspend fun loadShelves(): List<Bookshelf> = try {
        bookshelfRepository.getAllBookshelves()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        emptyList()
    }

    /** 在主线程弹出一次简短提示; 操作在 IO 线程结束, 因此这里必须切回主线程。 */
    private fun showToast(message: String) {
        if (message.isBlank()) return
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/** 把导入报告渲染为一行提示文字。 */
private fun describe(report: ImportReport): String {
    val shelf = report.newBookshelfId?.let { "（书架 id $it）" }.orEmpty()
    val failed = if (report.failed > 0) "，失败 ${report.failed} 本" else ""
    return "已导入 ${report.added} 本$shelf，跳过 ${report.skipped} 本$failed"
}

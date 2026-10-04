package io.github.lnrplugin.wenku8plus.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import io.github.lnrplugin.wenku8plus.PluginSettings
import io.github.lnrplugin.wenku8plus.cloud.CloudSyncEngine
import io.github.lnrplugin.wenku8plus.cloud.CloudSyncError
import io.github.lnrplugin.wenku8plus.cloud.RestoreReport
import io.github.lnrplugin.wenku8plus.cloud.SyncReport
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
 * 插件页面上展示的云端同步状态。
 *
 * 该状态由 [SyncViewModel] 维护, 页面只需要读取它并渲染, 不需要自己保存任何中间状态。
 *
 * @property running 是否有云端操作正在执行
 * @property runningLabel 正在执行的操作的描述文字, [running] 为 false 时为空串
 * @property message 最近一次操作的结果描述, 由本次操作直接产生 (可能是成功也可能是失败)
 * @property lastSyncStatus 用户数据 [PluginSettings.LAST_SYNC_STATUS] 的内容, 即同步引擎写入的最近结果
 * @property lastUploadTime 上次成功上传的时间戳(epoch 毫秒), 未同步过时为 null
 * @property lastRestoreTime 上次成功恢复的时间戳(epoch 毫秒), 未恢复过时为 null
 * @property remoteSnapshotCount 最近一次列出的远端快照数量, 未知时为 null
 */
data class SyncUiState(
    val running: Boolean = false,
    val runningLabel: String = "",
    val message: String = "",
    val lastSyncStatus: String = "",
    val lastUploadTime: Long? = null,
    val lastRestoreTime: Long? = null,
    val remoteSnapshotCount: Int? = null,
)

/**
 * 云端同步的状态持有者。
 *
 * 这里刻意不继承 `androidx.lifecycle.ViewModel`: 宿主不会向插件提供 `ViewModelStore`,
 * 插件也无法参与宿主的 ViewModel 生命周期, 因此状态由普通的 [MutableStateFlow] 持有,
 * 后台任务跑在自带的 [CoroutineScope] 上 (见 [dispose])。
 *
 * 所有涉及网络与数据库的调用都通过 [CloudSyncEngine] 的挂起函数完成, 并且只在本类的
 * IO 协程中执行, 不会阻塞界面线程。
 *
 * @param context 宿主注入的应用上下文, 目前用于操作结束后的 [Toast] 提示
 * @param userDataRepository 宿主提供的用户数据仓库, 用于读取/写入 [PluginSettings] 中的键
 * @param cloudSyncEngine 云端同步引擎, 由插件的入口类构造后注入
 */
class SyncViewModel(
    private val context: Context,
    private val userDataRepository: UserDataRepositoryApi,
    private val cloudSyncEngine: CloudSyncEngine
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(SyncUiState())

    /** 页面观察的同步状态流。 */
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    /**
     * 立即把本机数据同步到云端。
     *
     * 使用 [CloudSyncEngine.syncNow]: 它先构建本机快照, 再列出云端并把最新的一份快照合并进来,
     * 最后上传合并结果 (并按 [PluginSettings.KEEP_SNAPSHOTS] 清理旧快照)。因此这个按钮不会用
     * 本机的旧数据覆盖另一台设备上传的进度。
     * 上传成功后引擎会自行更新 [PluginSettings.LAST_UPLOAD_TIME] 与 [PluginSettings.LAST_SYNC_STATUS]。
     * 引擎在只采集到部分数据时同样会返回成功, 因此这里紧接着读取
     * [CloudSyncEngine.lastBuildWarnings], 把"本次备份不完整"的情况明确告诉用户。
     * 同一时刻只允许一个云端操作, 重复点击会被忽略。
     */
    fun upload() {
        launchOperation("正在同步到云端…") {
            var status = "上传失败：没有返回结果"
            cloudSyncEngine.syncNow()
                .onOk { report ->
                    // 必须立刻读取: 下一次 buildLocalSnapshot() 会覆盖这份警告列表。
                    val warnings = cloudSyncEngine.lastBuildWarnings
                    _uiState.update { it.copy(lastUploadTime = System.currentTimeMillis()) }
                    status = describe(report, warnings)
                }
                .onErr { error ->
                    status = describe(error)
                }
            status
        }
    }

    /**
     * 从云端恢复最新的一份备份。
     *
     * [CloudSyncEngine.listSnapshots] 按时间倒序返回快照, 因此取第一条即最新备份;
     * 恢复时使用 `overwrite = false`, 只做合并, 不会删除本机已有的书架。
     * [PluginSettings.LAST_RESTORE_TIME] 与 [PluginSettings.LAST_SYNC_STATUS] 由引擎自行写入。
     */
    fun restoreLatest() {
        launchOperation("正在从云端恢复…") {
            var status = "恢复失败：没有返回结果"
            cloudSyncEngine.listSnapshots()
                .onOk { snapshots ->
                    _uiState.update { it.copy(remoteSnapshotCount = snapshots.size) }
                    val newest = snapshots.firstOrNull()
                    if (newest == null) {
                        status = "云端还没有可恢复的备份"
                        return@onOk
                    }
                    cloudSyncEngine.download(newest.name)
                        .onOk { snapshot ->
                            cloudSyncEngine.restore(
                                snapshot = snapshot,
                                overwrite = false,
                                sourceName = newest.name
                            )
                                .onOk { report ->
                                    _uiState.update {
                                        it.copy(lastRestoreTime = System.currentTimeMillis())
                                    }
                                    status = "恢复完成：${describe(report)}"
                                }
                                .onErr { error ->
                                    status = describe(error)
                                }
                        }
                        .onErr { error ->
                            status = describe(error)
                        }
                }
                .onErr { error ->
                    status = describe(error)
                }
            status
        }
    }

    /**
     * 测试 WebDAV 配置是否可用。
     *
     * 直接使用 [CloudSyncEngine.testConnection]: 它只发一次列目录请求, 目录不存在也算配置正确,
     * 成功时返回一句可以直接展示的中文说明(其中包含云端已有的快照数量)。
     */
    fun testConnection() {
        launchOperation("正在测试连接…") {
            var status = "连接失败：没有返回结果"
            cloudSyncEngine.testConnection()
                .onOk { message ->
                    status = message
                }
                .onErr { error ->
                    status = describe(error)
                }
            status
        }
    }

    /**
     * 重新读取 [PluginSettings.LAST_SYNC_STATUS]、[PluginSettings.LAST_UPLOAD_TIME] 与
     * [PluginSettings.LAST_RESTORE_TIME], 用于页面首次显示以及手动刷新。
     */
    fun refreshStatus() {
        scope.launch { reloadStatus() }
    }

    /**
     * 取消所有后台任务。
     *
     * 插件被卸载时应当调用, 以免协程在宿主的类加载器失效后继续运行; 取消之后本对象不应再被使用。
     */
    fun dispose() {
        scope.cancel()
    }

    /**
     * 以互斥的方式执行一次云端操作。
     *
     * @param label 操作进行中的提示文字
     * @param block 真正执行的操作, 返回本次操作的结果描述
     */
    private fun launchOperation(label: String, block: suspend () -> String) {
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
            try {
                reloadStatus()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                // 刷新状态失败不影响本次操作的结果, 界面仍然显示刚才的 message。
            }
            _uiState.update { it.copy(running = false, runningLabel = "", message = status) }
            showToast(status)
        }
    }

    /** 从用户数据中读取同步状态与时间戳; 读取失败或未设置时保留当前值。 */
    private suspend fun reloadStatus() {
        val status = userDataRepository.stringUserData(PluginSettings.LAST_SYNC_STATUS).get().orEmpty()
        val lastUpload =
            userDataRepository.stringUserData(PluginSettings.LAST_UPLOAD_TIME).get()?.toLongOrNull()
        val lastRestore =
            userDataRepository.stringUserData(PluginSettings.LAST_RESTORE_TIME).get()?.toLongOrNull()
        _uiState.update {
            it.copy(
                lastSyncStatus = status,
                lastUploadTime = lastUpload ?: it.lastUploadTime,
                lastRestoreTime = lastRestore ?: it.lastRestoreTime
            )
        }
    }

    /** 在主线程弹出一次简短提示; 操作在 IO 线程结束, 因此这里必须切回主线程。 */
    private fun showToast(message: String) {
        if (message.isBlank()) return
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/** 把云端错误渲染为一行提示文字; [CloudSyncError.message] 本身就是面向用户的中文说明。 */
private fun describe(error: CloudSyncError): String = error.message

/** 把恢复报告渲染为一行提示文字。 */
private fun describe(report: RestoreReport): String =
    "应用 ${report.applied} 项，跳过 ${report.skipped} 项，失败 ${report.failed} 项"

/**
 * 把同步(先合并再上传)报告渲染为一行提示文字。
 *
 * @param warnings 上传前 [CloudSyncEngine.lastBuildWarnings] 的内容; 非空表示本次快照不完整
 */
private fun describe(report: SyncReport, warnings: List<String>): String {
    val total = report.bookshelfCount + report.readingCount + report.userDataCount
    val merged = report.mergedRemoteName?.let { "合并了云端 $it" } ?: "云端暂无可合并的快照"
    val summary = "已上传 $total 项（书架 ${report.bookshelfCount} / 阅读 ${report.readingCount} / " +
        "设置 ${report.userDataCount}），$merged，清理 ${report.prunedSnapshots} 个旧快照"
    if (warnings.isEmpty()) return summary
    return "$summary，注意：${warnings.size} 项数据未能读取，本次备份不完整"
}

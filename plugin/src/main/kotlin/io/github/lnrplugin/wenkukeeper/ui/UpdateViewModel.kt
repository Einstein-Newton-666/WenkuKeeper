package io.github.lnrplugin.wenkukeeper.ui

import io.github.lnrplugin.wenkukeeper.PluginConstants
import io.github.lnrplugin.wenkukeeper.update.UpdateCheckResult
import io.github.lnrplugin.wenkukeeper.update.UpdateChecker
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
 * 插件自身更新检查的状态。
 *
 * @property checking 是否正在检查
 * @property message 展示给用户的一句话结论
 * @property latestTag 远端最新 tag；无新版或未成功时为可空/空串
 * @property latestUrl 有新版本时的跳转地址（GitHub Release 页面），否则为 null
 */
data class UpdateUiState(
    val checking: Boolean = false,
    val message: String = "尚未检查",
    val latestTag: String = "",
    val latestUrl: String? = null
)

/**
 * 更新检查的状态持有者。
 *
 * 与其它 ViewModel 一样不继承 `androidx.lifecycle.ViewModel`：宿主不向插件提供
 * `ViewModelStore`，状态由 [MutableStateFlow] 持有，任务跑在自带的协程作用域上。
 *
 * 检查**没有自动轮询**：只在插件页面打开时跑一次（[checkOnce]），其余靠用户点「检查更新」。
 * 一个公开的 GitHub API 不值得按小时轮询，用户也不会因为晚几小时看到提示而受影响。
 *
 * @param checker 检查器；默认读 [PluginConstants.RELEASE_REPOSITORY]
 */
class UpdateViewModel(
    private val checker: UpdateChecker = UpdateChecker()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(UpdateUiState())

    /** 页面观察的状态流。 */
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    /** 是否已经查过一次（成功或失败都算），用于让页面打开时的自动检查只跑一次。 */
    private var checked = false

    /**
     * 页面打开时调用：只在本次进程内还没查过、且当前没有检查在跑时才真的发起请求。
     */
    fun checkOnce() {
        if (checked || _uiState.value.checking) return
        check()
    }

    /**
     * 手动检查更新。
     *
     * 已经有一次检查在跑时直接忽略，避免连点产生多个请求。
     */
    fun check() {
        if (_uiState.value.checking) return
        _uiState.update { it.copy(checking = true, message = "正在检查…") }
        scope.launch {
            val result = checker.check()
            checked = true
            _uiState.update { state ->
                when (result) {
                    is UpdateCheckResult.Available -> state.copy(
                        checking = false,
                        message = "发现新版本 ${result.tag}（当前 ${PluginConstants.PLUGIN_VERSION_NAME}）",
                        latestTag = result.tag,
                        latestUrl = result.url
                    )

                    is UpdateCheckResult.UpToDate -> state.copy(
                        checking = false,
                        message = "已是最新版本（${result.tag}）",
                        latestTag = "",
                        latestUrl = null
                    )

                    // 仓库还没发过 Release：说清"不是出错"，否则会被当成插件故障。
                    UpdateCheckResult.NoRelease -> state.copy(
                        checking = false,
                        message = "仓库还没有发布 Release，当前 ${PluginConstants.PLUGIN_VERSION_NAME} 即最新",
                        latestTag = "",
                        latestUrl = null
                    )

                    is UpdateCheckResult.Failed -> state.copy(
                        checking = false,
                        message = "检查失败：${result.message}",
                        latestTag = "",
                        latestUrl = null
                    )
                }
            }
        }
    }

    /** 释放协程作用域。 */
    fun dispose() {
        scope.cancel()
    }
}

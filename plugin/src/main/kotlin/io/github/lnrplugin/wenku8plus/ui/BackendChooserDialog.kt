package io.github.lnrplugin.wenku8plus.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.lnrplugin.wenku8plus.PluginSettings

/**
 * 云端存储后端选择对话框。
 *
 * 宿主提供的 `SettingsMenuEntry` 需要 [io.nightfish.lightnovelreader.api.settings.SettingsMenuOption]，
 * 而它的 `nameId` 是**宿主**的字符串资源 ID —— 插件没有资源加载通道（宿主不会加载插件的资源文件），
 * 传入插件自己的资源 ID 会在渲染时抛 `ResourceNotFound`。所以这里用宿主 API 里已有的 Material3
 * 组件自己画一个等价的对话框，只依赖 `material3`，不碰宿主的资源。
 *
 * @param current 当前生效的后端 id，用于在对话框里标出已选项
 * @param onSelect 用户选中某个后端 id 后的回调
 * @param onDismissRequest 关闭对话框
 */
@Composable
fun BackendChooserDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val webDavSelected = !current.trim().equals(PluginSettings.BACKEND_GITHUB, ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("存储后端") },
        text = {
            Text(
                "两种后端共用同一套快照格式、合并策略与保留数量策略，只是远端不同。" +
                        "切换后端不会迁移已有数据，需要在新后端上重新上传一次。"
            )
        },
        confirmButton = {
            TextButton(onClick = { onSelect(PluginSettings.BACKEND_WEBDAV) }) {
                Text(if (webDavSelected) "✓ WebDAV" else "WebDAV")
            }
        },
        dismissButton = {
            TextButton(onClick = { onSelect(PluginSettings.BACKEND_GITHUB) }) {
                Text(if (webDavSelected) "GitHub 仓库" else "✓ GitHub 仓库")
            }
        }
    )
}

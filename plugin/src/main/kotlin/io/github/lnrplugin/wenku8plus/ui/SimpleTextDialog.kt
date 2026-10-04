package io.github.lnrplugin.wenku8plus.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 一个简单的单行文本输入对话框。
 *
 * 用于编辑插件设置中的字符串或整数值。对话框自身不写入任何数据: 校验通过后,
 * 由调用方在 [onConfirm] 中决定如何保存。
 *
 * @param title 对话框标题
 * @param initialText 输入框的初始内容
 * @param onDismissRequest 取消或点击对话框外部时的回调
 * @param onConfirm 点击确认按钮时的回调, 参数是输入框中的原始内容
 * @param description 显示在输入框上方的说明文字, 为 null 时不显示
 * @param confirmText 确认按钮文字
 * @param dismissText 取消按钮文字
 * @param clearText 清除按钮文字, 仅在提供了 [onClear] 时显示
 * @param masked 是否以密文显示输入内容, 用于密码等敏感设置
 * @param keyboardType 输入法键盘类型, 例如 [KeyboardType.Number]
 * @param onClear 清除回调, 为 null 时不显示清除按钮
 * @param validate 校验函数, 返回 null 表示内容合法, 否则返回要显示的错误文字
 */
@Suppress("DEPRECATION")
@Composable
fun SimpleTextDialog(
    title: String,
    initialText: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
    description: String? = null,
    confirmText: String = "确定",
    dismissText: String = "取消",
    clearText: String = "清除",
    masked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onClear: (() -> Unit)? = null,
    validate: (String) -> String? = { null }
) {
    var input by remember(initialText) { mutableStateOf(initialText) }
    val error: String? = validate(input)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = title) },
        text = {
            Column {
                if (description != null) {
                    Text(
                        modifier = Modifier.padding(bottom = 8.dp),
                        text = description,
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = if (masked) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
                )
                if (error != null) {
                    Text(
                        modifier = Modifier.padding(top = 8.dp),
                        text = error,
                        color = colorScheme.error,
                        style = typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null,
                onClick = { onConfirm(input) }
            ) {
                Text(text = confirmText)
            }
        },
        dismissButton = {
            Row {
                if (onClear != null) {
                    TextButton(
                        onClick = {
                            onClear()
                            onDismissRequest()
                        }
                    ) {
                        Text(text = clearText)
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = dismissText)
                }
            }
        }
    )
}

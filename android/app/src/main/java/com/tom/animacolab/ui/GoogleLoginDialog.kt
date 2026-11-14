package com.tom.animacolab.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tom.animacolab.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Google 授权码输入弹窗。
 *
 * 为什么需要它：一键模式是 installed-app OAuth，Google 授权页会把 code 显示在页面上，
 * 用户要复制回 App。只读剪贴板的按钮在部分 ROM / 跨设备复制时不可靠，所以这里给一个
 * 可手动长按粘贴的输入框；打开时若剪贴板里已有内容会先填入，仍然可以改。
 */
@Composable
fun GoogleLoginCodeDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // 打开时先尝试读剪贴板：用户通常刚从浏览器复制完。
        val clip = readClipboardForLogin(context)
        if (clip.isNotBlank()) code = clip
        delay(120)
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 与存为模板弹窗一致：低一层 tonal 容器，让 AnimaTextField 的容器可见。
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text("粘贴 Google 授权码") },
        text = {
            Column {
                Text(
                    "在 Google 授权页复制那串授权码，粘贴到下面。支持直接粘贴整段文字或 code= 链接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                AnimaTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        hint = null
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                    label = "授权码",
                    placeholder = "粘贴 Google 页面显示的授权码",
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                )
                Spacer(Modifier.height(Spacing.xs))
                TextButton(
                    onClick = {
                        val clip = readClipboardForLogin(context)
                        if (clip.isBlank()) {
                            hint = "剪贴板是空的，先复制 Google 页面上的授权码"
                        } else {
                            code = clip
                            hint = null
                        }
                    },
                ) {
                    Icon(
                        Icons.Outlined.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("从剪贴板填入")
                }
                val message = hint
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(code.trim()) },
                enabled = code.isNotBlank(),
            ) {
                Text("完成登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun readClipboardForLogin(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = cm.primaryClip ?: return ""
    return buildString {
        for (i in 0 until clip.itemCount) {
            val text = clip.getItemAt(i).coerceToText(context)?.toString().orEmpty()
            if (text.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(text)
            }
        }
    }.trim()
}

package com.tom.animacolab.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.BuildConfig
import com.tom.animacolab.UiState
import com.tom.animacolab.data.AppLinks
import com.tom.animacolab.data.ConnectionState
import com.tom.animacolab.ui.theme.ErrorColor
import com.tom.animacolab.ui.theme.Primary
import com.tom.animacolab.ui.theme.SuccessColor
import com.tom.animacolab.ui.theme.TextPrimary
import com.tom.animacolab.ui.theme.TextSecondary

@Composable
fun SettingsScreen(vm: AppViewModel, state: UiState) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(state.config.baseUrl) }
    var token by remember { mutableStateOf(state.config.token) }

    LaunchedEffect(state.config) {
        url = state.config.baseUrl
        token = state.config.token
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(Modifier.height(10.dp))

        SectionCard(title = "连接 Colab") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务地址") },
                placeholder = { Text("https://xxxx.trycloudflare.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.setConfig(url, token) },
                    modifier = Modifier.weight(1f),
                ) { Text("保存并连接") }
                OutlinedButton(
                    onClick = {
                        val clip = readClipboard(context)
                        if (clip.isBlank()) vm.message("剪贴板是空的，先在 Colab 复制 PAIR 行") else vm.pastePair(clip)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("粘贴配对信息") }
            }
            Row {
                TextButton(onClick = vm::connect) { Text("重新连接") }
                TextButton(onClick = { vm.clearConfig(); url = ""; token = "" }) { Text("清除配对") }
            }
            Spacer(Modifier.height(2.dp))
            val cs = state.connection
            val (label, color) = when (cs) {
                is ConnectionState.Ready -> "已连接：${vm.serverSummary()}" to SuccessColor
                is ConnectionState.Checking -> "正在连接…" to TextSecondary
                is ConnectionState.Error -> cs.message to ErrorColor
                else -> "未配对" to TextSecondary
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(10.dp))
        SectionCard(title = "服务器") {
            val info = state.info
            if (info == null) {
                Text("连接后显示 Colab 上已安装的模型与服务信息", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            } else {
                ServerLine("设备", info.device ?: "T4")
                ServerLine("显存", info.vramTotalGb?.let { "%.1fG".format(java.util.Locale.US, it) } ?: "—")
                ServerLine("队列", "运行 ${state.serverQueue.running} / 排队 ${state.serverQueue.pending}")
                ServerLine("模型", info.unets.joinToString(", ").ifBlank { "无" })
                if (info.loras.isNotEmpty()) ServerLine("LoRA", info.loras.joinToString(", "))
                ServerLine("VAE", info.vaes.joinToString(", ").ifBlank { "—" })
                ServerLine("文本编码器", info.textEncoders.joinToString(", ").ifBlank { "—" })
                ServerLine("采样器", info.samplers.joinToString(", "))
                ServerLine("调度器", info.schedulers.joinToString(", "))
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = vm::connect) { Text("刷新服务器信息") }
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionCard(title = "怎么用（手机独立跑 Colab）") {
            Text(
                "1. 手机浏览器或 Colab App 打开 Anima 笔记本\n" +
                    "2. 点「全部运行」，等它装好 ComfyUI + 下载 base 模型（首次约 3-5 分钟）\n" +
                    "3. 跑完最后会打印一行：PAIR: https://...|token\n" +
                    "4. 长按复制整行，回到本页点「粘贴配对信息」\n" +
                    "5. 生成页写提示词 → 生成；base 1024² 30 步约 70-90 秒",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { openUrl(context, AppLinks.NOTEBOOK_URL) },
                    modifier = Modifier.weight(1f),
                ) { Text("打开笔记本") }
                OutlinedButton(
                    onClick = {
                        copyText(context, AppLinks.NOTEBOOK_URL)
                        vm.message("笔记本链接已复制")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("复制链接") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "注意：免费版 Colab 约 90 分钟不操作会断开，页面末尾的保活 cell 会保持活跃；" +
                    "但会话有总时长上限，且 /content 在会话结束后清空，下次要重新下载模型。" +
                    "每次新会话的 URL 都会变，需要重新粘贴 PAIR。\n" +
                    "Colab 内容政策：只跑正常内容。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                lineHeight = 17.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionCard(title = "关于") {
            ServerLine("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            ServerLine("数据", "配对信息只保存在本机；不收集任何数据")
            Text(
                "Anima 2B（Cosmos-Predict2 架构）+ Qwen3-0.6B 文本编码器 + Qwen-Image VAE，在 Colab T4 上以 fp16 运行。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ServerLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, color = TextPrimary, modifier = Modifier.weight(1f), lineHeight = 16.sp)
    }
}

private fun readClipboard(context: Context): String {
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

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("anima", text))
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.BuildConfig
import com.tom.animacolab.UiState
import com.tom.animacolab.colab.ColabSessionManager
import com.tom.animacolab.data.AppLinks
import com.tom.animacolab.data.ConnectionState
import com.tom.animacolab.ui.theme.ErrorColor
import com.tom.animacolab.ui.theme.Primary
import com.tom.animacolab.ui.theme.SuccessColor
import com.tom.animacolab.ui.theme.TextPrimary
import com.tom.animacolab.ui.theme.TextSecondary
import com.tom.animacolab.ui.theme.WarningColor

@Composable
fun SettingsScreen(vm: AppViewModel, state: UiState) {
    val context = LocalContext.current
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
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

        ColabSessionCard(vm, state, context)

        Spacer(Modifier.height(10.dp))
        SectionCard(title = "通知与保存") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("生成后自动下载图片", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("只存到 App 本地，不写入系统相册；点「保存」才进相册", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Switch(checked = state.autoDownload, onCheckedChange = vm::setAutoDownload)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("后台保活（常驻通知）", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text(
                        "关屏/切后台保持 Colab 会话，每 4 分钟一次心跳",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                Switch(checked = state.backgroundKeepalive, onCheckedChange = vm::setBackgroundKeepalive)
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionCard(title = "手动配对（备用）", subtitle = if (showAdvanced) null else "点右侧展开") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.config.configured) "当前配对：${state.config.baseUrl.take(40)}…" else "当前未配对",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "收起" else "展开") }
            }
            if (showAdvanced) {
                Spacer(Modifier.height(4.dp))
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
                    Button(onClick = { vm.setConfig(url, token) }, modifier = Modifier.weight(1f)) { Text("保存并连接") }
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
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = vm::connect) { Text("刷新服务器信息") }
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionCard(title = "怎么用") {
            Text(
                "【推荐】一键模式\n" +
                    "1. 点上面「登录 Google」完成一次授权\n" +
                    "2. 点「一键启动 Colab T4」，App 自动创建会话、部署 ComfyUI + Anima、自动配对（首次 3-5 分钟）\n" +
                    "3. 回生成页出图；App 每 4 分钟自动保活\n" +
                    "4. 不用时点「停止会话」释放（也可以直接放着，Colab 到时会自动回收）",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "【备用】手动配对\n" +
                    "也可以在手机浏览器打开 Colab 笔记本「全部运行」，复制打印的 PAIR 行，到「手动配对」里粘贴。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openUrl(context, AppLinks.NOTEBOOK_URL) },
                    modifier = Modifier.weight(1f),
                ) { Text("打开备用笔记本") }
                OutlinedButton(
                    onClick = { copyText(context, AppLinks.NOTEBOOK_URL); vm.message("笔记本链接已复制") },
                    modifier = Modifier.weight(1f),
                ) { Text("复制链接") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "注意：\n" +
                    "· App 访问 Google 需要手机能上 Google（开代理/VPN）。\n" +
                    "· 免费 Colab 单会话有时长和额度上限；断开后点「一键启动」会重新申请并部署（/content 清空后要重下模型）。\n" +
                    "· Colab 内容政策：只跑正常内容。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                lineHeight = 17.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionCard(title = "关于") {
            ServerLine("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            ServerLine("数据", "Google 登录令牌与会话信息只保存在本机；不收集任何数据")
            Text(
                "Anima 2B（Cosmos-Predict2 架构）+ Qwen3-0.6B 文本编码器 + Qwen-Image VAE，在 Colab T4 上以 fp16 运行。" +
                    "一键模式的 Colab 协议（OAuth / assignments / Jupyter kernel）移植自 Google 开源的 google-colab-cli。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- Colab 一键会话卡 ----------------

@Composable
private fun ColabSessionCard(vm: AppViewModel, state: UiState, context: Context) {
    var showAllLogs by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = "Colab 一键启动", subtitle = if (state.session is ColabSessionManager.State.Ready) "已就绪" else null) {
        if (!state.googleLoggedIn) {
            Text(
                "登录 Google 后，App 会自动创建 Colab T4、部署 ComfyUI + Anima、自动完成配对，" +
                    "不用再打开 Colab 页面复制 PAIR。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = vm::beginGoogleLogin, modifier = Modifier.fillMaxWidth()) { Text("登录 Google") }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clip = readClipboard(context)
                        if (clip.isBlank()) vm.message("剪贴板里没有内容，先复制 Google 页面上的授权码") else vm.completeGoogleLogin(clip)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("粘贴授权码完成登录") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "登录步骤：点「登录 Google」→ 浏览器里选账号并允许 → Google 页面显示一串授权码 → " +
                    "长按复制 → 回到这里点「粘贴授权码完成登录」。只需授权一次。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                lineHeight = 17.sp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Google 账号", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(1f))
                Text("已登录", style = MaterialTheme.typography.labelSmall, color = SuccessColor)
                TextButton(onClick = vm::logoutGoogle) { Text("退出", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            when (val session = state.session) {
                is ColabSessionManager.State.Starting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
                        Spacer(Modifier.width(8.dp))
                        Text("正在准备 Colab 会话…", style = MaterialTheme.typography.bodySmall, color = TextPrimary, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::cancelColabStart) { Text("取消", color = ErrorColor) }
                    }
                }

                is ColabSessionManager.State.Deploying -> {
                    Text(session.stage, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Primary)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("部署中（首次约 3-5 分钟，可切到后台）", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::cancelColabStart) { Text("取消", color = ErrorColor) }
                    }
                }

                is ColabSessionManager.State.Ready -> {
                    Text(
                        "T4 已就绪 · 已运行 ${elapsedMinutes(session.startedAtMs)} 分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessColor,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::connect, modifier = Modifier.weight(1f)) { Text("重新连接") }
                        OutlinedButton(onClick = vm::stopColabSession, modifier = Modifier.weight(1f)) { Text("停止会话") }
                    }
                }

                is ColabSessionManager.State.Failed -> {
                    Text(session.message, style = MaterialTheme.typography.bodySmall, color = ErrorColor, lineHeight = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::startColabSession, modifier = Modifier.weight(1f)) { Text("重新启动") }
                        OutlinedButton(onClick = vm::beginGoogleLogin, modifier = Modifier.weight(1f)) { Text("重新登录") }
                    }
                }

                else -> {
                    Button(onClick = vm::startColabSession, modifier = Modifier.fillMaxWidth()) { Text("一键启动 Colab T4") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "首次约 3-5 分钟（下载 5.3GB 模型）；同一会话里再启动几秒就能完成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        lineHeight = 16.sp,
                    )
                }
            }

            if (state.deployLogs.isNotEmpty() && (state.session is ColabSessionManager.State.Deploying || state.session is ColabSessionManager.State.Failed)) {
                Spacer(Modifier.height(8.dp))
                val lines = if (showAllLogs) state.deployLogs.takeLast(80) else state.deployLogs.takeLast(6)
                Column {
                    lines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 2,
                        )
                    }
                }
                TextButton(onClick = { showAllLogs = !showAllLogs }) {
                    Text(if (showAllLogs) "收起日志" else "展开部署日志（${state.deployLogs.size} 行）")
                }
            }
        }
    }
}

private fun elapsedMinutes(startedAtMs: Long): Long {
    if (startedAtMs <= 0) return 0
    return ((System.currentTimeMillis() - startedAtMs) / 60000L).coerceAtLeast(0)
}

@Composable
private fun ServerLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(88.dp))
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

package com.tom.animacolab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.UiState
import com.tom.animacolab.data.ConnectionState
import com.tom.animacolab.data.JobState
import com.tom.animacolab.data.PromptBuilder
import com.tom.animacolab.ui.theme.Bg
import com.tom.animacolab.ui.theme.ErrorColor
import com.tom.animacolab.ui.theme.Primary
import com.tom.animacolab.ui.theme.Secondary
import com.tom.animacolab.ui.theme.SuccessColor
import com.tom.animacolab.ui.theme.SurfaceHigh
import com.tom.animacolab.ui.theme.TextPrimary
import com.tom.animacolab.ui.theme.TextSecondary
import com.tom.animacolab.ui.theme.WarningColor
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun GenerateScreen(vm: AppViewModel, state: UiState, onNavigate: (Int) -> Unit) {
    val form = state.form
    val preset = state.currentPreset
    val job = state.job
    val active = job is JobState.Submitting || job is JobState.Queued || job is JobState.Running
    var paramsExpanded by rememberSaveable { mutableStateOf(true) }
    var showSaveTemplate by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(job) {
        while (job is JobState.Submitting || job is JobState.Queued || job is JobState.Running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        HeaderRow(state, vm, onOpenSettings = { onNavigate(3) })
        Spacer(Modifier.height(10.dp))

        if (state.connection is ConnectionState.NotConfigured) {
            SetupCard(onOpenSettings = { onNavigate(3) })
            Spacer(Modifier.height(20.dp))
        } else {
            val error = state.connection as? ConnectionState.Error
            if (error != null) {
                ErrorCard(error.message) { vm.connect() }
                Spacer(Modifier.height(10.dp))
            }
            ModelSelector(state, vm)
            Spacer(Modifier.height(10.dp))
            PromptCard(state, vm, onOpenExplore = { onNavigate(2) }, onSaveTemplate = { showSaveTemplate = true })
            Spacer(Modifier.height(10.dp))
            ParamsCard(state, vm, expanded = paramsExpanded, onToggle = { paramsExpanded = !paramsExpanded }, presetSizes = preset?.sizes ?: emptyList())
            Spacer(Modifier.height(12.dp))
            GenerateButton(state, vm, active)
            Spacer(Modifier.height(10.dp))
            if (active) JobProgressCard(state, vm, now)
            if (job is JobState.Failed) FailedCard(state, vm)
            if (job is JobState.Success) SuccessCard(state, vm, onOpenGallery = { onNavigate(1) })
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSaveTemplate) {
        SaveTemplateDialog(onDismiss = { showSaveTemplate = false }) { name ->
            vm.saveCurrentAsTemplate(name)
            showSaveTemplate = false
        }
    }
}

// ---------------- 头部 / 状态 ----------------

@Composable
private fun HeaderRow(state: UiState, vm: AppViewModel, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Anima Colab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                when (state.connection) {
                    is ConnectionState.Ready -> vm.serverSummary() + " · 队列 ${state.serverQueue.running + state.serverQueue.pending}"
                    is ConnectionState.Checking -> "正在连接 Colab…"
                    is ConnectionState.Error -> "连接异常"
                    else -> "未配对"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        when (state.connection) {
            is ConnectionState.Ready -> {
                StatusPill("已连接", SuccessColor)
                IconButton(onClick = { vm.connect() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = TextSecondary)
                }
            }

            is ConnectionState.Checking -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
            is ConnectionState.Error -> StatusPill("连接失败", ErrorColor)
            else -> StatusPill("未配对", WarningColor)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = TextSecondary)
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    SectionCard(title = "先把 Colab 跑起来") {
        Text(
            "1. 在手机浏览器 / Colab App 打开 Anima 笔记本\n" +
                "2. 点「全部运行」，等它下载模型并打印 PAIR 行（首次约 3-5 分钟）\n" +
                "3. 复制 PAIR 整行，到设置里粘贴并测试连接",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("去粘贴配对信息")
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    SectionCard(title = "连接问题") {
        Text(message, style = MaterialTheme.typography.bodySmall, color = ErrorColor)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun ModelSelector(state: UiState, vm: AppViewModel) {
    val models = state.availableModels
    SectionCard(title = "模型", subtitle = state.info?.let { "已装 ${it.unets.size} 个" }) {
        if (models.isEmpty()) {
            Text(
                if (state.connection is ConnectionState.Ready) "服务器没有可用模型，先在 Colab 部署 base"
                else "连接后自动识别已装模型",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else {
            ChipRow(
                options = models.map { (p, unet) -> p.id to p.display },
                selected = state.form.modelId,
                onSelect = { vm.setModel(it) },
            )
            val desc = state.currentPreset?.desc
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(desc, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

// ---------------- 提示词 ----------------

@Composable
private fun PromptCard(state: UiState, vm: AppViewModel, onOpenExplore: () -> Unit, onSaveTemplate: () -> Unit) {
    val form = state.form
    val preset = state.currentPreset
    SectionCard(title = "提示词") {
        OutlinedTextField(
            value = form.positive,
            onValueChange = vm::setPositive,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("正向提示词") },
            placeholder = { Text("safe, 1girl, solo, long hair, classroom, sunlight…") },
            minLines = 3,
            maxLines = 7,
            shape = RoundedCornerShape(12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动加质量标签", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(1f))
            Switch(checked = form.autoQuality, onCheckedChange = vm::setAutoQuality)
        }
        val quality = preset?.qualityPositive.orEmpty()
        if (form.autoQuality && quality.isNotBlank()) {
            MetaText("生成时前缀：$quality")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = form.negative,
            onValueChange = vm::setNegative,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("负向提示词（可选）") },
            placeholder = { Text("留空则用模型预设负向") },
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动负向提示词", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(1f))
            Switch(checked = form.autoNegative, onCheckedChange = vm::setAutoNegative)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenExplore, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Tag, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Tag / 模板", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onSaveTemplate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("存为模板", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { vm.setPositive(""); vm.setNegative("") }) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ---------------- 参数 ----------------

@Composable
private fun ParamsCard(
    state: UiState,
    vm: AppViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    presetSizes: List<Pair<Int, Int>>,
) {
    val form = state.form
    val info = state.info
    SectionCard {
        Row(Modifier.clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Text("参数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.weight(1f))
            MetaText("${form.steps}步 · CFG ${trimNum(form.cfg)} · ${PromptBuilder.sizeLabel(form.width, form.height)}")
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = TextSecondary,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("步数", style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.width(48.dp))
                Slider(
                    value = form.steps.toFloat(),
                    onValueChange = { vm.setSteps(it.toInt()) },
                    valueRange = 1f..60f,
                    modifier = Modifier.weight(1f),
                )
                Text("${form.steps}", style = MaterialTheme.typography.labelMedium, color = TextPrimary, modifier = Modifier.width(32.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = trimNum(form.cfg),
                    onValueChange = { v -> v.toDoubleOrNull()?.let(vm::setCfg) },
                    label = { Text("CFG") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.width(8.dp))
                BatchField(value = form.batch, onChange = vm::setBatch, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            DropdownField(label = "采样器", value = form.sampler, options = info?.samplers ?: listOf("er_sde", "euler"), onSelect = vm::setSampler)
            Spacer(Modifier.height(6.dp))
            DropdownField(label = "调度器", value = form.scheduler, options = info?.schedulers ?: listOf("simple"), onSelect = vm::setScheduler)
            Spacer(Modifier.height(8.dp))
            SectionHeader("尺寸")
            val sizes = (presetSizes + listOf(1024 to 1024, 832 to 1216, 1216 to 832, 1080 to 2400))
                .distinct()
                .take(8)
            ChipRow(
                options = sizes.map { (w, h) -> "${w}x$h" to PromptBuilder.sizeLabel(w, h) },
                selected = "${form.width}x${form.height}",
                onSelect = { key ->
                    key.split("x").mapNotNull { it.toIntOrNull() }.let { if (it.size == 2) vm.setSize(it[0], it[1]) }
                },
            )
            SizeEditor(form.width, form.height, onApply = { w, h -> vm.setSize(w, h) })
            Spacer(Modifier.height(8.dp))
            SectionHeader("种子")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = form.seedText,
                    onValueChange = vm::setSeedText,
                    label = { Text(if (form.seedLocked) "固定种子" else "随机种子（生成后显示）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                )
                IconButton(onClick = { vm.setSeedText(PromptBuilder.randomSeed().toString()) }) {
                    Icon(Icons.Outlined.Shuffle, contentDescription = "随机", tint = TextSecondary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("固定", style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontSize = 10.sp)
                    Switch(checked = form.seedLocked, onCheckedChange = { vm.toggleSeedLock() })
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!info?.loras.isNullOrEmpty()) {
                DropdownField(
                    label = "LoRA",
                    value = form.lora ?: "（不使用）",
                    options = listOf("（不使用）") + info.loras,
                    onSelect = { vm.setLora(if (it == "（不使用）") null else it) },
                )
            }
        }
    }
}

@Composable
private fun BatchField(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("张数", style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.width(40.dp))
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > 1, contentPadding = ButtonDefaults.ContentPadding) {
            Text("-")
        }
        Text("$value", style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.padding(horizontal = 10.dp))
        OutlinedButton(onClick = { onChange(value + 1) }, enabled = value < 8) {
            Text("+")
        }
    }
}

@Composable
private fun SizeEditor(width: Int, height: Int, onApply: (Int, Int) -> Unit) {
    var w by remember(width) { mutableStateOf(width.toString()) }
    var h by remember(height) { mutableStateOf(height.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(
            value = w,
            onValueChange = { w = it.filter { c -> c.isDigit() } },
            label = { Text("宽") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = h,
            onValueChange = { h = it.filter { c -> c.isDigit() } },
            label = { Text("高") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = {
            val wi = w.toIntOrNull() ?: return@OutlinedButton
            val hi = h.toIntOrNull() ?: return@OutlinedButton
            onApply(wi.coerceIn(256, 2048), hi.coerceIn(256, 2048))
        }) { Text("应用") }
    }
}

// ---------------- 生成 / 进度 ----------------

@Composable
private fun GenerateButton(state: UiState, vm: AppViewModel, active: Boolean) {
    val ready = state.connection is ConnectionState.Ready
    Button(
        onClick = vm::generate,
        enabled = ready && !active,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
    ) {
        if (active) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("生成中…")
        } else {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (ready) "生成" else "连接后生成", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun JobProgressCard(state: UiState, vm: AppViewModel, now: Long) {
    val job = state.job
    val startedAt = when (job) {
        is JobState.Submitting -> job.startedAt
        is JobState.Queued -> job.startedAt
        is JobState.Running -> job.startedAt
        else -> now
    }
    val elapsed = ((now - startedAt) / 1000).coerceAtLeast(0)
    SectionCard(title = "正在生成") {
        when (job) {
            is JobState.Submitting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
                Spacer(Modifier.width(8.dp))
                Text("提交任务中…", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }

            is JobState.Queued -> {
                Text("已排队，等待 Colab 开始…  （${elapsed}s）", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Primary, trackColor = Bg)
            }

            is JobState.Running -> {
                val max = job.max.coerceAtLeast(1)
                val fraction = (job.step.toFloat() / max).coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${job.node ?: "采样"}  ${job.step}/$max", style = MaterialTheme.typography.bodySmall, color = TextPrimary, modifier = Modifier.weight(1f))
                    MetaText("${elapsed}s")
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = Primary, trackColor = Bg)
            }

            else -> {}
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = vm::cancelJob) {
                Text("中断", color = ErrorColor)
            }
        }
    }
}

@Composable
private fun FailedCard(state: UiState, vm: AppViewModel) {
    val job = state.job as JobState.Failed
    SectionCard(title = "生成失败") {
        Text(job.message, style = MaterialTheme.typography.bodySmall, color = ErrorColor)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = vm::dismissJob) { Text("关闭") }
            Button(onClick = { vm.dismissJob(); vm.generate() }) { Text("重试") }
        }
    }
}

@Composable
private fun SuccessCard(state: UiState, vm: AppViewModel, onOpenGallery: () -> Unit) {
    val job = state.job as JobState.Success
    val item = job.item
    val image = item.firstImage ?: return
    val p = item.params
    val ratio = if (p != null && p.width > 0 && p.height > 0) p.width.toFloat() / p.height else 1f
    SectionCard(title = "生成完成", subtitle = "耗时 ${job.elapsedMs / 1000}s · 种子 ${p?.seed ?: ""}") {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
                .clip(RoundedCornerShape(12.dp))
                .clickable { vm.openViewer(item, image) },
        ) {
            NetworkImage(vm, image, Modifier.fillMaxSize(), targetPx = 1024)
        }
        Spacer(Modifier.height(8.dp))
        MetaText(
            listOfNotNull(
                p?.steps?.takeIf { it > 0 }?.let { "${it}步" },
                p?.cfg?.takeIf { it > 0 }?.let { "CFG ${trimNum(it)}" },
                p?.sampler?.takeIf { it.isNotBlank() },
                "${p?.width ?: 0}×${p?.height ?: 0}",
            ).joinToString(" · "),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.saveImage(item, image) }, modifier = Modifier.weight(1f)) { Text("保存", fontSize = 12.sp) }
            OutlinedButton(onClick = { vm.shareImage(item, image) }, modifier = Modifier.weight(1f)) { Text("分享", fontSize = 12.sp) }
            OutlinedButton(onClick = { vm.reuseParams(item) }, modifier = Modifier.weight(1f)) { Text("填回参数", fontSize = 12.sp) }
            OutlinedButton(onClick = onOpenGallery) { Text("图库", fontSize = 12.sp) }
        }
    }
}

// ---------------- 小部件 / 弹窗 ----------------

@Composable
fun DropdownField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val items = if (value in options || options.isEmpty()) options else listOf(value) + options
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
            Text("$label：$value", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { option ->
                DropdownMenuItem(text = { Text(option, fontSize = 13.sp) }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
        }
    }
}

@Composable
private fun SaveTemplateDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存为模板") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("模板名称") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

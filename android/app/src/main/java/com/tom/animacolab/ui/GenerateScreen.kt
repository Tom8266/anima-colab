package com.tom.animacolab.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.UiState
import com.tom.animacolab.colab.ColabSessionManager
import com.tom.animacolab.data.ConnectionState
import com.tom.animacolab.data.JobState
import com.tom.animacolab.data.PromptBuilder
import com.tom.animacolab.ui.theme.AnimaMotion
import com.tom.animacolab.ui.theme.AnimaShape
import com.tom.animacolab.ui.theme.Spacing
import com.tom.animacolab.ui.theme.StatusTone
import com.tom.animacolab.ui.theme.extended
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 生成页（创作画布）。
 *
 * 结构：ScreenHeader（Anima + 连接状态）→ 未配对时 Hero 卡 → 模型 → 提示词（核心）→
 * 参数（可折叠）→ 生成 SplitButton → 进度 / 失败 / 成功卡。
 */
@Composable
fun GenerateScreen(vm: AppViewModel, state: UiState, onNavigate: (Int) -> Unit) {
    val job = state.job
    val active = job is JobState.Submitting || job is JobState.Queued || job is JobState.Running
    val presetSizes = state.currentPreset?.sizes ?: emptyList()
    var paramsExpanded by rememberSaveable { mutableStateOf(true) }
    var showSaveTemplate by rememberSaveable { mutableStateOf(false) }
    var confirmInterrupt by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    // 常驻条只在「进度卡完全滚出视口」时出现：记录卡片在滚动内容里的实际位置来判断，
    // 而不是用 atBottom —— 后者在「卡片已可见、只差几像素到底」的过渡位置会同时出现两层进度。
    // 位置只在布局变化时写入状态，滚动本身不触发重组。
    val activeState = rememberUpdatedState(active)
    val viewportPx = remember { mutableIntStateOf(0) }
    val containerTopPx = remember { mutableIntStateOf(0) }
    val cardTopPx = remember { mutableIntStateOf(Int.MAX_VALUE) }
    val cardScrollAtPx = remember { mutableIntStateOf(0) }
    val cardHeightPx = remember { mutableIntStateOf(0) }
    val showSticky by remember {
        derivedStateOf {
            // 卡片上边缘在当前滚动位置下相对视口顶部的距离：
            // 记录的是「窗口坐标 + 当时的 scroll」，所以滚动本身不会让数值漂移。
            val top = cardTopPx.intValue.toLong() - containerTopPx.intValue +
                cardScrollAtPx.intValue - scrollState.value
            shouldShowStickyProgress(
                active = activeState.value,
                cardMeasured = cardTopPx.intValue != Int.MAX_VALUE,
                cardTopInViewportPx = top,
                cardHeightPx = cardHeightPx.intValue,
                viewportHeightPx = viewportPx.intValue,
            )
        }
    }

    // 任务结束时收起重置确认，避免「完成瞬间点中断」误清成功态
    LaunchedEffect(active) {
        if (!active) confirmInterrupt = false
    }
    // 成功后自动定位到结果卡
    LaunchedEffect(job) {
        if (job is JobState.Success) {
            delay(420)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // 这两个必须放在 verticalScroll 之前：之后测到的是「滚动内容」而不是视口。
                .onSizeChanged { viewportPx.intValue = it.height }
                .onGloballyPositioned { containerTopPx.intValue = it.positionInWindow().y.roundToInt() }
                .verticalScroll(scrollState),
        ) {
            GenerateHeader(state, vm)
            Column(Modifier.padding(horizontal = Spacing.screen)) {
                if (state.connection is ConnectionState.NotConfigured) {
                    OneTapHero(state, vm, context = context, onOpenSettings = { onNavigate(3) })
                    Spacer(Modifier.height(Spacing.xxl))
                } else {
                    val error = state.connection as? ConnectionState.Error
                    if (error != null) {
                        ConnectionErrorCard(error.message, state, vm, onOpenSettings = { onNavigate(3) })
                        Spacer(Modifier.height(Spacing.lg))
                    }
                    ModelCard(state, vm)
                    Spacer(Modifier.height(Spacing.lg))
                    PromptCard(
                        state = state,
                        vm = vm,
                        onOpenExplore = { onNavigate(2) },
                        onSaveTemplate = { showSaveTemplate = true },
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    ParamsCard(
                        state = state,
                        vm = vm,
                        expanded = paramsExpanded,
                        onToggle = { paramsExpanded = !paramsExpanded },
                        presetSizes = presetSizes,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    GenerateAction(
                        state = state,
                        vm = vm,
                        active = active,
                        onSaveTemplate = { showSaveTemplate = true },
                        onClearPrompt = { confirmClear = true },
                    )
                    if (active) {
                        Spacer(Modifier.height(Spacing.lg))
                        Box(
                            Modifier
                                .onGloballyPositioned {
                                    cardTopPx.intValue = it.positionInWindow().y.roundToInt()
                                    cardScrollAtPx.intValue = scrollState.value
                                }
                                .onSizeChanged { cardHeightPx.intValue = it.height },
                        ) {
                            JobProgressCard(state, vm, onRequestInterrupt = { confirmInterrupt = true })
                        }
                    }
                    // 结果卡出现时用弹簧展开，避免生硬跳变
                    AnimatedVisibility(
                        visible = job is JobState.Failed,
                        enter = fadeIn(animationSpec = AnimaMotion.effects) +
                            expandVertically(animationSpec = SizeSpring),
                        exit = fadeOut(animationSpec = AnimaMotion.effects) +
                            shrinkVertically(animationSpec = SizeSpring),
                    ) {
                        Column {
                            Spacer(Modifier.height(Spacing.lg))
                            FailedCard(state, vm)
                        }
                    }
                    AnimatedVisibility(
                        visible = job is JobState.Success,
                        enter = fadeIn(animationSpec = AnimaMotion.effects) +
                            expandVertically(animationSpec = SizeSpring),
                        exit = fadeOut(animationSpec = AnimaMotion.effects) +
                            shrinkVertically(animationSpec = SizeSpring),
                    ) {
                        Column {
                            Spacer(Modifier.height(Spacing.lg))
                            SuccessCard(
                                state = state,
                                vm = vm,
                                onOpenGallery = { onNavigate(1) },
                                onParamsReused = { paramsExpanded = true },
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.xxl))
                }
            }
            // 常驻条可见时留出滚动余量，避免遮住内容
            if (showSticky) Spacer(Modifier.height(Spacing.xxxl + Spacing.xxl + Spacing.xxl))
        }

        // 常驻 slim 进度条：运行中无论滚到哪都能看到进度并中断
        AnimatedVisibility(
            visible = showSticky,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Spacing.sm),
            enter = fadeIn(animationSpec = AnimaMotion.effects) +
                slideInVertically(animationSpec = SlideSpring) { it },
            exit = fadeOut(animationSpec = AnimaMotion.effects) +
                slideOutVertically(animationSpec = SlideSpring) { it },
        ) {
            StickyJobBar(state, onRequestInterrupt = { confirmInterrupt = true })
        }
    }

    if (showSaveTemplate) {
        SaveTemplateDialog(onDismiss = { showSaveTemplate = false }) { name ->
            vm.saveCurrentAsTemplate(name)
            showSaveTemplate = false
        }
    }
    if (confirmInterrupt) {
        AlertDialog(
            onDismissRequest = { confirmInterrupt = false },
            title = { Text("中断生成？") },
            text = { Text("当前任务会被取消，已经生成的部分不会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmInterrupt = false
                        vm.cancelJob()
                    },
                ) {
                    Text("中断", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmInterrupt = false }) { Text("继续等待") }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空提示词？") },
            text = { Text("正向与负向提示词都会被清空，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        vm.setPositive("")
                        vm.setNegative("")
                    },
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

// ===========================================================================
// 头部 / 状态
// ===========================================================================

private data class HeaderStatus(val label: String, val tone: StatusTone, val subtitle: String)

@Composable
private fun GenerateHeader(state: UiState, vm: AppViewModel) {
    val status = headerStatus(state, vm)
    ScreenHeader(
        title = "Anima",
        subtitle = status.subtitle,
        leading = { AnimaMark() },
        actions = {
            AnimatedContent(
                targetState = status.label to status.tone,
                transitionSpec = {
                    fadeIn(animationSpec = AnimaMotion.effects) togetherWith
                        fadeOut(animationSpec = AnimaMotion.effects)
                },
                label = "connectionStatus",
            ) { (label, tone) ->
                StatusPill(text = label, tone = tone)
            }
            IconButton(onClick = { vm.connect() }) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新连接",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** 状态行优先级：生成任务 > 部署 stage > 连接状态。 */
private fun headerStatus(state: UiState, vm: AppViewModel): HeaderStatus {
    val session = state.session
    val connection = state.connection
    val base = when {
        session is ColabSessionManager.State.Deploying ->
            HeaderStatus("部署中", StatusTone.Warning, "部署中：" + session.stage)

        session is ColabSessionManager.State.Starting ->
            HeaderStatus("准备中", StatusTone.Warning, "正在准备 Colab 会话…")

        session is ColabSessionManager.State.Failed ->
            HeaderStatus("启动失败", StatusTone.Error, "Colab 部署失败，可在下方重试")

        session is ColabSessionManager.State.Ready && connection is ConnectionState.NotConfigured ->
            HeaderStatus("配对中", StatusTone.Warning, "Colab 会话已就绪，正在完成配对…")

        connection is ConnectionState.Ready ->
            HeaderStatus(
                label = "已连接",
                tone = StatusTone.Success,
                subtitle = vm.serverSummary() + " · 队列 " + (state.serverQueue.running + state.serverQueue.pending),
            )

        connection is ConnectionState.Checking ->
            HeaderStatus("连接中", StatusTone.Warning, "正在连接 Colab…")

        connection is ConnectionState.Error ->
            HeaderStatus("连接异常", StatusTone.Error, "连接异常，可刷新重试")

        else -> HeaderStatus("未配对", StatusTone.Neutral, "未配对 · 登录 Google 即可一键启动")
    }
    // 生成任务状态优先于连接状态显示在副标题（pill 保持反映连接/部署）
    val jobSubtitle = when (val job = state.job) {
        is JobState.Submitting -> "提交任务中"
        is JobState.Queued -> "排队中"
        is JobState.Running ->
            "生成中 " + ((job.step.toFloat() / job.max.coerceAtLeast(1) * 100).roundToInt()) + "%"

        is JobState.Success -> "生成完成 · ${job.elapsedMs / 1000}s"
        is JobState.Failed -> "生成失败"
        else -> null
    }
    return if (jobSubtitle != null) base.copy(subtitle = jobSubtitle) else base
}

/** 左上角的 Anima 标记：主色容器圆底 + AutoAwesome。 */
@Composable
private fun AnimaMark() {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = AnimaShape.full,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ===========================================================================
// 未配对：一键 Colab Hero 卡
// ===========================================================================

@Composable
private fun OneTapHero(
    state: UiState,
    vm: AppViewModel,
    context: Context,
    onOpenSettings: () -> Unit,
) {
    val session = state.session
    ExpressiveCard(
        modifier = Modifier.fillMaxWidth(),
        shape = AnimaShape.hero,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            // 装饰形状只做背景层，alpha ≤ 0.2
            ShapeDecoration(
                Modifier
                    .size(150.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 48.dp, y = (-52).dp),
                tint = MaterialTheme.colorScheme.primary,
                shape = AnimaShape.softBurst,
                rotation = 12f,
                alpha = 0.18f,
            )
            ShapeDecoration(
                Modifier
                    .size(56.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = (-18).dp, y = 88.dp),
                tint = MaterialTheme.colorScheme.secondary,
                shape = AnimaShape.sunny,
                rotation = -16f,
                alpha = 0.18f,
            )
            Column(Modifier.padding(Spacing.card)) {
                HeroEyebrow()
                Spacer(Modifier.height(Spacing.lg))
                when (session) {
                    is ColabSessionManager.State.Starting -> HeroBusyContent(
                        stage = null,
                        logs = state.deployLogs,
                        onCancel = vm::cancelColabStart,
                    )

                    is ColabSessionManager.State.Deploying -> HeroBusyContent(
                        stage = session.stage,
                        logs = state.deployLogs,
                        onCancel = vm::cancelColabStart,
                    )

                    is ColabSessionManager.State.Failed -> HeroFailedContent(
                        message = session.message,
                        onRetry = vm::startColabSession,
                        onOpenSettings = onOpenSettings,
                    )

                    is ColabSessionManager.State.Ready -> HeroPairingContent(onOpenSettings)

                    else -> if (state.googleLoggedIn) {
                        HeroStartContent(vm::startColabSession, onOpenSettings)
                    } else {
                        HeroLoginContent(vm, context, onOpenSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroEyebrow() {
    Surface(
        shape = AnimaShape.pill,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(
                "Colab T4 · ComfyUI",
                style = MaterialTheme.typography.labelMediumEmphasized,
            )
        }
    }
}

@Composable
private fun HeroLoginContent(vm: AppViewModel, context: Context, onOpenSettings: () -> Unit) {
    HeroTitle("连上云端画布")
    Spacer(Modifier.height(Spacing.sm))
    HeroBody("登录 Google 后，一键创建 Colab T4 会话：自动部署 ComfyUI + Anima 并完成配对。")
    Spacer(Modifier.height(Spacing.lg))
    PrimaryActionButton(
        text = "登录 Google",
        onClick = vm::beginGoogleLogin,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(
        onClick = {
            val clip = readClipboard(context)
            if (clip.isBlank()) vm.message("剪贴板里没有授权码") else vm.completeGoogleLogin(clip)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("已复制授权码？点这里完成登录") }
    TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text("手动配对（备用）")
    }
}

@Composable
private fun HeroStartContent(onStart: () -> Unit, onOpenSettings: () -> Unit) {
    HeroTitle("开始云端创作")
    Spacer(Modifier.height(Spacing.sm))
    HeroBody("点一下自动创建 Colab T4、部署 ComfyUI + Anima、完成配对（首次约 3-5 分钟）。")
    Spacer(Modifier.height(Spacing.lg))
    PrimaryActionButton(
        text = "一键启动 Colab T4",
        onClick = onStart,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text("手动配对（备用）")
    }
}

@Composable
private fun HeroBusyContent(stage: String?, logs: List<String>, onCancel: () -> Unit) {
    HeroTitle(if (stage == null) "正在准备会话" else "部署中")
    Spacer(Modifier.height(Spacing.sm))
    HeroBody(stage ?: "正在连接 Google Colab，请稍候…")
    Spacer(Modifier.height(Spacing.lg))
    LinearWavyProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f),
    )
    Spacer(Modifier.height(Spacing.md))
    Text(
        "首次约 3-5 分钟，App 会自动完成部署和配对；可以先切到后台。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
    )
    val tail = logs.takeLast(3)
    if (tail.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            tail.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(Spacing.sm))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) {
            Text("取消启动", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun HeroFailedContent(message: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    HeroTitle("启动失败")
    Spacer(Modifier.height(Spacing.sm))
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(Spacing.lg))
    PrimaryActionButton(
        text = "重新启动",
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text("去设置")
    }
}

@Composable
private fun HeroPairingContent(onOpenSettings: () -> Unit) {
    HeroTitle("会话已就绪")
    Spacer(Modifier.height(Spacing.sm))
    HeroBody("正在完成配对，马上就可以开始创作…")
    Spacer(Modifier.height(Spacing.lg))
    LinearWavyProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f),
    )
    Spacer(Modifier.height(Spacing.md))
    TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text("手动配对（备用）")
    }
}

@Composable
private fun HeroTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmallEmphasized,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun HeroBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
    )
}

// ===========================================================================
// 已配对：模型
// ===========================================================================

@Composable
private fun ModelCard(state: UiState, vm: AppViewModel) {
    val models = state.availableModels
    SectionLabel(
        "模型",
        trailing = {
            if (models.isNotEmpty()) {
                // 中文 + 数字不要走等宽 MetaText：等宽字体给中文/空格加宽，会显得字距散。
                Text(
                    "已装 ${models.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    ExpressiveCard(modifier = Modifier.fillMaxWidth()) {
        if (models.isEmpty()) {
            Text(
                if (state.connection is ConnectionState.Ready) {
                    "服务器没有可用模型，先在 Colab 部署 base"
                } else {
                    "连接后自动识别已装模型"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ChoiceChips(
                options = models.map { (preset, _) -> ChipOption(preset.id, preset.display) },
                selectedValue = state.form.modelId,
                onSelect = vm::setModel,
            )
            val desc = state.currentPreset?.desc
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.md))
                TagLine(desc)
            }
        }
    }
}

// ===========================================================================
// 提示词（核心卡片）
// ===========================================================================

@Composable
private fun PromptCard(
    state: UiState,
    vm: AppViewModel,
    onOpenExplore: () -> Unit,
    onSaveTemplate: () -> Unit,
) {
    val form = state.form
    val quality = state.currentPreset?.qualityPositive.orEmpty()
    var negativeExpanded by rememberSaveable { mutableStateOf(false) }

    ExpressiveCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "提示词",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            CountBadge("${form.positive.length} 字")
        }
        Spacer(Modifier.height(Spacing.md))
        AnimaTextField(
            value = form.positive,
            onValueChange = vm::setPositive,
            label = "正向提示词",
            placeholder = "safe, 1girl, solo, long hair, classroom, sunlight…",
            minLines = 4,
            maxLines = 8,
        )
        Spacer(Modifier.height(Spacing.xs))
        SettingSwitchRow(
            title = "自动加质量标签",
            checked = form.autoQuality,
            onCheckedChange = vm::setAutoQuality,
            description = "按模型追加质量前缀，提升画面细节",
        )
        AnimatedVisibility(
            visible = form.autoQuality && quality.isNotBlank(),
            enter = fadeIn(animationSpec = AnimaMotion.effects) +
                expandVertically(animationSpec = SizeSpring),
            exit = fadeOut(animationSpec = AnimaMotion.effects) +
                shrinkVertically(animationSpec = SizeSpring),
        ) {
            MetaText(
                "生成时前缀：$quality",
                Modifier.padding(start = Spacing.sm, bottom = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Column(Modifier.animateContentSize(animationSpec = SizeSpring)) {
            Surface(
                onClick = { negativeExpanded = !negativeExpanded },
                modifier = Modifier.fillMaxWidth(),
                shape = AnimaShape.cardSmall,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "负向提示词",
                            style = MaterialTheme.typography.bodyLargeEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            when {
                                negativeExpanded -> "点击收起"
                                form.negative.isBlank() -> "留空则使用模型预设负向"
                                else -> "${form.negative.length} 字 · 点击展开"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AnimatedChevron(expanded = negativeExpanded)
                }
            }
            if (negativeExpanded) {
                Spacer(Modifier.height(Spacing.sm))
                AnimaTextField(
                    value = form.negative,
                    onValueChange = vm::setNegative,
                    placeholder = "留空则用模型预设负向",
                    minLines = 3,
                    maxLines = 6,
                )
                SettingSwitchRow(
                    title = "自动负向提示词",
                    checked = form.autoNegative,
                    onCheckedChange = vm::setAutoNegative,
                    description = "留空时自动使用模型预设负向",
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PromptAction(
                icon = Icons.Outlined.Tag,
                label = "Tag/模板",
                onClick = onOpenExplore,
                modifier = Modifier.weight(1f),
            )
            PromptAction(
                icon = Icons.Outlined.BookmarkAdd,
                label = "存为模板",
                onClick = onSaveTemplate,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CountBadge(text: String) {
    Surface(
        shape = AnimaShape.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs),
        )
    }
}

@Composable
private fun PromptAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = AnimaShape.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = Spacing.sm),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ===========================================================================
// 参数（可折叠）
// ===========================================================================

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
    ExpressiveCard(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = AnimaShape.cardSmall,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "参数",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                MetaText(
                    "${form.steps}步 · CFG ${trimNum(form.cfg)} · ${PromptBuilder.sizeLabel(form.width, form.height)}",
                )
                Spacer(Modifier.width(Spacing.sm))
                AnimatedChevron(expanded = expanded)
            }
        }
        Column(Modifier.animateContentSize(animationSpec = SizeSpring)) {
            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                AnimaSliderRow(
                    label = "步数",
                    valueText = "${form.steps} 步",
                    value = form.steps.toFloat(),
                    onValueChange = { vm.setSteps(it.roundToInt()) },
                    valueRange = 1f..60f,
                )
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Column(Modifier.weight(1f)) {
                        ParamLabel("CFG")
                        Spacer(Modifier.height(Spacing.xxs))
                        CfgField(cfg = form.cfg, onCfg = vm::setCfg, modifier = Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f)) {
                        ParamLabel("张数")
                        Spacer(Modifier.height(Spacing.xxs))
                        BatchStepper(
                            value = form.batch,
                            onChange = vm::setBatch,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    DropdownField(
                        label = "采样器",
                        value = form.sampler,
                        options = info?.samplers ?: listOf("er_sde", "euler"),
                        onSelect = vm::setSampler,
                        modifier = Modifier.weight(1f),
                    )
                    DropdownField(
                        label = "调度器",
                        value = form.scheduler,
                        options = info?.schedulers ?: listOf("simple"),
                        onSelect = vm::setScheduler,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(Spacing.lg))
                ParamLabel("尺寸")
                Spacer(Modifier.height(Spacing.sm))
                val sizes = (presetSizes + listOf(1024 to 1024, 832 to 1216, 1216 to 832, 1080 to 2400))
                    .distinct()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    sizes.take(4).chunked(2).forEach { rowSizes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            rowSizes.forEach { (w, h) ->
                                SizeOptionTile(
                                    label = PromptBuilder.sizeLabel(w, h),
                                    selected = form.width == w && form.height == h,
                                    onClick = { vm.setSize(w, h) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (rowSizes.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (sizes.size > 4) {
                    Spacer(Modifier.height(Spacing.sm))
                    MoreSizesMenu(
                        options = sizes.drop(4),
                        onSelect = { w, h -> vm.setSize(w, h) },
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                SizeEditor(form.width, form.height, onApply = vm::setSize)
                Spacer(Modifier.height(Spacing.lg))
                ParamLabel("种子")
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimaTextField(
                        value = form.seedText,
                        onValueChange = vm::setSeedText,
                        modifier = Modifier.weight(1f),
                        placeholder = "留空则每次随机",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            IconButton(onClick = { vm.setSeedText(PromptBuilder.randomSeed().toString()) }) {
                                Icon(Icons.Outlined.Shuffle, contentDescription = "随机种子")
                            }
                        },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(Spacing.md))
                    ToggleButton(
                        checked = form.seedLocked,
                        onCheckedChange = { vm.toggleSeedLock() },
                        icon = {
                            Icon(
                                if (form.seedLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                            )
                        },
                    ) {
                        Text("固定种子", maxLines = 1)
                    }
                }
                val loras = info?.loras.orEmpty()
                if (loras.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.md))
                    DropdownField(
                        label = "LoRA",
                        value = form.lora ?: "（不使用）",
                        options = listOf("（不使用）") + loras,
                        onSelect = { vm.setLora(if (it == "（不使用）") null else it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ParamLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** CFG 输入：本地草稿 + 外部值同步，允许输入 “4.” 这类中间态。 */
@Composable
private fun CfgField(cfg: Double, onCfg: (Double) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(trimNum(cfg)) }
    LaunchedEffect(cfg) {
        val parsed = text.toDoubleOrNull()
        if (parsed == null || trimNum(parsed) != trimNum(cfg)) text = trimNum(cfg)
    }
    AnimaTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() || it == '.' }
            if (filtered.length <= 5 && filtered.count { it == '.' } <= 1) {
                text = filtered
                filtered.toDoubleOrNull()?.let(onCfg)
            }
        },
        modifier = modifier,
        placeholder = "4.5",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

/** 张数 stepper：− 数值 +，触控目标 48dp。 */
@Composable
private fun BatchStepper(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = AnimaShape.field,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onChange(value - 1) }, enabled = value > 1) {
                Icon(
                    Icons.Outlined.Remove,
                    contentDescription = "减少张数",
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                "$value 张",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(value + 1) }, enabled = value < 8) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "增加张数",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 自定义宽高编辑：应用时 clamp 到 256-2048。 */
@Composable
private fun SizeEditor(width: Int, height: Int, onApply: (Int, Int) -> Unit) {
    var w by remember(width) { mutableStateOf(width.toString()) }
    var h by remember(height) { mutableStateOf(height.toString()) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimaTextField(
            value = w,
            onValueChange = { input -> w = input.filter { it.isDigit() }.take(4) },
            modifier = Modifier.weight(1f),
            label = "宽",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        AnimaTextField(
            value = h,
            onValueChange = { input -> h = input.filter { it.isDigit() }.take(4) },
            modifier = Modifier.weight(1f),
            label = "高",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        SecondaryActionButton(
            text = "应用",
            onClick = {
                val wi = w.toIntOrNull()
                val hi = h.toIntOrNull()
                if (wi != null && hi != null) onApply(wi.coerceIn(256, 2048), hi.coerceIn(256, 2048))
            },
            modifier = Modifier.width(76.dp),
        )
    }
}

// ===========================================================================
// 生成主操作（SplitButton）
// ===========================================================================

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GenerateAction(
    state: UiState,
    vm: AppViewModel,
    active: Boolean,
    onSaveTemplate: () -> Unit,
    onClearPrompt: () -> Unit,
) {
    val ready = state.connection is ConnectionState.Ready
    val focusManager = LocalFocusManager.current
    var menuOpen by remember { mutableStateOf(false) }
    val shapes = SplitButtonDefaults.leadingButtonShapesFor(SplitButtonDefaults.MediumContainerHeight)
    val trailingShapes = SplitButtonDefaults.trailingButtonShapesFor(SplitButtonDefaults.MediumContainerHeight)
    val leadingPadding =
        SplitButtonDefaults.leadingButtonContentPaddingFor(SplitButtonDefaults.MediumContainerHeight)
    val trailingPadding =
        SplitButtonDefaults.trailingButtonContentPaddingFor(SplitButtonDefaults.MediumContainerHeight)
    val generateNow = {
        focusManager.clearFocus()
        vm.generate()
    }

    Box(Modifier.fillMaxWidth()) {
        SplitButtonLayout(
            modifier = Modifier.fillMaxWidth(),
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = generateNow,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ready && !active,
                    shapes = shapes,
                    contentPadding = leadingPadding,
                ) {
                    if (active) {
                        LoadingIndicator(
                            modifier = Modifier.size(22.dp),
                            color = LocalContentColor.current,
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("生成中…")
                    } else {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.MediumIconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(if (ready) "开始生成" else "连接后生成")
                    }
                }
            },
            trailingButton = {
                Box {
                    SplitButtonDefaults.TrailingButton(
                        checked = menuOpen,
                        onCheckedChange = { menuOpen = it },
                        enabled = !active,
                        shapes = trailingShapes,
                        contentPadding = trailingPadding,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "更多生成选项",
                            modifier = Modifier.size(ButtonDefaults.MediumIconSize),
                        )
                    }
                    // 锚在展开按钮上：放在外层 Box 会从整行左端弹出，离触发点很远。
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("随机种子并生成") },
                            leadingIcon = { Icon(Icons.Outlined.Shuffle, contentDescription = null) },
                            enabled = ready && !active,
                            onClick = {
                                menuOpen = false
                                vm.setSeedText(PromptBuilder.randomSeed().toString())
                                generateNow()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("存为模板") },
                            leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onSaveTemplate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清空提示词", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Clear,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onClearPrompt()
                            },
                        )
                    }
                }
            },
        )
    }
}

// ===========================================================================
// 进度 / 失败 / 成功
// ===========================================================================

@Composable
private fun JobProgressCard(
    state: UiState,
    vm: AppViewModel,
    onRequestInterrupt: () -> Unit,
) {
    val job = state.job
    val startedAt = when (job) {
        is JobState.Submitting -> job.startedAt
        is JobState.Queued -> job.startedAt
        is JobState.Running -> job.startedAt
        else -> return
    }
    // tick 只存在于进度卡内部：每秒仅重组这一小块，不影响整页。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = ((now - startedAt) / 1000).coerceAtLeast(0)
    val title = when (job) {
        is JobState.Submitting -> "提交任务"
        is JobState.Queued -> "排队等待"
        else -> "正在生成"
    }
    val pillText = when (job) {
        is JobState.Submitting -> "提交中"
        is JobState.Queued -> "排队中"
        else -> "生成中"
    }
    val pillTone = when (job) {
        is JobState.Submitting -> StatusTone.Warning
        is JobState.Queued -> StatusTone.Neutral
        else -> StatusTone.Info
    }

    ExpressiveCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusPill(pillText, pillTone)
        }
        Spacer(Modifier.height(Spacing.md))
        when (job) {
            is JobState.Running -> {
                val max = job.max.coerceAtLeast(1)
                val target = (job.step.toFloat() / max).coerceIn(0f, 1f)
                // lambda 延迟读取：进度动画逐帧变化时不做整卡重组。
                val animatedProgress by animateFloatAsState(
                    targetValue = target,
                    animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
                    label = "jobProgress",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        nodeLabel(job.node),
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${job.step}/$max · ${(target * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                LinearWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }

            else -> {
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    if (job is JobState.Submitting) {
                        "正在把任务提交到云端…"
                    } else {
                        "任务已排队，等待 Colab 空闲…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetaText(
                "已用 ${formatElapsed(elapsed)} · 队列 ${state.serverQueue.running + state.serverQueue.pending}",
                Modifier.weight(1f),
            )
            TextButton(onClick = onRequestInterrupt) {
                Text("中断", color = MaterialTheme.colorScheme.error)
            }
        }
    }

}

/** 底部常驻 slim 进度条：运行中任意滚动位置都能看到进度与中断入口。 */
@Composable
private fun StickyJobBar(state: UiState, onRequestInterrupt: () -> Unit) {
    val job = state.job
    val startedAt = when (job) {
        is JobState.Submitting -> job.startedAt
        is JobState.Queued -> job.startedAt
        is JobState.Running -> job.startedAt
        else -> null
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = startedAt?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0
    val running = job as? JobState.Running
    val target = running?.let {
        (it.step.toFloat() / it.max.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    val animated by animateFloatAsState(
        targetValue = target ?: 0f,
        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "stickyJobProgress",
    )
    val title = when (job) {
        is JobState.Submitting -> "提交任务中"
        is JobState.Queued -> "排队中"
        is JobState.Running -> "生成中 ${((target ?: 0f) * 100).roundToInt()}%"
        is JobState.Success -> "生成完成"
        is JobState.Failed -> "生成失败"
        else -> "生成中"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        shape = AnimaShape.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                MetaText("已用 ${formatElapsed(elapsed)}")
                IconButton(onClick = onRequestInterrupt) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "中断生成",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xxs))
            if (target != null) {
                LinearWavyProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            } else {
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

/** 尺寸预设：2×2 网格 tile，选中态主色 + 勾选图标。 */
@Composable
private fun SizeOptionTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = AnimaShape.field,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 第 4 个之后的尺寸收进「更多尺寸」下拉，避免 chip 奇数换行。 */
@Composable
private fun MoreSizesMenu(options: List<Pair<Int, Int>>, onSelect: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = AnimaShape.field,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "更多尺寸",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (w, h) ->
                DropdownMenuItem(
                    text = { Text(PromptBuilder.sizeLabel(w, h)) },
                    onClick = {
                        open = false
                        onSelect(w, h)
                    },
                )
            }
        }
    }
}

/** ComfyUI 节点 id → 中文阶段名（与 ViewModel 的命名保持一致）。 */
private fun nodeLabel(node: String?): String = when (node) {
    null, "" -> "采样"
    "1", "2", "5" -> "加载模型"
    "3", "4" -> "文本编码"
    "6" -> "准备画布"
    "7" -> "采样"
    "8" -> "VAE 解码"
    "9" -> "保存图片"
    "10" -> "加载 LoRA"
    else -> "节点 $node"
}

@Composable
private fun FailedCard(state: UiState, vm: AppViewModel) {
    val job = state.job as? JobState.Failed ?: return
    val focusManager = LocalFocusManager.current
    ExpressiveCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "生成失败",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            job.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SecondaryActionButton(
                text = "重试",
                onClick = {
                    focusManager.clearFocus()
                    vm.dismissJob()
                    vm.generate()
                },
                modifier = Modifier.weight(1f),
            )
            SecondaryActionButton(
                text = "关闭",
                onClick = vm::dismissJob,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SuccessCard(
    state: UiState,
    vm: AppViewModel,
    onOpenGallery: () -> Unit,
    onParamsReused: () -> Unit,
) {
    val job = state.job as? JobState.Success ?: return
    val item = job.item
    val image = item.firstImage ?: return
    val params = item.params
    val ratio = if (params != null && params.width > 0 && params.height > 0) {
        params.width.toFloat() / params.height
    } else {
        1f
    }
    // 参数与 seed 分两行：拼成一行时换行点正好落在「 · 」后面，
    // 第一行会以孤立的分隔符结尾（视觉 QA P2-2 同类问题）。
    val meta = listOfNotNull(
        params?.steps?.takeIf { it > 0 }?.let { "${it}步" },
        params?.cfg?.takeIf { it > 0 }?.let { "CFG ${trimNum(it)}" },
        params?.sampler?.takeIf { it.isNotBlank() },
        params?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" },
    ).joinToString(" · ")
    val seedMeta = params?.seed?.takeIf { it > 0 }?.let { "seed $it" }

    ExpressiveCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.extended.success,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    "生成完成",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "耗时 ${job.elapsedMs / 1000}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill("完成", StatusTone.Success)
        }
        Spacer(Modifier.height(Spacing.md))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
                .clip(AnimaShape.hero)
                .clickable { vm.openViewer(item, image) },
        ) {
            NetworkImage(vm, image, Modifier.fillMaxSize(), targetPx = 1024)
            if (item.images.size > 1) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.sm),
                    shape = AnimaShape.pill,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        "共 ${item.images.size} 张",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs),
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MetaText(meta)
                if (seedMeta != null) {
                    Spacer(Modifier.height(Spacing.xxs))
                    MetaText(seedMeta)
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "点击看图",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SuccessAction(
                icon = Icons.Outlined.Save,
                label = "保存",
                onClick = { vm.saveImage(item, image) },
                modifier = Modifier.weight(1f),
            )
            SuccessAction(
                icon = Icons.Outlined.Share,
                label = "分享",
                onClick = { vm.shareImage(item, image) },
                modifier = Modifier.weight(1f),
            )
            SuccessAction(
                icon = Icons.Outlined.Tune,
                label = "填回参数",
                onClick = {
                    vm.reuseParams(item)
                    onParamsReused()
                },
                modifier = Modifier.weight(1f),
            )
            SuccessAction(
                icon = Icons.Outlined.PhotoLibrary,
                label = "图库",
                onClick = onOpenGallery,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SuccessAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = AnimaShape.cardSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ===========================================================================
// 连接异常卡
// ===========================================================================

@Composable
private fun ConnectionErrorCard(
    message: String,
    state: UiState,
    vm: AppViewModel,
    onOpenSettings: () -> Unit,
) {
    ExpressiveCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(AnimaShape.full)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Text(
                "连接异常",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.md))
        PrimaryActionButton(
            text = "重试连接",
            onClick = vm::connect,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        SecondaryActionButton(
            text = if (state.googleLoggedIn) "一键重启 Colab" else "去设置",
            onClick = if (state.googleLoggedIn) vm::startColabSession else onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ===========================================================================
// 弹窗 / 工具
// ===========================================================================

@Composable
private fun SaveTemplateDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    // 打开即聚焦名称输入框：省一次点按，键盘直接可用。
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focusRequester.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // 对话框用低一层的 tonal 容器：AnimaTextField 是 surfaceContainerHigh，
        // 默认两者同为 High，输入框在弹窗里会「隐身」（真机 QA 发现）。
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text("存为模板") },
        text = {
            AnimaTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.focusRequester(focusRequester),
                label = "模板名称",
                placeholder = "例如：黄昏教室",
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 读取系统剪贴板（登录授权码 / 配对信息）。 */
private fun readClipboard(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = cm.primaryClip ?: return ""
    return (0 until clip.itemCount)
        .mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }
        .joinToString("\n")
        .trim()
}

/** 秒数 → "42s / 1m05s"。 */
private fun formatElapsed(seconds: Long): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m${seconds % 60}s"

/** animateContentSize / 展开收起需要的 IntSize 弹簧（令牌里的 spatial 是 Float 版本）。 */
private val SizeSpring: FiniteAnimationSpec<IntSize> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 380f,
)

/** 底部进度条滑入滑出的 IntOffset 弹簧。 */
private val SlideSpring: FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 380f,
)

/** 数值去掉多余的 0：4.5 → "4.5"，4.0 → "4"。（其他屏幕也在用，保持 top-level public） */
fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

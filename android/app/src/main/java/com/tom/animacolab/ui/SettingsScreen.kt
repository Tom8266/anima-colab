package com.tom.animacolab.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.BuildConfig
import com.tom.animacolab.UiState
import com.tom.animacolab.colab.ColabAccelerator
import com.tom.animacolab.colab.ColabSessionManager
import com.tom.animacolab.data.ConnectionState
import com.tom.animacolab.data.ThemeMode
import com.tom.animacolab.ui.theme.AnimaShape
import com.tom.animacolab.ui.theme.Spacing
import com.tom.animacolab.ui.theme.StatusTone
import com.tom.animacolab.ui.theme.extended

/**
 * 设置页：外观 · 连接 · 数据。
 *
 * 说明：
 * - 一键 Colab 是本页 hero moment：primaryContainer + 状态色装饰形状 + 状态机。
 * - 外观（深浅色 / 动态取色）改动经 ViewModel 进入 UiState，MainActivity 里 AnimaTheme 即时重绘。
 * - 破坏性操作（停止会话 / 清除配对 / 退出登录）都走二次确认 Dialog。
 */
@Composable
fun SettingsScreen(vm: AppViewModel, state: UiState) {
    val context = LocalContext.current
    val notebookExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ipynb+json"),
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    context.assets.open("anima_colab.ipynb").use { input -> input.copyTo(out) }
                    true
                } ?: false
            }.getOrDefault(false)
            vm.message(if (ok) "笔记本已导出；上传到 Google Drive 后可在 Colab 打开" else "导出失败")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "设置", subtitle = "外观 · 连接 · 数据")
        Column(Modifier.padding(horizontal = Spacing.screen)) {
            ColabSection(vm, state)
            Spacer(Modifier.height(Spacing.lg))
            AppearanceSection(vm, state)
            Spacer(Modifier.height(Spacing.lg))
            NotificationSection(vm, state)
            Spacer(Modifier.height(Spacing.lg))
            ManualPairingSection(vm, state, context)
            Spacer(Modifier.height(Spacing.lg))
            ServerSection(vm, state)
            Spacer(Modifier.height(Spacing.lg))
            UsageSection(onExportNotebook = { notebookExport.launch("anima_colab.ipynb") })
            Spacer(Modifier.height(Spacing.lg))
            AboutSection()
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

// ===========================================================================
// 1. Colab 一键启动（Hero 卡 + 状态机）
// ===========================================================================

private data class HeroState(val label: String, val tone: StatusTone, val subtitle: String)

private fun heroState(state: UiState): HeroState {
    if (!state.googleLoggedIn) {
        return HeroState(
            "未登录",
            StatusTone.Neutral,
            "登录后自动创建 ${state.colabAccelerator.shortLabel} 并完成配对",
        )
    }
    return when (val session = state.session) {
        ColabSessionManager.State.Starting ->
            HeroState("准备中", StatusTone.Warning, "正在准备 Colab 会话…")

        is ColabSessionManager.State.Deploying ->
            HeroState("部署中", StatusTone.Warning, session.stage.ifBlank { "正在部署 ComfyUI + Anima…" })

        is ColabSessionManager.State.Ready ->
            HeroState(
                "已就绪",
                StatusTone.Success,
                "${session.device.ifBlank { state.colabAccelerator.label }} · " +
                    "已运行 ${elapsedMinutes(session.startedAtMs)} 分钟",
            )

        is ColabSessionManager.State.Failed ->
            HeroState("启动失败", StatusTone.Error, "上次启动失败，可重试")

        ColabSessionManager.State.Off ->
            HeroState(
                "未启动",
                StatusTone.Neutral,
                "自动创建 ${state.colabAccelerator.shortLabel} 并完成配对",
            )
    }
}

/**
 * 加速卡选择：只在未启动/失败时出现，Ready 后设备信息在 Hero 卡里展示。
 * T4 是稳定默认；TPU v5e1 是实验档，首次部署会打 ComfyUI 设备补丁。
 */
@Composable
private fun AcceleratorCard(
    selected: ColabAccelerator,
    onSelect: (ColabAccelerator) -> Unit,
) {
    ExpressiveCard(modifier = Modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    "运行设备",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "一键启动时申请哪种 Colab 加速卡",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        SegmentedOptions(
            options = ColabAccelerator.entries.map { it.label },
            selectedIndex = selected.ordinal,
            onSelect = { onSelect(ColabAccelerator.entries[it]) },
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            selected.summary,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            selected.detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun ColabSection(vm: AppViewModel, state: UiState) {
    var showAllLogs by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }

    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val quiet = onContainer.copy(alpha = 0.76f)
    val hero = heroState(state)

    SectionLabel("Colab 一键启动")
    if (state.session is ColabSessionManager.State.Off ||
        state.session is ColabSessionManager.State.Failed
    ) {
        AcceleratorCard(state.colabAccelerator, onSelect = vm::setColabAccelerator)
        Spacer(Modifier.height(Spacing.md))
    }
    ExpressiveCard(shape = AnimaShape.hero, containerColor = container, contentColor = onContainer) {
        Box(Modifier.fillMaxWidth()) {
            // 这里不放装饰形状：卡片右上角是状态 pill，下面是「退出登录」，
            // 任何 56dp 形状都会压到前景控件（视觉 QA P2-3 复验仍可见相交）。
            // 主色容器 + 火箭图标已足够表达 Hero，留白比装饰更干净。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.RocketLaunch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    // 胶囊只和标题同行，副标题独占整行 → 不会被挤压断词。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Colab ${state.colabAccelerator.shortLabel}",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = onContainer,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(hero.label, hero.tone)
                    }
                    Text(
                        hero.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = quiet,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        if (!state.googleLoggedIn) {
            LoggedOutBody(vm = vm, quiet = quiet, accelerator = state.colabAccelerator)
        } else {
            GoogleAccountRow(vm = vm, onLogoutClick = { confirmLogout = true })
            Spacer(Modifier.height(Spacing.md))
            when (val session = state.session) {
                ColabSessionManager.State.Starting -> StartingBody(vm, state.colabAccelerator)
                is ColabSessionManager.State.Deploying -> DeployingBody(vm, session)
                is ColabSessionManager.State.Ready -> ReadyBody(vm, state, session, onStopClick = { confirmStop = true })
                is ColabSessionManager.State.Failed -> FailedBody(vm, session)
                ColabSessionManager.State.Off -> OffBody(vm, state.colabAccelerator)
            }
            val deploying = state.session is ColabSessionManager.State.Deploying
            val failed = state.session is ColabSessionManager.State.Failed
            if (state.deployLogs.isNotEmpty() && (deploying || failed)) {
                Spacer(Modifier.height(Spacing.md))
                DeployLogs(
                    logs = state.deployLogs,
                    showAll = showAllLogs,
                    onToggle = { showAllLogs = !showAllLogs },
                )
            }
        }
    }

    if (confirmStop) {
        ConfirmDialog(
            title = "停止 Colab 会话？",
            text = "会立即释放当前 ${state.colabAccelerator.label} 会话并清除自动配对；" +
                "下次使用需要重新部署（首次约 3-5 分钟）。",
            confirmLabel = "停止会话",
            onConfirm = {
                confirmStop = false
                vm.stopColabSession()
            },
            onDismiss = { confirmStop = false },
        )
    }
    if (confirmLogout) {
        ConfirmDialog(
            title = "退出 Google 登录？",
            text = "退出后会断开一键模式并停止当前 Colab 会话；下次需要重新登录授权。",
            confirmLabel = "退出登录",
            onConfirm = {
                confirmLogout = false
                vm.logoutGoogle()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

/** 已登录账号行。 */
@Composable
private fun GoogleAccountRow(vm: AppViewModel, onLogoutClick: () -> Unit) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.AccountCircle,
            contentDescription = null,
            tint = onContainer.copy(alpha = 0.9f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "Google 账号",
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = onContainer,
            )
            Text(
                "已登录 · 可一键启动",
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.76f),
            )
        }
        HeroTextButton(text = "退出登录", onClick = onLogoutClick)
    }
}

/** 未登录：登录 Google + 粘贴授权码。 */
@Composable
private fun LoggedOutBody(vm: AppViewModel, quiet: Color, accelerator: ColabAccelerator) {
    var showLoginCode by rememberSaveable { mutableStateOf(false) }
    Text(
        "登录 Google 后，App 会自动创建 Colab ${accelerator.shortLabel}、" +
            "部署 ComfyUI + Anima、自动完成配对，不用再打开 Colab 页面复制 PAIR。",
        style = MaterialTheme.typography.bodySmall,
        color = quiet,
        lineHeight = 18.sp,
    )
    Spacer(Modifier.height(Spacing.md))
    PrimaryActionButton(
        text = "登录 Google",
        onClick = vm::beginGoogleLogin,
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.AutoMirrored.Outlined.Login,
    )
    Spacer(Modifier.height(Spacing.sm))
    HeroOutlinedButton(
        text = "粘贴授权码完成登录",
        onClick = { showLoginCode = true },
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.Outlined.ContentPaste,
    )
    Spacer(Modifier.height(Spacing.sm))
    Text(
        "登录步骤：点「登录 Google」→ 浏览器里选账号并允许 → Google 页面显示一串授权码 → " +
            "长按复制 → 回到这里点「粘贴授权码完成登录」，把授权码粘进输入框。只需授权一次。",
        style = MaterialTheme.typography.labelSmall,
        color = quiet,
        lineHeight = 16.sp,
    )
    if (showLoginCode) {
        GoogleLoginCodeDialog(
            onDismiss = { showLoginCode = false },
            onSubmit = { code ->
                showLoginCode = false
                vm.completeGoogleLogin(code)
            },
        )
    }
}

/** Starting：分配会话中，可取消。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StartingBody(vm: AppViewModel, accelerator: ColabAccelerator) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ContainedLoadingIndicator(
            modifier = Modifier.size(40.dp),
            containerColor = onContainer.copy(alpha = 0.10f),
            indicatorColor = onContainer,
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                "正在准备 Colab 会话…",
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
            )
            Text(
                "分配 ${accelerator.label} 并创建 Jupyter kernel",
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.76f),
            )
        }
        HeroTextButton(text = "取消", onClick = vm::cancelColabStart)
    }
}

/** Deploying：stage + 不定态 wavy 进度 + 取消；日志折叠区在卡片底部。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeployingBody(vm: AppViewModel, session: ColabSessionManager.State.Deploying) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ContainedLoadingIndicator(
            modifier = Modifier.size(40.dp),
            containerColor = onContainer.copy(alpha = 0.10f),
            indicatorColor = onContainer,
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                session.stage.ifBlank { "正在部署 ComfyUI + Anima…" },
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "首次约 3-5 分钟，可切到后台等待",
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.76f),
            )
        }
    }
    Spacer(Modifier.height(Spacing.md))
    LinearWavyProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        color = onContainer,
        trackColor = onContainer.copy(alpha = 0.20f),
    )
    Spacer(Modifier.height(Spacing.sm))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        HeroTextButton(text = "取消启动", onClick = vm::cancelColabStart)
    }
}

/** Ready：设备 / 显存 / 队列摘要 + 重新连接 + 停止会话（destructive，二次确认）。 */
@Composable
private fun ReadyBody(
    vm: AppViewModel,
    state: UiState,
    session: ColabSessionManager.State.Ready,
    onStopClick: () -> Unit,
) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.extended.success,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            "${session.device.ifBlank { "Colab" }} 已就绪 · 已运行 ${elapsedMinutes(session.startedAtMs)} 分钟",
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(Spacing.sm))
    HeroInfoRow(
        "设备",
        session.device.ifBlank { state.info?.device ?: state.colabAccelerator.label },
    )
    HeroInfoRow("显存", vramText(state))
    HeroInfoRow("队列", "运行 ${state.serverQueue.running} / 排队 ${state.serverQueue.pending}")
    Spacer(Modifier.height(Spacing.md))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryActionButton(
            text = "重新连接",
            onClick = vm::connect,
            modifier = Modifier.weight(1f),
        )
        HeroTextButton(
            text = "停止会话",
            onClick = onStopClick,
            icon = Icons.Outlined.StopCircle,
            iconTint = MaterialTheme.colorScheme.error,
        )
    }
}

/** Failed：错误原因 + 建议 + 重新启动 / 重新登录。 */
@Composable
private fun FailedBody(vm: AppViewModel, session: ColabSessionManager.State.Failed) {
    val quiet = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AnimaShape.cardSmall,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    session.message.ifBlank { "启动失败，请重试" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "请检查网络 / 代理后重试；多次失败可退出 Google 重新登录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.82f),
                    lineHeight = 16.sp,
                )
            }
        }
    }
    Spacer(Modifier.height(Spacing.md))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        PrimaryActionButton(
            text = "重新启动",
            onClick = vm::startColabSession,
            modifier = Modifier.weight(1f),
        )
        HeroOutlinedButton(
            text = "重新登录",
            onClick = vm::beginGoogleLogin,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        )
    }
    Spacer(Modifier.height(Spacing.xs))
    Text(
        "仍然失败时，可用下方「手动配对（备用）」作为兜底。",
        style = MaterialTheme.typography.labelSmall,
        color = quiet,
        lineHeight = 16.sp,
    )
}

/** Off：主操作一键启动。 */
@Composable
private fun OffBody(vm: AppViewModel, accelerator: ColabAccelerator) {
    val quiet = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
    PrimaryActionButton(
        text = "一键启动 Colab ${accelerator.shortLabel}",
        onClick = vm::startColabSession,
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.Outlined.RocketLaunch,
    )
    Spacer(Modifier.height(Spacing.sm))
    Text(
        if (accelerator.isTpu) {
            "首次约 4-6 分钟（下载 5.3GB 模型 + TPU 补丁）；首张图还会先编译约 1 分钟。"
        } else {
            "首次约 3-5 分钟（下载 5.3GB 模型）；同一会话里再启动几秒就能完成。"
        },
        style = MaterialTheme.typography.labelSmall,
        color = quiet,
        lineHeight = 16.sp,
    )
}

/** 部署日志：默认尾部 6 行，可展开到 80 行。 */
@Composable
private fun DeployLogs(logs: List<String>, showAll: Boolean, onToggle: () -> Unit) {
    val lines = if (showAll) logs.takeLast(80) else logs.takeLast(6)
    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AnimaShape.cardSmall,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(Spacing.md)) {
                lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HeroTextButton(
            text = if (showAll) "收起日志" else "展开部署日志（${logs.size} 行）",
            onClick = onToggle,
        )
    }
}

// ===========================================================================
// 2. 外观（即时生效）
// ===========================================================================

@Composable
private fun AppearanceSection(vm: AppViewModel, state: UiState) {
    SectionLabel("外观")
    ExpressiveCard {
        Text(
            "主题模式",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        SegmentedOptions(
            options = ThemeMode.entries.map { it.label },
            selectedIndex = state.themeMode.ordinal,
            onSelect = { vm.setThemeMode(ThemeMode.entries[it]) },
        )
        Spacer(Modifier.height(Spacing.lg))
        SettingSwitchRow(
            title = "动态取色（Material You）",
            checked = state.dynamicColor,
            onCheckedChange = vm::setDynamicColor,
            description = "从壁纸提取颜色，切换立即生效；Android 12+ 可用",
            leadingIcon = Icons.Outlined.Palette,
        )
        SettingRow(
            title = if (state.dynamicColor) "壁纸色板" else "品牌色板",
            description = if (state.dynamicColor) {
                "来自壁纸，随系统壁纸动态变化"
            } else {
                "电光紫 · 蜜桃粉 · 青柠蓝"
            },
            trailing = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaletteDot(MaterialTheme.colorScheme.primary)
                    PaletteDot(MaterialTheme.colorScheme.secondary)
                    PaletteDot(MaterialTheme.colorScheme.tertiary)
                }
            },
        )
    }
}

@Composable
private fun PaletteDot(color: Color) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

// ===========================================================================
// 3. 通知与保存
// ===========================================================================

@Composable
private fun NotificationSection(vm: AppViewModel, state: UiState) {
    SectionLabel("通知与保存")
    ExpressiveCard {
        SettingSwitchRow(
            title = "生成后自动下载图片",
            checked = state.autoDownload,
            onCheckedChange = vm::setAutoDownload,
            description = "只存到 App 本地，不写入系统相册；点「保存」才进相册",
            leadingIcon = Icons.Outlined.Download,
        )
        SettingSwitchRow(
            title = "后台保活（常驻通知）",
            checked = state.backgroundKeepalive,
            onCheckedChange = vm::setBackgroundKeepalive,
            description = "关屏 / 切后台保持 Colab 会话，每 4 分钟一次心跳",
            leadingIcon = Icons.Outlined.NotificationsActive,
        )
    }
}

// ===========================================================================
// 4. 手动配对（备用，折叠）
// ===========================================================================

@Composable
private fun ManualPairingSection(vm: AppViewModel, state: UiState, context: Context) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var url by remember { mutableStateOf(state.config.baseUrl) }
    var token by remember { mutableStateOf(state.config.token) }
    val expandSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

    LaunchedEffect(state.config) {
        url = state.config.baseUrl
        token = state.config.token
    }

    SectionLabel("手动配对（备用）")
    ExpressiveCard {
        SettingRow(
            title = "地址与 Token",
            description = if (state.config.configured) {
                "已保存配对信息 · 点此展开修改"
            } else {
                "当前未配对 · 点此展开填写或粘贴 PAIR 行"
            },
            leadingIcon = Icons.Outlined.Link,
            onClick = { expanded = !expanded },
            trailing = { AnimatedChevron(expanded) },
        )
        Column(Modifier.fillMaxWidth().animateContentSize(expandSpec)) {
            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                AnimaTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "服务地址",
                    placeholder = "https://xxxx.trycloudflare.com",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val target = url.trim().ifBlank { state.config.baseUrl.trim() }
                                when {
                                    target.isBlank() -> vm.message("还没有可复制的服务地址")
                                    copyToClipboard(context, target) -> vm.message("已复制服务地址")
                                    else -> vm.message("复制失败")
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "复制服务地址",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(Spacing.sm))
                AnimaTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = "Token",
                    singleLine = true,
                    visualTransformation = if (tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (tokenVisible) "隐藏 Token" else "显示 Token",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryActionButton(
                        text = "保存并连接",
                        onClick = { vm.setConfig(url, token) },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryActionButton(
                        text = "粘贴配对信息",
                        onClick = {
                            val clip = readClipboard(context)
                            if (clip.isBlank()) {
                                vm.message("剪贴板是空的，先在 Colab 复制 PAIR 行")
                            } else {
                                vm.pastePair(clip)
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        icon = Icons.Outlined.ContentPaste,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::connect) { Text("重新连接") }
                    TextButton(onClick = { confirmClear = true }) {
                        Text("清除配对", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                ConnectionStatusBlock(state)
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清除配对信息？",
            text = "会断开当前连接，并删除本机保存的服务地址与 Token。",
            confirmLabel = "清除配对",
            onConfirm = {
                confirmClear = false
                vm.clearConfig()
                url = ""
                token = ""
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** 当前连接状态：状态胶囊 + 详情（成功显示设备摘要，失败显示原因）。 */
@Composable
private fun ConnectionStatusBlock(state: UiState) {
    val (label, tone) = when (state.connection) {
        ConnectionState.NotConfigured -> "未配对" to StatusTone.Neutral
        ConnectionState.Checking -> "正在连接…" to StatusTone.Warning
        is ConnectionState.Ready -> "已连接" to StatusTone.Success
        is ConnectionState.Error -> "连接失败" to StatusTone.Error
    }
    val detail = when (val connection = state.connection) {
        is ConnectionState.Ready -> "当前连接：${serverSummaryText(state)}"
        is ConnectionState.Error -> connection.message
        else -> null
    }
    Column(Modifier.fillMaxWidth()) {
        StatusPill(label, tone)
        if (detail != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ===========================================================================
// 5. 服务器信息
// ===========================================================================

@Composable
private fun ServerSection(vm: AppViewModel, state: UiState) {
    var showAllSamplers by rememberSaveable { mutableStateOf(false) }
    SectionLabel(
        text = "服务器",
        trailing = {
            IconButton(onClick = vm::connect) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新服务器信息",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
    ExpressiveCard {
        val info = state.info
        if (info == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    if (state.config.configured) {
                        "正在等待 Colab 返回服务器信息…"
                    } else {
                        "连接 Colab 后显示设备、模型与服务信息"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            InfoRow("设备", info.device ?: state.colabAccelerator.label)
            InfoRow("显存", vramText(state))
            InfoRow("队列", "运行 ${state.serverQueue.running} / 排队 ${state.serverQueue.pending}")
            InfoRow("模型", info.unets.joinToString(", ").ifBlank { "—" })
            if (info.loras.isNotEmpty()) InfoRow("LoRA", info.loras.joinToString(", "))
            InfoRow("VAE", info.vaes.joinToString(", ").ifBlank { "—" })
            InfoRow("文本编码器", info.textEncoders.joinToString(", ").ifBlank { "—" })
            InfoRow("采样器", samplerText(info.samplers, showAllSamplers))
            if (info.samplers.size > SamplerPreviewCount) {
                TextButton(
                    onClick = { showAllSamplers = !showAllSamplers },
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                ) {
                    Text(
                        if (showAllSamplers) {
                            "收起采样器"
                        } else {
                            "展开全部 ${info.samplers.size} 个采样器"
                        },
                    )
                }
            }
        }
    }
}

// ===========================================================================
// 6. 怎么用 + 导出笔记本
// ===========================================================================

@Composable
private fun UsageSection(onExportNotebook: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

    SectionLabel("怎么用")
    ExpressiveCard {
        SettingRow(
            title = "使用说明",
            description = "一键模式 · 手动配对 · 注意事项",
            leadingIcon = Icons.AutoMirrored.Outlined.HelpOutline,
            onClick = { expanded = !expanded },
            trailing = { AnimatedChevron(expanded) },
        )
        Column(Modifier.fillMaxWidth().animateContentSize(expandSpec)) {
            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                HelpBlockTitle("一键模式（推荐）")
                Spacer(Modifier.height(Spacing.xs))
                HelpBody(
                    "1. 点「登录 Google」完成一次授权。\n" +
                        "2. 点「一键启动 Colab」，App 自动创建会话、部署 ComfyUI + Anima、自动配对（首次 3-5 分钟）。\n" +
                        "3. 回生成页出图；App 每 4 分钟自动保活。\n" +
                        "4. 不用时点「停止会话」释放（也可以直接放着，Colab 到时会自动回收）。",
                )
                Spacer(Modifier.height(Spacing.sm))
                HelpBlockTitle("备用：手动配对")
                Spacer(Modifier.height(Spacing.xs))
                HelpBody("也可以在手机浏览器打开 Colab 笔记本「全部运行」，复制打印的 PAIR 行，到「手动配对」里粘贴。")
                Spacer(Modifier.height(Spacing.sm))
                HelpBlockTitle("注意事项")
                Spacer(Modifier.height(Spacing.xs))
                HelpBody(
                    "· App 访问 Google 需要手机能上 Google（开代理 / VPN）。\n" +
                        "· 免费 Colab 单会话有时长和额度上限；断开后点「一键启动」会重新申请并部署（/content 清空后要重下模型）。\n" +
                        "· TPU v5e1 是实验档：部署时会打 ComfyUI 设备补丁，首图需编译；比 T4 快约 2 倍。\n" +
                        "· Colab 内容政策：只跑正常内容。",
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        SecondaryActionButton(
            text = "导出备用笔记本（.ipynb）",
            onClick = onExportNotebook,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Download,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "导出后把 anima_colab.ipynb 上传到 Google Drive → 用 Colab 打开 →「全部运行」→ " +
                "复制打印的 PAIR 行，回到「手动配对」粘贴即可作为备用方案。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun HelpBlockTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun HelpBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 20.sp,
    )
}

// ===========================================================================
// 7. 关于
// ===========================================================================

@Composable
private fun AboutSection() {
    SectionLabel("关于")
    ExpressiveCard {
        InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        InfoRow("数据", "Google 登录令牌与会话信息只保存在本机；不收集任何数据")
        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.sm),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            "模型说明",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Anima 2B（Cosmos-Predict2 架构）+ Qwen3-0.6B 文本编码器 + Qwen-Image VAE。" +
                "T4 用 fp16，TPU v5e1 用 bf16（需给 ComfyUI 打设备补丁）。" +
                "一键模式的 Colab 协议（OAuth / assignments / Jupyter kernel）移植自 Google 开源的 google-colab-cli。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
        )
    }
}

// ===========================================================================
// 通用小部件
// ===========================================================================

/**
 * Hero（primaryContainer）内专用 TextButton：内容色固定 onPrimaryContainer，
 * 避免 material 默认的 primary 直接落在 primaryContainer 上导致对比度不足。
 */
@Composable
private fun HeroTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint ?: MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(text)
    }
}

/** Hero（primaryContainer）内的信息行：label/value 全部走 onPrimaryContainer 系。 */
@Composable
private fun HeroInfoRow(label: String, value: String, labelWidth: androidx.compose.ui.unit.Dp = 88.dp) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = onContainer.copy(alpha = 0.80f),
            modifier = Modifier.width(labelWidth),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = onContainer,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Hero（primaryContainer）内专用描边次按钮：文字/描边走 onPrimaryContainer 系。 */
@Composable
private fun HeroOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = AnimaShape.pill,
        border = BorderStroke(1.dp, onContainer.copy(alpha = 0.55f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = onContainer),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 破坏性操作二次确认。 */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = AnimaShape.card,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

// ===========================================================================
// 纯函数 / 系统剪贴板
// ===========================================================================

private const val SamplerPreviewCount = 6

private fun samplerText(samplers: List<String>, showAll: Boolean): String {
    if (samplers.isEmpty()) return "—"
    val visible = if (showAll) samplers else samplers.take(SamplerPreviewCount)
    val suffix = if (!showAll && samplers.size > visible.size) " …" else ""
    return visible.joinToString(", ") + suffix
}

private fun vramText(state: UiState): String =
    state.info?.vramTotalGb?.let { "%.1fG".format(java.util.Locale.US, it) } ?: "—"

private fun serverSummaryText(state: UiState): String {
    val info = state.info ?: return "未连接"
    val device = info.device.orEmpty()
    val vram = info.vramTotalGb?.let { "%.1fG".format(java.util.Locale.US, it) }.orEmpty()
    return listOf(device, vram).filter { it.isNotBlank() }.joinToString(" · ")
        .ifBlank { state.colabAccelerator.shortLabel }
}

private fun elapsedMinutes(startedAtMs: Long): Long {
    if (startedAtMs <= 0) return 0
    return ((System.currentTimeMillis() - startedAtMs) / 60000L).coerceAtLeast(0)
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

private fun copyToClipboard(context: Context, text: String): Boolean {
    if (text.isBlank()) return false
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    return runCatching {
        cm.setPrimaryClip(ClipData.newPlainText("Anima Colab", text))
        true
    }.getOrDefault(false)
}

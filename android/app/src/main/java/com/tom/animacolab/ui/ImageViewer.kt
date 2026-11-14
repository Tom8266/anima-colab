package com.tom.animacolab.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.UiState
import com.tom.animacolab.data.HistoryParams
import com.tom.animacolab.data.ImageRef
import com.tom.animacolab.ui.theme.AnimaMotion
import com.tom.animacolab.ui.theme.AnimaShape
import com.tom.animacolab.ui.theme.Spacing
import java.util.Locale
import kotlin.math.absoluteValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 全屏大图查看器（图库的 hero moment）：深色沉浸背景 + 横向翻页 + 浮动操作工具栏。
 *
 * 保存 / 分享使用「当前页」的图片引用；操作后关闭查看器，与旧版行为一致。
 */
@Composable
fun ImageViewerDialog(vm: AppViewModel, state: UiState) {
    val item = state.viewerItem ?: return
    val images = remember(item.promptId) { item.images.ifEmpty { listOfNotNull(item.firstImage) } }
    if (images.isEmpty()) {
        // 没有可展示的图片时安静关闭，避免停留在空的全屏层。
        LaunchedEffect(Unit) { vm.closeViewer() }
        return
    }

    var closing by remember { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        if (!closing) {
            closing = true
        }
    }

    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 对话框窗口自己铺满全屏，由内容自行处理系统栏 insets。
            decorFitsSystemWindows = false,
        ),
    ) {
        // 返回键 / 返回手势：先播放退出动画，再真正关闭。
        BackHandler(enabled = !closing) { requestClose() }

        val view = LocalView.current
        SideEffect {
            // 查看器背景恒为深色，系统栏图标固定用浅色，浅色主题下也可读。
            val window = view.findDialogWindow() ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }

        // 出现 / 退出：透明度走 effects、缩放走 spatial，两者分离不互相拖慢。
        val alpha = remember { Animatable(0f) }
        val scale = remember { Animatable(0.97f) }
        LaunchedEffect(Unit) {
            coroutineScope {
                launch { alpha.animateTo(1f, animationSpec = AnimaMotion.effects) }
                launch { scale.animateTo(1f, animationSpec = AnimaMotion.springy) }
            }
        }
        LaunchedEffect(closing) {
            if (closing) {
                coroutineScope {
                    launch { alpha.animateTo(0f, animationSpec = AnimaMotion.effects) }
                    launch { scale.animateTo(0.98f, animationSpec = AnimaMotion.spatialFast) }
                }
                vm.closeViewer()
            }
        }

        val initialPage = remember(item.promptId, state.viewerRef) {
            images.indexOfFirst { it == state.viewerRef }.coerceAtLeast(0)
        }
        val pager = rememberPagerState(initialPage = initialPage) { images.size }
        val paramsLines = viewerParamsLines(item.params)
        val created = vm.formatCreated(item.created)
        val currentRef = images.getOrNull(pager.currentPage)

        // 保存 / 分享是异步的：不关闭查看器，用对话框内的 Snackbar 反馈结果。
        val feedbackHost = remember { SnackbarHostState() }
        var awaitingFeedback by remember { mutableStateOf(false) }
        LaunchedEffect(state.message) {
            val msg = state.message ?: return@LaunchedEffect
            if (awaitingFeedback) {
                awaitingFeedback = false
                feedbackHost.showSnackbar(msg)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.94f)),
        ) {
            // 横向翻页：滑动时相邻页轻微淡出 / 缩小，增加空间层次。
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageOffset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val fraction = pageOffset.coerceIn(0f, 1f)
                            this.alpha = 1f - 0.45f * fraction
                            val pageScale = 1f - 0.04f * fraction
                            scaleX = pageScale
                            scaleY = pageScale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    images.getOrNull(page)?.let { ref ->
                        ViewerImage(vm, ref, Modifier.fillMaxSize())
                    }
                }
            }

            ViewerTopBar(
                title = created.ifBlank { "查看大图" },
                pageText = if (images.size > 1) "${pager.currentPage + 1} / ${images.size}" else null,
                onClose = requestClose,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (paramsLines.isNotEmpty()) {
                    Surface(
                        shape = AnimaShape.cardSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        ) {
                            paramsLines.forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

                SnackbarHost(hostState = feedbackHost) { data ->
                    Snackbar(snackbarData = data, shape = AnimaShape.cardSmall)
                }
                if (feedbackHost.currentSnackbarData != null) {
                    Spacer(Modifier.height(Spacing.sm))
                }

                HorizontalFloatingToolbar(
                    expanded = true,
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        shape = AnimaShape.pill,
                    ),
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                        toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                        toolbarContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = AnimaShape.pill,
                    expandedShadowElevation = 3.dp,
                    collapsedShadowElevation = 3.dp,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
                ) {
                    ViewerAction(Icons.Outlined.Download, "保存") {
                        currentRef?.let {
                            awaitingFeedback = true
                            vm.saveImage(item, it)
                        }
                    }
                    ViewerAction(Icons.Outlined.Share, "分享") {
                        currentRef?.let {
                            awaitingFeedback = true
                            vm.shareImage(item, it)
                        }
                    }
                    ViewerAction(Icons.Outlined.Tune, "填回参数") {
                        vm.reuseParams(item)
                        requestClose()
                    }
                }
            }
        }
    }
}

/** 大图：全尺寸加载，加载中显示浮动 wavy 指示器，加载完成后淡入。 */
@Composable
private fun ViewerImage(vm: AppViewModel, ref: ImageRef, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, ref, vm) {
        value = vm.loadImageBitmap(ref, 2048, 2048)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp == null) {
            ViewerImageLoading()
        } else {
            val imageAlpha = remember(bmp) { Animatable(0f) }
            LaunchedEffect(bmp) {
                imageAlpha.animateTo(1f, animationSpec = AnimaMotion.effects)
            }
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "生成结果",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = imageAlpha.value },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** 大图加载中的悬浮胶囊：wavy 指示器 + 文案，避免整屏空白。 */
@Composable
private fun ViewerImageLoading() {
    Surface(
        shape = AnimaShape.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            CircularWavyProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = LocalContentColor.current,
            )
            Text("加载中…", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 顶部浮动胶囊：关闭 + 时间 + 页码。 */
@Composable
private fun ViewerTopBar(
    title: String,
    pageText: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = AnimaShape.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .padding(start = Spacing.xs, end = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭大图")
            }
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (pageText != null) {
                Text(
                    pageText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 工具栏里的「图标 + 文案」操作项（RowScope 内使用，带 Role.Button 语义）。 */
@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(AnimaShape.cardSmall)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(Spacing.xxs))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** 参数元数据按语义分两行：第一行采样参数，第二行尺寸/种子；行尾不留悬空分隔符。 */
private fun viewerParamsLines(params: HistoryParams?): List<String> {
    if (params == null) return emptyList()
    val sampling = listOfNotNull(
        params.steps.takeIf { it > 0 }?.let { "${it} 步" },
        params.cfg.takeIf { it > 0 }?.let { "CFG ${formatNumber(it)}" },
        params.sampler.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val image = listOfNotNull(
        if (params.width > 0 && params.height > 0) "${params.width}×${params.height}" else null,
        "seed ${params.seed}",
    ).joinToString(" · ")
    return listOf(sampling, image).filter { it.isNotBlank() }
}

/** 在 Dialog 的视图树里向上找到承载它的窗口（返回键/系统栏外观设置需要用）。 */
private fun View.findDialogWindow(): Window? {
    var node: ViewParent? = parent
    while (node != null) {
        if (node is DialogWindowProvider) return node.window
        node = node.parent
    }
    return null
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }

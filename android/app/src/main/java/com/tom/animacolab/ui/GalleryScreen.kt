@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.tom.animacolab.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.UiState
import com.tom.animacolab.data.HistoryItem
import com.tom.animacolab.ui.theme.AnimaShape
import com.tom.animacolab.ui.theme.Spacing

/**
 * 图库：服务器生成历史。
 *
 * 双列错落网格（按图片宽高比排版），点击任意作品打开全屏大图。
 * 未配对 / 首次加载 / 空列表都有独立的引导状态。
 */
@Composable
fun GalleryScreen(vm: AppViewModel, state: UiState, onNavigate: (Int) -> Unit) {
    // 每次进入图库都同步一次服务器历史。
    LaunchedEffect(Unit) {
        if (state.config.configured) vm.refreshHistory()
    }

    val historyError = state.historyError

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "图库",
            subtitle = "${state.history.size} 张",
            actions = {
                if (state.historyLoading) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                IconButton(onClick = vm::refreshHistory) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "刷新图库",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        when {
            !state.config.configured -> GalleryEmptyState(
                modifier = Modifier.weight(1f),
                title = "还没连接 Colab",
                description = "在设置里填入配对信息，服务器上的作品就会同步到这里",
                actionLabel = "去设置",
                icon = Icons.Outlined.Settings,
                onAction = { onNavigate(3) },
            )

            state.historyLoading && state.history.isEmpty() -> GalleryLoading(Modifier.weight(1f))

            // 加载失败且没有缓存数据：铺满空态区的错误卡 + 重试出口。
            historyError != null && state.history.isEmpty() -> GalleryErrorState(
                modifier = Modifier.weight(1f),
                message = historyError,
                onRetry = vm::refreshHistory,
                onOpenSettings = { onNavigate(3) },
            )

            state.history.isEmpty() -> GalleryEmptyState(
                modifier = Modifier.weight(1f),
                title = "还没有生成记录",
                description = "去生成第一张 Anima 图，作品会自动出现在这里",
                actionLabel = "去生成",
                icon = Icons.Outlined.PhotoLibrary,
                onAction = { onNavigate(0) },
            )

            else -> Column(Modifier.weight(1f)) {
                // 已有作品时失败不遮挡网格，只在顶部给一条可重试提示。
                if (historyError != null) {
                    HistoryErrorBanner(
                        message = historyError,
                        onRetry = vm::refreshHistory,
                        modifier = Modifier.padding(
                            start = Spacing.screen,
                            end = Spacing.screen,
                            top = Spacing.xs,
                            bottom = Spacing.xxs,
                        ),
                    )
                }
                HistoryGrid(vm, state, Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 历史网格
// ---------------------------------------------------------------------------

@Composable
private fun HistoryGrid(vm: AppViewModel, state: UiState, modifier: Modifier = Modifier) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.xs,
            bottom = Spacing.xxl,
        ),
        verticalItemSpacing = Spacing.md,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items(state.history, key = { it.promptId }) { item ->
            GalleryCard(vm = vm, item = item, modifier = Modifier.animateItem())
        }
    }
}

/** 单张作品卡：图片按参数宽高比错落，底部是克制的元数据。 */
@Composable
private fun GalleryCard(vm: AppViewModel, item: HistoryItem, modifier: Modifier = Modifier) {
    val image = item.firstImage ?: return
    val params = item.params

    // 没有可用宽高时退回 1:1；限制极端比例，避免卡片过高或过扁。
    val ratio = if (params != null && params.width > 0 && params.height > 0) {
        (params.width.toFloat() / params.height.toFloat()).coerceIn(0.6f, 1.8f)
    } else {
        1f
    }

    val created = vm.formatCreated(item.created)
    // 两行分组：第一行采样/尺寸，第二行种子/时间；行尾不留悬空分隔符。
    val metaLine1 = listOfNotNull(
        params?.steps?.takeIf { it > 0 }?.let { "${it} 步" },
        if (params != null && params.width > 0 && params.height > 0) "${params.width}×${params.height}" else null,
    ).joinToString(" · ")
    val metaLine2 = listOfNotNull(
        params?.seed?.let { "seed $it" },
        created.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    ExpressiveCard(
        modifier = modifier.fillMaxWidth(),
        onClick = { vm.openViewer(item, image) },
        shape = AnimaShape.cardSmall,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            NetworkImage(
                vm = vm,
                ref = image,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio),
                targetPx = 512,
            )
            if (item.images.size > 1) {
                // 多图数量角标：半透明小胶囊，深浅色下都可读。
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.sm),
                    shape = AnimaShape.pill,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        "+${item.images.size - 1}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                    )
                }
            }
        }
        Column(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            if (metaLine1.isNotBlank()) GalleryMetaLine(metaLine1)
            if (metaLine2.isNotBlank()) GalleryMetaLine(metaLine2)
        }
    }
}

/** 卡片元数据单行：11sp、单行 Ellipsis，避免机械换行/悬空分隔符。 */
@Composable
private fun GalleryMetaLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------------------
// 空态 / 加载态
// ---------------------------------------------------------------------------

@Composable
private fun GalleryEmptyState(
    title: String,
    description: String,
    actionLabel: String,
    icon: ImageVector,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = title,
            description = description,
            icon = icon,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}

@Composable
private fun GalleryLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingIndicator(modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(Spacing.md))
            Text(
                "正在同步历史…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 错误态
// ---------------------------------------------------------------------------

/** 首次加载就失败：整块错误卡，给出「重试」和「检查连接设置」两个出口。 */
@Composable
private fun GalleryErrorState(
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ExpressiveCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xxl),
            shape = AnimaShape.card,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            contentPadding = PaddingValues(Spacing.card),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    "历史同步失败",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.xl))
                PrimaryActionButton(
                    text = "重试",
                    onClick = onRetry,
                    icon = Icons.Outlined.Refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                SecondaryActionButton(
                    text = "检查连接设置",
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 已有作品但刷新失败：顶部一条紧凑提示，不遮挡网格。 */
@Composable
private fun HistoryErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AnimaShape.cardSmall,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(
                start = Spacing.md,
                end = Spacing.xs,
                top = Spacing.xs,
                bottom = Spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
            ) {
                Text("重试", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

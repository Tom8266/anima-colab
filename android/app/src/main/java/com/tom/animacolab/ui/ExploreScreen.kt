package com.tom.animacolab.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.UiState
import com.tom.animacolab.data.ModelPreset
import com.tom.animacolab.data.PromptTemplate
import com.tom.animacolab.data.Snippet
import com.tom.animacolab.data.Tag
import com.tom.animacolab.data.TagCategory
import com.tom.animacolab.ui.theme.AnimaShape
import com.tom.animacolab.ui.theme.Spacing
import java.util.Locale

// ===========================================================================
// 速查页：Tag 速查 + 提示词模板 双模式
//
// 功能全景（重写前的能力全部保留）：
//  - Tag 模式：中/英文搜索、分类筛选（全部 + 各分类横向滚动）、插入位置（正向/负向）、
//    常用片段点击插入、Tag 列表（中文名 + 英文 + 分类 + 画师标记，整行可点 + 右侧添加按钮）。
//  - 模板模式：搜索、模板卡（名称/note/prompt 预览/参数 meta）、应用、用户模板删除、空态。
//  - 模式切换用 SegmentedOptions；内容切换用 AnimatedContent 做轻微淡入缩放。
// ===========================================================================

@Composable
fun ExploreScreen(vm: AppViewModel, state: UiState, onBack: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    // 状态提升到屏幕级：模式来回切换时，搜索词 / 分类 / 插入位置都不丢失。
    var tagQuery by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf("all") }
    var positive by rememberSaveable { mutableStateOf(true) }
    var templateQuery by rememberSaveable { mutableStateOf("") }

    val tagCount = remember(state.tagCategories) { state.tagCategories.sumOf { it.tags.size } }
    val templateCount = state.templates.size

    // 模式切换动效走主题 motionScheme（spatial 用于缩放、effects 用于透明度）。
    val motion = MaterialTheme.motionScheme
    val fadeSpec = motion.defaultEffectsSpec<Float>()
    val scaleSpec = motion.defaultSpatialSpec<Float>()

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ScreenHeader(
            title = "速查",
            subtitle = "$tagCount 个 Tag · $templateCount 套模板",
        )

        SegmentedOptions(
            options = listOf("Tag 速查", "模板"),
            selectedIndex = mode,
            onSelect = { mode = it },
            modifier = Modifier.padding(horizontal = Spacing.screen),
        )
        Spacer(Modifier.height(Spacing.sm))

        AnimatedContent(
            targetState = mode,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter,
            transitionSpec = {
                (fadeIn(animationSpec = fadeSpec) + scaleIn(animationSpec = scaleSpec, initialScale = 0.98f))
                    .togetherWith(
                        fadeOut(animationSpec = fadeSpec) + scaleOut(animationSpec = scaleSpec, targetScale = 0.99f),
                    )
            },
            label = "exploreModeTransition",
        ) { current ->
            if (current == 0) {
                TagsContent(
                    vm = vm,
                    state = state,
                    query = tagQuery,
                    onQueryChange = { tagQuery = it },
                    categoryId = categoryId,
                    onCategoryChange = { categoryId = it },
                    positive = positive,
                    onPositiveChange = { positive = it },
                )
            } else {
                TemplatesContent(
                    vm = vm,
                    state = state,
                    query = templateQuery,
                    onQueryChange = { templateQuery = it },
                    onGoGenerate = onBack,
                )
            }
        }
    }
}

// ===========================================================================
// Tag 模式
// ===========================================================================

@Composable
private fun TagsContent(
    vm: AppViewModel,
    state: UiState,
    query: String,
    onQueryChange: (String) -> Unit,
    categoryId: String,
    onCategoryChange: (String) -> Unit,
    positive: Boolean,
    onPositiveChange: (Boolean) -> Unit,
) {
    val categories = state.tagCategories
    // 选中分类在「全部(0) + categories」里的下标；分类数据变化或 id 失效时回退到「全部」。
    val selectedCategoryIndex = remember(categories, categoryId) {
        if (categoryId == "all") {
            0
        } else {
            categories.indexOfFirst { it.id == categoryId }.let { if (it < 0) 0 else it + 1 }
        }
    }
    // 过滤结果：只在搜索词 / 分类变化时重算，288+ Tag 不会在每次重组时重复遍历。
    val filtered: List<Pair<TagCategory, Tag>> = remember(categories, categoryId, query) {
        val base = if (selectedCategoryIndex == 0) {
            categories
        } else {
            listOfNotNull(categories.getOrNull(selectedCategoryIndex - 1))
        }
        val q = query.trim()
        if (q.isBlank()) {
            base.flatMap { cat -> cat.tags.map { cat to it } }
        } else {
            base.flatMap { cat ->
                cat.tags
                    .filter { it.en.contains(q, ignoreCase = true) || it.zh.contains(q, ignoreCase = true) }
                    .map { cat to it }
            }
        }
    }

    val listState = rememberLazyListState()
    // 换关键词 / 换分类后回到结果顶部（只在确实滚动过时调用，空列表下不做无意义滚动）。
    LaunchedEffect(categoryId, query) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 搜索 + 插入位置同一行：把首屏留给 Tag 列表。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimaTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = "搜索 Tag（中/英文）",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                leadingIcon = Icons.Outlined.Search,
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "清除搜索",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else {
                    null
                },
            )
            Spacer(Modifier.width(Spacing.sm))
            InsertTargetToggle(
                positive = positive,
                onPositiveChange = onPositiveChange,
            )
        }
        Spacer(Modifier.height(Spacing.xs))

        CategoryTabRow(
            categories = categories,
            selectedIndex = selectedCategoryIndex,
            resultCount = filtered.size,
            onSelect = { onCategoryChange(it) },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(top = Spacing.xs, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.snippets.isNotEmpty()) {
                item(key = "snippets") {
                    SnippetsRow(
                        snippets = state.snippets,
                        onSnippet = { snippet ->
                            if (snippet.text.isBlank()) {
                                // 「留空」类片段是提示信息：点击给出一次解释，不静默无响应。
                                vm.message("「${snippet.name}」是提示片段：保持留空即可")
                            } else {
                                vm.appendSnippet(snippet.text, positive)
                            }
                        },
                    )
                }
            }

            if (filtered.isEmpty()) {
                item(key = "tagEmpty") {
                    val clearSearch: () -> Unit = { onQueryChange("") }
                    EmptyState(
                        title = "没有匹配的 Tag",
                        description = when {
                            categories.isEmpty() -> "Tag 数据还没准备好，稍后再试"
                            query.isNotBlank() -> "换个关键词，或点下面的按钮清除搜索"
                            else -> "这个分类下暂时没有 Tag，换个分类看看"
                        },
                        icon = Icons.Outlined.Search,
                        actionLabel = if (query.isNotBlank()) "清除搜索" else null,
                        onAction = if (query.isNotBlank()) clearSearch else null,
                    )
                }
            } else {
                items(filtered.take(400), key = { it.second.en }) { (category, tag) ->
                    TagRow(
                        category = category,
                        tag = tag,
                        modifier = Modifier.animateItem(),
                        onAdd = { vm.appendTag(tag.en, positive) },
                    )
                }
            }
        }
    }
}

/**
 * 插入位置（紧凑单键）：点一下在「正向 / 负向」间切换。
 *
 * 选中 = primary 实心 + check 图标；未选中 = surfaceContainerHigh 容器 + 1dp outlineVariant 描边，
 * 保证在深浅色下都不会与背景融为一体（不靠颜色单独传达状态：图标 + 形状 morph 同时变化）。
 */
@Composable
private fun InsertTargetToggle(
    positive: Boolean,
    onPositiveChange: (Boolean) -> Unit,
) {
    ToggleButton(
        checked = positive,
        onCheckedChange = onPositiveChange,
        colors = ToggleButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = if (positive) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        icon = {
            Icon(
                if (positive) Icons.Filled.Check else Icons.Outlined.Remove,
                contentDescription = null,
                modifier = Modifier.size(ToggleButtonDefaults.IconSize),
            )
        },
    ) {
        Text(
            if (positive) "正向" else "负向",
            letterSpacing = 0.sp,
            maxLines = 1,
        )
    }
}

/** 分类筛选：PrimaryScrollableTabRow（走主题，edgePadding 20dp），右侧显示当前筛选结果数。 */
@Composable
private fun CategoryTabRow(
    categories: List<TagCategory>,
    selectedIndex: Int,
    resultCount: Int,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val background = MaterialTheme.colorScheme.background
        Box(
            modifier = Modifier
                .weight(1f)
                .drawWithContent {
                    drawContent()
                    // 右边缘渐隐到页面背景：提示分类行可横向滚动，避免文字被硬裁切（视觉 QA P2-5）。
                    // 宽度 = 行尾 edgePadding，滚到最后一个分类时不会压暗文字。
                    val fade = 20.dp.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, background),
                            startX = size.width - fade,
                            endX = size.width,
                        ),
                        topLeft = Offset(size.width - fade, 0f),
                        size = Size(fade, size.height),
                    )
                },
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.fillMaxWidth(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = Spacing.screen,
                divider = {},
            ) {
                CategoryTab(label = "全部", selected = selectedIndex == 0, onClick = { onSelect("all") })
                categories.forEachIndexed { index, category ->
                    CategoryTab(
                        label = category.zh,
                        selected = selectedIndex == index + 1,
                        onClick = { onSelect(category.id) },
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.padding(start = Spacing.xs, end = Spacing.screen),
            shape = AnimaShape.pill,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                "$resultCount 个",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.sp,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            )
        }
    }
}

@Composable
private fun CategoryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        selectedContentColor = MaterialTheme.colorScheme.primary,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        text = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = 0.sp)
        },
    )
}

/** 常用片段：单行横向滚动的 AssistChip（12 个中文名过长，竖排换行会吃掉半屏）。 */
@Composable
private fun SnippetsRow(snippets: List<Snippet>, onSnippet: (Snippet) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs, bottom = Spacing.sm),
    ) {
        SectionLabel(
            text = "常用片段",
            modifier = Modifier.padding(horizontal = Spacing.screen),
            trailing = {
                Text(
                    "${snippets.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.sp,
                )
            },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(snippets, key = { it.id }) { snippet ->
                AssistChip(
                    onClick = { onSnippet(snippet) },
                    label = {
                        Text(
                            snippet.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            letterSpacing = 0.sp,
                        )
                    },
                    shape = AnimaShape.pill,
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = null,
                )
            }
        }
    }
}

/** Tag 列表行：整行可点（按压回弹）+ 右侧添加按钮，两种方式都能插入提示词。 */
@Composable
private fun TagRow(
    category: TagCategory,
    tag: Tag,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onAdd,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .pressScale(interaction)
            .semantics { role = Role.Button },
        shape = AnimaShape.cardSmall,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.xxs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tag.zh,
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                        letterSpacing = 0.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (tag.artist) {
                        Spacer(Modifier.width(Spacing.xs))
                        ArtistBadge()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tag.en,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        category.zh,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        letterSpacing = 0.sp,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.xxs))
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "插入 ${tag.zh}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 画师标记：secondary 色系小胶囊，保持与旧版「画师」文案一致。 */
@Composable
private fun ArtistBadge() {
    Surface(
        shape = AnimaShape.pill,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            "画师",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.sp,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        )
    }
}

// ===========================================================================
// 模板模式
// ===========================================================================

@Composable
private fun TemplatesContent(
    vm: AppViewModel,
    state: UiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onGoGenerate: () -> Unit,
) {
    // P2.5：删除用户模板前二次确认。
    var pendingDelete by remember { mutableStateOf<PromptTemplate?>(null) }
    val templates = remember(state.templates, query) {
        val q = query.trim()
        if (q.isBlank()) {
            state.templates
        } else {
            state.templates.filter { template ->
                template.name.contains(q, ignoreCase = true) ||
                    template.prompt.contains(q, ignoreCase = true) ||
                    (template.note ?: "").contains(q, ignoreCase = true)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AnimaTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.padding(horizontal = Spacing.screen),
            label = "搜索模板",
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            leadingIcon = Icons.Outlined.Search,
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "清除搜索",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                null
            },
        )

        if (templates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (state.templates.isEmpty()) {
                    EmptyState(
                        title = "没有模板",
                        description = "在生成页把提示词存为模板，之后就能在这里一键套用",
                        icon = Icons.Outlined.BookmarkBorder,
                        actionLabel = "去生成",
                        onAction = onGoGenerate,
                    )
                } else {
                    EmptyState(
                        title = "没有模板",
                        description = "没有匹配「$query」的模板，换个关键词试试",
                        icon = Icons.Outlined.Search,
                        actionLabel = "清除搜索",
                        onAction = { onQueryChange("") },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(templates, key = { it.id }) { template ->
                    TemplateCard(
                        state = state,
                        template = template,
                        onApply = { vm.applyTemplate(template) },
                        onDelete = { pendingDelete = template },
                    )
                }
            }
        }
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模板？") },
            text = {
                Text("将删除用户模板「${deleteTarget.name}」，删除后不可恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteTemplate(deleteTarget.id)
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 模板卡：名称 + 应用/删除（标题行）/ note / prompt 预览（3 行截断）/ 参数 meta（独占一行）。 */
@Composable
private fun TemplateCard(
    state: UiState,
    template: PromptTemplate,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    ExpressiveCard(
        onClick = onApply,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen),
        shape = AnimaShape.card,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(Spacing.card),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                template.name,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (template.user) {
                Spacer(Modifier.width(Spacing.sm))
                MyTemplateBadge()
            }
            Spacer(Modifier.width(Spacing.xxs))
            IconButton(onClick = onApply) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = "应用模板「${template.name}」",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (template.user) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除模板「${template.name}」",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (!template.note.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                template.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(Spacing.md))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AnimaShape.cardSmall,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                template.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        MetaText(templateMeta(template, state.presets))
    }
}

/** 用户模板标记。 */
@Composable
private fun MyTemplateBadge() {
    Surface(
        shape = AnimaShape.pill,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            "我的",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.sp,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        )
    }
}

/** 模板参数摘要：模型展示名 · 步数 · CFG · 尺寸。 */
private fun templateMeta(template: PromptTemplate, presets: List<ModelPreset>): String {
    val model = template.model?.let { id -> presets.firstOrNull { it.id == id }?.display ?: id }
    return listOfNotNull(
        model?.let { "模型 $it" },
        template.steps?.let { "${it} 步" },
        template.cfg?.let { "CFG ${formatCfg(it)}" },
        if (template.width != null && template.height != null) "${template.width}×${template.height}" else null,
    ).joinToString(" · ")
}

/**
 * CFG 小工具：4.5 → "4.5"，4.0 → "4"，4.25 → "4.25"。
 *
 * 旧版借用了 GenerateScreen.kt 里的 trimNum；这里自带实现，避免跨屏幕耦合。
 */
private fun formatCfg(value: Double): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

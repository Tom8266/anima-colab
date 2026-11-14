package com.tom.animacolab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.UiState
import com.tom.animacolab.data.PromptTemplate
import com.tom.animacolab.data.Tag
import com.tom.animacolab.data.TagCategory
import com.tom.animacolab.ui.theme.Bg
import com.tom.animacolab.ui.theme.Primary
import com.tom.animacolab.ui.theme.Secondary
import com.tom.animacolab.ui.theme.SurfaceHigh
import com.tom.animacolab.ui.theme.TextPrimary
import com.tom.animacolab.ui.theme.TextSecondary

@Composable
fun ExploreScreen(vm: AppViewModel, state: UiState, onBack: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("提示词速查", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                label = { Text("Tag 速查") },
                shape = RoundedCornerShape(10.dp),
            )
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                label = { Text("模板") },
                shape = RoundedCornerShape(10.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        if (mode == 0) TagsContent(vm, state) else TemplatesContent(vm, state)
    }
}

@Composable
private fun TagsContent(vm: AppViewModel, state: UiState) {
    var query by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf("all") }
    var positive by rememberSaveable { mutableStateOf(true) }
    val categories = state.tagCategories

    val filtered: List<Pair<TagCategory, Tag>> = remember(categories, categoryId, query) {
        val base = if (categoryId == "all") categories else categories.filter { it.id == categoryId }
        val q = query.trim().lowercase()
        base.flatMap { cat ->
            cat.tags.filter { t ->
                q.isBlank() || t.en.lowercase().contains(q) || t.zh.contains(query.trim(), ignoreCase = true)
            }.map { cat to it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("插入位置", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = positive, onClick = { positive = true }, label = { Text("正向") })
            FilterChip(selected = !positive, onClick = { positive = false }, label = { Text("负向") })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            label = { Text("搜索 tag（中/英文）") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = categoryId == "all",
                    onClick = { categoryId = "all" },
                    label = { Text("全部", fontSize = 12.sp) },
                    shape = RoundedCornerShape(10.dp),
                )
            }
            items(categories) { cat ->
                FilterChip(
                    selected = categoryId == cat.id,
                    onClick = { categoryId = cat.id },
                    label = { Text(cat.zh, fontSize = 12.sp) },
                    shape = RoundedCornerShape(10.dp),
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.snippets.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        SectionHeader("常用片段")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.snippets.forEach { snippet ->
                                AssistChip(
                                    onClick = { vm.appendSnippet(snippet.text, positive) },
                                    label = { Text(snippet.name, fontSize = 11.sp) },
                                )
                            }
                        }
                    }
                }
            }
            items(filtered.take(400)) { (cat, tag) ->
                TagRow(cat, tag) { vm.appendTag(tag.en, positive) }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun TagRow(category: TagCategory, tag: Tag, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tag.zh,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tag.artist) {
                    Spacer(Modifier.width(6.dp))
                    Text("画师", style = MaterialTheme.typography.labelSmall, color = Secondary, fontSize = 9.sp)
                }
            }
            MetaText(tag.en)
        }
        Text(category.zh, style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontSize = 10.sp)
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = "插入", tint = Primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TemplatesContent(vm: AppViewModel, state: UiState) {
    var query by rememberSaveable { mutableStateOf("") }
    val templates = state.templates.filter {
        query.isBlank() || it.name.contains(query, true) || it.prompt.contains(query, true) || (it.note ?: "").contains(query, true)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            label = { Text("搜索模板") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        if (templates.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有模板", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(templates, key = { it.id }) { template ->
                    TemplateCard(vm, template)
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun TemplateCard(vm: AppViewModel, template: PromptTemplate) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable { vm.applyTemplate(template) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(template.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.weight(1f))
                if (template.user) {
                    Text("我的", style = MaterialTheme.typography.labelSmall, color = Secondary, fontSize = 10.sp)
                    IconButton(onClick = { vm.deleteTemplate(template.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (!template.note.isNullOrBlank()) {
                Text(template.note, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                template.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary.copy(alpha = 0.86f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaText(
                    listOfNotNull(
                        template.model?.let { "模型 $it" },
                        template.steps?.let { "${it}步" },
                        template.cfg?.let { "CFG ${trimNum(it)}" },
                        if (template.width != null && template.height != null) "${template.width}×${template.height}" else null,
                    ).joinToString(" · "),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.applyTemplate(template) }) { Text("应用") }
            }
        }
    }
}

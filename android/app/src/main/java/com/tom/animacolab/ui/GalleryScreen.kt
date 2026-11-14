package com.tom.animacolab.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.UiState
import com.tom.animacolab.data.HistoryItem
import com.tom.animacolab.ui.theme.Primary
import com.tom.animacolab.ui.theme.SurfaceHigh
import com.tom.animacolab.ui.theme.TextPrimary
import com.tom.animacolab.ui.theme.TextSecondary

@Composable
fun GalleryScreen(vm: AppViewModel, state: UiState, onNavigate: (Int) -> Unit) {
    LaunchedEffect(Unit) {
        if (state.config.configured) vm.refreshHistory()
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("图库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text("${state.history.size} 张", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(1f))
            if (state.historyLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Primary)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = vm::refreshHistory) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = TextSecondary)
            }
        }

        when {
            !state.config.configured -> EmptyHint("还没连接 Colab", "先去设置里粘贴配对信息", "去设置") { onNavigate(3) }

            state.historyLoading && state.history.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }

            state.history.isEmpty() -> EmptyHint("还没有生成记录", "去生成第一张 Anima 图", "去生成") { onNavigate(0) }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.history, key = { it.promptId }) { item ->
                    GalleryCard(vm, item)
                }
            }
        }
    }
}

@Composable
private fun GalleryCard(vm: AppViewModel, item: HistoryItem) {
    val image = item.firstImage ?: return
    val p = item.params
    Card(
        modifier = Modifier.clickable { vm.openViewer(item, image) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceHigh),
    ) {
        Column {
            Box {
                NetworkImage(
                    vm,
                    image,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    targetPx = 512,
                )
                if (item.images.size > 1) {
                    Text(
                        "+${item.images.size - 1}",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }
            Column(Modifier.padding(8.dp)) {
                MetaText(
                    listOfNotNull(
                        p?.steps?.takeIf { it > 0 }?.let { "${it}步" },
                        p?.width?.takeIf { it > 0 }?.let { "${it}×${p.height}" },
                        p?.seed?.let { "seed $it" },
                    ).joinToString(" · "),
                )
                if (item.created > 0) {
                    Text(
                        vm.formatCreated(item.created),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

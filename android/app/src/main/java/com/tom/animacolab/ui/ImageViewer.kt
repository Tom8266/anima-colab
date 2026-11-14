package com.tom.animacolab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tom.animacolab.AppViewModel
import com.tom.animacolab.UiState

@Composable
fun ImageViewerDialog(vm: AppViewModel, state: UiState) {
    val item = state.viewerItem ?: return
    val images = item.images.ifEmpty { listOfNotNull(item.firstImage) }
    if (images.isEmpty()) {
        vm.closeViewer()
        return
    }
    val pager = rememberPagerState(pageCount = { images.size })
    val p = item.params

    Dialog(
        onDismissRequest = vm::closeViewer,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val ref = images.getOrNull(page)
                if (ref != null) {
                    NetworkImage(
                        vm,
                        ref,
                        Modifier.fillMaxSize(),
                        targetPx = 2048,
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::closeViewer) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
                Text(
                    vm.formatCreated(item.created),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (images.size > 1) {
                    Text(
                        "${pager.currentPage + 1}/${images.size}",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (p != null) {
                    Text(
                        listOfNotNull(
                            p.steps.takeIf { it > 0 }?.let { "${it}步" },
                            p.cfg.takeIf { it > 0 }?.let { "CFG ${trimNum(it)}" },
                            p.sampler.takeIf { it.isNotBlank() },
                            "${p.width}×${p.height}",
                            "seed ${p.seed}",
                        ).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ref = images.getOrNull(pager.currentPage)
                    OutlinedButton(
                        onClick = {
                            if (ref != null) vm.saveImage(item, ref)
                            vm.closeViewer()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            if (ref != null) vm.shareImage(item, ref)
                            vm.closeViewer()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("分享", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            vm.reuseParams(item)
                            vm.closeViewer()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("填回参数", fontSize = 12.sp) }
                }
            }
        }
    }
}

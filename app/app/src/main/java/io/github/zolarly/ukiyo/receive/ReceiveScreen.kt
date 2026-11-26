package io.github.zolarly.ukiyo.receive

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zolarly.ukiyo.formatSize
import io.github.zolarly.ukiyo.net.Fix
import io.github.zolarly.ukiyo.net.ShareLink
import io.github.zolarly.ukiyo.net.describeError
import io.github.zolarly.ukiyo.ui.GhostButton
import io.github.zolarly.ukiyo.ui.GlowButton
import io.github.zolarly.ukiyo.ui.HorizonRadius
import io.github.zolarly.ukiyo.ui.Incense
import io.github.zolarly.ukiyo.ui.TextAction
import io.github.zolarly.ukiyo.ui.theme.Abyss
import io.github.zolarly.ukiyo.ui.theme.Dim
import io.github.zolarly.ukiyo.ui.theme.Ember
import io.github.zolarly.ukiyo.ui.theme.Lavender
import io.github.zolarly.ukiyo.ui.theme.Moon
import io.github.zolarly.ukiyo.ui.theme.Tide

/** 接收页。没有链接时整页只有黑洞，点它就粘贴链接；有链接时显示这次分享。 */
@Composable
fun ReceiveTab(link: ShareLink?, onPaste: () -> Unit, onClose: () -> Unit) {
    if (link == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(HorizonRadius * 2)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "粘贴链接", role = Role.Button, onClick = onPaste)
                    .semantics { contentDescription = "粘贴好友发来的链接" },
            )
        }
    } else {
        ReceiveScreen(link, onClose)
    }
}

@Composable
fun ReceiveScreen(link: ShareLink, onClose: () -> Unit, vm: ReceiveViewModel = viewModel()) {
    DisposableEffect(link) {
        vm.open(link)
        onDispose { vm.close() }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        ReceiveState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Tide)
        }
        is ReceiveState.Failed -> Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val info = describeError(s.error)
            Text(info.message, color = Ember, style = MaterialTheme.typography.titleLarge)
            if (info.fix == Fix.RETRY) GlowButton("重试", vm::retry, Modifier.fillMaxWidth())
            GhostButton("关闭", onClose, Modifier.fillMaxWidth())
        }
        is ReceiveState.Ready -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 28.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${s.items.size} 个文件", style = MaterialTheme.typography.headlineMedium, color = Moon)
                            Text("共 ${formatSize(s.items.sumOf { it.file.size })}", style = MaterialTheme.typography.bodyMedium, color = Dim)
                        }
                        TextAction("关闭", onClose, Dim)
                    }
                    Incense(s.info.expiresAt)
                    if (!s.opened) {
                        s.info.viewsLeft?.let {
                            Text("还能打开 $it 次。点「查看」会用掉 1 次，然后下载原图", style = MaterialTheme.typography.bodyMedium, color = Dim)
                        }
                        GlowButton("查看", vm::view, Modifier.fillMaxWidth().padding(top = 6.dp), colors = listOf(Tide, Lavender))
                    }
                    if (s.opened && s.items.all { it.done }) {
                        if (s.items.all { it.saved }) {
                            Text("已全部存进相册，App 里没有留副本", style = MaterialTheme.typography.bodyLarge, color = Tide)
                        } else {
                            GlowButton("全部存进相册", vm::saveAll, Modifier.fillMaxWidth(), colors = listOf(Tide, Lavender))
                        }
                    }
                }
            }
            items(s.items) { Tile(it, s.opened) }
        }
    }
}

/** 一个文件的方块。下载时浅葱色的水从底部涨上来，下完显示缩略图。 */
@Composable
private fun Tile(item: Item, opened: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    val progress = if (item.file.size > 0) (item.received.toFloat() / item.file.size).coerceAtMost(1f) else 0f
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Moon.copy(alpha = 0.06f))
            .border(1.dp, Moon.copy(alpha = 0.12f), shape)
            .drawBehind {
                if (opened && !item.done) {
                    val h = size.height * progress
                    drawRect(Tide.copy(alpha = 0.35f), Offset(0f, size.height - h), Size(size.width, h))
                }
            },
    ) {
        val thumb = item.thumb
        if (thumb != null) {
            Image(thumb.asImageBitmap(), contentDescription = item.file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.padding(10.dp)) {
                Text(item.file.name, style = MaterialTheme.typography.bodySmall, color = Moon, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatSize(item.file.size), style = MaterialTheme.typography.bodySmall, color = Dim)
            }
        }
        if (item.saved) {
            Text(
                "已存",
                style = MaterialTheme.typography.labelSmall,
                color = Abyss,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Tide).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

package io.github.zolarly.ukiyo.receive

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zolarly.ukiyo.formatRemaining
import io.github.zolarly.ukiyo.formatSize
import io.github.zolarly.ukiyo.net.Fix
import io.github.zolarly.ukiyo.net.ShareLink
import io.github.zolarly.ukiyo.net.describeError
import kotlinx.coroutines.delay

@Composable
fun ReceiveScreen(link: ShareLink, vm: ReceiveViewModel = viewModel()) {
    DisposableEffect(link) {
        vm.open(link)
        onDispose { vm.close() }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = state) {
            ReceiveState.Loading -> CircularProgressIndicator()
            is ReceiveState.Failed -> {
                val info = describeError(s.error)
                Text(info.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                if (info.fix == Fix.RETRY) {
                    Button(onClick = vm::retry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                }
            }
            is ReceiveState.Ready -> {
                Text(
                    "${s.items.size} 个文件 · ${formatSize(s.items.sumOf { it.file.size })}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Countdown(s.info.expiresAt)
                if (!s.opened) {
                    s.info.viewsLeft?.let { Text("剩余 $it 次查看", style = MaterialTheme.typography.bodyMedium) }
                    Button(onClick = vm::view, modifier = Modifier.fillMaxWidth()) { Text("查看") }
                    if (s.info.viewsLeft != null) {
                        Text("点「查看」会用掉 1 次查看，然后下载原图", style = MaterialTheme.typography.bodySmall)
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(s.items) { ItemRow(it, s.opened) }
                }
                if (s.opened && s.items.all { it.done }) {
                    if (s.items.all { it.saved }) {
                        Text("已全部保存到相册，App 里没有留副本", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Button(onClick = vm::saveAll, modifier = Modifier.fillMaxWidth()) { Text("全部保存到相册") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: Item, opened: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp)) {
            item.thumb?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatSize(item.file.size), style = MaterialTheme.typography.bodySmall)
            if (item.saved) {
                Text("已保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (opened && !item.done) {
                val progress = if (item.file.size > 0) item.received.toFloat() / item.file.size else 0f
                LinearProgressIndicator(
                    progress = { progress.coerceAtMost(1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun Countdown(expiresAt: Long) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    Text(formatRemaining(expiresAt - now), style = MaterialTheme.typography.bodyMedium)
}

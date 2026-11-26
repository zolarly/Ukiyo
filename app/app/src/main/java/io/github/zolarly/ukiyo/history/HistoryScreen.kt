package io.github.zolarly.ukiyo.history

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.zolarly.ukiyo.net.Api
import io.github.zolarly.ukiyo.net.Fix
import io.github.zolarly.ukiyo.net.MyShare
import io.github.zolarly.ukiyo.net.describeError
import io.github.zolarly.ukiyo.receive.Countdown
import io.github.zolarly.ukiyo.send.copyText
import io.github.zolarly.ukiyo.send.shareText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(onLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<MyShare>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirm by remember { mutableStateOf<MyShare?>(null) }

    LaunchedEffect(reload) {
        error = null
        try {
            list = Api.myShares()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("我发出的", style = MaterialTheme.typography.titleMedium)
        val e = error
        val l = list
        when {
            e != null -> {
                val info = describeError(e)
                Text(info.message, color = MaterialTheme.colorScheme.error)
                when (info.fix) {
                    Fix.LOGIN -> Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("去登录") }
                    Fix.RETRY -> Button(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                    Fix.NONE -> {}
                }
            }
            l == null -> CircularProgressIndicator()
            l.isEmpty() -> Text("24 小时内没有发出过文件")
            else -> LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(l, key = { it.token }) { s ->
                    Column {
                        Text(s.url, style = MaterialTheme.typography.bodySmall)
                        val views = if (s.maxViews > 0) "${s.viewsUsed}/${s.maxViews}" else "${s.viewsUsed}"
                        Text("${s.count} 个文件 · 已查看 $views 次")
                        Countdown(s.expiresAt)
                        Row {
                            TextButton(onClick = { context.copyText(s.url) }) { Text("复制") }
                            TextButton(onClick = { context.shareText(s.url) }) { Text("分享") }
                            TextButton(onClick = { confirm = s }) { Text("提前销毁") }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("提前销毁？") },
            text = { Text("服务器上的文件会立刻删除，对方再打开链接会显示「已销毁」，不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        try {
                            Api.deleteShare(s.token)
                            reload++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, describeError(e).message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("销毁") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

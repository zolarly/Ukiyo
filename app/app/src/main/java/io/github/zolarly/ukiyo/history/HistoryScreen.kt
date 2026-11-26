package io.github.zolarly.ukiyo.history

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zolarly.ukiyo.Prefs
import io.github.zolarly.ukiyo.net.Api
import io.github.zolarly.ukiyo.net.Fix
import io.github.zolarly.ukiyo.net.MyShare
import io.github.zolarly.ukiyo.net.describeError
import io.github.zolarly.ukiyo.send.copyText
import io.github.zolarly.ukiyo.send.shareText
import io.github.zolarly.ukiyo.settings.ServerDialog
import io.github.zolarly.ukiyo.ui.GlowButton
import io.github.zolarly.ukiyo.ui.Incense
import io.github.zolarly.ukiyo.ui.PageHeader
import io.github.zolarly.ukiyo.ui.TextAction
import io.github.zolarly.ukiyo.ui.panel
import io.github.zolarly.ukiyo.ui.theme.Dim
import io.github.zolarly.ukiyo.ui.theme.Ember
import io.github.zolarly.ukiyo.ui.theme.Moon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 「我的」页：账号、服务器，以及 24 小时内发出的分享。[visible] 变成 true 时重新拉取列表。 */
@Composable
fun MineScreen(prefs: Prefs, account: String?, visible: Boolean, onLogin: () -> Unit, onAccountChange: () -> Unit) {
    var editServer by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(account ?: "未登录", "服务器 ${prefs.server.substringAfter("://")}")
        Row(Modifier.padding(bottom = 8.dp)) {
            TextAction("更换服务器", { editServer = true })
            if (account != null) {
                TextAction("退出登录", {
                    Api.logout()
                    onAccountChange()
                }, Dim)
            }
        }
        if (account == null) {
            Spacer(Modifier.weight(1f))
            Text("登录后，这里会列出你 24 小时内发出的分享", style = MaterialTheme.typography.bodyLarge, color = Dim)
            GlowButton("登录或注册", onLogin, Modifier.fillMaxWidth().padding(vertical = 24.dp))
        } else {
            Shares(account, visible, onLogin)
        }
    }
    if (editServer) {
        ServerDialog(
            prefs,
            onDone = {
                editServer = false
                onAccountChange()
            },
            onDismiss = { editServer = false },
        )
    }
}

@Composable
private fun Shares(account: String, visible: Boolean, onLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var list by remember(account) { mutableStateOf<List<MyShare>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirm by remember { mutableStateOf<MyShare?>(null) }

    LaunchedEffect(reload, account, visible) {
        if (!visible) return@LaunchedEffect
        error = null
        try {
            list = Api.myShares()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }

    Text("我发出的", style = MaterialTheme.typography.titleLarge, color = Moon, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
    val e = error
    val l = list
    when {
        e != null -> {
            val info = describeError(e)
            Text(info.message, color = Ember, style = MaterialTheme.typography.bodyLarge)
            when (info.fix) {
                Fix.LOGIN -> GlowButton("去登录", onLogin, Modifier.fillMaxWidth().padding(top = 16.dp))
                Fix.RETRY -> GlowButton("重试", { reload++ }, Modifier.fillMaxWidth().padding(top = 16.dp))
                Fix.NONE -> {}
            }
        }
        l == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        l.isEmpty() -> Text("24 小时内没有发出过文件。去「发送」页点一下月亮试试", style = MaterialTheme.typography.bodyLarge, color = Dim)
        else -> LazyColumn(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(l, key = { it.token }) { s ->
                Column(Modifier.fillMaxWidth().panel(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${s.count} 个文件", style = MaterialTheme.typography.titleLarge, color = Moon, modifier = Modifier.weight(1f))
                        val views = if (s.maxViews > 0) "${s.viewsUsed}/${s.maxViews}" else "${s.viewsUsed}"
                        Text("已打开 $views 次", style = MaterialTheme.typography.bodyMedium, color = Dim)
                    }
                    Incense(s.expiresAt)
                    Text(s.url, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 2.dp)) {
                        TextAction("复制", { context.copyText(s.url) })
                        TextAction("分享", { context.shareText(s.url) })
                        Spacer(Modifier.weight(1f))
                        TextAction("提前销毁", { confirm = s }, Ember)
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
                }) { Text("销毁", color = Ember) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

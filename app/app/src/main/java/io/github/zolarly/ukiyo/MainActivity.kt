package io.github.zolarly.ukiyo

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import io.github.zolarly.ukiyo.history.HistoryScreen
import io.github.zolarly.ukiyo.login.LoginScreen
import io.github.zolarly.ukiyo.net.Api
import io.github.zolarly.ukiyo.net.ShareLink
import io.github.zolarly.ukiyo.net.findShareLink
import io.github.zolarly.ukiyo.receive.ReceiveScreen
import io.github.zolarly.ukiyo.send.SendScreen
import io.github.zolarly.ukiyo.settings.SettingsScreen
import io.github.zolarly.ukiyo.ui.theme.UkiyoTheme
import java.io.File

// 顶层变量相当于 Java 的静态字段：进程活着就一直在，只在进程新启动时清理一次缓存，
// Activity 重建（比如切换深色模式）不会误删正在下载的文件。
private var receiveCacheCleared = false

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object History : Screen
    data class Send(val uris: List<Uri>) : Screen
    data class Receive(val link: ShareLink) : Screen
    data class Login(val then: Screen) : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)
    private val prefs by lazy { Prefs(this) }

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) openSend(uris)
    }

    private fun pickMedia() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    private fun openSend(uris: List<Uri>) {
        if (Transfers.busy) {
            Toast.makeText(this, "还有文件正在上传，完成后再发送", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Screen.Send(uris)
        screen = if (prefs.loggedIn) send else Screen.Login(then = send)
    }

    private fun pasteLink() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val link = text?.let(::findShareLink)
        if (link == null) {
            Toast.makeText(this, "剪贴板里没有 Ukiyo 链接", Toast.LENGTH_SHORT).show()
        } else {
            openReceive(link)
        }
    }

    private fun openReceive(link: ShareLink) {
        screen = Screen.Receive(link)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Api.init(prefs)
        if (!receiveCacheCleared) {
            File(cacheDir, "receive").deleteRecursively()
            receiveCacheCleared = true
        }
        setContent {
            UkiyoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Box(Modifier.padding(padding).padding(16.dp)) {
                        when (val s = screen) {
                            Screen.Home -> HomeScreen(
                                onSend = ::pickMedia,
                                onReceive = ::pasteLink,
                                onHistory = { screen = if (prefs.loggedIn) Screen.History else Screen.Login(then = Screen.History) },
                                onSettings = { screen = Screen.Settings },
                            )
                            Screen.History -> HistoryScreen(onLogin = { screen = Screen.Login(then = Screen.History) })
                            Screen.Settings -> SettingsScreen(prefs, onDone = { screen = Screen.Home })
                            is Screen.Send -> SendScreen(s.uris, onLogin = { screen = Screen.Login(then = s) })
                            is Screen.Receive -> ReceiveScreen(s.link)
                            is Screen.Login -> LoginScreen(prefs, onDone = { screen = s.then })
                        }
                    }
                }
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let(::findShareLink)?.let(::openReceive)
            return
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) openSend(uris)
    }
}

@Composable
fun HomeScreen(onSend: () -> Unit, onReceive: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("浮世", style = MaterialTheme.typography.headlineMedium)
        Text("原图互传，24 小时后销毁", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onSend, modifier = Modifier.fillMaxWidth()) { Text("发送照片") }
        OutlinedButton(onClick = onReceive, modifier = Modifier.fillMaxWidth()) { Text("接收（粘贴链接）") }
        TextButton(onClick = onHistory) { Text("我发出的") }
        TextButton(onClick = onSettings) { Text("设置") }
    }
}

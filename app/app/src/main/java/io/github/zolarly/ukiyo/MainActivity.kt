package io.github.zolarly.ukiyo

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zolarly.ukiyo.history.MineScreen
import io.github.zolarly.ukiyo.login.LoginScreen
import io.github.zolarly.ukiyo.net.Api
import io.github.zolarly.ukiyo.net.ShareLink
import io.github.zolarly.ukiyo.net.findShareLink
import io.github.zolarly.ukiyo.receive.ReceiveTab
import io.github.zolarly.ukiyo.send.SendScreen
import io.github.zolarly.ukiyo.send.UploadState
import io.github.zolarly.ukiyo.ui.SealBar
import io.github.zolarly.ukiyo.ui.Sky
import io.github.zolarly.ukiyo.ui.pageMotion
import io.github.zolarly.ukiyo.ui.theme.UkiyoTheme
import java.io.File

// 顶层变量相当于 Java 的静态字段：进程活着就一直在，只在进程新启动时清理一次缓存，
// Activity 重建（比如切换深色模式）不会误删正在下载的文件。
private var receiveCacheCleared = false

private const val SEND = 0
private const val RECEIVE = 1
private const val MINE = 2

class MainActivity : ComponentActivity() {
    private val prefs by lazy { Prefs(this) }
    private var tab by mutableIntStateOf(SEND)
    private var sending by mutableStateOf<List<Uri>>(emptyList())
    private var receiving by mutableStateOf<ShareLink?>(null)
    private var account by mutableStateOf<String?>(null)

    // 不为 null 时盖上登录页；登录成功后执行它。
    private var afterLogin by mutableStateOf<(() -> Unit)?>(null)

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
        val show = {
            sending = uris
            tab = SEND
        }
        if (prefs.loggedIn) show() else afterLogin = show
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
        receiving = link
        tab = RECEIVE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        Api.init(prefs)
        account = prefs.username
        if (!receiveCacheCleared) {
            File(cacheDir, "receive").deleteRecursively()
            receiveCacheCleared = true
        }
        setContent {
            UkiyoTheme {
                val pager = rememberPagerState(initialPage = tab) { 3 }
                // tab 和翻页互相同步：点底栏或收到链接时改 tab，翻页停稳后再写回 tab。
                LaunchedEffect(tab) { pager.animateScrollToPage(tab) }
                LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { tab = it } }
                val upload by Transfers.upload.collectAsStateWithLifecycle()
                val loginOpen = afterLogin != null
                val blur by animateDpAsState(if (loginOpen) 18.dp else 0.dp, label = "blur")

                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().blur(blur)) {
                        Sky(position = { pager.currentPage + pager.currentPageOffsetFraction })
                        // 三页都常驻（beyondViewportPageCount = 2），翻走时不会销毁接收页、打断下载。
                        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 2) { page ->
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .pageMotion(pager, page)
                                    .statusBarsPadding()
                                    .navigationBarsPadding()
                                    .padding(start = 24.dp, end = 24.dp, bottom = 100.dp),
                            ) {
                                when (page) {
                                    SEND -> SendScreen(
                                        uris = sending,
                                        onPick = ::pickMedia,
                                        onLogin = { afterLogin = {} },
                                    )
                                    RECEIVE -> ReceiveTab(receiving, onPaste = ::pasteLink, onClose = { receiving = null })
                                    else -> MineScreen(
                                        prefs,
                                        account,
                                        visible = pager.settledPage == MINE,
                                        onLogin = { afterLogin = {} },
                                        onAccountChange = { account = prefs.username },
                                    )
                                }
                            }
                        }
                        SealBar(
                            pager,
                            onSelect = { tab = it },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
                        )
                    }
                    AnimatedVisibility(
                        loginOpen,
                        enter = fadeIn() + slideInVertically { it / 4 },
                        exit = fadeOut() + slideOutVertically { it / 4 },
                    ) {
                        LoginScreen(
                            prefs,
                            onDone = {
                                val then = afterLogin
                                afterLogin = null
                                account = prefs.username
                                then?.invoke()
                            },
                            onCancel = { afterLogin = null },
                        )
                    }
                }

                val uploading = upload is UploadState.Uploading
                BackHandler(enabled = loginOpen || tab != SEND || (sending.isNotEmpty() && !uploading)) {
                    when {
                        loginOpen -> afterLogin = null
                        tab == RECEIVE && receiving != null -> receiving = null
                        tab != SEND -> tab = SEND
                        else -> sending = emptyList()
                    }
                }
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

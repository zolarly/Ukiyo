package io.github.zolarly.ukiyo.send

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zolarly.ukiyo.formatSize
import io.github.zolarly.ukiyo.net.Fix
import io.github.zolarly.ukiyo.net.describeError
import io.github.zolarly.ukiyo.ui.Choices
import io.github.zolarly.ukiyo.ui.GhostButton
import io.github.zolarly.ukiyo.ui.GlowButton
import io.github.zolarly.ukiyo.ui.MoonOrb
import io.github.zolarly.ukiyo.ui.TextAction
import io.github.zolarly.ukiyo.ui.panel
import io.github.zolarly.ukiyo.ui.theme.Abyss
import io.github.zolarly.ukiyo.ui.theme.Dim
import io.github.zolarly.ukiyo.ui.theme.Ember
import io.github.zolarly.ukiyo.ui.theme.Gold
import io.github.zolarly.ukiyo.ui.theme.Moon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val viewOptions = listOf(1 to "1 次", 3 to "3 次", 5 to "5 次", 0 to "不限")

/** 发送页。还没选文件时整页只有一轮月亮；选好之后月亮缩到上方，上传时里面涨潮。 */
@Composable
fun SendScreen(uris: List<Uri>, onPick: () -> Unit, onLogin: () -> Unit, vm: SendViewModel = viewModel()) {
    LaunchedEffect(uris) { if (uris.isNotEmpty()) vm.start(uris) }
    val files by vm.files.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.upload() }
    val startUpload = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.upload()
        }
    }
    val picking = uris.isEmpty() && state !is UploadState.Uploading
    val settle = spring<Dp>(dampingRatio = 0.7f, stiffness = 160f)
    val moonSize by animateDpAsState(if (picking) 280.dp else 184.dp, settle, label = "moon")
    val tide = when (val s = state) {
        is UploadState.Uploading -> if (s.total > 0) (s.sent.toFloat() / s.total).coerceAtMost(1f) else 0f
        is UploadState.Done -> 1f
        else -> 0f
    }

    // 选文件前月亮正好在页面中心，背景里的光束就照在它上面
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap by animateDpAsState(if (picking) (maxHeight - 280.dp) / 2 else 12.dp, settle, label = "gap")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(gap))
            MoonOrb(
                tide = if (picking) 0f else tide,
                modifier = Modifier.size(moonSize).align(Alignment.CenterHorizontally),
                onClick = if (picking) onPick else null,
                onClickLabel = "选择照片",
            ) {
                val s = state
                when {
                    picking -> {}
                    s is UploadState.Uploading -> MoonLabel("${(tide * 100).toInt()}%", "${formatSize(s.sent)} / ${formatSize(s.total)}")
                    s is UploadState.Done -> MoonLabel("好了", "链接在下面")
                    else -> MoonLabel("${files.size}", "个文件")
                }
            }
            AnimatedVisibility(!picking, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Details(files, state, startUpload, onPick, onLogin, vm)
            }
        }
    }
}

@Composable
private fun MoonLabel(big: String, small: String) {
    // 星球有亮面也有暗面，字用浅色加一层暗影，两边都看得清
    val shade = Shadow(Abyss, blurRadius = 16f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(big, style = MaterialTheme.typography.headlineMedium.copy(shadow = shade), color = Moon)
        Text(small, style = MaterialTheme.typography.bodySmall.copy(shadow = shade), color = Moon.copy(alpha = 0.8f))
    }
}

@Composable
private fun Details(
    files: List<LocalFile>,
    state: UploadState,
    startUpload: () -> Unit,
    onPick: () -> Unit,
    onLogin: () -> Unit,
    vm: SendViewModel,
) {
    val context = LocalContext.current
    val editable = state is UploadState.Idle || state is UploadState.Failed
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("共 ${formatSize(files.sumOf { it.size })}", style = MaterialTheme.typography.titleLarge, color = Moon, modifier = Modifier.weight(1f))
            if (editable || state is UploadState.Done) {
                TextAction(if (state is UploadState.Done) "再发一批" else "重新选择", onPick)
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            itemsIndexed(files, key = { _, f -> f.uri }) { i, f -> Print(f, i) }
        }
        if (state !is UploadState.Done) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("对方能打开几次", style = MaterialTheme.typography.titleMedium, color = Moon)
                Choices(viewOptions, vm.maxViews, { vm.maxViews = it }, enabled = editable)
            }
        }
        when (state) {
            UploadState.Idle -> GlowButton("上传并生成链接", startUpload, Modifier.fillMaxWidth(), enabled = files.isNotEmpty())
            is UploadState.Uploading -> Text(
                "可以离开 App，上传会在后台继续",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            is UploadState.Done -> {
                val limit = if (state.share.maxViews > 0) "，限看 ${state.share.maxViews} 次" else ""
                Column(Modifier.fillMaxWidth().panel(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("24 小时后销毁$limit", style = MaterialTheme.typography.bodyMedium, color = Gold)
                    SelectionContainer { Text(state.share.url, style = MaterialTheme.typography.bodyLarge, color = Moon) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlowButton("复制链接", { context.copyText(state.share.url) }, Modifier.weight(1f))
                    GhostButton("分享到…", { context.shareText(state.share.url) }, Modifier.weight(1f))
                }
            }
            is UploadState.Failed -> {
                val info = describeError(state.error)
                Text(info.message, color = Ember, style = MaterialTheme.typography.bodyLarge)
                if (info.fix == Fix.LOGIN) GhostButton("去登录", onLogin, Modifier.fillMaxWidth())
                if (info.fix != Fix.NONE) GlowButton("重试", startUpload, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 一张小照片，像随手摊在桌上的相纸，左右交替微微倾斜。缩略图由系统生成，只读不改原文件。 */
@Composable
private fun Print(file: LocalFile, index: Int) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, file.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(file.uri, Size(360, 360), null).asImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(width = 96.dp, height = 124.dp)
            .graphicsLayer { rotationZ = if (index % 2 == 0) -3f else 2.5f }
            .clip(shape)
            .background(Moon.copy(alpha = 0.08f))
            .border(1.dp, Moon.copy(alpha = 0.16f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(bitmap, contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                file.name,
                style = MaterialTheme.typography.bodySmall,
                color = Dim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

fun Context.copyText(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ukiyo", text))
    // Android 13 起系统自己会弹出"已复制"的提示
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
}

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null))
}

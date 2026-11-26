package io.github.zolarly.ukiyo.send

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zolarly.ukiyo.formatSize
import io.github.zolarly.ukiyo.net.Fix
import io.github.zolarly.ukiyo.net.describeError

private val viewOptions = listOf(1 to "1 次", 3 to "3 次", 5 to "5 次", 0 to "不限")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(uris: List<Uri>, onLogin: () -> Unit, vm: SendViewModel = viewModel()) {
    LaunchedEffect(uris) { vm.start(uris) }
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("发送 ${files.size} 个文件 · ${formatSize(files.sumOf { it.size })}", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(files) { f ->
                Text("${f.name} · ${formatSize(f.size)}", Modifier.padding(vertical = 4.dp))
            }
        }
        Text("查看次数", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            viewOptions.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = vm.maxViews == value,
                    onClick = { vm.maxViews = value },
                    shape = SegmentedButtonDefaults.itemShape(i, viewOptions.size),
                    enabled = state is UploadState.Idle || state is UploadState.Failed,
                ) { Text(label) }
            }
        }
        when (val s = state) {
            UploadState.Idle -> Button(
                onClick = startUpload,
                enabled = files.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("上传并生成链接") }
            is UploadState.Uploading -> {
                val progress = if (s.total > 0) (s.sent.toFloat() / s.total).coerceAtMost(1f) else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("上传中 ${(progress * 100).toInt()}% · ${formatSize(s.sent)} / ${formatSize(s.total)}")
            }
            is UploadState.Done -> {
                val limit = if (s.share.maxViews > 0) "，限看 ${s.share.maxViews} 次" else ""
                Text("链接（24 小时后销毁$limit）：", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(s.share.url) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { context.copyText(s.share.url) }) { Text("复制") }
                    OutlinedButton(onClick = { context.shareText(s.share.url) }) { Text("分享到…") }
                }
            }
            is UploadState.Failed -> {
                val info = describeError(s.error)
                Text(info.message, color = MaterialTheme.colorScheme.error)
                if (info.fix == Fix.LOGIN) {
                    OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("去登录") }
                }
                if (info.fix != Fix.NONE) {
                    Button(onClick = startUpload, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                }
            }
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

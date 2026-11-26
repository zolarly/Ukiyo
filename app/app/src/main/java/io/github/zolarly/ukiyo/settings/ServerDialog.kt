package io.github.zolarly.ukiyo.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.zolarly.ukiyo.Prefs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 修改服务器地址。换了服务器会自动退出登录（见 Prefs.server）。 */
@Composable
fun ServerDialog(prefs: Prefs, onDone: () -> Unit, onDismiss: () -> Unit) {
    var server by remember { mutableStateOf(prefs.server) }
    val valid = server.trim().toHttpUrlOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器地址") },
        text = {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                isError = !valid,
                supportingText = { Text(if (valid) "换服务器后需要重新登录" else "格式如 http://192.168.1.100:8090") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    prefs.server = server
                    onDone()
                },
                enabled = valid,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

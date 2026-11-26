package io.github.zolarly.ukiyo.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zolarly.ukiyo.Prefs
import io.github.zolarly.ukiyo.net.Api
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun SettingsScreen(prefs: Prefs, onDone: () -> Unit) {
    var server by remember { mutableStateOf(prefs.server) }
    var username by remember { mutableStateOf(prefs.username) }
    val valid = server.trim().toHttpUrlOrNull() != null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("服务器地址") },
            isError = !valid,
            supportingText = { if (!valid) Text("格式如 http://192.168.1.100:8090") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                prefs.server = server
                onDone()
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
        username?.let { name ->
            Text("当前账号：$name")
            OutlinedButton(
                onClick = {
                    Api.logout()
                    username = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("退出登录") }
        }
    }
}

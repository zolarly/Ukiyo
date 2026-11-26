package io.github.zolarly.ukiyo.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.zolarly.ukiyo.Prefs
import io.github.zolarly.ukiyo.net.Api
import java.io.IOException
import kotlinx.coroutines.launch

private val usernameRe = Regex("[A-Za-z0-9_]{3,20}")

@Composable
fun LoginScreen(prefs: Prefs, onDone: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = usernameRe.matches(username) && password.length >= 8

    fun submit(register: Boolean) {
        busy = true
        error = null
        scope.launch {
            try {
                Api.login(register, username, password)
                onDone()
            } catch (e: IOException) {
                error = e.message ?: "网络问题"
            }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("登录", style = MaterialTheme.typography.headlineSmall)
        Text("服务器：${prefs.server}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim() },
            label = { Text("用户名") },
            supportingText = { Text("3–20 位字母、数字或下划线") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            supportingText = { Text("至少 8 位") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { submit(register = false) }, enabled = valid && !busy) { Text("登录") }
            OutlinedButton(onClick = { submit(register = true) }, enabled = valid && !busy) { Text("注册") }
        }
    }
}

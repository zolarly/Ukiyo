package io.github.zolarly.ukiyo.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import io.github.zolarly.ukiyo.ui.GhostButton
import io.github.zolarly.ukiyo.ui.GlowButton
import io.github.zolarly.ukiyo.ui.TextAction
import io.github.zolarly.ukiyo.ui.theme.Abyss
import io.github.zolarly.ukiyo.ui.theme.Dim
import io.github.zolarly.ukiyo.ui.theme.Ember
import io.github.zolarly.ukiyo.ui.theme.Moon
import java.io.IOException
import kotlinx.coroutines.launch

private val usernameRe = Regex("[A-Za-z0-9_]{3,20}")

@Composable
fun LoginScreen(prefs: Prefs, onDone: () -> Unit, onCancel: () -> Unit) {
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

    val field = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxSize()
            .background(Abyss.copy(alpha = 0.82f))
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextAction("返回", onCancel, Dim)
        Spacer(Modifier.height(48.dp))
        Text("登录", style = MaterialTheme.typography.displayMedium, color = Moon)
        Text("发送需要账号，接收不需要。服务器 ${prefs.server.substringAfter("://")}", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim() },
            label = { Text("用户名") },
            supportingText = { Text("3–20 位字母、数字或下划线") },
            singleLine = true,
            shape = field,
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
            shape = field,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = Ember) }
        GlowButton("登录", { submit(register = false) }, Modifier.fillMaxWidth(), enabled = valid && !busy)
        GhostButton("注册新账号", { submit(register = true) }, Modifier.fillMaxWidth(), enabled = valid && !busy)
        Spacer(Modifier.height(24.dp))
    }
}

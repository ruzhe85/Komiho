package app.mihonsy.komga.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaAuthType
import app.mihonsy.komga.data.KomgaConnection
import app.mihonsy.komga.data.KomgaPreferences
import kotlinx.coroutines.launch

/**
 * Komga 服务器连接设置页（M1）。
 * 支持 API Key / 账号密码两种认证；连接成功后进入主界面。
 */
class KomgaConnectActivity : KomgaBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = KomgaPreferences(applicationContext)
        // Komiho: if a connection was already saved, skip the setup screen and
        // go straight to the main tabs (user reported the app always showing
        // the connect screen on relaunch).
        if (prefs.hasConnection() && savedInstanceState == null) {
            startActivity(
                android.content.Intent(this, KomgaMainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            finish()
            return
        }
        setContent { KomihoTheme { KomgaConnectScreen() } }
    }
}

@Composable
private fun KomgaConnectScreen() {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }

    var baseUrl by remember { mutableStateOf(prefs.baseUrl) }
    var authType by remember { mutableStateOf(prefs.authType) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var username by remember { mutableStateOf(prefs.username) }
    var password by remember { mutableStateOf(prefs.password) }

    var testing by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doConnect(testOnly: Boolean) {
        val conn = KomgaConnection(
            baseUrl = baseUrl.trim(),
            authType = authType,
            apiKey = apiKey.trim(),
            username = username.trim(),
            password = password,
        )
        val client = KomgaApiClient(conn)
        scope.launch {
            if (testOnly) testing = true else connecting = true
            // testConnection() already returns Result<Unit> (never throws), so
            // unwrap it directly — wrapping it in another runCatching would
            // always succeed and mask the failure.
            val result = client.testConnection()
            if (testOnly) testing = false else connecting = false
            val ok = result.isSuccess
            if (ok) {
                if (!testOnly) {
                    prefs.baseUrl = conn.baseUrl
                    prefs.authType = authType
                    prefs.apiKey = apiKey
                    prefs.username = username
                    prefs.password = password
                    Toast.makeText(context, "连接成功", Toast.LENGTH_SHORT).show()
                    context.startActivity(
                        android.content.Intent(context, KomgaMainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                } else {
                    Toast.makeText(context, "连接正常", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Komiho: surface the real failure instead of a generic message.
                // Common case — server logs show successful auth, so a 4xx/5xx
                // (often 403: API key missing library permission) gets swallowed.
                val msg = describeFailure(result.exceptionOrNull())
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("连接 Komga 服务器") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("服务器地址", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                placeholder = { Text("http://192.168.1.10:25600") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))

            // 认证方式切换
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = authType == KomgaAuthType.API_KEY,
                    onClick = { authType = KomgaAuthType.API_KEY },
                )
                Text("API Key（推荐）")
                Spacer(Modifier.weight(1f))
                RadioButton(
                    selected = authType == KomgaAuthType.BASIC,
                    onClick = { authType = KomgaAuthType.BASIC },
                )
                Text("账号密码")
            }
            Spacer(Modifier.height(12.dp))

            if (authType == KomgaAuthType.API_KEY) {
                Text("API Key", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = { Text("在 Komga 设置 → 用户 → API Key 生成") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("用户名", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("密码", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { doConnect(testOnly = true) },
                    enabled = !testing && !connecting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试连接")
                    }
                }
                Button(
                    onClick = { doConnect(testOnly = false) },
                    enabled = !testing && !connecting && baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (connecting) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("连接")
                    }
                }
            }
        }
    }
}

// File-private helper for surfacing connection errors. Extracted from the
// Composable so it can be unit-tested in isolation later.
private fun describeFailure(e: Throwable?): String {
    if (e == null) return "连接失败：未知错误"
    val raw = e.message.orEmpty()
    return when {
        // KomgaApiClient tags 401 / 5xx specifically
        raw.contains("401") -> "认证失败（401）：请检查 API Key 或账号密码"
        raw.contains("403") -> "权限不足（403）：请在 Komga 用户设置 → API Key 创建时勾选 Library 权限"
        raw.contains("500") || raw.contains("502") || raw.contains("503") -> "服务器错误（$raw）：请查看 Komga 服务端日志"
        raw.contains("Failed to connect") || raw.contains("Unable to resolve host") ||
            raw.contains("ConnectException") || raw.contains("UnknownHost") ->
            "网络失败：$raw（请检查服务器地址和端口）"
        raw.isNotBlank() -> "连接失败：$raw"
        else -> "连接失败：${e::class.java.simpleName}"
    }
}

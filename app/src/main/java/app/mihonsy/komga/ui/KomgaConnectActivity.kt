package app.mihonsy.komga.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * 支持 API Key / 账号密码两种认证；新增或编辑一条连接后进入/返回主界面。
 *
 * 通过 [EXTRA_CONNECTION_ID] 区分：
 *  - 不传 → 新增模式（空白表单）
 *  - 传入已存连接 id → 编辑模式（预填，且不再因"已有连接"而自动跳回主页——
 *    这正是此前「重新配置连接」点了重进 home 的根因）
 */
class KomgaConnectActivity : KomgaBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editId = intent.getStringExtra(EXTRA_CONNECTION_ID)
        setContent { KomihoTheme { KomgaConnectScreen(editId = editId) } }
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "app.mihonsy.komga.ui.ConnectActivity.editId"
    }
}

@Composable
private fun KomgaConnectScreen(editId: String? = null) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    // 编辑模式下的原始连接（新增模式为 null）
    val editConn = remember(editId) { editId?.let { prefs.getConnection(it) } }

    var name by remember { mutableStateOf(editConn?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(editConn?.baseUrl ?: "") }
    var authType by remember { mutableStateOf(editConn?.authType ?: prefs.authType) }
    var apiKey by remember { mutableStateOf(editConn?.apiKey ?: "") }
    var username by remember { mutableStateOf(editConn?.username ?: "") }
    var password by remember { mutableStateOf(editConn?.password ?: "") }

    var testing by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 编辑已有连接、或已存在其他连接时允许返回；纯首次配置（无任何连接）不强制返回
    val canGoBack = editId != null || prefs.hasConnection()

    fun doConnect(testOnly: Boolean) {
        val conn = KomgaConnection(
            id = editId ?: java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { hostOf(baseUrl.trim()) },
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
                    prefs.saveConnection(conn)
                    // 新增 → 设为激活并重启主界面用新连接重拉数据；
                    // 编辑的是当前激活服务器 → 重启主界面；
                    // 编辑非激活服务器 → 直接返回（上层设置页）
                    if (editId == null) {
                        prefs.setActiveConnection(conn.id)
                        restartMain(context)
                    } else if (prefs.activeConnectionId == conn.id) {
                        restartMain(context)
                    } else {
                        (context as? Activity)?.finish()
                    }
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
        topBar = {
            TopAppBar(
                title = { Text(if (editId != null) "编辑服务器" else "连接 Komga 服务器") },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { (context as? Activity)?.finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("名称（可选）", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("默认用服务器地址，如 192.168.1.10:25600") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))

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

private fun hostOf(url: String): String {
    return runCatching {
        val u = java.net.URI(url)
        u.host.takeIf { it.isNotBlank() } ?: url
    }.getOrDefault(url)
}

/** 重启主界面，使所有 composable 用新的激活连接重新拉取数据。 */
private fun restartMain(context: android.content.Context) {
    context.startActivity(
        Intent(context, KomgaMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
    (context as? Activity)?.finish()
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

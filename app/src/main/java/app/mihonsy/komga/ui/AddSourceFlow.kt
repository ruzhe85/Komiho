package app.mihonsy.komga.ui

// SY --> Komiho Phase4: 「添加来源」重做 —— 全屏流程，替代早前的「一源一菜单入口 + WebDavFlowDialog」。
// 流程：类型选择页（本地 → Komga → WebDAV → SMB 卡片，各类型已添加来源列在卡片下方，
// 编辑/删除为右侧图标按钮）→ 点卡片进对应表单页：
//  - WebDAV 表单：来源名称 / 协议+服务器 / 端口 / 路径 / 账户 / 测试连接 / 取消·保存
//  - Komga 表单：来源名称 / 协议+服务器 / 端口 / 认证方式二选一（账号密码 | API Key，
//    切换只切显示、不清空已输入凭据）/ 测试连接 / 取消·保存
//  - 本地：无来源名称（唯一内置来源）；存储管理权限引导（去授权 / 使用 SAF）+ 漫画根目录
// 删除需确认；删除 Komga 激活连接或当前选中来源失效时由外层/重启兜底。

import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaAuthType
import app.mihonsy.komga.data.KomgaConnection
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.webdav.WebDavConnection
import app.mihonsy.komga.data.webdav.WebDavConnectionStore
import app.mihonsy.komga.data.webdav.WebDavPropfind
import com.hippo.unifile.UniFile
import kotlinx.coroutines.launch

/** 添加来源流程内的页面栈（简化为单层：表单页返回即回类型选择）。 */
internal sealed interface AddSourceScreen {
    data object TypeSelect : AddSourceScreen
    /** connId = null 新增，否则编辑该连接。 */
    data class WebDav(val connId: String?) : AddSourceScreen
    data class Komga(val connId: String?) : AddSourceScreen
    data object Local : AddSourceScreen
}

/** 来源类型小图标（来源切换按钮 / 菜单 / 类型卡片共用）。 */
internal fun sourceIcon(kind: SourceKind): ImageVector = when (kind) {
    SourceKind.Komga -> Icons.Filled.Dns
    SourceKind.WebDav -> Icons.Filled.CloudQueue
    SourceKind.Smb -> Icons.Filled.Lan
    SourceKind.Local -> Icons.Filled.Folder
}

/**
 * 全屏「添加来源」流程。由顶栏菜单「＋ 添加来源」打开。
 * @param manageTick 外层权限状态翻转计数（ON_RESUME 复查），用于本地页权限卡实时刷新。
 * @param onPickLocalFolder 触发外层 SAF 选目录 launcher（结果直接写 localSourceRoot 偏好）。
 * @param onManageAccess 跳系统「所有文件访问」设置页。
 * @param onDismiss 关闭整个流程（外层负责 sourceVersion++、komgaConnected 复查与来源回落）。
 */
@Composable
internal fun AddSourceFlow(
    prefs: KomgaPreferences,
    manageTick: Int,
    localDir: UniFile?,
    onPickLocalFolder: () -> Unit,
    onManageAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<AddSourceScreen>(AddSourceScreen.TypeSelect) }
    // 类型选择页列表刷新计数：每次从表单页返回 / 删除后 +1。
    var listTick by remember { mutableIntStateOf(0) }
    var deleteKomga by remember { mutableStateOf<KomgaConnection?>(null) }
    var deleteWebDav by remember { mutableStateOf<WebDavConnection?>(null) }

    BackHandler(enabled = screen != AddSourceScreen.TypeSelect) {
        screen = AddSourceScreen.TypeSelect
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (screen == AddSourceScreen.TypeSelect) onDismiss() else screen = AddSourceScreen.TypeSelect
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        text = when (val s = screen) {
                            AddSourceScreen.TypeSelect -> "添加来源"
                            is AddSourceScreen.WebDav -> "WebDAV"
                            is AddSourceScreen.Komga -> "Komga"
                            AddSourceScreen.Local -> "本地"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                HorizontalDivider()
                when (val s = screen) {
                    AddSourceScreen.TypeSelect -> TypeSelectContent(
                        prefs = prefs,
                        listTick = listTick,
                        onSelect = { screen = it },
                        onRequestDeleteKomga = { deleteKomga = it },
                        onRequestDeleteWebDav = { deleteWebDav = it },
                    )

                    is AddSourceScreen.WebDav -> WebDavFormPage(
                        connId = s.connId,
                        onBack = { screen = AddSourceScreen.TypeSelect },
                        onSaved = {
                            listTick++
                            screen = AddSourceScreen.TypeSelect
                        },
                    )

                    is AddSourceScreen.Komga -> KomgaFormPage(
                        prefs = prefs,
                        connId = s.connId,
                        onBack = { screen = AddSourceScreen.TypeSelect },
                        onSavedInactive = {
                            listTick++
                            screen = AddSourceScreen.TypeSelect
                        },
                        // Komga 激活连接变化（新增 / 编辑激活项）→ 重启主界面重拉数据（沿用 M1 行为）。
                        onActiveChanged = { restartMainFlow(context) },
                    )

                    AddSourceScreen.Local -> LocalFormPage(
                        manageTick = manageTick,
                        localDir = localDir,
                        onPickLocalFolder = onPickLocalFolder,
                        onManageAccess = onManageAccess,
                        onDone = { listTick++; onDismiss() },
                    )
                }
            }
        }
    }

    // 删除确认（Komga）：删激活连接 → 删后重启主界面；非激活 → 仅刷新列表。
    deleteKomga?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleteKomga = null },
            title = { Text("删除来源") },
            text = { Text("确定删除「${connDisplayName(conn.name, conn.baseUrl)}」？历史与书签记录将保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteKomga = null
                        val wasActive = prefs.activeConnectionId == conn.id
                        prefs.deleteConnection(conn.id)
                        if (wasActive) {
                            // 剩余连接（若有）自动成为激活项；重启后用新连接重拉数据。
                            restartMainFlow(context)
                        } else {
                            listTick++
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteKomga = null }) { Text("取消") }
            },
        )
    }

    // 删除确认（WebDAV）：当前选中的该来源失效由外层 onDismiss 回落本地兜底。
    deleteWebDav?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleteWebDav = null },
            title = { Text("删除来源") },
            text = { Text("确定删除「${conn.displayName()}」？历史与书签记录将保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteWebDav = null
                        WebDavConnectionStore.remove(conn.id)
                        listTick++
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteWebDav = null }) { Text("取消") }
            },
        )
    }
}

// ------------------------------------------------------------ 类型选择页

@Composable
private fun TypeSelectContent(
    prefs: KomgaPreferences,
    listTick: Int,
    onSelect: (AddSourceScreen) -> Unit,
    onRequestDeleteKomga: (KomgaConnection) -> Unit,
    onRequestDeleteWebDav: (WebDavConnection) -> Unit,
) {
    val context = LocalContext.current
    val komgaConns = remember(listTick) { prefs.connections() }
    val webdavConns = remember(listTick) { WebDavConnectionStore.all() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "选择来源类型",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // 排序：本地 → Komga → WebDAV → SMB（与顶栏来源菜单一致）。
        TypeCard(
            leading = { TypeCardIcon(Icons.Filled.Folder) },
            title = "本地",
            subtitle = "手机存储",
            onClick = { onSelect(AddSourceScreen.Local) },
        )

        TypeCard(
            leading = { TypeCardIcon(icon = null, letter = "K") },
            title = "Komga",
            subtitle = "Komga 服务器",
            onClick = { onSelect(AddSourceScreen.Komga(null)) },
        )
        komgaConns.forEach { conn ->
            AddedSourceRow(
                name = connDisplayName(conn.name, conn.baseUrl),
                onEdit = { onSelect(AddSourceScreen.Komga(conn.id)) },
                onDelete = { onRequestDeleteKomga(conn) },
            )
        }

        TypeCard(
            leading = { TypeCardIcon(Icons.Filled.CloudQueue) },
            title = "WebDAV",
            subtitle = "WebDAV 服务器",
            onClick = { onSelect(AddSourceScreen.WebDav(null)) },
        )
        webdavConns.forEach { conn ->
            AddedSourceRow(
                name = conn.displayName(),
                onEdit = { onSelect(AddSourceScreen.WebDav(conn.id)) },
                onDelete = { onRequestDeleteWebDav(conn) },
            )
        }

        TypeCard(
            leading = { TypeCardIcon(Icons.Filled.Lan) },
            title = "SMB",
            subtitle = "Windows / NAS · 暂未支持",
            enabled = false,
            onClick = {
                android.widget.Toast.makeText(context, "SMB 暂未支持", android.widget.Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun TypeCard(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 类型卡片左侧圆角图标位：图标或字母（Komga 用「K」）。 */
@Composable
private fun TypeCardIcon(icon: ImageVector? = null, letter: String? = null) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        } else {
            Text(letter.orEmpty(), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 已添加来源条目行：名称居左，右侧「编辑 / 删除」图标按钮成组靠右（删除红色）。 */
@Composable
private fun AddedSourceRow(
    name: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "编辑",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ------------------------------------------------------------ WebDAV 表单页

@Composable
private fun WebDavFormPage(
    connId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val existing = remember(connId) {
        connId?.let { id -> WebDavConnectionStore.all().firstOrNull { it.id == id } }
    }
    val parsed = remember(existing) { parseHttpUrl(existing?.baseUrl.orEmpty()) }

    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var useHttps by remember(existing) { mutableStateOf(parsed.scheme == "https") }
    var host by remember(existing) { mutableStateOf(parsed.host) }
    var port by remember(existing) { mutableStateOf(parsed.port) }
    var path by remember(existing) { mutableStateOf(parsed.path) }
    var user by remember(existing) { mutableStateOf(existing?.user.orEmpty()) }
    var pass by remember(existing) { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    fun buildBaseUrl(): String {
        val scheme = if (useHttps) "https" else "http"
        val portPart = if (port.isBlank()) "" else ":${port.trim()}"
        val pathPart = if (path.isBlank()) "" else if (path.startsWith("/")) path.trim() else "/${path.trim()}"
        return "$scheme://${host.trim()}$portPart$pathPart"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        FieldLabel("来源名称")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("如：我的 WebDAV") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel("服务器")
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.width(132.dp)) {
                SegmentedButton(
                    selected = !useHttps,
                    onClick = { useHttps = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("HTTP", style = MaterialTheme.typography.bodySmall) }
                SegmentedButton(
                    selected = useHttps,
                    onClick = { useHttps = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("HTTPS", style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                placeholder = { Text("dav.example.com") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        FieldLabel("端口")
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            placeholder = { Text("5007（可空）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel("路径")
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            placeholder = { Text("/（根目录）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel("账户")
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("用户名（可空 = 匿名）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text(if (existing == null) "密码（可空 = 匿名）" else "密码（留空 = 不修改）") },
            singleLine = true,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Text(if (showPass) "隐藏" else "显示", style = MaterialTheme.typography.bodySmall)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "凭据经 Keystore 加密后落盘，不会明文保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                val temp = WebDavConnection(
                    id = connId.orEmpty(),
                    name = name,
                    baseUrl = buildBaseUrl(),
                    user = user,
                    // 编辑留空 = 沿用旧密码：直接带旧密文，decryptStored 兼容明文/密文两种形态。
                    passEnc = pass.ifBlank { existing?.passEnc.orEmpty() },
                )
                testing = true
                testMsg = null
                scope.launch {
                    testMsg = try {
                        WebDavPropfind.list(temp, temp.baseUrl)
                        "连接正常"
                    } catch (e: Throwable) {
                        e.message ?: "连接失败"
                    }
                    testing = false
                }
            },
            enabled = host.isNotBlank() && !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("测试连接")
        }
        testMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it == "连接正常") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val baseUrl = buildBaseUrl()
                    if (connId == null) {
                        WebDavConnectionStore.add(name, baseUrl, user, pass)
                    } else {
                        WebDavConnectionStore.update(connId, name, baseUrl, user, pass)
                    }
                    onSaved()
                },
                enabled = host.isNotBlank(),
            ) { Text("保存") }
        }
    }
}

// ------------------------------------------------------------ Komga 表单页

@Composable
private fun KomgaFormPage(
    prefs: KomgaPreferences,
    connId: String?,
    onBack: () -> Unit,
    onSavedInactive: () -> Unit,
    onActiveChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val existing = remember(connId) { connId?.let { prefs.getConnection(it) } }
    val parsed = remember(existing) { parseHttpUrl(existing?.baseUrl.orEmpty()) }

    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var useHttps by remember(existing) { mutableStateOf(parsed.scheme == "https") }
    var host by remember(existing) { mutableStateOf(parsed.host) }
    var port by remember(existing) { mutableStateOf(parsed.port) }
    // 认证方式二选一：切换只切换显示哪组输入框，两组已输入的凭据均保留。
    var authType by remember(existing) { mutableStateOf(existing?.authType ?: prefs.authType) }
    var username by remember(existing) { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember(existing) { mutableStateOf(existing?.password.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }
    var apiKey by remember(existing) { mutableStateOf(existing?.apiKey.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    fun buildConn(): KomgaConnection {
        val scheme = if (useHttps) "https" else "http"
        val portPart = if (port.isBlank()) "" else ":${port.trim()}"
        return KomgaConnection(
            id = connId ?: java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { host.trim() },
            baseUrl = "$scheme://${host.trim()}$portPart",
            authType = authType,
            apiKey = apiKey.trim(),
            username = username.trim(),
            password = password,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        FieldLabel("来源名称")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("如：我的 Komga") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel("服务器")
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.width(132.dp)) {
                SegmentedButton(
                    selected = !useHttps,
                    onClick = { useHttps = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("HTTP", style = MaterialTheme.typography.bodySmall) }
                SegmentedButton(
                    selected = useHttps,
                    onClick = { useHttps = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("HTTPS", style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                placeholder = { Text("komga.example.com") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        FieldLabel("端口")
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            placeholder = { Text("25600（可空）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel("认证方式（二选一）")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = authType == KomgaAuthType.BASIC,
                onClick = { authType = KomgaAuthType.BASIC },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("账号密码", style = MaterialTheme.typography.bodySmall) }
            SegmentedButton(
                selected = authType == KomgaAuthType.API_KEY,
                onClick = { authType = KomgaAuthType.API_KEY },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("API Key", style = MaterialTheme.typography.bodySmall) }
        }

        Spacer(Modifier.height(10.dp))
        if (authType == KomgaAuthType.BASIC) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名 / 邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "隐藏" else "显示", style = MaterialTheme.typography.bodySmall)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // API Key 非密码：明文输入，无可见切换。
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                val conn = buildConn()
                testing = true
                testMsg = null
                scope.launch {
                    val result = KomgaApiClient(conn).testConnection()
                    testing = false
                    testMsg = result.fold(
                        onSuccess = { "连接正常" },
                        onFailure = { it.message ?: "连接失败" },
                    )
                }
            },
            enabled = host.isNotBlank() && !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("测试连接")
        }
        testMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it == "连接正常") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val conn = buildConn()
                    val wasActive = connId != null && prefs.activeConnectionId == connId
                    prefs.saveConnection(conn)
                    if (connId == null || wasActive) {
                        // 新增 / 编辑的是激活连接：设为激活并重启主界面用新连接重拉数据。
                        prefs.setActiveConnection(conn.id)
                        onActiveChanged()
                    } else {
                        // 编辑非激活连接：仅落盘，返回类型选择页。
                        onSavedInactive()
                    }
                },
                enabled = host.isNotBlank(),
            ) { Text("保存") }
        }
    }
}

// ------------------------------------------------------------ 本地表单页

@Composable
private fun LocalFormPage(
    manageTick: Int,
    localDir: UniFile?,
    onPickLocalFolder: () -> Unit,
    onManageAccess: () -> Unit,
    onDone: () -> Unit,
) {
    // 已授予 MANAGE_EXTERNAL_STORAGE：浏览根恒为内部存储、localSourceRoot 被忽略（与主界面一致）。
    val manageGranted = remember(manageTick) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        FieldLabel("存储管理权限")
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (manageGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                        shape = CircleShape,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (manageGranted) "已授予所有文件访问权限" else "未授予管理权限",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (manageGranted) {
                        "浏览根为内部存储，可全盘切换存储卷"
                    } else {
                        "授权后直读真实文件系统，浏览更快、封面更稳"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!manageGranted) {
                Button(
                    onClick = onManageAccess,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("去授权") }
            }
        }

        if (!manageGranted) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("不想授权？使用 SAF", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "走系统文件选择器，无需特殊权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onPickLocalFolder,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("使用 SAF") }
            }

            FieldLabel("漫画根目录")
            Text(
                text = localDir?.filePath ?: "未选择（点下方按钮选择）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onPickLocalFolder, modifier = Modifier.fillMaxWidth()) {
                Text("选择文件夹")
            }
        }

        Text(
            "本地为内置来源、始终存在；此页仅用于管理权限与漫画根目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onDone) { Text("完成") }
        }
    }
}

// ------------------------------------------------------------ 公共小件

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

/** Komga 连接显示名：name 为空时退回 host（与保存逻辑一致，覆盖迁移数据）。 */
private fun connDisplayName(name: String, baseUrl: String): String =
    name.ifBlank { parseHttpUrl(baseUrl).host.ifBlank { baseUrl } }

/** 把 base URL 拆成 表单字段（scheme / host / port / path）；空 URL 给 HTTPS 默认值。 */
private data class ParsedUrl(val scheme: String, val host: String, val port: String, val path: String)

private fun parseHttpUrl(url: String): ParsedUrl {
    if (url.isBlank()) return ParsedUrl(scheme = "https", host = "", port = "", path = "")
    val scheme = if (url.startsWith("http://")) "http" else "https"
    val rest = url.substringAfter("://")
    val authority = rest.substringBefore('/')
    val host = authority.substringBefore(':')
    val port = authority.substringAfter(':', "")
    val rawPath = rest.substringAfter('/', missingDelimiterValue = "")
    val path = if (rawPath.isBlank()) "" else "/$rawPath"
    return ParsedUrl(scheme, host, port, path)
}

/** Komga 激活连接变化后重启主界面（对齐 KomgaConnectActivity.restartMain 的 M1 行为）。 */
private fun restartMainFlow(context: android.content.Context) {
    context.startActivity(
        android.content.Intent(context, KomgaMainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
    (context as? android.app.Activity)?.finish()
}
// SY <--

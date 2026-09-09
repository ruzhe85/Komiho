package app.mihonsy.komga.ui

// SY --> Komiho Phase4: 「来源管理」重做 —— 全屏流程，替代早前的「一源一菜单入口 + WebDavFlowDialog」。
// 注意：不是 Compose Dialog——Dialog 是独立窗口，系统返回键在 Dialog 层就被消费成
// dismiss（onDismissRequest），内部的 BackHandler 收不到事件，编辑页按返回会直接
// 退回主页。这里渲染为宿主组合内的全屏 overlay（顶栏/底栏由调用方在流程打开时隐藏），
// BackHandler 与主界面同组合且后注册，返回键逐层回退：表单页 → 类型选择页 → 关闭流程。
// 视觉口径：SegmentedButton 选中 = 反色底 + 无 ✓（icon = {} 覆盖默认勾）；✓ 标记只用于来源菜单下拉的当前项。
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.res.stringResource as composeStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaAuthType
import app.mihonsy.komga.data.KomgaConnection
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.SourceVisibilityStore
// SY --> Komiho Phase7: SMB 表单接入。
import app.mihonsy.komga.data.smb.SmbBrowse
import app.mihonsy.komga.data.smb.SmbConnection
import app.mihonsy.komga.data.smb.SmbConnectionStore
// SY <--
import app.mihonsy.komga.data.webdav.WebDavConnection
import app.mihonsy.komga.data.webdav.WebDavConnectionStore
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import app.mihonsy.komga.data.webdav.WebDavPropfind
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 协议默认端口：HTTP=80 / HTTPS=443；切换协议时空端口或仍是另一协议默认值时自动填新默认。 */
internal const val DEFAULT_PORT_HTTP = "80"
internal const val DEFAULT_PORT_HTTPS = "443"

// SY --> Komiho Phase7: SMB 报错识别——smbj 的 TransportException（含 Broken pipe）对用户
// 无意义。成因几乎总是「服务器在协商/认证阶段主动断开」：方言不匹配（SMB1-only）/ guest
// 或账号无该共享权限 / 服务器强制加密。识别命中返回 true，由调用方换成可行动的本地化提示。
/**
 * 扫描局域网内开放 SMB（TCP 445）的主机——质感文件「添加 LAN SMB 服务器」的简化版：
 * 不依赖 jcifs NBT 名字服务（SMB1 时代产物，Win10+ 常失效），直接并发探测当前
 * Wi-Fi /24 网段的 445 端口（64 线程 × 300ms 超时，254 个地址秒级完成）。
 * @return 存活主机 IP 列表（按末段升序；本机排除）。
 */
internal suspend fun scanLanSmbHosts(): List<String> = withContext(Dispatchers.IO) {
    val local = java.net.NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<java.net.Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        ?.hostAddress
        ?: return@withContext emptyList()
    val prefix = local.substringBeforeLast('.')
    val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
    try {
        (1..254).map { last ->
            pool.submit(java.util.concurrent.Callable {
                val ip = "$prefix.$last"
                if (ip == local) return@Callable null
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(ip, 445), 300)
                        ip
                    }
                } catch (e: Exception) {
                    null
                }
            })
        }.mapNotNull { it.get() }
    } finally {
        pool.shutdownNow()
    }.sortedBy { it.substringAfterLast('.').toInt() }
}

internal fun smbIsConnectionReset(e: Throwable): Boolean {
    var cause: Throwable? = e
    while (cause != null) {
        val msg = cause.message ?: ""
        if (cause is com.hierynomus.protocol.transport.TransportException ||
            msg.contains("Broken pipe", ignoreCase = true) ||
            msg.contains("Connection reset", ignoreCase = true)
        ) {
            return true
        }
        cause = cause.cause
    }
    return false
}
// SY <--

/** 来源管理流程内的页面栈（简化为单层：表单页返回即回类型选择）。 */
internal sealed interface AddSourceScreen {
    data object TypeSelect : AddSourceScreen
    /** connId = null 新增，否则编辑该连接。 */
    data class WebDav(val connId: String?) : AddSourceScreen
    // SY --> Komiho Phase7: SMB 连接表单（host/port/share/path/domain/user/pass）。
    data class Smb(val connId: String?) : AddSourceScreen
    // SY <--
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
 * 全屏「来源管理」流程。由顶栏菜单「来源管理」打开（宿主组合内的全屏 overlay，
 * 见文件头注释：为何不用 Dialog）。
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
    // SY --> Komiho Onboarding: 首启欢迎页复用本流程——点来源卡直达对应表单页，
    // 表单返回仍回类型选择页、再返回才 onDismiss（= 回欢迎页）。
    initialScreen: AddSourceScreen = AddSourceScreen.TypeSelect,
    // SY <--
) {
    val context = LocalContext.current
    // SY: Onboarding 传入 initialScreen 时从这里起步（默认仍是类型选择页）。
    var screen by remember { mutableStateOf<AddSourceScreen>(initialScreen) }
    // 类型选择页列表刷新计数：每次从表单页返回 / 删除后 +1。
    var listTick by remember { mutableIntStateOf(0) }
    var deleteKomga by remember { mutableStateOf<KomgaConnection?>(null) }
    var deleteWebDav by remember { mutableStateOf<WebDavConnection?>(null) }
    // SY --> Komiho Phase7: SMB 连接删除确认状态。
    var deleteSmb by remember { mutableStateOf<SmbConnection?>(null) }
    // SY <--

    // 返回键统一接管（本组合内后注册，优先于主界面的 BackHandler）：
    // 表单页 → 类型选择页；类型选择页 → 关闭整个流程。
    BackHandler {
        if (screen == AddSourceScreen.TypeSelect) onDismiss() else screen = AddSourceScreen.TypeSelect
    }

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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = composeStringResource(R.string.addsrc_back_cd))
                }
                Text(
                    text = when (val s = screen) {
                        AddSourceScreen.TypeSelect -> composeStringResource(R.string.addsrc_title)
                        is AddSourceScreen.WebDav -> "WebDAV"
                        is AddSourceScreen.Smb -> "SMB"
                        is AddSourceScreen.Komga -> "Komga"
                        AddSourceScreen.Local -> composeStringResource(R.string.addsrc_type_local)
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
                    // SY --> Komiho Phase7: SMB 卡片编辑/删除回调。
                    onRequestDeleteSmb = { deleteSmb = it },
                    // SY <--
                )

                is AddSourceScreen.WebDav -> WebDavFormPage(
                    connId = s.connId,
                    onBack = { screen = AddSourceScreen.TypeSelect },
                    onSaved = {
                        listTick++
                        screen = AddSourceScreen.TypeSelect
                    },
                )

                // SY --> Komiho Phase7: SMB 表单页。
                is AddSourceScreen.Smb -> SmbFormPage(
                    connId = s.connId,
                    onBack = { screen = AddSourceScreen.TypeSelect },
                    onSaved = {
                        listTick++
                        screen = AddSourceScreen.TypeSelect
                    },
                )
                // SY <--

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

    // 删除确认（Komga）：删激活连接 → 删后重启主界面；非激活 → 仅刷新列表。
    deleteKomga?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleteKomga = null },
            title = { Text(composeStringResource(R.string.addsrc_delete_title)) },
            text = { Text(composeStringResource(R.string.addsrc_delete_msg, connDisplayName(conn.name, conn.baseUrl))) },
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
                ) { Text(composeStringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteKomga = null }) { Text(composeStringResource(R.string.cancel)) }
            },
        )
    }

    // 删除确认（WebDAV）：当前选中的该来源失效由外层 onDismiss 回落本地兜底。
    deleteWebDav?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleteWebDav = null },
            title = { Text(composeStringResource(R.string.addsrc_delete_title)) },
            text = { Text(composeStringResource(R.string.addsrc_delete_msg, conn.displayName())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteWebDav = null
                        WebDavConnectionStore.remove(conn.id)
                        listTick++
                    },
                ) { Text(composeStringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteWebDav = null }) { Text(composeStringResource(R.string.cancel)) }
            },
        )
    }

    // SY --> Komiho Phase7: 删除确认（SMB）：store.remove 内部会作废该连接的 SMB 会话。
    deleteSmb?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleteSmb = null },
            title = { Text(composeStringResource(R.string.addsrc_delete_title)) },
            text = { Text(composeStringResource(R.string.addsrc_delete_msg, conn.displayName())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteSmb = null
                        SmbConnectionStore.remove(conn.id)
                        listTick++
                    },
                ) { Text(composeStringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSmb = null }) { Text(composeStringResource(R.string.cancel)) }
            },
        )
    }
    // SY <--
}

// ------------------------------------------------------------ 类型选择页

@Composable
private fun TypeSelectContent(
    prefs: KomgaPreferences,
    listTick: Int,
    onSelect: (AddSourceScreen) -> Unit,
    onRequestDeleteKomga: (KomgaConnection) -> Unit,
    onRequestDeleteWebDav: (WebDavConnection) -> Unit,
    // SY --> Komiho Phase7: SMB 卡片删除回调。
    onRequestDeleteSmb: (SmbConnection) -> Unit,
    // SY <--
) {
    val komgaConns = remember(listTick) { prefs.connections() }
    val webdavConns = remember(listTick) { WebDavConnectionStore.all() }
    // SY --> Komiho Phase7: SMB 已添加连接列表。
    val smbConns = remember(listTick) { SmbConnectionStore.all() }
    // SY <--

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            composeStringResource(R.string.addsrc_select_type),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // 排序：本地 → Komga → WebDAV → SMB（与顶栏来源菜单一致）。
        // SY: 本地也可隐藏——聚合页显示开关（唯一内置来源，无编辑/删除，只给开关）。
        // 与其他来源同口径：只影响聚合页卡片，顶栏来源菜单仍可切回本地。
        val localVisId = SourceVisibilityStore.ID_LOCAL
        var localVisible by remember(localVisId, listTick) {
            mutableStateOf(SourceVisibilityStore.isVisible(localVisId))
        }
        TypeCard(
            leading = { TypeCardIcon(Icons.Filled.Folder) },
            title = composeStringResource(R.string.addsrc_type_local),
            trailing = {
                IconButton(onClick = {
                    localVisible = !localVisible
                    SourceVisibilityStore.setVisible(localVisId, localVisible)
                }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (localVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = composeStringResource(R.string.addsrc_toggle_visible_cd),
                        modifier = Modifier.size(16.dp),
                        tint = if (localVisible) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
            onClick = { onSelect(AddSourceScreen.Local) },
        )

        TypeCard(
            leading = { TypeCardIcon(icon = null, letter = "K") },
            title = "Komga",
            onClick = { onSelect(AddSourceScreen.Komga(null)) },
        )
        komgaConns.forEach { conn ->
            // SY: 聚合页显示开关（按连接记；聚合页 Komga 单卡——任一条可见即显示）。
            val visId = SourceVisibilityStore.ID_KOMGA_CONN_PREFIX + conn.id
            var visible by remember(visId) { mutableStateOf(SourceVisibilityStore.isVisible(visId)) }
            AddedSourceRow(
                name = connDisplayName(conn.name, conn.baseUrl),
                visible = visible,
                onToggleVisible = {
                    visible = !visible
                    SourceVisibilityStore.setVisible(visId, visible)
                },
                onEdit = { onSelect(AddSourceScreen.Komga(conn.id)) },
                onDelete = { onRequestDeleteKomga(conn) },
            )
        }

        TypeCard(
            leading = { TypeCardIcon(Icons.Filled.CloudQueue) },
            title = "WebDAV",
            onClick = { onSelect(AddSourceScreen.WebDav(null)) },
        )
        webdavConns.forEach { conn ->
            // SY: 聚合页显示开关（来源 id 与聚合页卡片 id 对齐：`webdav:<id>`）。
            val visId = SourceVisibilityStore.ID_WEBDAV_PREFIX + conn.id
            var visible by remember(visId) { mutableStateOf(SourceVisibilityStore.isVisible(visId)) }
            AddedSourceRow(
                name = conn.displayName(),
                visible = visible,
                onToggleVisible = {
                    visible = !visible
                    SourceVisibilityStore.setVisible(visId, visible)
                },
                onEdit = { onSelect(AddSourceScreen.WebDav(conn.id)) },
                onDelete = { onRequestDeleteWebDav(conn) },
            )
        }

        // SY --> Komiho Phase7: SMB 卡片（可用，镜像 WebDAV 卡 + 已添加连接列表）。
        TypeCard(
            leading = { TypeCardIcon(Icons.Filled.Lan) },
            title = "SMB",
            onClick = { onSelect(AddSourceScreen.Smb(null)) },
        )
        smbConns.forEach { conn ->
            // SY: 聚合页显示开关（来源 id 与聚合页卡片 id 对齐：`smb:<id>`）。
            val visId = SourceVisibilityStore.ID_SMB_PREFIX + conn.id
            var visible by remember(visId) { mutableStateOf(SourceVisibilityStore.isVisible(visId)) }
            AddedSourceRow(
                name = conn.displayName(),
                visible = visible,
                onToggleVisible = {
                    visible = !visible
                    SourceVisibilityStore.setVisible(visId, visible)
                },
                onEdit = { onSelect(AddSourceScreen.Smb(conn.id)) },
                onDelete = { onRequestDeleteSmb(conn) },
            )
        }
        // SY <--
    }
}

@Composable
private fun TypeCard(
    leading: @Composable () -> Unit,
    title: String,
    enabled: Boolean = true,
    trailingTag: String? = null,
    /** 标题右侧、chevron 左侧的自定义控件（内置来源无编辑/删除，只放聚合页显示开关）。 */
    trailing: @Composable (() -> Unit)? = null,
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
        }
        if (trailingTag != null) {
            Text(
                trailingTag,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        // SY: 内置来源（本地）没有「已添加条目行」，聚合页显示开关直接挂在类型卡右侧。
        if (trailing != null) {
            trailing()
            Spacer(Modifier.width(4.dp))
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

/** 已添加来源条目行：名称居左，右侧「显示 / 编辑 / 删除」图标按钮成组靠右（删除红色）。 */
@Composable
private fun AddedSourceRow(
    name: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
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
        // SY: 聚合页显示开关——开（眼睛）才在聚合页显示该来源卡片，关（斜杠眼）则隐藏。
        IconButton(onClick = onToggleVisible, modifier = Modifier.size(32.dp)) {
            Icon(
                if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = composeStringResource(R.string.addsrc_toggle_visible_cd),
                modifier = Modifier.size(16.dp),
                tint = if (visible) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = composeStringResource(R.string.addsrc_edit_cd),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = composeStringResource(R.string.addsrc_delete_cd),
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
        // SY: 同 parseHttpUrl——剥 host 末尾的 DNS 根标记「.」，保证测试连接 / 保存出去的
        // baseUrl 一定不带尾点（带尾点会被 OkHttp 拒收 →「非法 WebDAV URL」）。
        return "$scheme://${host.trim().removeSuffix(".")}$portPart$pathPart"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        FieldLabel(composeStringResource(R.string.addsrc_name))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text(composeStringResource(R.string.addsrc_name_hint_webdav)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel(composeStringResource(R.string.addsrc_server))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.width(132.dp)) {
                SegmentedButton(
                    selected = !useHttps,
                    onClick = {
                        // 切 HTTP：空端口或仍是 HTTPS 默认值 → 填 80；手动改过则不动。
                        if (port.isBlank() || port.trim() == DEFAULT_PORT_HTTPS) port = DEFAULT_PORT_HTTP
                        useHttps = false
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = {},
                ) { Text("HTTP", style = MaterialTheme.typography.bodySmall) }
                SegmentedButton(
                    selected = useHttps,
                    onClick = {
                        // 切 HTTPS：空端口或仍是 HTTP 默认值 → 填 443；手动改过则不动。
                        if (port.isBlank() || port.trim() == DEFAULT_PORT_HTTP) port = DEFAULT_PORT_HTTPS
                        useHttps = true
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = {},
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

        FieldLabel(composeStringResource(R.string.addsrc_port))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            placeholder = { Text(composeStringResource(R.string.addsrc_port_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel(composeStringResource(R.string.addsrc_path))
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            placeholder = { Text(composeStringResource(R.string.addsrc_path_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel(composeStringResource(R.string.addsrc_account))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text(composeStringResource(R.string.addsrc_username_anon)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = {
                Text(
                    if (existing == null) {
                        composeStringResource(R.string.addsrc_password_new)
                    } else {
                        composeStringResource(R.string.addsrc_password_edit)
                    },
                )
            },
            singleLine = true,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(
                        imageVector = if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = composeStringResource(
                            if (showPass) R.string.addsrc_hide_password_cd else R.string.addsrc_show_password_cd,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            composeStringResource(R.string.addsrc_keystore_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(16.dp))
        // 测试结果文案在组合期取好（stringResource 是 @Composable，不能在 onClick lambda 里调）。
        val okMsg = composeStringResource(R.string.addsrc_test_ok)
        val failMsg = composeStringResource(R.string.addsrc_test_failed)
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
                        okMsg
                    } catch (e: Throwable) {
                        e.message ?: failMsg
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
            Text(composeStringResource(R.string.addsrc_test))
        }
        testMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it == composeStringResource(R.string.addsrc_test_ok)) {
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
            TextButton(onClick = onBack) { Text(composeStringResource(R.string.cancel)) }
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
            ) { Text(composeStringResource(R.string.action_save)) }
        }
    }
}

// SY --> Komiho Phase7: SMB 表单页（镜像 WebDavFormPage；无协议切换——SMB 恒 TCP 直连，
// 端口默认 445）。字段：名称 / 主机 / 端口 / 路径（第一段=共享名）/ 域 / 账户 / 密码。
// 测试连接：填了共享 = 列起始目录；留空 = 枚举全部共享（srvsvc RPC，质感文件同款）。
// SMB 地址模型 \\host\share\path 两层，共享是必须的——留空只是把「选共享」推迟到浏览根视图。
@Composable
private fun SmbFormPage(
    connId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val existing = remember(connId) {
        connId?.let { id -> SmbConnectionStore.all().firstOrNull { it.id == id } }
    }

    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember(existing) { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember(existing) { mutableStateOf(existing?.port?.toString().orEmpty()) }
    // SY: 单路径字段 = 共享名/子目录（第一段即共享名）。存储模型不变（share+path 两字段），
    // 只是表单口径与常见 SMB 地址 \\host\share\path 对齐，不拆两个输入框。
    var fullPath by remember(existing) {
        mutableStateOf(
            listOfNotNull(existing?.share?.takeIf { it.isNotBlank() }, existing?.path?.takeIf { it.isNotBlank() })
                .joinToString("/"),
        )
    }
    var domain by remember(existing) { mutableStateOf(existing?.domain.orEmpty()) }
    var user by remember(existing) { mutableStateOf(existing?.user.orEmpty()) }
    var pass by remember(existing) { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    // 「共享名/子目录」→ (share, path)。留空 = 不指定共享（浏览根 = 列出服务器全部共享）。
    fun splitFullPath(): Pair<String, String> {
        val norm = fullPath.trim().replace('\\', '/').trim('/')
        if (norm.isEmpty()) return "" to ""
        return norm.substringBefore('/') to norm.substringAfter('/', "")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        FieldLabel(composeStringResource(R.string.addsrc_name))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text(composeStringResource(R.string.addsrc_name_hint_smb)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel(composeStringResource(R.string.addsrc_server))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            placeholder = { Text(composeStringResource(R.string.addsrc_smb_host_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // SY --> Komiho Phase7: 扫描局域网（质感文件「添加 LAN SMB 服务器」简化版）——
        // 并发探测当前网段 445 端口，结果点击回填主机；免手输 IP。
        var lanScanning by remember { mutableStateOf(false) }
        var lanScanned by remember { mutableStateOf(false) }
        var lanHosts by remember { mutableStateOf<List<String>>(emptyList()) }
        val scanLabel = composeStringResource(
            if (lanScanning) R.string.addsrc_smb_scanning else R.string.addsrc_smb_scan,
        )
        TextButton(
            onClick = {
                if (lanScanning) return@TextButton
                lanScanning = true
                lanScanned = false
                lanHosts = emptyList()
                scope.launch {
                    lanHosts = scanLanSmbHosts()
                    lanScanning = false
                    lanScanned = true
                }
            },
        ) {
            if (lanScanning) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(scanLabel, style = MaterialTheme.typography.bodyMedium)
        }
        if (!lanScanning && lanScanned && lanHosts.isEmpty()) {
            Text(
                composeStringResource(R.string.addsrc_smb_scan_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        lanHosts.forEach { ip ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { host = ip }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Lan,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(ip, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // SY <--

        FieldLabel(composeStringResource(R.string.addsrc_port))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            placeholder = { Text("445") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // SY: 单路径字段——第一段为共享名，其余为共享内子目录。
        FieldLabel(composeStringResource(R.string.addsrc_path))
        OutlinedTextField(
            value = fullPath,
            onValueChange = { fullPath = it },
            placeholder = { Text(composeStringResource(R.string.addsrc_smb_path_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel(composeStringResource(R.string.addsrc_smb_domain))
        OutlinedTextField(
            value = domain,
            onValueChange = { domain = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel(composeStringResource(R.string.addsrc_account))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text(composeStringResource(R.string.addsrc_smb_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = {
                Text(
                    if (existing == null) {
                        composeStringResource(R.string.addsrc_password_new)
                    } else {
                        composeStringResource(R.string.addsrc_password_edit)
                    },
                )
            },
            singleLine = true,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(
                        imageVector = if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = composeStringResource(
                            if (showPass) R.string.addsrc_hide_password_cd else R.string.addsrc_show_password_cd,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            composeStringResource(R.string.addsrc_keystore_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(16.dp))
        // 测试结果文案在组合期取好（stringResource 是 @Composable，不能在 onClick lambda 里调）。
        val okMsg = composeStringResource(R.string.addsrc_test_ok)
        val failMsg = composeStringResource(R.string.addsrc_test_failed)
        // SY: broken pipe 提示同样在组合期取好（stringResource 是 @Composable，launch 协程里不能调）。
        val resetMsg = composeStringResource(R.string.smb_conn_reset)
        OutlinedButton(
            onClick = {
                val (share, path) = splitFullPath()
                val temp = SmbConnectionStore.temp(
                    name = name,
                    host = host,
                    port = port.trim().toIntOrNull() ?: SmbConnection.DEFAULT_PORT,
                    share = share,
                    path = path,
                    domain = domain,
                    user = user,
                    // 编辑留空 = 沿用旧密码：直接带旧密文（decryptStored 兼容明文/密文两种形态）。
                    pass = pass.ifBlank { existing?.passEnc.orEmpty() },
                )
                testing = true
                testMsg = null
                scope.launch {
                    testMsg = try {
                        // SY: 未填共享 = 枚举全部共享（srvsvc RPC）；填了 = 列该共享/起始目录。
                        if (temp.share.isBlank()) {
                            SmbBrowse.listShares(temp, WebDavCredentialCrypto.decryptStored(temp.passEnc))
                        } else {
                            SmbBrowse.list(temp, WebDavCredentialCrypto.decryptStored(temp.passEnc), temp.path)
                        }
                        okMsg
                    } catch (e: Throwable) {
                        if (smbIsConnectionReset(e)) resetMsg else (e.message ?: failMsg)
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
            Text(composeStringResource(R.string.addsrc_test))
        }
        testMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it == composeStringResource(R.string.addsrc_test_ok)) {
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
            TextButton(onClick = onBack) { Text(composeStringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val (share, path) = splitFullPath()
                    if (connId == null) {
                        SmbConnectionStore.add(name, host, port.trim().toIntOrNull() ?: SmbConnection.DEFAULT_PORT, share, path, domain, user, pass)
                    } else {
                        SmbConnectionStore.update(connId, name, host, port.trim().toIntOrNull() ?: SmbConnection.DEFAULT_PORT, share, path, domain, user, pass)
                    }
                    onSaved()
                },
                enabled = host.isNotBlank(),
            ) { Text(composeStringResource(R.string.action_save)) }
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
        FieldLabel(composeStringResource(R.string.addsrc_name))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text(composeStringResource(R.string.addsrc_name_hint_komga)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel(composeStringResource(R.string.addsrc_server))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.width(132.dp)) {
                SegmentedButton(
                    selected = !useHttps,
                    onClick = {
                        // 切 HTTP：空端口或仍是 HTTPS 默认值 → 填 80；手动改过则不动。
                        if (port.isBlank() || port.trim() == DEFAULT_PORT_HTTPS) port = DEFAULT_PORT_HTTP
                        useHttps = false
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = {},
                ) { Text("HTTP", style = MaterialTheme.typography.bodySmall) }
                SegmentedButton(
                    selected = useHttps,
                    onClick = {
                        // 切 HTTPS：空端口或仍是 HTTP 默认值 → 填 443；手动改过则不动。
                        if (port.isBlank() || port.trim() == DEFAULT_PORT_HTTP) port = DEFAULT_PORT_HTTPS
                        useHttps = true
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = {},
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

        FieldLabel(composeStringResource(R.string.addsrc_port))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            placeholder = { Text(composeStringResource(R.string.addsrc_port_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel(composeStringResource(R.string.addsrc_auth_type))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = authType == KomgaAuthType.BASIC,
                onClick = { authType = KomgaAuthType.BASIC },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = {},
            ) { Text(composeStringResource(R.string.addsrc_auth_basic), style = MaterialTheme.typography.bodySmall) }
            SegmentedButton(
                selected = authType == KomgaAuthType.API_KEY,
                onClick = { authType = KomgaAuthType.API_KEY },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = {},
            ) { Text("API Key", style = MaterialTheme.typography.bodySmall) }
        }

        Spacer(Modifier.height(10.dp))
        if (authType == KomgaAuthType.BASIC) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(composeStringResource(R.string.addsrc_username_email)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(composeStringResource(R.string.addsrc_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = composeStringResource(
                                if (showPassword) R.string.addsrc_hide_password_cd else R.string.addsrc_show_password_cd,
                            ),
                        )
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
        // 测试结果文案在组合期取好（stringResource 是 @Composable，不能在 onClick lambda 里调）。
        val okMsg = composeStringResource(R.string.addsrc_test_ok)
        val failMsg = composeStringResource(R.string.addsrc_test_failed)
        OutlinedButton(
            onClick = {
                val conn = buildConn()
                testing = true
                testMsg = null
                scope.launch {
                    val result = KomgaApiClient(conn).testConnection()
                    testing = false
                    testMsg = result.fold(
                        onSuccess = { okMsg },
                        onFailure = { it.message ?: failMsg },
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
            Text(composeStringResource(R.string.addsrc_test))
        }
        testMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it == composeStringResource(R.string.addsrc_test_ok)) {
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
            TextButton(onClick = onBack) { Text(composeStringResource(R.string.cancel)) }
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
            ) { Text(composeStringResource(R.string.action_save)) }
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
        FieldLabel(composeStringResource(R.string.addsrc_storage_perm))
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
                    if (manageGranted) {
                        composeStringResource(R.string.addsrc_perm_granted)
                    } else {
                        composeStringResource(R.string.addsrc_perm_denied)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (manageGranted) {
                        composeStringResource(R.string.addsrc_perm_granted_desc)
                    } else {
                        composeStringResource(R.string.addsrc_perm_denied_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!manageGranted) {
                Button(
                    onClick = onManageAccess,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text(composeStringResource(R.string.addsrc_grant)) }
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
                    Text(composeStringResource(R.string.addsrc_saf_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        composeStringResource(R.string.addsrc_saf_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onPickLocalFolder,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text(composeStringResource(R.string.addsrc_saf)) }
            }

            FieldLabel(composeStringResource(R.string.addsrc_root))
            Text(
                text = localDir?.filePath ?: composeStringResource(R.string.addsrc_root_none),
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
                Text(composeStringResource(R.string.addsrc_pick_folder))
            }
        }

        Text(
            composeStringResource(R.string.addsrc_local_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onDone) { Text(composeStringResource(R.string.done)) }
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
    // SY: 剥 host 末尾的 DNS 根标记「.」（FQDN 绝对名，如 `host.`）——HTTP 不需要该尾点，
    // 且带尾点 host 会被 OkHttp 拒收（→「非法 WebDAV URL」）。旧连接可能存了带尾点的
    // baseUrl，这里回填时洗净，避免编辑页把它原样带回去。
    val host = authority.substringBefore(':').removeSuffix(".")
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

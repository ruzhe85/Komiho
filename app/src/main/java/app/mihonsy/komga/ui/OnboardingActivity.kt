package app.mihonsy.komga.ui

// SY --> Komiho Onboarding: 首启欢迎页（方案二）。
// 背景：Komiho 已从「纯 Komga 阅读器」演进为多来源客户端（本地 / Komga / WebDAV），
// 旧首启链路 KomgaLauncherActivity → 无连接 → 强制 KomgaConnectActivity 把 Komga
// 当成了必选项。现在无连接时进本页：三来源卡任选（点卡直达 AddSourceFlow 对应表单），
// 也可直接「开始使用」——本地源内置兜底，主界面随时可用；来源之后在设置里随时添加。
// 复用点：表单页全部来自 AddSourceFlow（initialScreen 直达），SAF 选目录与
// 「所有文件访问」授权引导与主界面同款，行为一致。

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource as composeStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hippo.unifile.UniFile
import app.mihonsy.komga.data.KomgaPreferences
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.tachiyomi.R
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OnboardingActivity : KomgaBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KomihoTheme {
                OnboardingScreen(
                    onDone = {
                        startActivity(Intent(this, KomgaMainActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val storagePreferences = remember { Injekt.get<StoragePreferences>() }
    val localSourceFs = remember { Injekt.get<LocalSourceFileSystem>() }
    fun computeLocalDir(): UniFile? = localSourceFs.getBaseDirectory()
    var localDir by remember { mutableStateOf(computeLocalDir()) }

    // 授权状态翻转（授予/撤销 MANAGE_EXTERNAL_STORAGE 返回）→ 重算根目录；与主界面同款。
    var permTick by remember { mutableStateOf(0) }
    var lastPermState by remember {
        mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
                if (now != lastPermState) {
                    lastPermState = now
                    permTick++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        storagePreferences.localSourceRoot.changes().collect { localDir = computeLocalDir() }
    }
    LaunchedEffect(permTick) { localDir = computeLocalDir() }
    val pickLocalFolder = SettingsDataScreen.storageLocationPicker(storagePreferences.localSourceRoot)

    // null = 欢迎页；非 null = AddSourceFlow（从对应表单页起步）。
    var flowScreen by remember { mutableStateOf<AddSourceScreen?>(null) }
    var komgaReady by remember { mutableStateOf(prefs.hasConnection()) }

    if (flowScreen == null) {
        OnboardingWelcome(
            komgaReady = komgaReady,
            localReady = localDir != null,
            onPick = { flowScreen = it },
            onEnter = onDone,
        )
    } else {
        AddSourceFlow(
            prefs = prefs,
            manageTick = permTick,
            localDir = localDir,
            onPickLocalFolder = { pickLocalFolder.launch(null) },
            onManageAccess = { launchManageAllFilesAccess(context) },
            onDismiss = {
                komgaReady = prefs.hasConnection()
                flowScreen = null
            },
            initialScreen = flowScreen!!,
        )
    }
}

@Composable
private fun OnboardingWelcome(
    komgaReady: Boolean,
    localReady: Boolean,
    onPick: (AddSourceScreen) -> Unit,
    onEnter: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // SY --> Komiho: 欢迎页顶图直接用 APP 启动图标（mipmap ic_launcher 位图，painterResource 可加载）。
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = composeStringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = composeStringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 28.dp),
        )

        // SY --> Komiho: 卡片顺序 本地 → Komga → WebDAV（本地内置兜底放最前）。
        OnboardingSourceCard(
            icon = sourceIcon(SourceKind.Local),
            title = composeStringResource(R.string.onboarding_local),
            desc = composeStringResource(R.string.onboarding_local_desc),
            ready = localReady,
            onClick = { onPick(AddSourceScreen.Local) },
        )
        OnboardingSourceCard(
            icon = sourceIcon(SourceKind.Komga),
            title = composeStringResource(R.string.onboarding_komga),
            desc = composeStringResource(R.string.onboarding_komga_desc),
            ready = komgaReady,
            onClick = { onPick(AddSourceScreen.Komga(connId = null)) },
        )
        OnboardingSourceCard(
            icon = sourceIcon(SourceKind.WebDav),
            title = composeStringResource(R.string.onboarding_webdav),
            desc = composeStringResource(R.string.onboarding_webdav_desc),
            ready = false,
            onClick = { onPick(AddSourceScreen.WebDav(connId = null)) },
        )

        // SY --> Komiho: 去掉「随便逛逛」——本地源内置兜底，按钮固定为「开始使用」；
        // 什么都没配也能进主界面（客户端已改为懒构造，无 Komga 连接不会崩）。
        TextButton(onClick = onEnter, modifier = Modifier.padding(top = 20.dp)) {
            Text(text = composeStringResource(R.string.onboarding_enter))
        }
    }
}

@Composable
private fun OnboardingSourceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    ready: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (ready) {
                    composeStringResource(R.string.onboarding_ready)
                } else {
                    composeStringResource(R.string.onboarding_pending)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

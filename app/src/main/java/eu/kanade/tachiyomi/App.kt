package eu.kanade.tachiyomi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import java.io.File
import okio.Path.Companion.toOkioPath
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
// SY --> Komiho: 进程启动时把「应用语言」对齐到平台 per-app locale（阅读器走 AppCompat 的那条路）
import app.mihonsy.komga.applyAppLanguageToPlatform
import app.mihonsy.komga.data.KomgaPreferences
// SY <--
import eu.kanade.domain.DomainModule
import eu.kanade.domain.SYDomainModule
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.crash.CrashActivity
import eu.kanade.tachiyomi.crash.GlobalExceptionHandler
import eu.kanade.tachiyomi.data.coil.BufferedSourceFetcher
// SY --> Komiho: 本地封面（自带 filesDir/komiho_local_covers 缓存，与 Komga 缓存隔离）
import eu.kanade.tachiyomi.data.coil.LocalCoverFetcher
import eu.kanade.tachiyomi.data.coil.SmbCoverFetcher
import eu.kanade.tachiyomi.data.coil.LocalCoverKeyer
// SY <--
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import eu.kanade.tachiyomi.data.coil.MangaKeyer
// SY --> Komiho: 进度条缩略图——本地/SMB/WebDAV/远程源均复用 reader 的 PageLoader 加载页原图
import eu.kanade.tachiyomi.data.coil.ReaderPageThumbnailFetcher
// SY <--
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.InjektKoinBridge
import eu.kanade.tachiyomi.di.PreferenceModule
import eu.kanade.tachiyomi.di.importModule
import eu.kanade.tachiyomi.di.initExpensiveComponents
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.EinkMotion
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import eu.kanade.tachiyomi.diagnostic.DiagnosticLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import logcat.LogcatLogger
import mihon.core.firebase.FirebaseConfig
import mihon.core.migration.Migrator
import mihon.core.migration.migrations.migrations
import org.conscrypt.Conscrypt
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.widget.WidgetManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.Security

class App : Application(), DefaultLifecycleObserver, SingletonImageLoader.Factory {

    private val basePreferences: BasePreferences by injectLazy()
    private val privacyPreferences: PrivacyPreferences by injectLazy()
    private val networkPreferences: NetworkPreferences by injectLazy()

    private val disableIncognitoReceiver = DisableIncognitoReceiver()

    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate() {
        super<Application>.onCreate()
        try {
            FirebaseConfig.init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        GlobalExceptionHandler.initialize(applicationContext, CrashActivity::class.java)

        // TLS 1.3 support for Android < 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Avoid potential crashes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }

        Injekt.importModule(PreferenceModule(this))
        Injekt.importModule(AppModule(this))
        Injekt.importModule(DomainModule())
        // SY -->
        Injekt.importModule(SYDomainModule())
        InjektKoinBridge.startKoin(this)
        initExpensiveComponents(this)
        // SY <--

        // Komiho: E-Ink 功能已下线（设置入口与 EINK 皮肤项都已隐藏）。做一次性收敛 ——
        // 老设备可能还开着 einkMode，而界面上已经没有开关可关，不清掉就会一直吃灰阶渲染
        // 与全局关动画。皮肤一并回退到 DEFAULT（「黑白/灰阶皮肤」不该在功能下线后还留着）。
        runCatching {
            val uiPrefs = Injekt.get<UiPreferences>()
            if (uiPrefs.einkMode.get()) uiPrefs.einkMode.set(false)
            if (uiPrefs.appTheme.get() == AppTheme.EINK) uiPrefs.appTheme.set(AppTheme.DEFAULT)
        }
        // Komga 侧皮肤是另一份存储（KomgaPreferences.appTheme，String），单独收敛。
        runCatching {
            val komgaPrefs = KomgaPreferences(this)
            if (komgaPrefs.appTheme == AppTheme.EINK.name) komgaPrefs.appTheme = AppTheme.DEFAULT.name
        }

        LogcatLogger.install()
        DiagnosticLogBuffer.install(this) // SY 落盘缓冲所有 logcat（崩溃自动重启后仍可导出）

        setupNotificationChannels()

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        val scope = ProcessLifecycleOwner.get().lifecycleScope

        // Show notification to disable Incognito Mode when it's enabled
        basePreferences.incognitoMode.changes()
            .onEach { enabled ->
                if (enabled) {
                    disableIncognitoReceiver.register()
                    notify(
                        Notifications.ID_INCOGNITO_MODE,
                        Notifications.CHANNEL_INCOGNITO_MODE,
                    ) {
                        setContentTitle(stringResource(MR.strings.pref_incognito_mode))
                        setContentText(stringResource(MR.strings.notification_incognito_text))
                        setSmallIcon(R.drawable.ic_glasses_24dp)
                        setOngoing(true)

                        val pendingIntent = PendingIntent.getBroadcast(
                            this@App,
                            0,
                            Intent(ACTION_DISABLE_INCOGNITO_MODE).setPackage(BuildConfig.APPLICATION_ID),
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        setContentIntent(pendingIntent)
                    }
                } else {
                    disableIncognitoReceiver.unregister()
                    cancelNotification(Notifications.ID_INCOGNITO_MODE)
                }
            }
            .launchIn(scope)

        privacyPreferences.analytics
            .changes()
            .onEach(FirebaseConfig::setAnalyticsEnabled)
            .launchIn(scope)

        privacyPreferences.crashlytics
            .changes()
            .onEach(FirebaseConfig::setCrashlyticsEnabled)
            .launchIn(scope)

        basePreferences.hardwareBitmapThreshold.let { preference ->
            if (!preference.isSet()) preference.set(GLUtil.DEVICE_TEXTURE_LIMIT)
        }

        basePreferences.hardwareBitmapThreshold.changes()
            .onEach { ImageUtil.hardwareBitmapThreshold = it }
            .launchIn(scope)

        // Komiho: E-Ink 模式 → 释放 GPU/NPU 引擎。市场墨水屏的 SoC 跑不动 Vulkan 推理，
        // 留着 AI 档只是白占显存、白耗电。
        //
        // 顺序不能反：**先打断**在跑的推理，再销毁。否则在途的预热任务会再次
        // ensureEngine 把 Vulkan 上下文建起来（引擎是懒加载的，只有 destroy 之后
        // 没有人再 process 才真的释放）。另外增强侧已经把 AI 档收敛到 CPU 档
        // （MihonSyEnhancer.effectiveMode），所以这里销毁后不会被自动重建。
        Injekt.get<UiPreferences>().einkMode.changes()
            .drop(1) // 首发是当前值，不是"刚被打开"
            .onEach { enabled ->
                if (!enabled) return@onEach
                runCatching { Waifu2x.abortProcessing() }
                runCatching { Waifu2x.destroy() }
            }
            .launchIn(scope)

        // Komiho: Compose 动画总闸逐窗口应用。Compose 动画读的是各自窗口 recomposer
        // 里的 MotionDurationScale（见 EinkMotion.applyComposeDurationScale 的注释），
        // 没有全 App 一处生效的入口，所以挂在生命周期回调上：PostCreated 兜住首次
        // 进入，Resumed 兜住运行中切开关 / 系统倍率变化（ContentObserver 会覆盖回
        // 系统值，靠这里收敛）。
        var resumedActivity: Activity? = null
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                EinkMotion.applyComposeDurationScale(activity)
                EinkMotion.applyWindowAnimations(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                resumedActivity = activity
                EinkMotion.applyComposeDurationScale(activity)
                EinkMotion.applyWindowAnimations(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity === activity) resumedActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // Komiho: 在设置页**当场**切换 E-Ink 总开关/关动画子项时，用户还停在这个
        // Activity 上 —— 只靠 Resumed 重应用的话，要等离开再回来动画才归零，观感上
        // 就是「设置了没效果」。所以订阅偏好变化，对前台 Activity 立即重应用。
        val reapplyEinkMotion = { activity: Activity ->
            EinkMotion.applyComposeDurationScale(activity)
            EinkMotion.applyWindowAnimations(activity)
        }
        Injekt.get<UiPreferences>().let { prefs ->
            prefs.einkMode.changes().drop(1)
                .onEach { reapplyEinkMotion(resumedActivity ?: return@onEach) }
                .launchIn(scope)
            prefs.einkDisableAnimation.changes().drop(1)
                .onEach { reapplyEinkMotion(resumedActivity ?: return@onEach) }
                .launchIn(scope)
        }

        setAppCompatDelegateThemeMode(Injekt.get<UiPreferences>().themeMode.get())

        // SY --> Komiho: 「应用语言」与平台 per-app locale 是两份状态，会分叉（旧版本写入的偏好、
        // 或从未点过语言项）。AppCompatActivity（阅读器）的 configuration 由 AppCompat 按
        // per-app locale 重写，分叉时它读到空值 → 回退系统语言（中文机锁死中文），
        // 把 ReaderActivity.attachBaseContext 的 withAppLanguage() 包装整个冲掉。
        // 这里每次进程启动无条件对齐一次。
        applyAppLanguageToPlatform()
        // SY <--

        // Updates widget update
        WidgetManager(Injekt.get(), Injekt.get()).apply { init(scope) }

        /*if (!LogcatLogger.isInstalled && networkPreferences.verboseLogging().get()) {
            LogcatLogger.install(AndroidLogcatLogger(LogPriority.VERBOSE))
        }*/

        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(this, Configuration.Builder().build())
        }

        initializeMigrator()
    }

    private fun initializeMigrator() {
        val preferenceStore = Injekt.get<PreferenceStore>()
        // SY -->
        // 注：key 名沿用 SY 时代的 "eh_last_version_code"（历史命名，仅作为迁移版本号存档，与 exh 无关）
        val preference = preferenceStore.getInt(Preference.appStateKey("eh_last_version_code"), 0)
        // SY <--
        logcat { "Migration from ${preference.get()} to ${BuildConfig.VERSION_CODE}" }
        Migrator.initialize(
            old = preference.get(),
            new = BuildConfig.VERSION_CODE,
            migrations = migrations,
            onMigrationComplete = {
                logcat { "Updating last version to ${BuildConfig.VERSION_CODE}" }
                preference.set(BuildConfig.VERSION_CODE)
            },
        )
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(this).apply {
            val callFactoryLazy = lazy { Injekt.get<NetworkHelper>().client }
            components {
                // NetworkFetcher.Factory
                add(OkHttpNetworkFetcherFactory(callFactoryLazy::value))
                // Decoder.Factory
                add(TachiyomiImageDecoder.Factory())
                // Fetcher.Factory
                add(BufferedSourceFetcher.Factory())
                add(MangaCoverFetcher.MangaCoverFactory(callFactoryLazy))
                add(MangaCoverFetcher.MangaFactory(callFactoryLazy))
                // SY -->
                // Komiho: 本地（文件型来源）封面，自带 filesDir 缓存，与 Komga 缓存隔离
                add(LocalCoverFetcher.Factory(context.applicationContext))
                // Komiho Phase7: SMB 浏览列表封面（归档首图/单图，filesDir 缓存隔离）
                add(SmbCoverFetcher.Factory(context.applicationContext))
                // Komiho: 进度条缩略图（本地 / SMB / WebDAV / 远程 复用 PageLoader 加载页原图）
                add(ReaderPageThumbnailFetcher.Factory())
                // SY <--
                // Keyer
                add(MangaCoverKeyer())
                add(MangaKeyer())
                // SY -->
                // Komiho: 本地封面缓存键（uri + lastModified）
                add(LocalCoverKeyer())
                // Komiho Phase7: SMB 浏览封面 Keyer
                add(eu.kanade.tachiyomi.data.coil.SmbCoverKeyer())
                // SY <--
            }

            memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context)
                    .build(),
            )

            // SY: Cover disk cache shared by Komga covers AND local (file-source)
            // 预览图（Komga 封面走 Coil 同一磁盘池 komga_covers）不再设用户上限：
            // 取消「书库 → 预览图」里的缓存上限滑块，Coil 用自身默认策略做 LRU 淘汰；
            // 本设置项改为「清除预览图」整行点击弹确认框。HTTP 层 etag/304 协商与
            // 本地封面 lastModified 失效逻辑不变。
            diskCache(
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "komga_covers").toOkioPath())
                    .build(),
            )

            // Komiho: E-Ink 模式下关掉淡入 —— 墨水屏上一次交叉淡入要在两个灰阶之间
            // 渐变整屏，留下明显的灰色残影。页面级请求本来就都是 crossfade(false)，
            // 只有这个 App 级的还开着。
            if (EinkMotion.isAnimationOff) {
                crossfade(false)
            } else {
                crossfade((300 * this@App.animatorDurationScale).toInt())
            }
            allowRgb565(DeviceUtil.isLowRamDevice(this@App))
            if (networkPreferences.verboseLogging.get()) logger(DebugLogger())

            // Coil spawns a new thread for every image load by default
            fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))
            // Komiho: 3 → 5。AI 超分的推理是在解码器内部同步跑的（TachiyomiImageDecoder 调
            // MihonSyEnhancer.enhance），而原生引擎全程持一把全局锁（waifu2x_jni.cpp:143），
            // 一次只能跑一页、每页 1–3 秒。池只有 3 时，3 页同时增强就把解码线程全占满
            // （其中 2 条还在等锁），此时新页解码拿不到线程 → 翻页长时间卡住。
            // 补到 5 留出余量。读数器侧活页数仍受 offscreen(=1) 限制，并发解码不会因此变多。
            decoderCoroutineContext(Dispatchers.IO.limitedParallelism(5))
        }
            .build()
    }

    override fun onStart(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStart()
    }

    override fun onStop(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStopped()
    }

    override fun getPackageName(): String {
        // This causes freezes in Android 6/7 for some reason
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Override the value passed as X-Requested-With in WebView requests
                val stackTrace = Looper.getMainLooper().thread.stackTrace
                val isChromiumCall = stackTrace.any { trace ->
                    trace.className.lowercase() in setOf("org.chromium.base.buildinfo", "org.chromium.base.apkinfo") &&
                        trace.methodName.lowercase() in setOf("getall", "getpackagename", "<init>")
                }

                if (isChromiumCall) return WebViewUtil.spoofedPackageName(applicationContext)
            } catch (_: Exception) {
            }
        }

        return super.getPackageName()
    }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to modify notification channels" }
        }
    }

    private inner class DisableIncognitoReceiver : BroadcastReceiver() {
        private var registered = false

        override fun onReceive(context: Context, intent: Intent) {
            basePreferences.incognitoMode.set(false)
        }

        fun register() {
            if (!registered) {
                ContextCompat.registerReceiver(
                    this@App,
                    this,
                    IntentFilter(ACTION_DISABLE_INCOGNITO_MODE),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                unregisterReceiver(this)
                registered = false
            }
        }
    }
}

private const val ACTION_DISABLE_INCOGNITO_MODE = "tachi.action.DISABLE_INCOGNITO_MODE"

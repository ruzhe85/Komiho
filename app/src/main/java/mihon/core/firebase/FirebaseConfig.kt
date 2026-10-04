package mihon.core.firebase

import android.content.Context

/**
 * Komiho (2026-10-04): 遥测入口的**空实现**。
 *
 * 本仓库不发布 Firebase 遥测（没有 google-services.json），但此前 release 变体**真的**会去
 * 初始化 Analytics / Crashlytics —— 那三个依赖是 APK 里唯一的 Google Play 服务来源，无 GMS 的
 * 墨水屏设备启动后会收到「需要 Google Play 服务」的系统通知。依赖已整体删除，这里保留同名入口，
 * 让 `App` 的生命周期初始化与隐私开关（`PrivacyPreferences.analytics` / `crashlytics`）的调用点
 * 继续成立（空实现下它们是无作用的 no-op）。
 *
 * 原先 release / foss / debug 三份变体各有一份实现，现在统一成这一份。
 */
object FirebaseConfig {
    fun init(context: Context) = Unit

    fun setAnalyticsEnabled(enabled: Boolean) = Unit

    fun setCrashlyticsEnabled(enabled: Boolean) = Unit
}

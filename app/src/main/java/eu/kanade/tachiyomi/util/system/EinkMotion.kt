package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.view.View
import android.content.Context
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.MotionDurationScale
import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho: E-Ink 模式的动画总闸。
 *
 * 墨水屏上「取消动画」不是观感偏好，而是**物理约束**：每次动画都要全屏刷新若干次，
 * 残影会叠加、功耗上升，而且半透明的中间态在 16 级灰阶上会被量化成脏块。所以这里
 * 提供一个全局开关，三类动画各自接入：
 *
 * - **Compose**：[applyComposeDurationScale]（见其注释 —— 为什么不是 CompositionLocal）。
 * - **View**：[duration] 严格返回 **0**，不要用系统动画倍率折算 —— 现有的
 *   `Int.getSystemScaledDuration()` 带 `coerceAtLeast(1)`，1ms 仍会触发一次
 *   invalidate，在墨水屏上就是**一帧残影**，等于没关。
 * - **Coil**：`App` 里全局 `crossfade(300 * animatorDurationScale)` 改 `false`。
 *   页面级请求本来就都是 `crossfade(false)`，只有 App 级的那个还开着。
 *
 * 读的是 [UiPreferences.isEinkAnimationOff]（= E-Ink 模式开 **且**「关闭动画」子项开），
 * 两个条件分开是为了让用户能只要动画归零、不要灰阶化 —— 前者是纯收益，后者主观。
 */
object EinkMotion {

    /** E-Ink 模式下动画是否应归零。 */
    val isAnimationOff: Boolean
        get() = Injekt.get<UiPreferences>().isEinkAnimationOff

    /**
     * View 层动画时长（ms）。E-Ink 下严格 0，否则用调用方给的常规值。
     *
     * SSIV 的 `setDoubleTapZoomDuration(0)` 与 PhotoView 的
     * `setZoomTransitionDuration(0)` 都是合法的"无动画"用法。
     */
    fun duration(context: Context, normalMillis: Int): Int =
        if (isAnimationOff) 0 else normalMillis

    /**
     * Compose 侧的动画缩放因子：0 = 立即跳终值。
     *
     * 非 E-Ink 时返回 1f（保持 Compose 自己的链式缩放，不覆盖系统倍率）。
     */
    val composeScaleFactor: Float
        get() = if (isAnimationOff) 0f else 1f

    /**
     * 把 Compose 动画总闸应用到 [activity] 窗口的**真** recomposer 上（App 的
     * ActivityLifecycleCallbacks 在 PostCreated / Resumed 各调一次）。
     *
     * ## 为什么不是 `LocalMotionDurationScale` CompositionLocal
     *
     * 原方案想在主题里 `CompositionLocalProvider(LocalMotionDurationScale provides 0f)`
     * 一处生效 —— 但该符号在本项目解析的 Compose BOM 2026.06.01（ui/runtime/animation
     * 1.10.1）里**根本不存在**（已解包 aar 逐个确认），那是更新的 androidx 才有的 API。
     * 本版本里 Compose 动画读时长倍率的唯一来源是 recomposer 协程上下文里的
     * [MotionDurationScale] 元素（`androidx.compose.ui.MotionDurationScale`，公开接口），
     * 由 `WindowRecomposer` 在建窗口时用系统「动画时长倍率」初始化。
     *
     * ## 为什么只读缓存、绝不创建
     *
     * `setContent` 创建的 recomposer 缓存在 `android.R.id.content` **直接子 view** 的
     * tag（`R.id.androidx_compose_ui_view_composition_context`）上。internal 的
     * `View.windowRecomposer()` 会沿 view 树向上找这个缓存，找不到就**创建新的** ——
     * 传 decorView 时找的是缓存 fallback 到 decor 自身，于是凭空造出第二个孤儿
     * recomposer：scale 设在孤儿上对真正的 composition 无效（真机表现「动画照旧」），
     * 孤儿还带着全套生命周期观察者与真身并存（皮肤切换 recreate 后行为异常）。
     *
     * 所以这里直接读那个 tag 拿真身；**拿不到就什么都不做**（组合还没开始 / 非
     * Compose Activity），等下一次 Resumed 再收敛 —— 绝不借 internal 入口创建实例。
     * - E-Ink 开 → scale **严格 0**（Compose 视作动画已禁用，立即跳终值）；
     * - E-Ink 关 → 设回系统倍率（[animatorDurationScale]），与默认行为一致。
     *
     * `MotionDurationScaleImpl` 的 `scaleFactor` 是公开读 / 私有写（且 release 包里
     * 被 R8 改名），所以 setter 按**签名**扫（单 float 参数、void 返回），不按名字。
     * 反射全程 [runCatching] 兜底：失败 = Compose 动画保持系统倍率，其余两层
     * （View / Coil）不受影响。系统倍率变化时 Compose 的 ContentObserver 会覆盖回
     * 系统值 —— Resumed 重调本函数即可收敛。
     */
    fun applyComposeDurationScale(activity: Activity) {
        val target = if (isAnimationOff) 0f else activity.animatorDurationScale
        // post 到主线程：PostCreated 时 setContent 的缓存可能还没落 tag。
        // ⚠️ lambda 在主线程**稍后**才跑，runCatching 必须包在 lambda 内部 ——
        // 包在外面的话异常直接打进主线程 → 无限崩溃循环（真机踩过）。
        activity.window.decorView.post {
            runCatching {
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    ?: return@post bail("no android.R.id.content")
                val contentChild = content.getChildAt(0) ?: return@post bail("content has no child")
                val tagId = contentChild.resources.getIdentifier(
                    "androidx_compose_ui_view_composition_context",
                    "id",
                    "androidx.compose.ui",
                )
                if (tagId == 0) return@post bail("tag id not found")
                val recomposer = contentChild.getTag(tagId) as? Recomposer
                    ?: return@post bail("recomposer tag missing (class=${contentChild.getTag(tagId)?.javaClass?.simpleName})")
                val scale = recomposer.effectCoroutineContext[MotionDurationScale]
                    ?: return@post bail("no MotionDurationScale in recomposer context")
                val setter = scale.javaClass.declaredMethods.firstOrNull {
                    it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                        it.returnType == Void.TYPE
                } ?: return@runCatching bail("setter signature not found")
                setter.isAccessible = true
                setter.invoke(scale, target)
                android.util.Log.i("EinkMotion", "compose durationScale → $target")
            }
        }
    }

    /** 失败原因落到 logcat（Log.i 级别，避开部分厂商对 debug 日志的过滤）。 */
    private fun bail(reason: String): Unit =
        run { android.util.Log.i("EinkMotion", "compose gate skipped: $reason") }
}

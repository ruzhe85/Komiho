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
     * 把 Compose 动画总闸应用到 [activity] 窗口的 recomposer 上（App 的
     * ActivityLifecycleCallbacks 在 PostCreated / Resumed 各调一次，Resumed 里再
     * post 一拍执行以避开与 recomposer 创建的时序竞争）。
     *
     * ## 为什么不是 `LocalMotionDurationScale` CompositionLocal
     *
     * 原方案想在主题里 `CompositionLocalProvider(LocalMotionDurationScale provides 0f)`
     * 一处生效 —— 但该符号在本项目解析的 Compose BOM 2026.06.01（ui/runtime/animation
     * 1.10.1）里**根本不存在**（已解包 aar 逐个确认），那是更新的 androidx 才有的 API。
     * 本版本里 Compose 动画读时长倍率的唯一来源是 recomposer 协程上下文里的
     * [MotionDurationScale] 元素（`androidx.compose.ui.MotionDurationScale`，公开接口），
     * 由 `WindowRecomposer` 在建窗口时用系统「动画时长倍率」初始化；而拿到 recomposer
     * 实例的入口 `View.windowRecomposer()` 与 `Recomposer.runningRecomposers`
     * （返回的是只有元数据的 `RecomposerInfo`）都拿不到上下文。
     *
     * 所以这里**按签名自发现**地反射调用 `WindowRecomposer_androidKt` 里
     * `(View) -> Recomposer` 的静态方法（internal、且 internal 函数会被 Kotlin 做
     * JVM 名修饰，按签名找比按名字找稳），再逐个把 scale 写成目标值：
     * - E-Ink 开 → **严格 0**（0 = Compose 视作动画已禁用，立即跳终值，不产生中间帧）；
     * - E-Ink 关 → 设回系统倍率（[animatorDurationScale]），与 Compose 自己的默认行为一致。
     *
     * 实例的类型是 ui 内部的 `MotionDurationScaleImpl`（`scaleFactor` 有公开读、私有写），
     * 接口本身没有 setter，同样走反射写。两层脆弱性都用 [runCatching] 兜住：失败 =
     * Compose 动画保持系统倍率，其余两层（View / Coil）不受影响。
     *
     * 系统倍率变化时 Compose 的 ContentObserver 会覆盖回系统值 —— 所以 Resumed 时
     * 重调本函数即可收敛。
     */
    fun applyComposeDurationScale(activity: Activity) {
        val target = if (isAnimationOff) 0f else activity.animatorDurationScale
        runCatching {
            // post 到主线程：PostCreated 时 recomposer 可能还没建好，推后一拍收敛。
            activity.window.decorView.post {
                val recomposer = runCatching { findWindowRecomposer(activity.window.decorView) }
                    .getOrNull() ?: return@post
                val scale = recomposer.effectCoroutineContext[MotionDurationScale]
                    ?: return@post
                scale.javaClass
                    .getDeclaredMethod("setScaleFactor", Float::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(scale, target)
            }
        }
    }

    /**
     * 反射调用 `androidx.compose.ui.platform.WindowRecomposer_androidKt` 中签名
     * `(View) -> Recomposer` 的方法（即 internal 的 `View.windowRecomposer()`）。
     * 没建过窗口 recomposer 时它同时会创建一个 —— 这正是 setContent 的同款入口。
     */
    private fun findWindowRecomposer(rootView: View): Recomposer {
        val ktClass = Class.forName("androidx.compose.ui.platform.WindowRecomposer_androidKt")
        val method = ktClass.declaredMethods.firstOrNull {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == View::class.java &&
                it.returnType == Recomposer::class.java
        } ?: error("WindowRecomposer_androidKt: 未找到 (View) -> Recomposer 的方法")
        method.isAccessible = true
        return method.invoke(null, rootView) as Recomposer
    }
}

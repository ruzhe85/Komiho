package eu.kanade.tachiyomi.util.system

import android.content.Context
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
 * - **Compose**：`animatedScaleFactor` 配 `LocalMotionDurationScale`（见 [KomihoTheme]）。
 *   一处生效，`AnimatedVisibility` / `animate*AsState` / `Crossfade` / `updateTransition`
 *   全部立即跳到终值。scale 为 0 时 Compose 视作"动画已禁用"，不会等待。
 * - **View**：[duration] 严格返回 **0**，不要用系统动画倍率折算 —— 现有的
 *   `Int.getSystemScaledDuration()` 带 `coerceAtLeast(1)`，1ms 仍会触发一次
 *   invalidate，在墨水屏上就是**一帧残影**，等于没关。
 * - **Coil**：`App` 里全局 `crossfade(300 * animatorDurationScale)` 改 `false`。
 *   页面级请求本来就都是 `crossfade(false)`，只有 App 级那个是开着的。
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
     * 非 E-Ink 时返回 1f（保持 Compose 自己的链式缩放，不覆盖系统倍率 —— 调用方应用
     * `LocalMotionDurationScale.current` 与本值相乘/择一，见 [KomihoTheme]）。
     */
    val composeScaleFactor: Float
        get() = if (isAnimationOff) 0f else 1f
}

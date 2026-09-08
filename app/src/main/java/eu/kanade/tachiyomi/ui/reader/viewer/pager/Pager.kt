package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import kotlin.math.abs

/**
 * Pager implementation that listens for tap and long tap and allows temporarily disabling touch
 * events in order to work with child views that need to disable touch events on this parent. The
 * pager can also be declared to be vertical by creating it with [isHorizontal] to false.
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    // SY -->
    var isRestoring = false

    override fun onRestoreInstanceState(state: Parcelable?) {
        isRestoring = true
        val currentItem = currentItem
        super.onRestoreInstanceState(state)
        setCurrentItem(currentItem, false)
        isRestoring = false
    }
    // SY <--

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            // Komiho: 已被 150ms 快速确认处理过的点击跳过，避免一次点击翻两页
            if (ev.downTime == fastConfirmedDownTime) {
                return true
            }
            tapListener?.invoke(ev)
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    // Komiho: 150ms 快速单击确认——内置 GestureDetector 的 onSingleTapConfirmed
    // 固定等 300ms（DOUBLE_TAP_TIMEOUT，不可配置），page 模式点击翻页因此有半秒级
    // 体感停顿。这里在抬手后自己挂 150ms 定时器提前触发翻页；窗口内出现第二击
    // （双击候选）、位移超出 slop、或事件被取消则撤销定时器，交回内置判定。
    // 300ms 后内置 confirmed 仍会来一次，用 fastConfirmedDownTime 去重。
    // 长按不受影响：定时器只在 ACTION_UP 之后才启动，按住不放永远不会提前确认。
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longTapTime = ViewConfiguration.getLongPressTimeout()
    private val tapHandler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var tapSlopBroken = false
    private var pendingTapUp: MotionEvent? = null
    private var fastConfirmedDownTime = -1L

    private val fastTapConfirmRunnable = Runnable {
        val up = pendingTapUp
        pendingTapUp = null
        if (up != null) {
            fastConfirmedDownTime = up.downTime
            tapListener?.invoke(up)
        }
    }

    private fun cancelFastTapConfirm() {
        pendingTapUp = null
        tapHandler.removeCallbacks(fastTapConfirmRunnable)
    }

    public override fun onDetachedFromWindow() {
        cancelFastTapConfirm()
        super.onDetachedFromWindow()
    }

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 新手势开始：清掉上一击的待确认与去重标记
                cancelFastTapConfirm()
                fastConfirmedDownTime = -1L
                downX = ev.x
                downY = ev.y
                tapSlopBroken = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) {
                    tapSlopBroken = true
                    cancelFastTapConfirm()
                }
            }
            MotionEvent.ACTION_UP -> {
                // 按压短于长按阈值且未超出 slop，才走 150ms 快速确认；
                // 手势检测被禁用时（如 ReaderButton 按下期间）同样不走快速路径
                if (isGestureDetectorEnabled && !tapSlopBroken && ev.eventTime - ev.downTime < longTapTime) {
                    pendingTapUp = ev
                    tapHandler.postDelayed(fastTapConfirmRunnable, FAST_TAP_CONFIRM_MS)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelFastTapConfirm()
        }
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            false
        } catch (e: IndexOutOfBoundsException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }
}

// Komiho: page 模式单击快速确认窗口——内置判定是固定 300ms，这里压到 150ms。
// 窗口内（抬手后）出现第二击即视为双击候选，撤销翻页交给双击缩放处理；
// 超过窗口的双击第二击到来时第一击已翻页（跟手优先，接受该退化）。
private const val FAST_TAP_CONFIRM_MS = 150L

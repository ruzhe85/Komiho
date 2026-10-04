package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.os.Parcelable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import eu.kanade.tachiyomi.util.system.EinkMotion
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

    /**
     * Komiho (2026-10-04): E-Ink「不跟手翻页」的滑动回调 —— 抬手时给出这次手势的位移
     * （dx/dy，屏幕坐标）。由 `PagerViewer` 分派到 moveRight/moveLeft/moveDown/moveUp；
     * 为 null 时该手势完全不参与翻页。
     */
    var swipePageListener: ((dx: Float, dy: Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** 本次手势是否走「不跟手翻页」（只在 ACTION_DOWN 判定一次，避免每个事件都读偏好）。 */
    private var einkSwipeGesture = false
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeMoved = false
    private var swipeMaxPointers = 1

    /** 子视图是否要求独占本次手势（SSIV 放大后平移会置 true，到图片边缘再置回 false）。 */
    private var childDisallowIntercept = false

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

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            beginSwipeTracking(ev)
        }
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        if (einkSwipeGesture) {
            trackSwipe(ev)
        }
        return handled
    }

    /**
     * Komiho (2026-10-04): 手势起点处判定这一次要不要走「不跟手翻页」。
     *
     * 判据与动画总闸一致（[EinkMotion.isAnimationOff]）：墨水屏上「跟手拖动 + 抬手回位」是两段
     * 整屏连续重绘，残影会叠起来，所以 E-Ink 关动画时改成「抬手一次性换页」。
     */
    private fun beginSwipeTracking(ev: MotionEvent) {
        einkSwipeGesture = swipePageListener != null && EinkMotion.isAnimationOff
        swipeDownX = ev.x
        swipeDownY = ev.y
        swipeMoved = false
        swipeMaxPointers = 1
        // 子视图在本次 DOWN 里还会自己调 requestDisallowInterceptTouchEvent（SSIV 就靠它
        // 「放大后平移占住手势、到图片边缘再让给翻页」），所以先清零，让子视图的调用说话。
        childDisallowIntercept = false
    }

    /**
     * 只观察、不消费：抬手时若确定为「滑动」就把位移交给 [swipePageListener]。
     *
     * 排除三种情况 —— 位移没过 touchSlop（那是点击）、多指（捏合缩放）、子视图要求独占
     * （[childDisallowIntercept]，图片放大后的平移）。
     */
    private fun trackSwipe(ev: MotionEvent) {
        if (ev.pointerCount > swipeMaxPointers) {
            swipeMaxPointers = ev.pointerCount
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!swipeMoved && (abs(ev.x - swipeDownX) > touchSlop || abs(ev.y - swipeDownY) > touchSlop)) {
                    swipeMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (swipeMoved && swipeMaxPointers == 1 && !childDisallowIntercept) {
                    swipePageListener?.invoke(ev.x - swipeDownX, ev.y - swipeDownY)
                }
                einkSwipeGesture = false
            }
            MotionEvent.ACTION_CANCEL -> einkSwipeGesture = false
        }
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Komiho: 「不跟手翻页」期间**永不拦截** —— 页面不再跟手，子视图（SSIV / PhotoView 的
        // 缩放、双击、平移）照旧拿到完整事件序列。
        if (einkSwipeGesture) return false
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
        // Komiho: 「不跟手翻页」期间只消费、不交给 ViewPager —— 否则它会启动拖动与抬手后的
        // Scroller 回位动画（正是墨水屏上要消掉的那两段重绘）。翻页由 [swipePageListener] 结算。
        if (einkSwipeGesture) return true
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
     * Komiho: 记录子视图是否要求独占手势（SSIV 放大后平移会置 true，到图片边缘再置回 false
     * 「switch to page swipe」）。「不跟手翻页」据此避免用户平移图片时误翻页。
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        childDisallowIntercept = disallowIntercept
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
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

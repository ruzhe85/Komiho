package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.TextView
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.MihonSyEnhancer
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    // MihonSY: the alwaysDecodeLongStripWithSSIV preference no longer gates the decode
    // path — every mode decodes through Coil into a software bitmap so image
    // enhancement applies everywhere.

    private var pageView: View? = null

    private var config: Config? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * MihonSY: the pixel height of the loaded image, or 0 if unknown. Exposed so the
     * webtoon holder can match the item height to the real 1:1 image height when
     * "original resolution" is enabled (avoids the black gap below each strip).
     */
    val imageSHeight: Int
        get() = (pageView as? SubsamplingScaleImageView)?.sHeight ?: 0

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        val config = config
        if (config != null &&
            config.landscapeZoom &&
            config.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                (animateScaleAndCenter(targetScale, point) ?: return@postDelayed)
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        // MihonSY: invalidate any in-flight enhancement from the previous image.
        enhanceGeneration++
        // MihonSY: hide any leftover enhancement status from the previous image.
        enhanceStatusView?.visibility = View.GONE
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        // MihonSY: invalidate any in-flight enhancement from the previous image.
        enhanceGeneration++
        // MihonSY: hide any leftover enhancement status from the previous image.
        enhanceStatusView?.visibility = View.GONE
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() = pageView?.let {
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
    }

    // MihonSY -->
    /**
     * MihonSY: small overlay at the bottom-left showing the image enhancement state.
     * Shows the real outcome (elapsed time on success, 跳过 on failure).
     * Created lazily and only when the "show enhancement status" toggle is on.
     */
    private var enhanceStatusView: TextView? = null

    /** Monotonic counter used to discard stale enhancement results after page changes. */
    private var enhanceGeneration = 0L

    private fun ensureEnhanceStatusView(): TextView {
        enhanceStatusView?.let { return it }
        val tv = TextView(context).apply {
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt()) // white text
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            alpha = 1f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x99000000.toInt()) // dark translucent background for contrast on white pages
                cornerRadius = dpToPx(4f).toFloat()
            }
            setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
            visibility = View.GONE
        }
        val lp = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = dpToPx(8f)
            bottomMargin = dpToPx(8f)
        }
        addView(tv, lp)
        enhanceStatusView = tv
        return tv
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * MihonSY: shows the enhancement outcome badge. Success shows the real elapsed
     * time; failure/skip shows 跳过. No-op unless enhancement is on AND the status
     * toggle is on. Called from the async enhancement callbacks (and the sync
     * fallback) so the badge reports the true outcome.
     */
    private fun showEnhancementOutcome(success: Boolean, elapsedMillis: Long) {
        val preferences = Injekt.get<ReaderPreferences>()
        if (preferences.enhancementMode.get() == 0 || !preferences.showEnhancementStatus.get()) return
        val tv = ensureEnhanceStatusView()
        tv.text = if (success) {
            String.format(java.util.Locale.US, "OK %.1fs", elapsedMillis / 1000f)
        } else {
            "跳过"
        }
        tv.visibility = View.VISIBLE
    }

    /**
     * MihonSY: asynchronously enhances the page image (Lanczos3) without blocking the
     * reader — the original is already displayed at full resolution. The enhanced
     * bitmap replaces the original in place when ready. Any failure leaves the
     * original and reports 跳过 via the badge.
     *
     * KomihoV2: guarded by isStandardImageStream — Komga streams can yield non-image
     * bytes (encrypted/raw CBZ archives) that would crash BitmapFactory.decodeByteArray;
     * those still get the fast SSIV path and skip enhancement.
     */
    private fun maybeEnhanceAsync(bytes: ByteArray) {
        val preferences = Injekt.get<ReaderPreferences>()
        val mode = preferences.enhancementMode.get()
        if (mode == 0) return
        val generation = ++enhanceGeneration
        val statusView = if (preferences.showEnhancementStatus.get()) ensureEnhanceStatusView() else null
        val startTime = android.os.SystemClock.uptimeMillis()

        // Decode the source into a mutable ARGB bitmap on the background thread.
        // SY: decode at view size (not full resolution) so the Lanczos upscale works on
        // a small image — mirrors MihonSY's Coil ViewSizeResolver + enhanceTarget(2048)
        // optimisation. Without this, Komga's large source pages (3k-5k px) get decoded
        // at full res and the 2x LZ3 pass takes several seconds.
        MihonSyEnhancer.submit(
            block = {
                // 1) peek bounds only
                val boundsOpts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                val srcW = boundsOpts.outWidth
                val srcH = boundsOpts.outHeight
                if (srcW <= 0 || srcH <= 0) return@submit null

                // 2) target long edge = max(view long edge, 2048), then LZ3 scales by lanczosScale.
                //    Small images (smaller than target) keep full res (never upsample on decode).
                val view = pageView as? SubsamplingScaleImageView
                val viewLongEdge = maxOf(view?.width ?: 0, view?.height ?: 0)
                val scale = preferences.lanczosScale.get() / 100f
                val targetLongEdge = maxOf(viewLongEdge, 2048)
                val neededLongEdge = (targetLongEdge * scale).toInt().coerceAtLeast(1)
                var inSampleSize = 1
                while ((srcW / inSampleSize) > neededLongEdge || (srcH / inSampleSize) > neededLongEdge) {
                    inSampleSize *= 2
                }

                // 3) real decode at the computed sample size
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inDither = false
                    this.inSampleSize = inSampleSize
                }
                val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@submit null
                MihonSyEnhancer.enhance(original, preferences)
            },
            onProgress = { elapsedMillis ->
                if (generation == enhanceGeneration && statusView != null) {
                    statusView.text = String.format(java.util.Locale.US, "%.1fs", elapsedMillis / 1000f)
                    statusView.visibility = View.VISIBLE
                }
            },
            onResult = { enhanced ->
                val elapsed = android.os.SystemClock.uptimeMillis() - startTime
                if (generation != enhanceGeneration || enhanced.isRecycled || !isVisibleOnScreen()) {
                    enhanced.recycle()
                    return@submit
                }
                (pageView as? SubsamplingScaleImageView)?.let { view ->
                    view.recycle()
                    view.setImage(ImageSource.bitmap(enhanced))
                    isVisible = true
                }
                showEnhancementOutcome(success = true, elapsedMillis = elapsed)
            },
            onError = {
                val elapsed = android.os.SystemClock.uptimeMillis() - startTime
                if (generation == enhanceGeneration) {
                    showEnhancementOutcome(success = false, elapsedMillis = elapsed)
                }
            },
        )
    }
    // MihonSY <--

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            // MihonSY: show the original at full resolution immediately, then run the
            // (lightweight) enhancement asynchronously and swap it in when done. The
            // original is always shown first so there is never a blank frame.
            // KomihoV2: only enhance streams that actually look like a standard image
            // (JPEG/PNG/WebP/GIF magic) — Komga can yield non-image bytes that would
            // crash BitmapFactory.decodeByteArray; those keep the fast SSIV path.
            is BufferedSource -> {
                val preferences = Injekt.get<ReaderPreferences>()
                val enhancementOn = preferences.enhancementMode.get() != 0 && isStandardImageStream(data)
                // MihonSY: snapshot bytes BEFORE SSIV consumes the stream, so the
                // background enhancement can decode from them. Skip when enhancement
                // is off to avoid copying the whole image for nothing.
                val bytes = if (enhancementOn) {
                    try {
                        data.peek().readByteArray()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                setImage(ImageSource.inputStream(data.inputStream()))
                isVisible = true
                if (bytes != null) {
                    maybeEnhanceAsync(bytes)
                }
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F

/**
 * MihonSY: true when the stream begins with a known image magic number
 * (JPEG/PNG/WebP/GIF). Downloaded CBZ-packed chapters can hand the reader a
 * non-image stream (encrypted/raw archive data); the enhancement decoder
 * crashes on those, so they must skip enhancement and use the standard path.
 * [BufferedSource.peek] does not consume the stream.
 */
private fun isStandardImageStream(source: BufferedSource): Boolean {
    return try {
        source.peek().use { peek ->
            val head = peek.readByteArray(16)
            (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) || // JPEG
                (head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() && head[4] == 0x0D.toByte() && head[5] == 0x0A.toByte() && head[6] == 0x1A.toByte() && head[7] == 0x0A.toByte()) || // PNG
                (head.size >= 12 && head[0] == 0x52.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x46.toByte() && head[8] == 0x57.toByte() && head[9] == 0x45.toByte() && head[10] == 0x42.toByte() && head[11] == 0x50.toByte()) || // WEBP
                (head.size >= 6 && head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x38.toByte()) // GIF
        }
    } catch (e: Exception) {
        false
    }
}

package eu.kanade.tachiyomi.util

// Komiho: Application is used by the GPU AI upscaler (model asset extraction).
import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Image enhancement for Komiho.
 *
 * Two families, selected by [ReaderPreferences.enhancementMode]:
 *  - Lanczos3 / Catmull-Rom (CPU): classic resampling, cheap and fully deterministic.
 *  - AI upscale (GPU): ncnn + Vulkan 2x super-resolution ported from
 *    HaoweiLi97/mihon_img_upscale. Falls back to the original image when this ABI has no
 *    Vulkan build or when inference fails.
 *
 * MihonSY: Anime4K (GPU shaders) remains disabled — its native sources are not compiled
 * into the build, so the Kotlin bindings below stay commented out.
 */
object MihonSyEnhancer {

    // MihonSY: Anime4K disabled.
    // private const val ANIME4K_ASSET_DIR = "anime4k"

    /**
     * 增强结果任一边的上限；超出即等比缩小。推导见 [capOutputSize]。
     * 取 16384 = 现代移动 GPU 的常见 GL 最大纹理边长。
     */
    private const val MAX_ENHANCE_OUTPUT_SIDE = 16384

    init {
        System.loadLibrary("mihonsy-enhance")
    }

    /** Serialises enhancement work so the single CPU is not contended. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MihonSyEnhancer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // MihonSY: Anime4K state removed.
    // @Volatile
    // var isAnime4kInitialized = false
    //     private set

    // MihonSY: the Anime4K mode (0 Fast / 1 High / 2 Ultra) the native renderer was loaded with.
    // @Volatile
    // private var anime4kInitializedMode = -1

    // JNI bindings ------------------------------------------------------------------

    // MihonSY: Anime4K JNI bindings disabled (native side no longer compiled).
    // private external fun nativeInitAnime4K(shaders: Array<String>, names: Array<String>): Boolean
    // private external fun nativeGetMaxTextureSize(): Int
    // private external fun nativeProcessAnime4K(bitmap: Bitmap): Bitmap
    private external fun nativeLanczosProcess(bitmap: Bitmap, scale: Float): Bitmap
    private external fun nativeResample(bitmap: Bitmap, scale: Float, kernel: Int): Bitmap

    // Initialisation ----------------------------------------------------------------

    // MihonSY: Anime4K disabled — init/size helpers removed with the native build.
    // /**
    //  * Loads the Anime4K shaders for [mode] (0 = Fast, 1 = High, 2 = Ultra) and initialises
    //  * the native GLES renderer. Safe to call multiple times (idempotent).
    //  */
    // fun initAnime4K(context: Context, mode: Int): Boolean {
    //     if (isAnime4kInitialized && anime4kInitializedMode == mode) return true
    //
    //     val shaders = mutableListOf<String>()
    //     val names = mutableListOf<String>()
    //
    //     fun addShader(name: String) {
    //         val content = context.assets.open("$ANIME4K_ASSET_DIR/$name")
    //             .bufferedReader()
    //             .use { it.readText() }
    //         shaders.add(content)
    //         names.add(name)
    //     }
    //
    //     return try {
    //         addShader("Anime4K_Clamp_Highlights.glsl")
    //         when (mode) {
    //             0 -> addShader("Anime4K_Restore_CNN_M.glsl") // Fast
    //             1 -> addShader("Anime4K_Restore_CNN_VL.glsl") // High
    //             else -> { // Ultra
    //                 addShader("Anime4K_Restore_CNN_VL.glsl")
    //                 addShader("Anime4K_Upscale_CNN_x2_VL.glsl")
    //             }
    //         }
    //         isAnime4kInitialized = nativeInitAnime4K(shaders.toTypedArray(), names.toTypedArray())
    //         if (isAnime4kInitialized) {
    //             anime4kInitializedMode = mode
    //         } else {
    //             logcat(LogPriority.WARN) { "Anime4K native init failed" }
    //         }
    //         isAnime4kInitialized
    //     } catch (e: Exception) {
    //         logcat(LogPriority.WARN, e) { "Anime4K init failed" }
    //         false
    //     }
    // }
    //
    // /** Anime4K renders through a full-image framebuffer, so it can only handle images that fit the GPU texture limit. */
    // fun anime4kSupportsSize(width: Int, height: Int, mode: Int = 0): Boolean {
    //     val maxTexture = nativeGetMaxTextureSize()
    //     if (maxTexture <= 0) return true
    //     val scale = if (mode >= 2) 2 else 1
    //     return width * scale <= maxTexture && height * scale <= maxTexture
    // }

    // Enhancement entry points -------------------------------------------------------

    /**
     * Runs [block] on the background enhancement thread and posts [onResult] (or [onError])
     * back to the main thread. Used by the reader to avoid blocking the UI.
     *
     * @param onProgress receives the real elapsed time (ms) while [block] runs, about
     *   every 500ms. The overlay uses it to show a stopwatch so the user can observe
     *   how long enhancement actually takes.
     */
    fun submit(
        block: () -> Bitmap?,
        onResult: (Bitmap) -> Unit,
        onError: (Exception) -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ) {
        executor.execute {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val startMillis = SystemClock.uptimeMillis()
            val ticker = object : Runnable {
                override fun run() {
                    handler.post { onProgress(SystemClock.uptimeMillis() - startMillis) }
                    handler.postDelayed(this, 500)
                }
            }
            handler.postDelayed(ticker, 500)
            try {
                val result = block()
                handler.removeCallbacks(ticker)
                if (result != null) {
                    handler.post { onResult(result) }
                } else {
                    // MihonSY: a null result (enhancement failed/skipped) still needs
                    // to be surfaced so the reader badge can report 跳过.
                    handler.post { onError(NoEnhancementException()) }
                }
            } catch (e: Exception) {
                handler.removeCallbacks(ticker)
                logcat(LogPriority.WARN, e) { "Enhancement failed" }
                handler.post { onError(e) }
            }
        }
    }

    /**
     * Synchronously enhances [input] according to the current reader preferences.
     * Returns the enhanced bitmap, or null when no enhancement applies / fails.
     *
     * @param input must be an ARGB_8888 bitmap.
     * @param onComplete optional callback invoked with (enhanced != null, elapsedMillis)
     *   so callers can show a meaningful status (time taken / success).
     */
    fun enhance(
        input: Bitmap,
        preferences: ReaderPreferences = Injekt.get(),
        onComplete: ((enhanced: Boolean, elapsedMillis: Long) -> Unit)? = null,
    ): Bitmap? {
        val start = SystemClock.uptimeMillis()
        if (input.isRecycled) {
            onComplete?.invoke(false, SystemClock.uptimeMillis() - start)
            return null
        }
        // MihonSY: never enhance hardware bitmaps — reading their pixels is unreliable
        // (can produce all-black frames on some devices). Decode-time enhancement runs
        // on software bitmaps, so a HARDWARE input simply skips enhancement.
        if (input.config == Bitmap.Config.HARDWARE) {
            onComplete?.invoke(false, SystemClock.uptimeMillis() - start)
            return null
        }

        // Single selector: 0 = Off, 2 = Lanczos3, 3 = Catmull-Rom.
        // (MihonSY: Anime4K (1) and Spline36 (4) are disabled and excluded from the build.)
        val mode = preferences.enhancementMode.get()
        val result = when (mode) {
            // MihonSY: Anime4K branch disabled — native side no longer compiled.
            // 1 -> {
            //     val a4kMode = preferences.anime4kMode.get()
            //     val latch = java.util.concurrent.CountDownLatch(1)
            //     var a4kResult: Bitmap? = null
            //     var a4kError: Exception? = null
            //     executor.execute {
            //         try {
            //             val argb = ensureArgb(input)
            //             if (argb != null &&
            //                 initAnime4K(Injekt.get<Application>(), a4kMode) &&
            //                 anime4kSupportsSize(argb.width, argb.height, a4kMode)
            //             ) {
            //                 a4kResult = nativeProcessAnime4K(argb).takeUnless { it === argb }
            //             }
            //         } catch (e: Exception) {
            //             a4kError = e
            //         } finally {
            //             latch.countDown()
            //         }
            //     }
            //     try { latch.await() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            //     if (a4kError != null) { logcat(LogPriority.WARN, a4kError) { "Anime4K enhancement failed" } }
            //     a4kResult
            // }

            // MihonSY: CPU resamplers — Lanczos3 (2), Catmull-Rom (3).
            // (Spline36 (4) disabled; kernel id: 0 = Lanczos3, 1 = Catmull-Rom native side.)
            in 2..3 -> {
                val scale = preferences.lanczosScale.get() / 100f
                val argb = ensureArgb(input) ?: run {
                    onComplete?.invoke(false, SystemClock.uptimeMillis() - start)
                    return null
                }
                if (scale > 1f) {
                    when (mode) {
                        3 -> nativeResample(argb, scale, 1)
                        // MihonSY: Spline36 (4) disabled.
                        // 4 -> nativeResample(argb, scale, 2)
                        else -> nativeLanczosProcess(argb, scale)
                    }.takeUnless { it === argb }
                } else {
                    null
                }
            }

            // Komiho: GPU AI upscale (ncnn + Vulkan). Scale is fixed by the model (2x).
            5 -> enhanceWithGpu(input, preferences)

            else -> null
        }
        // 只在结果确实是新对象时才缩（避免误 recycle 调用方仍在用的 input）。
        val capped = if (result != null && result !== input) capOutputSize(result) else result
        onComplete?.invoke(capped != null && capped !== input, SystemClock.uptimeMillis() - start)
        return capped
    }

    /**
     * Komiho: 增强结果的尺寸上限（对标上游的 textureLimit 保护）。
     *
     * 输出 = 输入 × 倍率，长条页会被放大到很夸张的尺寸：
     *  - 普通页：输入受 MAX_ENHANCE_SOURCE_DIMENSION(2048) 约束 → 2x 输出 ≤4096，**永不触发**；
     *  - 长条页：输入保留全高 → 实测 800×9927 经 2x 得 1600×19854 ≈ **127MB**，高度已超常见
     *    的 GPU 纹理/画布上限 16384；
     *  - CPU 倍率档在长条上更极端：Lanczos3 3x 会得到 2400×29781 ≈ **286MB**，基本必 OOM。
     *
     * [MAX_ENHANCE_OUTPUT_SIDE] 取 16384：现代 Adreno/Mali 的 GL 最大纹理普遍就是 16384，
     * 取它当上限既保证结果落在纹理可绘制范围内，又把长条内存从 127MB 压到约 86MB
     * （宽 1320，仍接近 1440 的显示宽，肉眼无感）。普通页与 ≤2x 的倍率都不会触发。
     */
    private fun capOutputSize(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.isRecycled) return bitmap
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= MAX_ENHANCE_OUTPUT_SIDE) return bitmap
        val factor = MAX_ENHANCE_OUTPUT_SIDE.toFloat() / maxSide
        val width = maxOf(1, (bitmap.width * factor).roundToInt())
        val height = maxOf(1, (bitmap.height * factor).roundToInt())
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled !== bitmap) {
                bitmap.recycle()
                scaled
            } else {
                bitmap
            }
        } catch (e: Throwable) {
            // 缩不下来就用原尺寸（宁可大也不要丢结果）。
            logcat(LogPriority.WARN, e) { "Enhancement output cap failed; keeping full size" }
            bitmap
        }
    }

    /**
     * Komiho: GPU AI upscale branch — used when [ReaderPreferences.enhancementMode] is 5.
     *
     * The bundled ncnn model is a fixed 2x network, so [ReaderPreferences.lanczosScale] does
     * not choose the AI output size. When this ABI has no Vulkan build (or inference fails)
     * we fall back to the CPU Lanczos3 resampler at the configured scale, so the page is
     * still enlarged instead of silently staying at its original size.
     */
    private fun enhanceWithGpu(input: Bitmap, preferences: ReaderPreferences): Bitmap? {
        if (Waifu2x.isSupported) {
            Waifu2x.process(Injekt.get<Application>(), input)?.let { return it }
            logcat(LogPriority.WARN) { "AI upscale produced no result; falling back to Lanczos3" }
        } else {
            logcat(LogPriority.WARN) { "AI upscale unavailable for this ABI; falling back to Lanczos3" }
        }

        val scale = preferences.lanczosScale.get() / 100f
        if (scale <= 1f) return null
        val argb = ensureArgb(input) ?: return null
        return nativeLanczosProcess(argb, scale).takeUnless { it === argb }
    }

    /** Returns [input] if it is already a mutable ARGB_8888 bitmap, otherwise a copy. */
    private fun ensureArgb(input: Bitmap): Bitmap? {
        if (input.config == Bitmap.Config.ARGB_8888 && input.isMutable) {
            return input
        }
        // MihonSY fix: RGB_565 sources (Coil's memory-saving default) have no alpha
        // channel and Bitmap.copy()/Canvas conversion may fill alpha with 0, making
        // the Lanczos3 alpha-weighted resampler output fully transparent (black).
        // Copy to ARGB_8888 and force the alpha channel opaque.
        val copied = input.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        if (input.config != Bitmap.Config.ARGB_8888) {
            copied.setHasAlpha(false)
        }
        return copied
    }
}

/**
 * MihonSY: thrown when enhancement produced no bitmap (failed or skipped), so the
 * submit() flow can surface a non-success outcome to the reader badge.
 */
class NoEnhancementException : Exception("Enhancement produced no result")

package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Komiho: ncnn + Vulkan AI upscaler.
 *
 * Ported from HaoweiLi97/mihon_img_upscale and trimmed to the Vulkan backend (no
 * Qualcomm NPU, no Anime4K, no depth estimation). The native side lives in
 * `libwaifu2x-jni.so` (`app/src/main/cpp/waifu2x{,_jni}.cpp`) and is only built for
 * ABIs whose ncnn SDK is present under `third_party/`.
 *
 * The bundled model is a 2x residual ncnn network, so the output scale is fixed —
 * unlike the CPU resamplers there is no user-selectable scale factor.
 */
object Waifu2x {

    /** Asset folder + file stem of the bundled model. */
    private const val MODEL_ASSET_DIR = "w2xex-esrgan/AnimeVideo-MiniV1.8-W2xEX"
    private const val MODEL_STEM = "AnimeVideo-MiniV1.8-W2xEX"

    /** Fixed 2x: the network ends in a PixelShuffle(2) plus a 2x residual branch. */
    const val SCALE = 2

    /** Receptive field of the 10-layer 3x3 stack — matches the upstream W2xEX default. */
    private const val PADDING = 10

    /** ncnn precision mode: 0 = fp32 (the most portable across GPUs). */
    private const val PRECISION = 0

    /**
     * fp16 arithmetic (accumulators in fp16, on top of fp16 storage).
     *
     * `waifu2x.cpp` gates this on `vkdev->info.support_fp16_arithmetic()`, so devices that do
     * not support it silently keep fp32 arithmetic — safe to request unconditionally.
     * Verified supported on the Adreno 750 (log: `FP16 arithmetic ... supported=1`).
     */
    private const val FP16_ARITHMETIC = true

    /** Bump when the bundled model assets change so existing installs re-extract. */
    private const val MODEL_CACHE_VERSION = "1"

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var isInitialized = false

    init {
        libraryLoaded = try {
            System.loadLibrary("waifu2x-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            // No GPU build for this ABI (only arm64-v8a ships the ncnn SDK for now).
            logcat(LogPriority.WARN, e) { "Waifu2x: native library unavailable" }
            false
        }
    }

    /** False when this ABI has no GPU build — callers should fall back to the CPU resamplers. */
    val isSupported: Boolean get() = libraryLoaded

    /**
     * Runs AI upscaling on [input]. **Blocking** — call it from a background thread.
     * Returns the upscaled bitmap, or null when unavailable / failed (caller keeps the original).
     */
    fun process(context: Context, input: Bitmap, id: Int = -1): Bitmap? {
        if (!libraryLoaded || input.isRecycled) return null
        if (!isInitialized && !init(context)) return null

        val argb = if (input.config != Bitmap.Config.ARGB_8888) {
            try {
                input.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        } else {
            input
        } ?: return null

        return try {
            // Komiho 临时诊断（量完可删）：把「排队等待」与「纯推理」拆开。
            // nativeClearAbortProcessing() 内部要拿 g_lock（waifu2x_jni.cpp:357-362），
            // 所以它的耗时 ≈ 等上一个推理（可能 1–3 秒）释放锁的时间 = 排队等待。
            // 判据：wait 常年 ≈0 → 解码线程没被占住，方案 A 不必做；wait 经常上千毫秒
            // → 线程饥饿真实存在，再考虑把增强搬出解码器。
            // 用 android.util.Log 而非项目 logcat()：release 构建下 XLog 级别是 WARN
            // （App.kt 的 setupExhLogging），logcat() 的 DEBUG/INFO 会被整条吞掉。
            val waitStart = android.os.SystemClock.uptimeMillis()
            nativeClearAbortProcessing()
            val waitMs = android.os.SystemClock.uptimeMillis() - waitStart

            val procStart = android.os.SystemClock.uptimeMillis()
            val out = nativeProcess(argb, id)
            val procMs = android.os.SystemClock.uptimeMillis() - procStart

            android.util.Log.d(
                "Waifu2xTiming",
                "wait=${waitMs}ms inference=${procMs}ms total=${waitMs + procMs}ms " +
                    "src=${argb.width}x${argb.height}",
            )

            out?.takeUnless { it === argb }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Waifu2x: processing failed" }
            null
        } finally {
            if (argb !== input) argb.recycle()
        }
    }

    /** Releases native resources. Safe to call when nothing is loaded. */
    fun destroy() {
        if (!libraryLoaded) return
        try {
            nativeDestroy()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Waifu2x: destroy failed" }
        } finally {
            isInitialized = false
        }
    }

    /** Asks an in-flight inference to stop at its next cancellation check. */
    fun abortProcessing() {
        if (!libraryLoaded) return
        try {
            nativeAbortProcessing()
        } catch (_: Exception) {
            // ignored — abort is best-effort
        }
    }

    /** Real progress (0..100) of the current inference, or -1 when idle. */
    fun progressPercent(): Int = try {
        (nativeGetProgress() and 0xFFFFFFFFL).toInt()
    } catch (_: Exception) {
        -1
    }

    // Internals -----------------------------------------------------------------------

    private fun init(context: Context): Boolean = synchronized(this) {
        if (isInitialized) return true
        val dir = prepareModel(context)
        if (dir == null) {
            logcat(LogPriority.WARN) { "Waifu2x: bundled model not found in assets" }
            return false
        }
        isInitialized = try {
            nativeInitW2xEx(dir, MODEL_STEM, SCALE, PRECISION, FP16_ARITHMETIC, PADDING)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Waifu2x: native init threw" }
            false
        }
        if (!isInitialized) {
            logcat(LogPriority.WARN) { "Waifu2x: native init failed (Vulkan device missing?)" }
        }
        isInitialized
    }

    /** Extracts the bundled model into the cache dir and returns its absolute path. */
    private fun prepareModel(context: Context): String? = try {
        val dir = File(context.cacheDir, "waifu2x-models/$MODEL_STEM")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            null
        } else {
            val files = context.assets.list(MODEL_ASSET_DIR).orEmpty()
            if (files.isEmpty()) {
                null
            } else {
                val versionFile = File(dir, ".model-version")
                val refresh = versionFile.takeIf { it.exists() }?.readText() != MODEL_CACHE_VERSION
                for (name in files) {
                    val out = File(dir, name)
                    if (refresh || !out.exists() || out.length() == 0L) {
                        context.assets.open("$MODEL_ASSET_DIR/$name").use { input ->
                            out.outputStream().use(input::copyTo)
                        }
                    }
                }
                if (refresh) versionFile.writeText(MODEL_CACHE_VERSION)
                dir.absolutePath
            }
        }
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Waifu2x: failed to prepare model assets" }
        null
    }

    // JNI — see app/src/main/cpp/waifu2x_jni.cpp ---------------------------------------

    private external fun nativeInitW2xEx(
        modelDir: String,
        modelStem: String,
        scale: Int,
        precision: Int,
        fp16Arithmetic: Boolean,
        padding: Int,
    ): Boolean

    private external fun nativeProcess(bitmap: Bitmap, id: Int): Bitmap?

    private external fun nativeDestroy()

    private external fun nativeAbortProcessing()

    private external fun nativeClearAbortProcessing()

    private external fun nativeGetProgress(): Long
}

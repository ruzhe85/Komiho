package eu.kanade.tachiyomi.util.waifu2x

import android.graphics.Bitmap
import android.os.Build
import java.io.File

/**
 * Komiho (2026-10-27): MTK NPU 后端（LiteRT + NeuroPilot）的 Kotlin 入口。
 *
 * 现状（M1）：接口齐全，但**生产代码没有任何调用点** —— 模型包与引擎会话化在 M2/M3，
 * 因此对现有用户零影响，唯一可见变化是 APK 体积。
 *
 * 设计约束（后续里程碑必须守住）：
 *  - 加载惰性且静默：`loadLibrary` 失败只退化成「后端不可用」，绝不影响启动；
 *  - `liblitert-jni.so` 只在 arm64-v8a 编译，其他 ABI 恒为未加载；
 *  - 引擎状态全在 native 侧，且与 `waifu2x-jni`（Vulkan/QNN）完全隔离；
 *  - NPU 模式**要求全量委托**：`isNpuActive()` 为 false 就当作失败回退，绝不冒充满血。
 *
 * 模型契约（M3 的 `.tflite` 必须满足）：输入 `[1,256,256,3]` float32、输出
 * `[1,256*scale,256*scale,3]` float32，`scale ∈ {2,3}`，NHWC 连续。
 */
internal object LiteRtNative {

    /** 运行时库名（见 `app/src/main/cpp/litert_jni.cpp`）。 */
    const val RUNTIME_LIBRARY = "libLiteRt.so"
    const val MTK_COMPILER_PLUGIN = "libLiteRtCompilerPlugin_MediaTek.so"
    const val MTK_DISPATCH = "libLiteRtDispatch_MediaTek.so"

    /** 只在首次使用时加载；失败被吞掉（非 arm64 / 缺库都属于预期情况）。 */
    val isLoaded: Boolean by lazy {
        runCatching { System.loadLibrary("litert-jni") }.isSuccess
    }

    private external fun nativeVersion(): String

    private external fun nativeProbeRuntime(): Boolean

    private external fun nativeInitialize(
        modelPath: String,
        cacheDir: String,
        libraryDir: String,
        scale: Int,
        padding: Int,
        cpuOnly: Boolean,
    ): String

    private external fun nativeIsInitialized(): Boolean

    private external fun nativeIsActive(): Boolean

    private external fun nativeScale(): Int

    private external fun nativeTileSize(): Int

    private external fun nativeProcess(rgba: ByteArray, width: Int, height: Int): ByteArray?

    private external fun nativeProcessBitmap(bitmap: Bitmap): Bitmap?

    private external fun nativeGetProgress(): Int

    private external fun nativeLastInferenceMs(): Long

    private external fun nativeLastTotalMs(): Long

    private external fun nativeAbort()

    private external fun nativeClearAbort()

    private external fun nativeLastError(): String

    private external fun nativeShutdown()

    /** 后端版本串；未加载返回 null。 */
    fun version(): String? = if (isLoaded) runCatching { nativeVersion() }.getOrNull() else null

    /**
     * 三个运行时库是否都随包存在且可 open。
     * ⚠️ `true` ≠ NPU 可用——真正的判定是 [initialize] 成功 + [isNpuActive]。
     */
    fun isRuntimePresent(): Boolean =
        isLoaded && runCatching { nativeProbeRuntime() }.getOrDefault(false)

    /**
     * 是否联发科平台（宽口径：与上游同标准）。
     *
     * 只看厂商/平台名，**不做芯片级白名单** —— 真正的能力由 native 的全量委托校验兜底，
     * 判定过宽只会多花一次「首次编译后失败」的代价（失败会被上层记忆，不重复尝试）。
     */
    fun isMtkDevice(): Boolean {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return false
        val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            ""
        }
        return manufacturer.contains("mediatek", ignoreCase = true) ||
            Build.HARDWARE.startsWith("mt", ignoreCase = true)
    }

    /**
     * 初始化引擎（NPU 模式下会**同步编译**模型，首次可达数十秒 —— 必须放在 IO 线程）。
     *
     * @param model 已解包到本地的 `.tflite`
     * @param cacheDir NPU 编译缓存目录（LiteRT 独占写）
     * @param libraryDir `ApplicationInfo.nativeLibraryDir`（插件/调度库所在目录）
     * @param cpuOnly true = 只用 CPU（分块/进度/取消的验证通道，不需要 MTK 设备）
     * @return null = 成功；否则为错误描述
     */
    fun initialize(
        model: File,
        cacheDir: File,
        libraryDir: String,
        scale: Int,
        padding: Int,
        cpuOnly: Boolean = false,
    ): String? {
        if (!isLoaded) return "liblitert-jni not loaded"
        return runCatching {
            nativeInitialize(model.absolutePath, cacheDir.absolutePath, libraryDir, scale, padding, cpuOnly)
        }.getOrElse { it.message ?: "initialize failed" }
            .takeIf { it.isNotEmpty() }
    }

    fun isInitialized(): Boolean = isLoaded && runCatching { nativeIsInitialized() }.getOrDefault(false)

    /** 引擎是否真的跑在 NPU 上（CPU 验证模式恒为 false）。 */
    fun isNpuActive(): Boolean = isLoaded && runCatching { nativeIsActive() }.getOrDefault(false)

    fun scale(): Int = if (isLoaded) runCatching { nativeScale() }.getOrDefault(0) else 0

    fun tileSize(): Int = if (isLoaded) runCatching { nativeTileSize() }.getOrDefault(0) else 0

    /**
     * 整页放大：输入 RGBA_8888（`width*height*4`），输出放大后的 RGBA_8888。
     * 失败/被中止返回 null，原因见 [lastError]。
     *
     * 只给自检/工具用；阅读器生产路径走 [processBitmap]（省一次上百 MB 的字节数组）。
     */
    fun process(rgba: ByteArray, width: Int, height: Int): ByteArray? =
        if (!isLoaded) null else runCatching { nativeProcess(rgba, width, height) }.getOrNull()

    /**
     * 生产路径：Bitmap 进 / Bitmap 出（native 侧逐行拷贝，避开大页的额外内存峰值）。
     * 失败/被中止返回 null，原因见 [lastError]。
     */
    fun processBitmap(bitmap: Bitmap): Bitmap? =
        if (!isLoaded) null else runCatching { nativeProcessBitmap(bitmap) }.getOrNull()

    fun progress(): Int = if (isLoaded) runCatching { nativeGetProgress() }.getOrDefault(0) else 0

    /** 最近一次 [process] 的纯推理耗时（ms，-1 = 未知）。 */
    fun lastInferenceMs(): Long = if (isLoaded) runCatching { nativeLastInferenceMs() }.getOrDefault(-1L) else -1L

    /** 最近一次 [process] 的整页墙钟（ms，-1 = 未知）。 */
    fun lastTotalMs(): Long = if (isLoaded) runCatching { nativeLastTotalMs() }.getOrDefault(-1L) else -1L

    fun abort() {
        if (isLoaded) runCatching { nativeAbort() }
    }

    fun clearAbort() {
        if (isLoaded) runCatching { nativeClearAbort() }
    }

    fun lastError(): String =
        if (isLoaded) runCatching { nativeLastError() }.getOrDefault("") else "liblitert-jni not loaded"

    fun shutdown() {
        if (isLoaded) runCatching { nativeShutdown() }
    }
}

package eu.kanade.tachiyomi.util.waifu2x

/**
 * Komiho (2026-10-27): MTK NPU 后端（LiteRT + NeuroPilot）的 Kotlin 入口。
 *
 * **M0 脚手架**——只有加载与探针，**没有任何调用点**：现有 Vulkan / QNN 链路一行未改，
 * 用户可感知的变化只有 APK 体积（arm64 约 +6MB）。
 *
 * 设计约束（后续里程碑必须守住）：
 *  - 加载必须惰性且静默：`System.loadLibrary` 失败只能退化成「后端不可用」，绝不能影响启动；
 *  - `liblitert-jni.so` 只在 arm64-v8a 编译，其他 ABI 恒为未加载；
 *  - 本类不持有任何全局引擎状态——引擎归 native 侧的单例，且与 `waifu2x-jni` 完全隔离。
 */
internal object LiteRtNative {

    /** `dlopen` 探测用的三个运行时库（见 `app/src/main/cpp/litert_jni.cpp`）。 */
    const val RUNTIME_LIBRARY = "libLiteRt.so"
    const val MTK_COMPILER_PLUGIN = "libLiteRtCompilerPlugin_MediaTek.so"
    const val MTK_DISPATCH = "libLiteRtDispatch_MediaTek.so"

    /** 仅在首次使用时加载；失败被吞掉（非 arm64 / 缺库都属于预期情况）。 */
    val isLoaded: Boolean by lazy {
        runCatching { System.loadLibrary("litert-jni") }.isSuccess
    }

    private external fun nativeVersion(): String

    private external fun nativeProbeRuntime(): Boolean

    /** 后端版本串；未加载返回 null。 */
    fun version(): String? = if (isLoaded) runCatching { nativeVersion() }.getOrNull() else null

    /**
     * 三个运行时库是否都随包存在且可 open。
     *
     * ⚠️ `true` ≠ NPU 可用——真正的判定在 M2（全量委托 + `isActive`）。
     */
    fun isRuntimePresent(): Boolean =
        isLoaded && runCatching { nativeProbeRuntime() }.getOrDefault(false)
}

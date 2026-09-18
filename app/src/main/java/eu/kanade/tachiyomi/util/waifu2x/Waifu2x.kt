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
 * Models come from [AiUpscaleModel]: scale and tile padding are per-model properties, and
 * the native engine is rebuilt whenever the selected model differs from the running one.
 * Unlike the CPU resamplers there is still no free-form scale factor — the scale is baked
 * into the network itself.
 */
object Waifu2x {

    /**
     * Komiho: 单次推理的耗时拆分，用调用方传入的持有对象回传（不用共享字段，避免并发串号）。
     *
     * `process()` 里其实是**两次**拿 `g_lock`：
     * - [waitMs]：第一次（`nativeClearAbortProcessing()` 要拿锁）—— 等**别人**推理跑完的排队时间；
     * - `nativeProcess` 内部**再拿一次**，那段排队被并进了 [procMs]。
     *
     * ⇒ 单看 [waitMs] **不足以**剔除排队：实测某页 `wait=2413ms`、`procMs=4913ms`，而原生自报
     * 本次只跑了 2449ms —— 多出来的 2464ms 就是第二次排队。所以引入 [nativeInferenceMs]
     * （原生在每次运行结束时登记），由 [totalWaitMs] 把两段等锁一起算出来。
     *
     * 「显示增强状态」角标要的是「从 0 开始解码 + 增强的实际消耗」，必须把**两段**等锁都剔除，
     * 否则并发时一页会被显示成 4–9 秒（实测 `wait` 可到 4.4s / 9.7s）。
     */
    class Timing {
        @Volatile var waitMs = -1L
        @Volatile var procMs = -1L

        /** 原生自报的**纯推理**耗时；-1 = 未知（失败 / 被抢占 / 没拿到）。 */
        @Volatile var nativeInferenceMs = -1L

        /** 需要从「总流程」里剔除的等锁总时长（两段之和）；拿不到拆分的部分按 0 计。 */
        fun totalWaitMs(): Long {
            if (waitMs < 0) return 0L
            val secondWait = if (nativeInferenceMs > 0 && procMs > nativeInferenceMs) {
                procMs - nativeInferenceMs
            } else {
                0L
            }
            return waitMs + secondWait
        }
    }

    /**
     * ncnn precision mode。**0 = FP16**（见 `waifu2x.cpp:191`：`case 0` 与 `default` 同一分支，
     * 置 fp16 packed/storage/arithmetic；`waifu2x.h:48` 亦注明 `0 = fp16`）。
     * 1 = FP32、2 = INT8、3 = BF16 —— 与上游 `realCuganPrecision()` 的
     * 0:FP16 / 1:FP32 / 2:INT8 / 3:BF16 编号一致。
     *
     * 这里固定用 0（FP16），不暴露成设置项：
     *  - 最终输出只有 8 bit/通道，中间多几位精度肉眼看不见，FP32 反而慢约一倍、内存翻倍；
     *  - INT8 需要自行量化模型（本项目只有原始 fp32 权重，缺 scale/zero-point，硬跑会出错值），
     *    且超分是回归任务，量化误差会直接变成色带与噪点；
     *  - **只有 0/1 能启用 fused 快路径**：`waifu2x.cpp:208-211` 的 `gpu_pipeline_available`
     *    要求 `precision_mode == 1 || (precision_mode == 0 && support_fp16_storage())`，
     *    选 INT8/BF16 会掉回慢的 Staged 路径。
     * 日志实证（Adreno 750）：`Precision=0 FP16 arithmetic requested=1 supported=1 enabled=1`，
     * 且 `Embedded fused pipeline create: precision=fp16`。
     */
    private const val PRECISION = 0

    /**
     * fp16 arithmetic (accumulators in fp16, on top of fp16 storage).
     *
     * `waifu2x.cpp` gates this on `vkdev->info.support_fp16_arithmetic()`, so devices that do
     * not support it silently keep fp32 arithmetic — safe to request unconditionally.
     * Verified supported on the Adreno 750 (log: `FP16 arithmetic ... supported=1`).
     */
    private const val FP16_ARITHMETIC = true

    /**
     * Bump when the bundled model assets change so existing installs re-extract.
     *
     * 2026-09-17 v2: QNN contexts moved from a single v81 target to a per-arch set
     * (v75 + v79), and the cached file name changed with them. Without a bump, an install
     * that had already extracted `*.v81.bin` would keep that stale file and the new finger
     * print would never be honoured.
     *
     * 2026-09-19 v3: the [AiUpscaleModel.QnnUniversalFastV2Fp16] contexts were recompiled —
     * same *file names*, different bytes (NHWC-wrapped so the graph I/O matches this code's
     * contract). Note that an app **update does not clear `cacheDir`** on AOSP, so a plain
     * reinstall would otherwise keep serving the old file: during debugging two different
     * builds reported the exact same loader error because of this.
     */
    private const val MODEL_CACHE_VERSION = "3"

    /**
     * Komiho: picks the context asset for [model] on a device whose on-chip HTP is [arch].
     *
     * Each NPU entry ships one context per generation ([AiUpscaleModel.qnnArches]) because
     * QNN context binaries are **not** Flexible Context Binaries — byte-level comparison of
     * the v75/v79/v81 builds of one model shows 85–94% differing bytes and different file
     * lengths, so a context only loads on the generation it was compiled for.
     *
     * A device whose arch we do not ship returns null; [ensureEngine] then logs the
     * mismatch and falls back to Vulkan rather than handing the engine a file that cannot
     * load. This is intentionally a *runtime* lookup: the same APK serves v75 and v79
     * devices, and the choice is made from the probed hardware, not a build-time constant.
     */
    fun contextAssetFor(model: AiUpscaleModel, arch: Int): String? {
        if (arch <= 0) return null
        return model.stem + ".v" + arch + ".bin"
    }

    /**
     * Komiho: AI tile edge (px) — the native default is 128 (`waifu2x.cpp:150`).
     * Each tile allocates `(tilesize + 2*prepadding)` input and `tilesize * scale` output,
     * so the GPU working set grows with the square of this value.
     */
    private const val DEFAULT_TILE_SIZE = 128

    /**
     * 256 is the ceiling the bundled `prepadding = 18` is documented safe for
     * (`waifu2x.cpp:151`); below 64 the per-tile overhead starts to dominate.
     */
    private const val MIN_TILE_SIZE = 64
    private const val MAX_TILE_SIZE = 256

    /**
     * `tile_sleep_ms` — inter-tile sleep for thermal throttling, 0 = full speed.
     * Kept at the engine default (`waifu2x.h:44`); forwarded only because
     * `nativeUpdatePerformanceConfig` sets both fields in one call.
     */
    private const val TILE_SLEEP_MS = 0

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var isInitialized = false

    /** Tile size the caller asked for; applied on the next [process] call. */
    @Volatile
    private var requestedTileSize = DEFAULT_TILE_SIZE

    /** Tile size currently pushed to the native engine; -1 = not yet applied. */
    @Volatile
    private var appliedTileSize = -1

    /** Model the caller asked for; the engine is (re)built for it on the next [process]. */
    @Volatile
    private var requestedModel: AiUpscaleModel = AiUpscaleModel.Default

    /** Model the running native engine was built for; null = no engine yet. */
    @Volatile
    private var activeModel: AiUpscaleModel? = null

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
     * Komiho: sets the AI tile edge (px) for subsequent inferences, clamped to 64..256.
     *
     * The value is applied lazily on the next [process] call rather than here — the native
     * engine only exists after [init], so pushing it eagerly would be a no-op on a cold
     * start. Forwarding is guarded by a change check because the native side takes the
     * engine lock.
     */
    fun setTileSize(size: Int) {
        requestedTileSize = size.coerceIn(MIN_TILE_SIZE, MAX_TILE_SIZE)
    }

    /**
     * Komiho: selects the GPU model ([AiUpscaleModel]) for subsequent inferences.
     *
     * Applied lazily on the next [process] call. Switching models is expensive — the native
     * engine is torn down and re-initialised (model load + Vulkan pipeline creation) — so
     * this only records the intent; [ensureEngine] compares it against the running model and
     * short-circuits when they match, keeping the hot path free of extra work.
     *
     * Note for the cross-HTP experiment: a QNN model is **never blacklisted** on failure, so
     * every attempt still probes the real device. [lastFailedQnnModel] exists only for
     * observability, letting the diagnostics distinguish "NPU actually ran" from
     * "NPU was selected but the context did not load".
     */
    fun setModel(model: AiUpscaleModel) {
        requestedModel = model
    }

    /**
     * Last QNN model whose context failed to initialise on this device, or null if the most
     * recent attempt succeeded. Diagnostic only — nothing consults it to change behaviour.
     */
    @Volatile
    var lastFailedQnnModel: AiUpscaleModel? = null
        private set

    /**
     * The engine that actually produced the most recent successful inference.
     *
     * Komiho (2026-09-17): the reader badge used to report success/failure only, so a
     * **silent fallback** looked identical to a real NPU run — the v81-on-v75 experiment was
     * misread as "NPU works" for exactly that reason. The badge now reports the engine that
     * genuinely ran, which is derived here from the live engine state rather than from what
     * the user selected.
     *
     * [EngineKind.QNN_HTP] is reported only when the QNN engine is the one currently loaded
     * ([activeModel] has the QNN backend); if init failed, [ensureEngine] has already swapped
     * [requestedModel] to [AiUpscaleModel.Default], so the next successful run reports
     * [EngineKind.NCNN_VULKAN] instead.
     */
    enum class EngineKind { NONE, NCNN_VULKAN, QNN_HTP }

    /** Engine that ran the last successful [process] call; [EngineKind.NONE] if none yet. */
    @Volatile
    var lastEngine: EngineKind = EngineKind.NONE
        private set

    /**
     * Komiho (2026-09-19): engine recorded **per page index**, because the reader badge is
     * drawn per page while [lastEngine] is one global slot.
     *
     * Why the global slot alone was wrong: enhancements run concurrently for neighbouring
     * pages (background prewarm + the page on screen), and [markCpuFallback] used to clear
     * the global value for *any* page that produced no result — including pages whose
     * inference the pager deliberately aborted when the user swiped. The next badge to be
     * drawn then read `NONE` and printed `CPU OK` on a page that had in fact run on the NPU.
     *
     * Never evicted: the key space is the current chapter's page count, so it stays tiny and
     * is simply overwritten when another chapter is opened.
     */
    private val engineByPage = java.util.concurrent.ConcurrentHashMap<Int, EngineKind>()

    /**
     * Komiho: engine that produced the image for [pageIndex].
     *
     * Falls back to the global [lastEngine] when this page has no record yet and for callers
     * that have no page number (prepared-cache reuse), so the badge never goes blank.
     */
    fun engineFor(pageIndex: Int): EngineKind =
        if (pageIndex >= 0) engineByPage[pageIndex] ?: lastEngine else lastEngine

    /**
     * Komiho: records that [pageIndex] had to be completed on the **CPU** resampler because
     * neither the NPU nor the Vulkan engine produced a result.
     *
     * Called by [MihonSY]'s enhancer on the CPU fallback path. Without it the badge would
     * keep showing the previous page's engine — the state is sticky by design (a page that
     * never reaches [process] must not clear an earlier result), so the transition to CPU
     * has to be reported explicitly.
     *
     * Scoped to [pageIndex] so one page's fallback can no longer mislabel its neighbours;
     * `pageIndex < 0` (no page number) still clears the global slot only.
     */
    fun markCpuFallback(pageIndex: Int = -1) {
        lastEngine = EngineKind.NONE
        if (pageIndex >= 0) {
            engineByPage[pageIndex] = EngineKind.NONE
        }
    }

    /**
     * Runs AI upscaling on [input]. **Blocking** — call it from a background thread.
     * Returns the upscaled bitmap, or null when unavailable / failed (caller keeps the original).
     *
     * @param tag Komiho 诊断：请求来源标识（如 `prewarm#12` / `holder#12`），只写进日志。
     * @param timing Komiho：非空时回传本次的等锁/纯推理耗时拆分（角标要用「剔除等锁」的口径）。
     */
    fun process(
        context: Context,
        input: Bitmap,
        id: Int = -1,
        tag: String = "",
        timing: Timing? = null,
    ): Bitmap? {
        if (!libraryLoaded || input.isRecycled) return null
        if (!ensureEngine(context)) return null
        applyTileSizeIfNeeded()

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
            // ⚠️ 这**只是第一段**排队：nativeProcess 内部还会再拿一次同样的锁，
            // 那段被计入下面的 `inference`（用 pure= 才能摘出来，见 [Timing] 的说明）。
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

            // Komiho：记录**真正跑完的那台引擎**，供阅读器角标显示 CPU/GPU/NPU OK。
            // 必须在推理成功之后才登记：只有 nativeProcess 返回了结果，才能说这条路径成立。
            // activeModel 是引擎侧的事实（QNN 加载失败时 ensureEngine 已把它换成 Default），
            // 所以这里读它而不是读 requestedModel —— 否则又会把「回落 Vulkan」报成 NPU。
            // 2026-09-19：同时按 `id`（页号）登记 —— 角标是每页画的，而 lastEngine 是全局槽，
            // 并发页（预取 + 当前页）会互相覆盖，见 [engineByPage] 的说明。
            if (out != null && out !== argb) {
                val kind = when (activeModel?.backend) {
                    AiUpscaleModel.Backend.QNN_HTP -> EngineKind.QNN_HTP
                    AiUpscaleModel.Backend.NCNN_VULKAN -> EngineKind.NCNN_VULKAN
                    null -> null
                }
                if (kind != null) {
                    lastEngine = kind
                    if (id >= 0) {
                        engineByPage[id] = kind
                    }
                }
            }

            // Komiho：原生自报的纯推理耗时（已剔除 nativeProcess 内部那次 g_lock 排队）。
            // 读不到就保持 -1，角标退回「只剔第一段等锁」的旧口径（fail-open，不会算错方向）。
            val pureMs = try {
                nativeGetLastInferenceMs()
            } catch (e: Throwable) {
                -1L
            }

            timing?.waitMs = waitMs
            timing?.procMs = procMs
            timing?.nativeInferenceMs = pureMs

            // Komiho 诊断：把「排队等待」与「纯推理」拆开。
            // ⚠️ 老脚本按 `total=…ms src=… from=…` 解析，所以新字段一律追加在 `from=` **之后**。
            android.util.Log.d(
                "Waifu2xTiming",
                "wait=${waitMs}ms inference=${procMs}ms total=${waitMs + procMs}ms " +
                    "src=${argb.width}x${argb.height} from=${tag.ifEmpty { "?" }} " +
                    "pure=${pureMs}ms queue2=${if (pureMs > 0) procMs - pureMs else -1}ms",
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
            activeModel = null
            appliedTileSize = -1
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

    /**
     * Forwards [requestedTileSize] to the native engine, skipping the call when unchanged.
     * Must run after [init] — `nativeUpdatePerformanceConfig` is a no-op while no engine exists.
     */
    private fun applyTileSizeIfNeeded() {
        val size = requestedTileSize
        if (size == appliedTileSize) return
        try {
            nativeUpdatePerformanceConfig(TILE_SLEEP_MS, size)
            appliedTileSize = size
        } catch (e: Throwable) {
            // Symbol missing in an older .so — keep the engine default rather than failing
            // the pass. Caught as Throwable because JNI resolution failures surface as
            // UnsatisfiedLinkError (an Error, not an Exception).
            logcat(LogPriority.WARN, e) { "Waifu2x: failed to apply tile size $size" }
        }
    }

    /**
     * Makes sure a native engine is running for [requestedModel], building it if needed.
     *
     * Also covers model switches: a different entry means a different network (weights,
     * scale, tile padding), and the native side cannot swap that in place —
     * `nativeInitW2xEx` deletes the previous instance and constructs a new one, waiting for
     * any in-flight inference to release the engine lock first.
     *
     * Komiho: QNN models take the [Backend.QNN_HTP] branch — they load a prebuilt context
     * binary instead of the ncnn engine. When that fails (unsupported HTP arch, missing
     * runtime, corrupt context) the request silently falls back to the Vulkan engine with
     * [AiUpscaleModel.Default], so an NPU selection can never degrade below the status quo.
     */
    private fun ensureEngine(context: Context): Boolean = synchronized(this) {
        val model = requestedModel
        if (isInitialized && activeModel == model) return true

        if (model.backend == AiUpscaleModel.Backend.QNN_HTP) {
            val ok = initQnnEngine(context, model)
            if (ok) {
                isInitialized = true
                activeModel = model
                lastFailedQnnModel = null
                logcat(LogPriority.WARN) { "Waifu2x: NPU ACTIVE — ${model.id} running on HTP" }
                // QNN 的 tile 几何来自 context（设置项对它不生效）——标记为「已应用」，
                // 免得第一次 process 还去 nativeUpdatePerformanceConfig 白拿一次引擎锁。
                appliedTileSize = requestedTileSize
                return true
            }
            // Komiho（2026-09-17 跨 HTP 试验期）：**故意不回写偏好、也不拉黑**。
            // 这次是在多台高通机上试「v81 context 到底能不能跑」，一旦失败就把设置改回
            // Default，用户在界面上看不到自己选了什么，也无法重试 —— 测试就做不下去。
            // 因此这里只做两件事：留痕（下面这条 WARN 带 arch，一眼可判）+ 回落到 Vulkan
            // 保证当页仍能出图。恢复正常行为时再把 onQnnFallback 接回去即可。
            lastFailedQnnModel = model
            logcat(LogPriority.WARN) {
                "Waifu2x: NPU UNAVAILABLE — QNN init failed for ${model.id} " +
                    "(on-chip HTP v$detectedQnnArchitecture vs packed arches " +
                    "${AiUpscaleModel.packedQnnArches.joinToString { it.toString() }}); " +
                    "falling back to Vulkan Default"
            }
            requestedModel = AiUpscaleModel.Default
            onQnnFallback?.invoke(model)
            return ensureEngineLocked(context, AiUpscaleModel.Default)
        }
        return ensureEngineLocked(context, model)
    }

    /**
     * Invoked when a QNN model had to fall back to Vulkan at init time. The UI layer uses it
     * to persist the correction so the setting stops lying about the active engine.
     */
    var onQnnFallback: ((AiUpscaleModel) -> Unit)? = null

    /** Vulkan/ncnn branch of [ensureEngine] (kept out of the dispatcher for clarity). */
    private fun ensureEngineLocked(context: Context, model: AiUpscaleModel): Boolean {
        val dir = prepareModel(context, model)
        if (dir == null) {
            logcat(LogPriority.WARN) { "Waifu2x: model assets missing for ${model.id}" }
            return false
        }

        val ok = try {
            nativeInitW2xEx(dir, model.stem, model.scale, PRECISION, FP16_ARITHMETIC, model.padding)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Waifu2x: native init threw" }
            false
        }
        isInitialized = ok
        activeModel = if (ok) model else null
        if (ok) {
            // 新引擎回到原生默认 tilesize(128)，旧值随上一个引擎一起释放 —— 标记为待重新下发。
            appliedTileSize = -1
        } else {
            logcat(LogPriority.WARN) { "Waifu2x: native init failed (Vulkan device missing?)" }
        }
        return ok
    }

    /**
     * Komiho: loads a QNN context binary for [model] and initialises the HTP engine.
     *
     * The context is extracted from assets into `cacheDir/qnn-contexts/` (versioned like the
     * ncnn models), then handed to [nativeInitQnn] together with the app's
     * `nativeLibraryDir` — the HTP Skel library is loaded by the DSP runtime, which resolves
     * `ADSP_LIBRARY_PATH` against that directory. The padding travels with the model entry.
     *
     * The file is chosen from the **probed** on-chip arch ([contextAssetFor]), so one entry
     * covers both generations we ship. An arch we have no context for fails here — before
     * the DSP is asked to do anything — and the caller falls back to Vulkan.
     */
    private fun initQnnEngine(context: Context, model: AiUpscaleModel): Boolean {
        val arch = detectedQnnArchitecture
        val asset = contextAssetFor(model, arch)
        if (asset == null) {
            logcat(LogPriority.WARN) {
                "Waifu2x: no QNN context for ${model.id} on HTP v$arch " +
                    "(packed arches: ${AiUpscaleModel.packedQnnArches.joinToString { it.toString() }})"
            }
            return false
        }
        val contextPath = prepareQnnContext(context, model, asset) ?: return false
        val libraryDir = context.applicationInfo.nativeLibraryDir
        return try {
            nativeInitQnn(contextPath, libraryDir, model.padding)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Waifu2x: nativeInitQnn threw (missing symbol?)" }
            false
        }
    }

    /** Extracts a QNN context from assets; returns its absolute path, or null. */
    private fun prepareQnnContext(context: Context, model: AiUpscaleModel, asset: String): String? = try {
        val dir = File(context.cacheDir, "qnn-contexts")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            null
        } else {
            val out = File(dir, asset)
            val versionFile = File(dir, ".model-version")
            val refresh = versionFile.takeIf { it.exists() }?.readText() != MODEL_CACHE_VERSION
            if (refresh || !out.exists() || out.length() == 0L) {
                context.assets.open("${model.assetDir}/$asset").use { input ->
                    out.outputStream().use(input::copyTo)
                }
            }
            if (refresh) versionFile.writeText(MODEL_CACHE_VERSION)
            out.absolutePath
        }
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Waifu2x: failed to prepare QNN context" }
        null
    }

    // Komiho: NPU 门控 ————————————————————————————————————————————————————————————

    /** Cached result of [nativeIsQnnRuntimeAvailable]; null = not probed yet. */
    @Volatile
    private var qnnRuntimeAvailable: Boolean? = null

    /** Cached result of [nativeGetQnnArchitecture]; -1 = not probed yet. */
    @Volatile
    private var qnnArchitecture: Int = -1

    /**
     * Whether an NPU model entry should be offered on this device.
     *
     * Komiho (2026-09-17): the original probe only dlopen'd `libQnnHtp.so`, but those
     * libraries ship inside our own APK, so the call succeeds on **every** device —
     * including non-Qualcomm ones, where it used to leak the whole NPU group into the
     * model list and then silently fall back to Vulkan at inference time.
     *
     * The real discriminator is the on-chip HTP: a non-Qualcomm device gets no
     * `ON_CHIP` hardware device from `deviceGetPlatformInfo`, so [nativeGetQnnArchitecture]
     * reports 0. NPU entries are then hidden and stored ids normalise to [AiUpscaleModel.Default].
     */
    val isQnnRuntimeAvailable: Boolean
        get() {
            if (!libraryLoaded) return false
            return qnnRuntimeAvailable ?: run {
                val available = try {
                    nativeIsQnnRuntimeAvailable() && detectedQnnArchitecture > 0
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN, e) { "Waifu2x: QNN probe failed" }
                    false
                }
                qnnRuntimeAvailable = available
                available
            }
        }

    /**
     * Detected on-chip HTP architecture (75 / 79 / 81 …), or 0 when this device has no
     * usable HTP. Probed once and cached — it dlopens the QNN runtime.
     */
    val detectedQnnArchitecture: Int
        get() {
            if (!libraryLoaded) return 0
            if (qnnArchitecture >= 0) return qnnArchitecture
            val arch = try {
                nativeGetQnnArchitecture()
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Waifu2x: QNN architecture probe failed" }
                0
            }
            logcat(LogPriority.WARN) { "Waifu2x: detected HTP architecture v$arch" }
            qnnArchitecture = arch
            return arch
        }

    /**
     * UI filter: an NPU entry is offered on any device with a real Qualcomm HTP, regardless
     * of generation.
     *
     * Komiho (2026-09-17): the gate deliberately does **not** compare against the packed
     * arch set ([AiUpscaleModel.qnnArches]). We ship contexts for v75 and v79, but a newer
     * HTP runs older contexts (downward compatible), so restricting by architecture would
     * hide entries on devices that can actually run them. An unmatched arch reports an init
     * failure and the page falls back to Vulkan — the badge then shows what really ran,
     * which is the whole point of observing the actual execution path.
     *
     * Non-Qualcomm hardware reports architecture 0 (no `ON_CHIP` HTP device) and stays hidden.
     * Vulkan entries are always shown (their failure path is the CPU resampler fallback).
     */
    fun isModelSupported(model: AiUpscaleModel): Boolean = when (model.backend) {
        AiUpscaleModel.Backend.NCNN_VULKAN -> true
        AiUpscaleModel.Backend.QNN_HTP -> isQnnRuntimeAvailable
    }

    /**
     * Extracts the given model's assets into the cache dir and returns its absolute path.
     *
     * Each model gets its own directory keyed by [AiUpscaleModel.id] so switching models
     * never mixes files; [MODEL_CACHE_VERSION] invalidates previously extracted copies.
     */
    private fun prepareModel(context: Context, model: AiUpscaleModel): String? = try {
        val dir = File(context.cacheDir, "waifu2x-models/${model.id}")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            null
        } else {
            val files = context.assets.list(model.assetDir).orEmpty()
            if (files.isEmpty()) {
                null
            } else {
                val versionFile = File(dir, ".model-version")
                val refresh = versionFile.takeIf { it.exists() }?.readText() != MODEL_CACHE_VERSION
                for (name in files) {
                    val out = File(dir, name)
                    if (refresh || !out.exists() || out.length() == 0L) {
                        context.assets.open("${model.assetDir}/$name").use { input ->
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

    /**
     * Komiho: 原生登记的「最近一次推理纯耗时」（ms，不含等锁）；-1 = 未知。
     *
     * 须**紧跟 [nativeProcess] 之后**读：原生在每次运行结束时写值，而下一个任务的写入要等它
     * 自己跑完（≥1 秒）才会发生，所以这里不会串到别人的数字。用途见 [Timing.totalWaitMs]。
     * 若 .so 里没有这个符号（例如本地 ABI 没更新）会抛 UnsatisfiedLinkError —— 调用处已兜住。
     */
    private external fun nativeGetLastInferenceMs(): Long

    /**
     * Komiho: forwards tile geometry to the running engine (`waifu2x_jni.cpp:606`).
     * Takes the engine lock, so call it only when the value actually changes.
     * `tileSleepMs` is the inter-tile cooling sleep; 0 = full speed.
     */
    private external fun nativeUpdatePerformanceConfig(tileSleepMs: Int, tileSize: Int)

    private external fun nativeDestroy()

    private external fun nativeAbortProcessing()

    private external fun nativeClearAbortProcessing()

    private external fun nativeGetProgress(): Long

    // Komiho: QNN/HTP — see app/src/main/cpp/waifu2x_jni.cpp -------------------------

    /** dlopens `libQnnHtp.so`; false when this device has no usable QNN runtime. */
    private external fun nativeIsQnnRuntimeAvailable(): Boolean

    /**
     * On-chip HTP architecture (75 / 79 / 81 …) read from the QNN platform info, or 0 when
     * this device has no usable HTP. This — not [nativeIsQnnRuntimeAvailable] — is what
     * separates a real Qualcomm NPU from "our own .so happened to load".
     */
    private external fun nativeGetQnnArchitecture(): Int

    /**
     * Loads a prebuilt context binary into the HTP engine.
     * Takes the engine lock; also exports `ADSP_LIBRARY_PATH` for the DSP-side Skel lookup.
     */
    private external fun nativeInitQnn(contextPath: String, nativeLibraryDir: String, padding: Int): Boolean

    // NOTE: the QNN arch set is declared at file scope in `AiUpscaleModel.kt` (the catalogue
    // owns which generations ship; it is re-exposed as
    // `AiUpscaleModel.packedQnnArches` for reporting), and the per-device file is resolved by
    // [contextAssetFor]. Do NOT add a `companion object` here — `Waifu2x` is already a
    // standalone `object`, so a second one is a compile error: "Modifier 'companion' is not
    // applicable inside 'standalone object'".
}

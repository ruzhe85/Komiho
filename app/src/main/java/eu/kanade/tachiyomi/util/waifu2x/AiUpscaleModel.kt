package eu.kanade.tachiyomi.util.waifu2x

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Komiho: HTP generations every NPU entry ships a context for — v75 (SM8650 / 8 Gen 3)
 * and v79 (SM8750 / 8 Elite), so one APK covers both test devices.
 *
 * ⚠️ Must stay in sync with the `<stem>.v<arch>.bin` files under `assets/qnn-contexts/`
 * and the `libQnnHtpV<arch>{Skel,Stub}.so` pair under `jniLibs/arm64-v8a/`. The DSP looks
 * the Skel up by its own on-chip arch **before** any context is read, so a missing Skel
 * fails the whole init (`loadRemoteSymbols failed with err 4000`) even when the context
 * itself would have loaded.
 *
 * Declared here — at file scope, *not* in the companion object — because enum entries are
 * initialised before the companion: `qnnArches = QNN_ARCHES` inside an entry only compiles
 * against a top-level declaration. See the class KDoc for the full explanation.
 */
private val QNN_ARCHES: List<Int> = listOf(75, 79)

/**
 * Komiho: the model catalogue for the AI upscaler.
 *
 * The engine rebuilds itself whenever the selected entry differs from the running one.
 * Since 2026-09-17 an entry also carries a [backend]: the classic ncnn+Vulkan models and
 * the Qualcomm NPU (QNN/HTP) context binaries live side by side in this catalogue, and
 * [Waifu2x.ensureEngine] branches on the backend when building the native engine.
 *
 * Adding a Vulkan model is: drop the `.param`/`.bin` pair under `assets/`, add one enum
 * entry with its asset path. Adding an NPU model is: drop the `.<arch>.bin` context under
 * `assets/qnn-contexts/` and add an entry with [Backend.QNN_HTP]. Nothing else needs to
 * change — the reader UI lists [entries] and persists [id].
 *
 * @property id stable, persisted identifier — never rename an existing one.
 * @property assetDir folder under `assets/` holding the model files.
 * @property stem Vulkan models: file stem (`assetDir/stem.param` + `assetDir/stem.bin`).
 *   QNN models: context file stem (`assetDir/stem.v<arch>.bin`).
 * @property scale output scale baked into the network.
 * @property padding receptive-field halo required per tile; must match the network depth.
 * @property labelRes display name shown under its platform group (GPU for Vulkan, NPU for QNN); model names are not translated.
 * @property backend which native engine runs this model.
 * @property qnnArches HTP architectures this entry ships a context for. Empty for Vulkan
 *   models. One entry can cover several generations at once — the matching file
 *   (`<stem>.<arch>.bin`) is picked at init time from the device's on-chip HTP arch, see
 *   [Waifu2x.contextAssetFor]. QNN context binaries are **not** Flexible Context Binaries,
 *   so each generation needs its own file.
 *
 * ⚠️ Kotlin initialisation order: an enum entry's arguments are evaluated **before** the
 * enum's `companion object` is initialised, so an entry can never reference a companion
 * member — `qnnArches = QNN_ARCHES` fails to compile with *"Companion object of enum class
 * 'AiUpscaleModel' is uninitialized here"*. The arch list therefore lives as a file-level
 * private constant ([QNN_ARCHES]) that the entries can see, and is re-exposed to callers
 * as [companion object.packedQnnArches].
 */
enum class AiUpscaleModel(
    val id: String,
    val assetDir: String,
    val stem: String,
    val scale: Int,
    val padding: Int,
    val labelRes: StringResource,
    val backend: Backend = Backend.NCNN_VULKAN,
    val qnnArches: List<Int> = emptyList(),
) {
    /**
     * 2x residual ESRGAN-style network, 10 conv layers, ~89 KB of weights.
     * Small enough that shipping it in the APK costs nothing measurable.
     */
    AnimeVideoMiniV18(
        id = "animevideo-mini-v18-w2xex",
        assetDir = "w2xex-esrgan/AnimeVideo-MiniV1.8-W2xEX",
        stem = "AnimeVideo-MiniV1.8-W2xEX",
        scale = 2,
        padding = 10,
        labelRes = MR.strings.ai_model_anime_video,
    ),

    /**
     * Omni-Mini V2 — verified **layer-for-layer identical** to [AnimeVideoMiniV18]: same 24
     * layers, same 10 convolutions, same channel ladder (24 … 24 → 12), same 44,712 weight
     * elements. Only the training run differs, so running it costs exactly the same.
     *
     * Its `.bin` is larger (176 KB vs 89 KB) purely because the weights are stored **fp32**
     * instead of fp16 — that must not be read as more compute.
     */
    OmniMiniV2(
        id = "omni-mini-v2-w2xex",
        assetDir = "w2xex-esrgan/Omni-MiniV2-W2xEX",
        stem = "Omni-MiniV2-W2xEX",
        scale = 2,
        padding = 10,
        labelRes = MR.strings.ai_model_omni_mini,
    ),

    /**
     * Omni-Turbo V1.5 — same author's retrained sibling of [AnimeVideoMiniV18].
     *
     * Verified by parsing both `.param` files: identical topology (24 layers, 10 convolutions,
     * PReLU x9, `PixelShuffle(0=2)`, bilinear `Interp` bypass), so [scale] and [padding] carry
     * over unchanged — tile seams behave exactly like the existing model.
     *
     * Weights are heavier though: channels are 64 wide instead of 24, i.e. **303,552 weight
     * elements = 6.79x** the compute of [AnimeVideoMiniV18] (measured 0.99 s per 2.97 MP page
     * for the latter, so expect roughly 6 s here). Use it when quality matters more than speed.
     */
    OmniTurboV15(
        id = "omni-turbo-v15-w2xex",
        assetDir = "w2xex-esrgan/Omni-TurboV1.5-W2xEX",
        stem = "Omni-TurboV1.5-W2xEX",
        scale = 2,
        padding = 10,
        labelRes = MR.strings.ai_model_omni_turbo,
    ),

    /**
     * Komiho: Qualcomm NPU (QNN/HTP) context — W2xEX Photo-Small x2, int8.
     *
     * One of the two NPU models kept after the 2026-09-18 retune. The fp16 sibling,
     * Real-CUGAN-Pro, RealESRGAN-animevideov3, Real-CUGAN-SE-conservative and
     * Span-NomosUni entries were all dropped (severe colour blocking / user preference).
     * This int8 build is the photo-quality specialist; the other survivor is
     * [QnnRealEsrganGeneralX4v3X2Int8] (general-purpose, [padding] = 10).
     *
     * padding = 18: 40-layer net with 18 stride-1 convolutions (verified from its `.param`
     * before the GPU entry was dropped), i.e. per-side halo 18.0.
     */
    QnnW2xexPhotoSmallX2Int8(
        id = "qnn-w2xex-photo-small-x2-int8",
        assetDir = "qnn-contexts",
        stem = "w2xex-photo-small-x2-int8",
        scale = 2,
        padding = 18,
        labelRes = MR.strings.ai_model_qnn_w2xex_photo_int8,
        backend = Backend.QNN_HTP,
        qnnArches = QNN_ARCHES,
    ),

    /**
     * Komiho: Qualcomm NPU (QNN/HTP) context — Real-ESRGAN General x4v3, int8 (2x output).
     *
     * The x4v3 general model resized to 2x output (its graph keeps the x4 weights but
     * runs at 2x). [padding] = 10 follows the Real-ESRGAN family convention used by the
     * reference QNN backend (`prepadding = 10` for Real-ESRGAN in waifu2x_jni.cpp) and
     * ncnn's standard tile overlap. int8 keeps it small and fast on the HTP MAC units.
     */
    QnnRealEsrganGeneralX4v3X2Int8(
        id = "qnn-realesrgan-general-x4v3-x2-int8",
        assetDir = "qnn-contexts",
        stem = "realesrgan-general-x4v3-x2-int8",
        scale = 2,
        padding = 10,
        labelRes = MR.strings.ai_model_qnn_realesrgan_general_x4v3_int8,
        backend = Backend.QNN_HTP,
        qnnArches = QNN_ARCHES,
    ),

    /**
     * Komiho: Qualcomm NPU (QNN/HTP) context — W2xEX Universal-Fast V2, **fp16** (2x output).
     *
     * Compiled **locally** on 2026-09-19 using the amd64 `QnnHtp.dll` that ships inside
     * `onnxruntime-qnn` — no QAIRT SDK, no Docker and no AI Hub were involved. Pipeline:
     * `Universal-FastV2-W2xEX.param/.bin` → ONNX(fp32) → ONNX(fp16) → `<stem>.v<arch>.bin`.
     * The reusable script lives in the `komiho-add-upscale-model` skill
     * (`references/qnn_pipeline/local_compile_context.py`).
     *
     * Why fp16 instead of int8: **fp16 needs no calibration set**, which is what makes local
     * compilation practical (int8/W8A16 would require a representative image set). The cost is
     * size — 1,714,560 B (v75) / 1,722,752 B (v79), against 907,336 B for the int8 sibling with
     * identical weight count. [Backend.QNN_HTP] needs no change for this: `qnn_backend.cpp`
     * already accepts fp16 input tensors (`is_fp16_tensor`).
     *
     * padding = 18: same 40-layer / 18-convolution topology as [QnnW2xexPhotoSmallX2Int8],
     * verified by parsing its `.param` (18 Convolution + 17 PReLU, PixelShuffle 0=2).
     */
    QnnUniversalFastV2Fp16(
        id = "qnn-universal-fast-v2-fp16",
        assetDir = "qnn-contexts",
        stem = "universal-fast-v2",
        scale = 2,
        padding = 18,
        labelRes = MR.strings.ai_model_qnn_universal_fast,
        backend = Backend.QNN_HTP,
        qnnArches = QNN_ARCHES,
    ),

    // Photo-Small W2xEX 曾在此处（40 层 / 18 卷积 / 598,464 权重元素 = 13.4x 算力 / padding 18），
    // 2026-09-16 按用户反馈「效果很差」移除。若要恢复：把 assets/w2xex-esrgan/Photo-Small-W2xEX/
    // 放回去 + 三语补 ai_model_photo_small，并注意它的 padding 是 18（不是同族的 10）。
    // 其同网络的 NPU 版已回归：见上方 [QnnW2xexPhotoSmallX2Int8]（QNN context，非 Vulkan）。
    ;

    /** Which native engine executes this model. */
    enum class Backend { NCNN_VULKAN, QNN_HTP }

    companion object {
        /** Model used on a fresh install and whenever a stored id is unknown. */
        val Default: AiUpscaleModel = AnimeVideoMiniV18

        /**
         * The packed HTP generations — see the file-level [QNN_ARCHES] declaration.
         *
         * Re-exposed here for callers that want to report which generations an APK carries
         * (e.g. the arch-mismatch warning in [Waifu2x]).
         */
        val packedQnnArches: List<Int> get() = QNN_ARCHES

        /**
         * Resolves a persisted id, falling back to [Default].
         *
         * The fallback matters for upgrade safety: an id can disappear if a model is
         * dropped from the catalogue, and a missing model must not leave the reader
         * with a GPU mode that cannot start.
         */
        fun fromId(id: String?): AiUpscaleModel = entries.firstOrNull { it.id == id } ?: Default
    }
}

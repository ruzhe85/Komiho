package eu.kanade.tachiyomi.util.waifu2x

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Komiho: the ncnn model catalogue for the GPU (Vulkan) upscaler.
 *
 * Until now the model was a pair of compile-time constants inside [Waifu2x]
 * (`MODEL_ASSET_DIR` / `MODEL_STEM` plus a fixed `SCALE`), so "adding a model" meant
 * editing code in three places. This enum is the thin indirection layer: the UI lists
 * [entries] and persists [id], and the engine rebuilds itself when the id changes.
 *
 * Adding a model is now: drop the `.param`/`.bin` pair under `assets/`, add one enum
 * entry with its asset path. Nothing else needs to change — [Waifu2x.ensureEngine]
 * re-initialises the native engine whenever the active entry differs from the running one.
 *
 * @property id stable, persisted identifier — never rename an existing one.
 * @property assetDir folder under `assets/` holding the model files.
 * @property stem file stem: `assetDir/stem.param` + `assetDir/stem.bin`.
 * @property scale output scale baked into the network (PixelShuffle factor).
 * @property padding receptive-field halo required per tile; must match the network depth.
 * @property labelRes display name shown in the GPU group; model names are not translated.
 */
enum class AiUpscaleModel(
    val id: String,
    val assetDir: String,
    val stem: String,
    val scale: Int,
    val padding: Int,
    val labelRes: StringResource,
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
     * Omni-Turbo V1.5 — same author's retrained sibling of [AnimeVideoMiniV18].
     *
     * Verified by parsing both `.param` files: identical topology (24 layers, 10 convolutions,
     * PReLU x9, `PixelShuffle(0=2)`, bilinear `Interp` bypass), so [scale] and [padding] carry
     * over unchanged — tile seams behave exactly like the existing model.
     *
     * Only the weights differ, and they are heavier: 598 KB vs 89 KB. Expect somewhat more
     * compute per tile in exchange for the retrained quality.
     */
    OmniTurboV15(
        id = "omni-turbo-v15-w2xex",
        assetDir = "w2xex-esrgan/Omni-TurboV1.5-W2xEX",
        stem = "Omni-TurboV1.5-W2xEX",
        scale = 2,
        padding = 10,
        labelRes = MR.strings.ai_model_omni_turbo,
    ),
    ;

    companion object {
        /** Model used on a fresh install and whenever a stored id is unknown. */
        val Default: AiUpscaleModel = AnimeVideoMiniV18

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

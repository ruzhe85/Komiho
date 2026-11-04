package eu.kanade.tachiyomi.util

import android.graphics.Bitmap
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.eink.EinkGray
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho (2026-11-04): 「已经是位图」的页面在显示前要做的处理。
 *
 * 与 [eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder] 解码之后那一段保持一致：
 * ① AI/Lanczos 增强（含跳过门）→ ② 透明压白 → ③ E-Ink 灰阶。
 *
 * 存在的理由：PDF 渲染页由 PDFium 直接出位图，本来不必再「编码成 WebP/JPEG → 解码」；
 * 但增强逻辑原本只挂在解码器里，于是那条路径要么丢 AI、要么白付一次编码+解码。
 * 把处理搬到「位图入口」后，两条路径都不必编码 —— 这也是 Comicscreen 那类阅读器
 * （渲染即显示）与我们现在的差距所在。
 */
object PdfRenderPostProcess {

    /** 与解码器一致的条漫判定（高宽比）。 */
    private const val TALL_ASPECT_RATIO = 2.5f

    /**
     * 处理一张「已解码/已渲染」的位图，返回最终用于显示的位图（入参可能被回收）。
     *
     * @param viewWidth / viewHeight 当前视图尺寸，用于 AI 跳过门（r ≤ 1 = 屏幕只会缩小，不必 2x）。
     */
    fun process(
        bitmap: Bitmap,
        viewWidth: Int,
        viewHeight: Int,
        pageIndex: Int,
    ): Bitmap {
        var bmp = bitmap
        if (bmp.isRecycled) return bmp

        val prefs = Injekt.get<ReaderPreferences>()
        val mode = prefs.enhancementMode.get()
        val denoise = prefs.denoiseLevel.get()

        // 必须在增强**之前**判断：增强按 RGB 处理会把 alpha 写成 255，透明区变黑就分辨不出来了。
        val srcTransparent = ImageUtil.hasTransparentPixels(bmp)

        // ① 增强（门限与解码器一致）
        if (mode != 0 || denoise != 0) {
            val tall = bmp.height > 0 && bmp.width > 0 &&
                bmp.height.toFloat() / bmp.width.toFloat() > TALL_ASPECT_RATIO
            val fitRatio = if (tall) {
                viewWidth.toFloat() / bmp.width.toFloat()
            } else {
                minOf(
                    viewWidth.toFloat() / bmp.width.toFloat(),
                    viewHeight.toFloat() / bmp.height.toFloat(),
                )
            }
            val bypassGate = prefs.aiBypassFitGate.get()
            val skipAi = mode == 5 && !bypassGate && fitRatio <= 1f
            if (skipAi) {
                // 与解码器同口径：登记「跳过」，角标显示跳过而不是空白。
                EnhanceTimings.markSkipped(pageIndex)
            } else {
                runCatching {
                    val enhanced = MihonSyEnhancer.enhance(
                        bmp,
                        prefs,
                        onComplete = { _, _, _ -> },
                        sourceTag = "pdf#$pageIndex",
                        pageIndex = pageIndex,
                    )
                    var result = enhanced
                    // 与解码器一致：开了「AI 面积回缩」时把 2x 结果压回显示尺寸（防摩尔纹）。
                    if (result != null && result !== bmp && !result.isRecycled &&
                        mode == 5 && prefs.aiAreaDownscale.get()
                    ) {
                        val downscaled = runCatching {
                            MihonSyEnhancer.areaDownscaleToDisplay(
                                result,
                                viewWidth = viewWidth,
                                viewHeight = viewHeight,
                                isTallStrip = tall,
                                strength = prefs.aiAreaDownscaleStrength.get() / 100f,
                            )
                        }.getOrNull()
                        if (downscaled != null && downscaled !== result) {
                            result.recycle()
                            result = downscaled
                        }
                    }
                    if (result != null && result !== bmp && !result.isRecycled) {
                        bmp.recycle()
                        bmp = result
                    }
                }
            }
        }

        // ② 透明压白（增强破坏 alpha 后统一合成到白底，与 PDF 渲染同口径）
        if (srcTransparent) {
            val flattened = ImageUtil.flattenOnWhite(bmp)
            if (flattened != null && flattened !== bmp) {
                bmp.recycle()
                bmp = flattened
            }
        }

        // ③ E-Ink 灰阶（必须在软件位图阶段；硬件位图禁止 setPixels）
        val eink = Injekt.get<UiPreferences>()
        if (eink.isEinkGrayscaleActive) {
            runCatching {
                EinkGray.apply(
                    bmp,
                    EinkGray.Config(
                        levels = eink.einkGrayLevelsOrDefault(),
                        keepColorPages = eink.einkKeepColorPages.get(),
                        curve = EinkGray.Curve.SOFT,
                        dither = false,
                    ),
                )
            }
        }

        return bmp
    }
}

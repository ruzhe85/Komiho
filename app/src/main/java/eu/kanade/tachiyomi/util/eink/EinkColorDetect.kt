package eu.kanade.tachiyomi.util.eink

import android.graphics.Bitmap

/**
 * Komiho: 彩色页判定 —— 移植 KCC 的 `calculate_color` / `color_precision`。
 *
 * 用途：「彩色页保持彩色」开关。判错的后果是双向的：把黑白页当彩色页 → 该做的
 * 灰阶化没做（16 级量化缺席，色带照旧）；把彩色页当黑白页 → 彩色漫画被强行去色，
 * 观感变差。所以判定宁可**保守**（倾向判为黑白页，因为黑白屏上转灰总没错）。
 *
 * ## KCC 的判据
 *
 * 1. 转 YCbCr，取 Cb / Cr 的 256 桶直方图；
 * 2. **先切掉直方图两端各 cutoff% 的像素** —— KCC 注释写明是为了消除 JPEG 压缩
 *    伪影对判定的影响。不切的话一张几乎无色的页会因几个孤立噪声桶被判成彩色；
 * 3. 用**非零桶的分布跨度**（`last - first`）判断：`cbSpread < 7 && crSpread < 7`
 *    判为无色；
 * 4. 否则看非零区间是否明显偏离中性点 128（差 ≥ `diffThreshold`）→ 判为有色；
 * 5. 以上两步都没结论时**换更严格的 cutoff 重试**，最多三轮：
 *
 *    | 轮次 | 两端切掉 | diff 阈值 |
 *    |------|---------|----------|
 *    | 1    | 0%      | 22       |
 *    | 2    | 0.2%    | 10       |
 *    | 3    | 3%      | 4        |
 *
 *    KCC 源码注释留了调参提示：「不要提高 22，可以降低 10，4 或许还能更高」——
 *    调高 diff 会漏判（彩色页被判成黑白），调低则会误判（黑白页被判成彩色）。
 *
 * 与原版的一处差异：KCC 对全图统计，这里是**抽样**（[EinkGray] 的预扫只取 256 行，
 * 约 4096~27 万像素）。阈值不变 —— 彩色页的色度分布与黑白页相差一个数量级，
 * 抽样足够稳定。
 */
object EinkColorDetect {

    /** 色度跨度小于此值即视为「无色」。来自 KCC 的 `SPREAD_THRESHOLD`。 */
    private const val SPREAD_THRESHOLD = 7

    /** 中性点：Cb / Cr 的 0 对应值。 */
    private const val NEUTRAL = 128

    /** KCC 的三级递进：(两端切掉的百分比, diff 阈值)。 */
    private val CUTOFF_LADDER = listOf(0 to 22, 1 to 10, 3 to 4)

    /**
     * 判定整页是否彩色。
     *
     * @param cbHistogram Cb 的 256 桶直方图（索引 0..255，对应 -128..127 再平移）
     * @param crHistogram Cr 的 256 桶直方图
     * @param sampleCount 直方图对应的**总像素数**（切边时按百分比算）
     */
    fun isColorPage(cbHistogram: IntArray, crHistogram: IntArray, sampleCount: Int): Boolean {
        if (sampleCount <= 0) return false
        for ((cutoffPercent, diffThreshold) in CUTOFF_LADDER) {
            val cb = cbHistogram.copyOf()
            val cr = crHistogram.copyOf()
            if (cutoffPercent > 0) {
                trimEnds(cb, sampleCount, cutoffPercent)
                trimEnds(cr, sampleCount, cutoffPercent)
            }
            val cbRange = nonzeroRange(cb) ?: return false
            val crRange = nonzeroRange(cr) ?: return false

            if (cbRange.span() < SPREAD_THRESHOLD && crRange.span() < SPREAD_THRESHOLD) {
                // 两个色度通道都挤在 7 级以内 → 无色，直接定案。
                return false
            }
            if (cbRange.first <= NEUTRAL - diffThreshold ||
                crRange.first <= NEUTRAL - diffThreshold ||
                cbRange.last >= NEUTRAL + diffThreshold ||
                crRange.last >= NEUTRAL + diffThreshold
            ) {
                return true
            }
            // 结论不明 → 下一轮用更严的 cutoff 重试
        }
        return false
    }

    /** 便于单测与「只想问一句」的场景：自行抽样判定整页。 */
    fun isColorPage(bitmap: Bitmap): Boolean {
        if (bitmap.config == Bitmap.Config.HARDWARE) return false
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false

        val cbHistogram = IntArray(256)
        val crHistogram = IntArray(256)
        val stepX = (width / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (height / SAMPLE_GRID).coerceAtLeast(1)
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                cbHistogram[((-43 * r - 85 * g + 128 * b) shr 8) + NEUTRAL]++
                crHistogram[((128 * r - 107 * g - 21 * b) shr 8) + NEUTRAL]++
                count++
                x += stepX
            }
            y += stepY
        }
        return isColorPage(cbHistogram, crHistogram, count)
    }

    /** 抽样网格边长（每边最多取这么多点）。 */
    private const val SAMPLE_GRID = 64

    private data class Range(val first: Int, val last: Int) {
        fun span(): Int = last - first
    }

    /** 非零桶的 [first, last]；全空返回 null。 */
    private fun nonzeroRange(histogram: IntArray): Range? {
        var first = -1
        var last = -1
        for (i in histogram.indices) {
            if (histogram[i] > 0) {
                if (first < 0) first = i
                last = i
            }
        }
        return if (first < 0) null else Range(first, last)
    }

    /**
     * 从直方图两端各切掉 [cutoffPercent]% 的像素。
     *
     * 整桶清零优先、不足则部分扣除 —— 与 KCC 的 `histograms_cutoff` 一致。
     */
    private fun trimEnds(histogram: IntArray, total: Int, cutoffPercent: Int) {
        val target = total * cutoffPercent / 100
        if (target <= 0) return
        var cut = target
        for (i in histogram.indices) {
            if (cut <= 0) break
            val count = histogram[i]
            if (count > cut) {
                histogram[i] = count - cut
                cut = 0
            } else {
                cut -= count
                histogram[i] = 0
            }
        }
        cut = target
        for (i in histogram.indices.reversed()) {
            if (cut <= 0) break
            val count = histogram[i]
            if (count > cut) {
                histogram[i] = count - cut
                cut = 0
            } else {
                cut -= count
                histogram[i] = 0
            }
        }
    }
}

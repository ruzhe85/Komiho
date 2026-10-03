package eu.kanade.tachiyomi.util.eink

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Komiho: E-Ink 灰阶化 —— 把解码后的漫画位图量化到面板真实灰阶。
 *
 * ## 为什么不过度设计
 *
 * 本实现照 KCC（Kindle Comic Converter，漫画转换的事实标准）的管线：`convert("L")`
 * → 可选 gamma/autocontrast → 量化到 16 级调色板。KCC **没有**做误差扩散抖动、
 * CLAHE、色度平滑、Laplacian 金字塔灰度化，这里同样不做：
 *
 * - 抖动交给设备固件。KoReader 的移植文档（`doc/Porting.md`）明确「若硬件支持，
 *   应尽量把 dithering offload 给硬件」，且「文字与 UI 不该抖动，只有图片才按需」。
 *   KCC 也只做 `quantize(palette)`。所以默认**关**，仅在平坦区按需开。
 * - CLAHE / Laplacian 灰度化是给 LCD 的。墨水屏动态范围本就只有 LCD 的 1/60，
 *   再加局部增强只会把网点和纸纹一起放大。
 *
 * ## 灰阶档位为什么是 0x00, 0x11, …, 0xFF
 *
 * KCC 的 `Palette16` 步长是 17（0x11），而 KoReader 移植文档实测：「0x00 显示为纯黑，
 * **小于 0x11 的值也会显示为纯黑**」—— 面板的黑位死区正好是 0~16，第一个能显示为
 * 非纯黑的灰阶是 0x11。所以 16 级的输出必须落在 17 的倍数上，否则最低一两档白做。
 * 4 / 8 级步长不是整数（85 / 36.4），用查表。
 *
 * ## 成本
 *
 * 一次轻量预扫（抽样 256 行，约 1~2ms）+ 一趟分块流式处理（每块 512 行）。内存
 * `width × 512 × 4B`（1080 宽 ≈ 2.1MB）+ 两行行缓冲（约 8KB）。**不申请整页
 * IntArray** —— 1080×2400 整页一份就 10MB，在这类设备上最容易被 GC 压垮；
 * 长条页（Webtoon 可达 1080×8000）也因此不会 OOM。
 */
object EinkGray {

    /** 灰阶曲线预设，见 [buildCurve]。 */
    enum class Curve {
        /** 柔和（默认）：黑点 6 / 白点 250 / 无 gamma。 */
        SOFT,

        /** 强对比：黑点 12 / 白点 252 / gamma 1.15。适合网点硬派漫画。 */
        CONTRAST,

        /** 暗部提亮：黑点 0 / 白点 248 / gamma 0.85。适合夜景与灰底网点。 */
        SHADOW_LIFT,

        /** 印刷体：黑点 8 / 白点 245 / gamma 1.05。 */
        PRINT,

        /** 不做曲线（只去色 + 量化）。 */
        NONE,
        ;

        companion object {
            fun fromOrdinalOrDefault(value: Int): Curve = entries.getOrElse(value) { SOFT }
        }
    }

    /**
     * 灰阶化参数。
     *
     * @param levels 量化级数（4 / 8 / 16）
     * @param keepColorPages 彩色页是否保持彩色（彩色墨水屏才有意义）
     * @param curve 灰阶曲线预设
     * @param dither 是否在平坦区做 Bayer 8×8 有序抖动
     */
    data class Config(
        val levels: Int = 16,
        val keepColorPages: Boolean = true,
        val curve: Curve = Curve.SOFT,
        val dither: Boolean = false,
    )

    /**
     * KCC 的低对比保护阈值：动态范围不足此值就**放弃**调曲线。
     *
     * KCC 原文注释是「如果图像对比度极低，那大概是作者刻意的」。少了这条，一个
     * 刻意的灰调风格页面会被强行拉成纯黑白 —— 比不处理更糟。
     */
    private const val MIN_DYNAMIC_RANGE = 255 - 32 * 3 // = 159

    /** 抖动只在「平坦区」启用：线条/网点区本来就不会 band，抖了只会变脏。 */
    private const val FLAT_GRADIENT_LIMIT = 4

    /** 抖动强度，占半个量化步长的比例。 */
    private const val DITHER_STRENGTH = 0.5f

    /** 分块处理的行数。 */
    private const val CHUNK_ROWS = 512

    /** 恒等映射（不做曲线时用）。 */
    private val IDENTITY: IntArray = IntArray(256) { it }

    /** 曲线 LUT：纯函数，按预设各构建一次。 */
    private val curveTables: Map<Curve, IntArray> by lazy {
        Curve.entries.associateWith { buildCurve(it) }
    }

    private val bayer8: IntArray by lazy { bayerTable(BORDER_ORDER_8X8) }

    /**
     * 抖动表的阶数（= log2(边长)）。8×8 是权衡：4×4 的图案在墨水屏上会看出规则
     * 网纹，16×16 的表计算/查表开销更大且收益递减。
     */
    private const val BORDER_ORDER_8X8 = 3

    /**
     * 量化后各灰阶的像素数（诊断用，16 个桶；级数不足 16 时其余为 0）。
     *
     * 理想分布是各级都有量 —— 全挤在 0 级说明暗部被压死，只在最高级说明白阶浪费。
     * 每像素一次数组自增，1.5MP 约 2~3ms。
     */
    @Volatile
    var lastLevelHistogram: IntArray = IntArray(16)
        private set

    /**
     * 就地对 [bitmap] 做灰阶化。
     *
     * @return true 表示位图已被改写；false 表示未处理（彩色页保持彩色 / 硬件位图 /
     *   尺寸非法），调用方应继续沿用原图。
     */
    fun apply(bitmap: Bitmap, config: Config): Boolean {
        if (bitmap.config == Bitmap.Config.HARDWARE) {
            // 硬件位图禁止读写像素（与 MihonSyEnhancer 的同款兜底）。
            return false
        }
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false

        // 预扫：一次抽样同时得到「是否彩色页」与「动态范围」，两件事共用同一趟数据。
        val analysis = analyze(bitmap)
        if (config.keepColorPages && analysis.isColorPage) return false

        val levels = config.levels.coerceIn(2, 16)
        val curve = if (analysis.dynamicRange >= MIN_DYNAMIC_RANGE) {
            curveTables.getValue(config.curve)
        } else {
            // 低对比保护：这一页大概是刻意的灰调风格，原样去色后量化即可。
            IDENTITY
        }

        val step = 255f / (levels - 1)
        val bayer = if (config.dither) bayer8 else null
        val histogram = IntArray(16)
        val chunkPixels = IntArray(width * min(CHUNK_ROWS, height))
        val prevRow = IntArray(width) // 上一行「曲线后、量化前」的 Y

        var y0 = 0
        while (y0 < height) {
            val rows = min(CHUNK_ROWS, height - y0)
            bitmap.getPixels(chunkPixels, 0, width, 0, y0, width, rows)

            for (row in 0 until rows) {
                val y = y0 + row
                val base = row * width
                val bayerRow = if (bayer != null) (y and 7) shl 3 else 0
                for (x in 0 until width) {
                    val index = base + x
                    val p = chunkPixels[index]
                    // Rec.601 亮度（Pillow 的 convert("L") 就是这个）。
                    // 不用线性光 / L*：那需要每像素 2 次 pow（1.5MP 约 300ms，在这类设备上
                    // 不可接受）；它与 L* 的差异主要在暗部偏暗，正好被曲线的黑点抬升吸收。
                    val yValue = curve[(77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8]

                    // 抖动项是 Float（阈值 × 半步长比例），所以量化输入用 Float 承载；
                    // 不抖动的热路径上 toFloat() 会被 JIT/ART 淡化，且 Int/Float 除法
                    // 在这里语义一致（step 本来就是 Float）。
                    var value = yValue.toFloat()
                    if (bayer != null && abs(yValue - prevRow[x]) <= FLAT_GRADIENT_LIMIT) {
                        val threshold = bayer[bayerRow or (x and 7)] - 128
                        value += threshold * DITHER_STRENGTH * step / 128f
                    }

                    var q = (value / step + 0.5f).toInt()
                    if (q < 0) q = 0 else if (q >= levels) q = levels - 1
                    histogram[q]++
                    // 保留原 alpha：PNG 可能有透明区域，不该被强行写成不透明。
                    val gray = (q * step).roundToInt().coerceIn(0, 255)
                    chunkPixels[index] = (p and -0x1000000) or (gray shl 16) or (gray shl 8) or gray
                    // 本像素处理完才写 prevRow[x] ⇒ 下一个像素读到的仍是上一行的值。
                    prevRow[x] = yValue
                }
            }
            bitmap.setPixels(chunkPixels, 0, width, 0, y0, width, rows)
            y0 += rows
        }

        lastLevelHistogram = histogram
        return true
    }

    /** 预扫结果。 */
    private data class Analysis(val isColorPage: Boolean, val dynamicRange: Int)

    /**
     * 抽样预扫：一次遍历同时算出「是否彩色页」与「动态范围」。
     *
     * 抽样 256 行（整幅等比步进）而不是全图：全图 min/max 会被极少数异常像素拉偏，
     * 而我们只想判断「是不是刻意的低对比」与「有没有明显彩色」，抽样足够稳定。
     * 一次 [Bitmap.getPixels] 取一整行，256 次 JNI 调用约 1~2ms。
     */
    private fun analyze(bitmap: Bitmap): Analysis {
        val width = bitmap.width
        val height = bitmap.height
        val cbHistogram = IntArray(256)
        val crHistogram = IntArray(256)
        val row = IntArray(width)
        var minLuma = 255
        var maxLuma = 0

        val stepY = (height / SAMPLE_ROWS).coerceAtLeast(1)
        var y = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (p in row) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = (77 * r + 150 * g + 29 * b) shr 8
                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma
                cbHistogram[((-43 * r - 85 * g + 128 * b) shr 8) + 128]++
                crHistogram[((128 * r - 107 * g - 21 * b) shr 8) + 128]++
            }
            y += stepY
        }
        return Analysis(
            isColorPage = EinkColorDetect.isColorPage(cbHistogram, crHistogram, width * (height / stepY)),
            dynamicRange = maxLuma - minLuma,
        )
    }

    /** 预扫抽样行数。 */
    private const val SAMPLE_ROWS = 256

    /** 仅按预设构建曲线 LUT（纯函数，不依赖位图）。 */
    internal fun buildCurve(curve: Curve): IntArray {
        val black: Int
        val white: Int
        val gamma: Double
        when (curve) {
            Curve.SOFT -> {
                black = 6; white = 250; gamma = 1.0
            }
            Curve.CONTRAST -> {
                black = 12; white = 252; gamma = 1.15
            }
            Curve.SHADOW_LIFT -> {
                black = 0; white = 248; gamma = 0.85
            }
            Curve.PRINT -> {
                black = 8; white = 245; gamma = 1.05
            }
            Curve.NONE -> {
                black = 0; white = 255; gamma = 1.0
            }
        }
        val span = (white - black).coerceAtLeast(1)
        return IntArray(256) { v ->
            val clamped = v.coerceIn(black, white)
            val normalized = (clamped - black).toDouble() / span
            val shaped = if (gamma == 1.0) normalized else normalized.pow(gamma)
            (black + shaped * span).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * 量化 LUT：0..255 → 最接近该级数的灰阶输出值。
     *
     * 16 级步长恰好 17（= KCC 的 `Palette16`），4 级是 85（= `Palette4`）。级数表在
     * 这里只用于测试与诊断；主循环用 `(q * step)` 直接算，省一次查表。
     */
    internal fun quantTable(levels: Int): IntArray {
        val n = levels.coerceIn(2, 16)
        val step = 255f / (n - 1)
        return IntArray(256) { v ->
            val q = (v / step + 0.5f).toInt().coerceIn(0, n - 1)
            (q * step).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Bayer 有序抖动阈值表，值域 0..255 且每个值恰好出现一次。
     *
     * 用递归构造而非手写常量 —— 手写表一旦出错就会产生**结构性**条纹，很难一眼看出：
     * `M₁ = [0]`，`M₂ₙ` 由 `Mₙ` 放大 4 倍后在**块内**位置加偏移 `[[0,2],[3,1]]`。
     *
     * @param order 阶数（指数），决定表的边长 = `2^order`：
     *   `1 → 2×2`、`2 → 4×4`、`3 → 8×8`。注意**不是**边长本身。
     *
     * 归一化分母是**最终格子数 − 1**（值域 0..格子数−1）。这里踩过一次坑：早先按
     * `1 shl order` 算分母，order=2 时得到 15 而实际只有 4 格 —— 阈值被压到 0..60，
     * 抖动幅度整体偏小且失去均匀性。数组长度同理，早先写成 `levels * levels` 会在
     * order ≥ 3 时直接越界（**开抖动即崩**）。
     *
     * 产出的 4×4（未归一化）是 `[[0,2,8,10],[3,1,11,9],[12,14,4,6],[15,13,7,5]]`，
     * 与教科书那张 `[[0,8,2,10],…]` **不是转置关系** —— 教科书用块偏移，这里用块内
     * 偏移，两者同属 Bayer 构造（值域 0..N−1 的一个置换，均匀性一致）。
     * 期望值已用独立实现交叉验证，别手改。
     */
    internal fun bayerTable(order: Int): IntArray {
        require(order in 1..5) { "order 必须在 1..5（边长 2..32），收到 $order" }
        var m = intArrayOf(0)
        repeat(order) {
            val block = m.size
            val next = IntArray(block * 4)
            for (y in 0 until block) {
                for (x in 0 until block) {
                    val v = m[y * block + x] * 4
                    next[(y * 2) * block * 2 + x * 2] = v
                    next[(y * 2) * block * 2 + x * 2 + 1] = v + 2
                    next[(y * 2 + 1) * block * 2 + x * 2] = v + 3
                    next[(y * 2 + 1) * block * 2 + x * 2 + 1] = v + 1
                }
            }
            m = next
        }
        // m.size == (2^order)^2，分母 = 格子数 − 1
        val divisor = m.size - 1
        return IntArray(m.size) { m[it] * 255 / divisor }
    }
}

package eu.kanade.tachiyomi.util.eink

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [EinkGray] / [EinkColorDetect] 的纯算法测试。
 *
 * 只测不碰 Android 的部分：抖动阈值表、曲线 LUT、量化表、彩色页判定。
 * 需要 `Bitmap` 的分块流式处理与直方图诊断不在这里（要 Robolectric 或真机）。
 */
class EinkGrayTest {

    // ---- Bayer 抖动表 -------------------------------------------------------

    @Test
    fun `bayer 2x2 matches the standard matrix`() {
        // 分母 = 2^2-1 = 3：[0, 2, 3, 1] → [0, 170, 255, 85]
        assertArrayEqualsFlat(intArrayOf(0, 170, 255, 85), EinkGray.bayerTable(1))
    }

    @Test
    fun `bayer 4x4 is a valid threshold matrix`() {
        // 未归一化的递归结果是 0..15 的一个合法 Bayer 构造（块内偏移），
        // 归一化到 0..255（分母 = 格子数−1 = 15）后即下表。
        //
        // 这条断言同时锁住两件事：递归式的正确性与归一化分母 —— 早先按
        // `1 shl order` 算分母，这里会得到 0..60 的阈值，抖动幅度整体偏小。
        // 期望值是用独立实现（PowerShell 复算同一递归式）交叉验证过的，别手改。
        assertArrayEqualsFlat(
            intArrayOf(
                0, 34, 136, 170,
                51, 17, 187, 153,
                204, 238, 68, 102,
                255, 221, 119, 85,
            ),
            EinkGray.bayerTable(2),
        )
    }

    @Test
    fun `bayer 8x8 covers every level exactly once`() {
        val table = EinkGray.bayerTable(3)
        assertEquals(64, table.size)
        assertEquals(64, table.toSet().size) // 无重复 ⇒ 无结构性条纹
        assertEquals(0, table.min())
        assertEquals(255, table.max())
    }

    // ---- 量化表 -------------------------------------------------------------

    @Test
    fun `16-level quantization lands on multiples of 17`() {
        // KCC 的 Palette16 步长是 17（0x11），而 KoReader 实测「小于 0x11 都显示为
        // 纯黑」—— 输出必须落在 17 的倍数上，最低两档才不是白做。
        val table = EinkGray.quantTable(16)
        assertEquals(256, table.size)
        table.forEachIndexed { value, mapped ->
            assertEquals(0, mapped % 17, "value=$value mapped=$mapped 不是 17 的倍数")
        }
        assertEquals(0, table.first())
        assertEquals(255, table.last())
    }

    @Test
    fun `4-level quantization lands on multiples of 85`() {
        val table = EinkGray.quantTable(4)
        table.forEach { assertEquals(0, it % 85, "不是 85 的倍数: $it") }
    }

    @Test
    fun `quantization is monotonic and never overshoots`() {
        for (levels in listOf(4, 8, 16)) {
            val table = EinkGray.quantTable(levels)
            for (v in 1..255) {
                assertTrue(
                    table[v] >= table[v - 1],
                    "levels=$levels 在 $v 处非单调：${table[v - 1]} → ${table[v]}",
                )
            }
        }
    }

    // ---- 曲线 ---------------------------------------------------------------

    @Test
    fun `soft curve pins the endpoints and stays monotonic`() {
        val curve = EinkGray.buildCurve(EinkGray.Curve.SOFT)
        assertEquals(6, curve.first())  // 黑点抬升
        assertEquals(250, curve.last()) // 白点压缩
        for (v in 1..255) {
            assertTrue(curve[v] >= curve[v - 1], "曲线在 $v 处非单调")
        }
    }

    @Test
    fun `none curve is the identity`() {
        val curve = EinkGray.buildCurve(EinkGray.Curve.NONE)
        for (v in 0..255) assertEquals(v, curve[v])
    }

    @Test
    fun `every preset keeps output inside 0 to 255`() {
        for (curve in EinkGray.Curve.entries) {
            val table = EinkGray.buildCurve(curve)
            table.forEach { assertTrue(it in 0..255, "$curve 越界: $it") }
        }
    }

    @Test
    fun `shadow lift brightens the low end relative to soft`() {
        val soft = EinkGray.buildCurve(EinkGray.Curve.SOFT)
        val lift = EinkGray.buildCurve(EinkGray.Curve.SHADOW_LIFT)
        // 暗部（输入 60）应当被提亮得更多，否则「暗部提亮」名不副实
        assertTrue(lift[60] > soft[60], "lift[60]=${lift[60]} 应高于 soft[60]=${soft[60]}")
    }

    // ---- 彩色页判定（KCC 移植）---------------------------------------------

    @Test
    fun `pure grayscale page is not a color page`() {
        // 黑白漫画的所有像素 r=g=b ⇒ Cb=Cr=128 ⇒ 跨度 0 ⇒ 无色
        val (cb, cr, count) = histogramOf(List(256) { gray(it) })
        assertFalse(EinkColorDetect.isColorPage(cb, cr, count))
    }

    @Test
    fun `grayscale page with mild jpeg noise is still not a color page`() {
        // 真实扫描件的黑白页总有 ±几级的噪声。这就是 KCC 先切直方图两端的原因。
        val pixels = List(512) { i -> gray((i * 37 % 256) + (i % 3) - 1) }
        val (cb, cr, count) = histogramOf(pixels)
        assertFalse(EinkColorDetect.isColorPage(cb, cr, count))
    }

    @Test
    fun `white page with a red block is a color page`() {
        // 真实的彩色漫画页：绝大多数是白底（Cb=Cr=128），色块落在别处 ⇒ 跨度很大。
        val pixels = List(100) { 0xFFFFFF } + List(20) { 0xFF0000 }
        val (cb, cr, count) = histogramOf(pixels)
        assertTrue(EinkColorDetect.isColorPage(cb, cr, count), "白底红块应判为彩色页")
    }

    @Test
    fun `white page with a pale yellow block is a color page`() {
        // 老漫画的泛黄块。色度偏移不大（Cb≈114 / Cr≈133），第一轮 diff=22 判不了，
        // 靠第二轮 diff=10 命中「非零区间明显偏离 128」—— 这正是三级递进的意义。
        val pixels = List(100) { 0xFFFFFF } + List(20) { 0xFFF8E0 }
        val (cb, cr, count) = histogramOf(pixels)
        assertTrue(EinkColorDetect.isColorPage(cb, cr, count), "白底淡黄块应判为彩色页")
    }

    @Test
    fun `a single flat color is not detected as color`() {
        // 整页单色时色度直方图只有**一个**桶，跨度为 0 → 按 KCC 的判据就是无色。
        // 这不是缺陷而是原判据的固有性质：KCC 判的是「色度的分布范围」，而真实的
        // 漫画页总有白底 + 内容，绝不是单色。这里把这个行为显式锁住，避免以后
        // 误以为它坏了而"修"错方向。
        val pixels = List(200) { 0xFFF8E0 }
        val (cb, cr, count) = histogramOf(pixels)
        assertFalse(EinkColorDetect.isColorPage(cb, cr, count))
    }

    @Test
    fun `empty input is not a color page`() {
        assertFalse(EinkColorDetect.isColorPage(IntArray(256), IntArray(256), 0))
    }

    // ---- helpers ------------------------------------------------------------

    private fun gray(value: Int): Int {
        val v = value.coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** 与 EinkGray/EinkColorDetect 里完全相同的 YCbCr 整数系数。 */
    private fun histogramOf(pixels: List<Int>): Triple<IntArray, IntArray, Int> {
        val cb = IntArray(256)
        val cr = IntArray(256)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            cb[((-43 * r - 85 * g + 128 * b) shr 8) + 128]++
            cr[((128 * r - 107 * g - 21 * b) shr 8) + 128]++
        }
        return Triple(cb, cr, pixels.size)
    }

    private fun assertArrayEqualsFlat(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size, "长度不同")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], "下标 $i 不同")
        }
    }
}

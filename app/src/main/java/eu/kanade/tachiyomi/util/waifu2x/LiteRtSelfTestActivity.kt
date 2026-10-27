package eu.kanade.tachiyomi.util.waifu2x

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import eu.kanade.tachiyomi.diagnostic.DiagLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Komiho (2026-10-27): MTK 后端的 **CPU 验证通道**自检。
 *
 * 为什么需要它：MTK NPU 只能在联发科机器上验，但**分块几何、ABI 用法、进度、取消、耗时**
 * 这些与芯片无关 —— `cpuOnly=true` 走的是同一套 native 代码，只是把加速器换成 CPU。
 * 于是任何 arm64 安卓机（含骁龙）都能把除「NPU 委托 / 加速比」以外的链路验完。
 *
 * **放在 main 源集、但不在任何 UI 里出现**：只在 `adb` 里按类名启动。这样跑的是正式包
 * （native `-O2`），耗时数据才有意义 —— debug 变体是 `-O0`，同一份推理会慢数倍。
 *
 * 触发方式（见 `app/src/main/AndroidManifest.xml`）：
 * ```
 * adb shell am start -n cn.ruzhe.komiho/eu.kanade.tachiyomi.util.waifu2x.LiteRtSelfTestActivity
 * adb logcat -s LiteRtSelfTest
 * ```
 *
 * 判定项（不靠肉眼）：
 *  1. 尺寸 = `width*scale × height*scale`；
 *  2. alpha 全 255、输出非纯色；
 *  3. **通道顺序**：左/中/右三分区的主色通道分别是 R/G/B（防 RGB↔BGR、归一化写反）；
 *  4. **拼缝指标**：分块边界处的相邻像素差 ÷ 全图平均相邻差 —— 拼错会显著放大；
 *  5. 进度单调递增到 100；
 *  6. 取消：另跑一次大图，中途 abort，必须返回 null 且原因为 aborted。
 */
class LiteRtSelfTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val result = runCatching { runSelfTest() }
                .getOrElse { e ->
                    log("FAIL", "exception: ${e.javaClass.simpleName}: ${e.message}")
                    RESULT_FAIL
                }
            log("EXIT", "code=$result")
            runOnUiThread { finish() }
        }.apply { name = "litert-selftest" }.start()
    }

    private fun runSelfTest(): Int {
        log(
            "INFO",
            "device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull()} " +
                "mtk=${LiteRtNative.isMtkDevice()} loaded=${LiteRtNative.isLoaded}",
        )
        if (!LiteRtNative.isLoaded) return fail("liblitert-jni 未加载（非 arm64 或打包缺失）")
        log("INFO", "runtimePresent=${LiteRtNative.isRuntimePresent()}")

        // 1) 从已安装的模型包里找 LiteRT 模型（与生产扫描同一条路径）
        val models = UpscaleModelRegistry.refresh(applicationContext)
            .filter { it.backend == UpscaleModelSpec.Backend.LITERT_NPU }
        if (models.isEmpty()) {
            log("SKIP", "未安装 LiteRT 模型包（装 komiho-model-mediatek.apk 后重试）")
            return RESULT_SKIP
        }
        val model = models.first()
        log(
            "INFO",
            "model=${model.id} scale=${model.scale} padding=${model.padding} " +
                "pkg=${model.sourcePackage} assetDir=${model.assetDir}",
        )

        // 2) 把 tflite 解到本地（生产侧同样要先落到文件）
        val workDir = File(cacheDir, "litert-selftest").apply { mkdirs() }
        val modelFile = File(workDir, "${model.stem}.tflite")
        if (!copyModelAsset(model, modelFile)) {
            return fail("模型资源缺失：${model.assetDir}/${model.stem}.tflite")
        }
        log("INFO", "modelFile=$modelFile size=${modelFile.length()}B")

        // 3) 初始化（CPU 加速器 = 验证通道）
        LiteRtNative.shutdown()
        val cacheDirForEngine = File(workDir, "compiler-cache").apply { mkdirs() }
        val initStart = SystemClock.elapsedRealtime()
        val initError = LiteRtNative.initialize(
            model = modelFile,
            cacheDir = cacheDirForEngine,
            libraryDir = applicationInfo.nativeLibraryDir,
            scale = model.scale,
            padding = model.padding,
            cpuOnly = true,
        )
        log(
            "INFO",
            "initialize(${SystemClock.elapsedRealtime() - initStart}ms) error=${initError ?: "none"} " +
                "npuActive=${LiteRtNative.isNpuActive()} (CPU 模式应为 false) " +
                "tile=${LiteRtNative.tileSize()} scale=${LiteRtNative.scale()}",
        )
        if (initError != null) return fail("initialize 失败：$initError")

        // 4) 正常路径：合成图 → 放大 → 指标
        val width = 384
        val height = 256
        val input = syntheticImage(width, height)
        val progressSeen = mutableListOf<Int>()
        val polling = AtomicBoolean(true)
        val poller = Thread {
            while (polling.get()) {
                val p = LiteRtNative.progress()
                if (progressSeen.lastOrNull() != p) progressSeen += p
                Thread.sleep(120)
            }
        }.apply { isDaemon = true; start() }

        val start = SystemClock.elapsedRealtime()
        val output = LiteRtNative.process(input, width, height)
        val wall = SystemClock.elapsedRealtime() - start
        polling.set(false)
        if (output == null) return fail("process 失败：${LiteRtNative.lastError()}")

        val scale = model.scale
        val outW = width * scale
        val outH = height * scale
        log(
            "INFO",
            "process ${width}x$height -> ${outW}x$outH bytes=${output.size} wall=${wall}ms " +
                "nativeInference=${LiteRtNative.lastInferenceMs()}ms " +
                "nativeTotal=${LiteRtNative.lastTotalMs()}ms progress=${LiteRtNative.progress()}",
        )
        log("INFO", "progress seen=$progressSeen")

        if (output.size != outW * outH * 4) {
            return fail("输出字节数不匹配：${output.size} != ${outW * outH * 4}")
        }
        if (!alphaAllOpaque(output)) return fail("输出存在非 255 的 alpha")
        if (isFlat(output)) return fail("输出是纯色（推理没生效或权重异常）")

        val channels = channelOrder(output, outW, outH)
        log("INFO", "channel means left=${channels.left} mid=${channels.mid} right=${channels.right}")
        if (!channels.ok) return fail("通道顺序异常（疑似 RGB↔BGR 或归一化写反）")

        val stride = LiteRtNative.tileSize() - 2 * model.padding
        if (stride <= 0) return fail("padding 过大：tile=${LiteRtNative.tileSize()} padding=${model.padding}")
        val boundaries = boundaryColumns(outW, stride * scale)
        val seam = seamRatio(output, outW, outH, boundaries)
        log(
            "INFO",
            "stride=$stride boundaries=$boundaries seamRatio=%.2f (阈值 3.0)".format(seam),
        )
        if (seam > SEAM_THRESHOLD) return fail("拼缝明显：ratio=%.2f".format(seam))

        // 5) 取消路径：大图 + 中途 abort
        val abortResult = abortCase(model.scale)
        log("INFO", "abort case: $abortResult")
        if (!abortResult) return fail("取消未生效（process 应返回 null 且原因为 aborted）")

        log("PASS", "全部通过（CPU 验证通道）")
        return RESULT_PASS
    }

    /** 大图跑到一半 abort，必须被打断；否则翻页抢占会卡住整页。 */
    private fun abortCase(scale: Int): Boolean {
        val w = 768
        val h = 768
        val input = syntheticImage(w, h)
        val aborted = AtomicBoolean(false)
        val worker = Thread {
            val out = LiteRtNative.process(input, w, h)
            aborted.set(out == null && LiteRtNative.lastError().contains("abort", ignoreCase = true))
        }
        worker.start()
        Thread.sleep(250)
        LiteRtNative.abort()
        worker.join(30_000)
        val ok = aborted.get()
        LiteRtNative.clearAbort()
        log(
            "INFO",
            "abort -> ok=$ok alive=${worker.isAlive} lastError=${LiteRtNative.lastError()} " +
                "out=${w * scale}x${h * scale}",
        )
        return ok
    }

    // ---- 合成图与指标 -------------------------------------------------------

    /**
     * 三分区原色（左红/中绿/右蓝）+ 每 8px 一条 1px 深色网格。
     *
     * 分区用来验通道顺序；细网格用来暴露拼缝（错位的 1px 线在边界处会出现双线/断线）。
     */
    private fun syntheticImage(width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        val third = width / 3
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                val grid = x % 8 == 0 || y % 8 == 0
                val r: Int
                val g: Int
                val b: Int
                if (grid) {
                    r = 30; g = 30; b = 30
                } else when {
                    x < third -> {
                        r = 200; g = 70; b = 70
                    }
                    x < third * 2 -> {
                        r = 70; g = 200; b = 70
                    }
                    else -> {
                        r = 70; g = 70; b = 200
                    }
                }
                out[i] = r.toByte()
                out[i + 1] = g.toByte()
                out[i + 2] = b.toByte()
                out[i + 3] = 255.toByte()
            }
        }
        return out
    }

    private fun alphaAllOpaque(rgba: ByteArray): Boolean {
        var i = 3
        while (i < rgba.size) {
            if (rgba[i] != 255.toByte()) return false
            i += 4
        }
        return true
    }

    private fun isFlat(rgba: ByteArray): Boolean {
        val first = rgba[0] to rgba[1]
        var i = 0
        while (i + 4 <= rgba.size) {
            if (rgba[i] != first.first || rgba[i + 1] != first.second) return false
            i += 4 * 37 // 抽样，避免整页遍历
        }
        return true
    }

    private class Channels(val left: IntArray, val mid: IntArray, val right: IntArray) {
        val ok: Boolean
            get() = left[0] > left[1] && left[0] > left[2] &&
                mid[1] > mid[0] && mid[1] > mid[2] &&
                right[2] > right[0] && right[2] > right[1]
    }

    private fun channelOrder(rgba: ByteArray, outW: Int, outH: Int): Channels {
        fun regionMean(fromX: Int, toX: Int): IntArray {
            val sum = LongArray(3)
            var n = 0L
            var y = outH / 4
            while (y < outH * 3 / 4) {
                var x = fromX
                while (x < toX) {
                    val i = (y * outW + x) * 4
                    sum[0] += rgba[i].toInt() and 0xFF
                    sum[1] += rgba[i + 1].toInt() and 0xFF
                    sum[2] += rgba[i + 2].toInt() and 0xFF
                    n++
                    x += 3
                }
                y += 3
            }
            return IntArray(3) { (sum[it] / max(1L, n)).toInt() }
        }
        // 避开分块与分区边界附近的过渡带，取各分区中间 60%
        val third = outW / 3
        return Channels(
            left = regionMean(third / 5, third * 4 / 5),
            mid = regionMean(third + third / 5, third * 2 - third / 5),
            right = regionMean(third * 2 + third / 5, outW - third / 5),
        )
    }

    private fun boundaryColumns(outW: Int, step: Int): List<Int> {
        if (step <= 0) return emptyList()
        val result = mutableListOf<Int>()
        var x = step
        while (x < outW) {
            result += x
            x += step
        }
        return result
    }

    /** 边界处平均相邻差 ÷ 全图平均相邻差。拼缝越明显比值越大（正常 ≈ 1.0）。 */
    private fun seamRatio(rgba: ByteArray, outW: Int, outH: Int, boundaries: List<Int>): Double {
        fun colDiff(x: Int): Double {
            if (x <= 0 || x >= outW) return 0.0
            var sum = 0L
            var n = 0L
            var y = 0
            while (y < outH) {
                val i = (y * outW + x) * 4
                val j = (y * outW + x - 1) * 4
                sum += abs((rgba[i].toInt() and 0xFF) - (rgba[j].toInt() and 0xFF)).toLong()
                sum += abs((rgba[i + 1].toInt() and 0xFF) - (rgba[j + 1].toInt() and 0xFF)).toLong()
                sum += abs((rgba[i + 2].toInt() and 0xFF) - (rgba[j + 2].toInt() and 0xFF)).toLong()
                n += 3
                y++
            }
            return sum.toDouble() / max(1L, n)
        }

        var globalSum = 0.0
        var globalN = 0
        var x = 1
        while (x < outW) {
            globalSum += colDiff(x)
            globalN++
            x += 5
        }
        val global = globalSum / max(1, globalN)
        if (boundaries.isEmpty()) return 0.0
        val seam = boundaries.map { colDiff(it) }.average()
        return seam / max(0.5, global)
    }

    // ---- 工具 ---------------------------------------------------------------

    private fun copyModelAsset(model: PluginUpscaleModel, dest: File): Boolean = runCatching {
        val pkg = model.sourcePackage ?: return false
        val ctx = createPackageContext(pkg, 0)
        ctx.assets.open("${model.assetDir}/${model.assetFileName(0)}").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.length() > 0
    }.getOrDefault(false)

    private fun log(level: String, message: String) {
        Log.i(TAG, "$level $message")
        runCatching { DiagLog.i(TAG, "$level $message") }
    }

    private fun fail(reason: String): Int {
        log("FAIL", reason)
        return RESULT_FAIL
    }

    private companion object {
        const val TAG = "LiteRtSelfTest"
        const val SEAM_THRESHOLD = 3.0
        const val RESULT_PASS = 0
        const val RESULT_FAIL = 1
        const val RESULT_SKIP = 2
    }
}

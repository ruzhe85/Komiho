package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import eu.kanade.tachiyomi.diagnostic.DiagLog
import tachiyomi.core.common.util.system.ImageUtil
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Komiho: 封面 / 缩略图的采样解码统一入口，在 [ImageUtil.decodeSampled] 外面再裹一层
 * 「只在失败时写日志」的诊断。
 *
 * 起因：部分机型（定制 ROM）系统 AVIF 解码不可用，导致「封面空白但点开能正常阅读」——
 * 这类问题开发侧无法复现，只能靠用户导出的诊断日志定性。日志内容刻意能区分三种情况：
 * - `type`：嗅探到的真实格式（`unknown` = 连格式都认不出，说明字节本身有问题）；
 * - `strategy`：AVIF/JXL 走 JNI 直解，其余格式是「系统解码失败后已尝试 JNI 兜底」——
 *   由此可判断「两路都失败」还是「只挂系统那一路」；
 * - `bytes`：源数据长度（只读前 64KB，`0` = 空流，属于上游取图问题而非解码器）。
 */
internal fun decodeCoverBitmap(
    label: String,
    maxPx: Int,
    open: () -> InputStream?,
): Bitmap? {
    val bitmap = ImageUtil.decodeSampled(open, maxPx)
    if (bitmap != null) return bitmap

    runCatching {
        val type = open()?.use { ImageUtil.findImageType(BufferedInputStream(it)) }
        val strategy = when (type) {
            ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL -> "JNI"
            else -> "system->JNI"
        }
        val bytes = runCatching {
            open()?.use { input ->
                val buf = ByteArray(HEAD_BYTES)
                var total = 0L
                while (total < HEAD_BYTES) {
                    val n = input.read(buf, 0, HEAD_BYTES - total.toInt())
                    if (n <= 0) break
                    total += n
                }
                total
            } ?: -1L
        }.getOrDefault(-1L)
        DiagLog.w(
            "CoverDecode",
            "解码失败 $label type=${type?.name ?: "unknown"} strategy=$strategy bytes=$bytes",
        )
    }
    return null
}

/** 失败诊断只需判断「是不是空流」，读这么多足够，避免为一条日志把远程整张图拉下来。 */
private const val HEAD_BYTES = 64 * 1024

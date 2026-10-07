package eu.kanade.tachiyomi.util.mobi

import java.io.File

/** DRM（Kindle 加密书）——无 key 无法解密，明确报错提示用户。 */
class MobiDrmException(message: String? = null) : Exception(message)

/**
 * libmobi 抽图结果：元数据 + 有序图片文件列表 + 封面。
 * 图片已按记录顺序落盘为独立文件，页 stream 直接读文件，解码+增强管线拿到原始字节。
 */
data class MobiBook(
    val title: String?,
    val author: String?,
    val coverFile: File?,
    val imageFiles: List<File>,
)

/**
 * MOBI/AZW3/AZW 解析包装（纯抽图，不做文字渲染 —— 语义对齐 EpubPageLoader）。
 * 引擎为 vendored libmobi（app/src/main/cpp/mobi/），JNI 见 mobi_jni.cpp：
 * 一次调用完成「载入 → DRM 判定 → 解析 → 遍历资源部件落盘 → 元数据/封面」，返回
 * [title, author, coverPath, img1, img2, ...] 字符串数组。DRM 抛 [MobiDrmException]。
 */
object MobiExtractor {

    init {
        // mobi-jni 链接 libmobi.so；API 24+ 链接器可从 APK lib 目录解析 DT_NEEDED，
        // 显式先载 libmobi 兜底老设备。
        runCatching { System.loadLibrary("mobi") }
        System.loadLibrary("mobi-jni")
    }

    fun extractImages(file: File, outDir: File): MobiBook {
        outDir.mkdirs()
        val result = nativeExtract(file.absolutePath, outDir.absolutePath)
        val title = result[0]?.takeIf { it.isNotBlank() }
        val author = result[1]?.takeIf { it.isNotBlank() }
        val cover = result[2]?.takeIf { it.isNotBlank() }?.let(::File)
        val images = result.drop(3).filterNotNull().map(::File)
        return MobiBook(title, author, cover, images)
    }

    private external fun nativeExtract(path: String, outDir: String): Array<String?>
}

package app.mihonsy.komga.data.cloud115

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 115 账号信息（用于跨设备识别「同一个 115」）。 */
data class Cloud115User(
    val userId: String,
    val userName: String = "",
)

/**
 * Komiho：115 目录里的一个条目（目录或文件）。
 *
 * 字段名对应 115 开放平台 `/open/ufile/files` 的返回：
 * `fn` 名称、`fc` 分类（0=文件夹，1=文件）、`fid` 文件 id、`pid` 父目录 id、
 * `pc` 提取码（pickcode，取直链用）、`fs` 大小、`sha1`、`upt` 修改时间（秒）。
 */
data class Cloud115Entry(
    val name: String,
    val isDir: Boolean,
    val fid: String,
    val parentCid: String,
    /** 文件提取码；目录为空。改名/移动不变，是章节 URL 与页缓存的稳定键。 */
    val pickCode: String,
    val size: Long = 0L,
    /** 修改时间 epoch ms；未知为 0（仅用于排序）。 */
    val lastModified: Long = 0L,
    val sha1: String = "",
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()

    /** 是否为可打开的压缩包（当前只做压缩文件；epub/pdf 等暂不列）。 */
    val isArchive: Boolean get() = !isDir && ext in ARCHIVE_EXTS

    /** 页缓存失效键素材：size + sha1 前 8 位（比 Last-Modified 可靠）。 */
    val fingerprint: String get() = "$size:${sha1.take(8)}"

    companion object {
        val ARCHIVE_EXTS = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7")

        /**
         * 解析一个条目对象。
         *
         * **两套字段名都要吃**，因为列表和搜索是两个接口、结构不等价：
         *  - `/open/ufile/files`：`fid / pid / pc / fn / fc / fs / upt`（短名，数值型）
         *  - `/open/ufile/search`：`file_id / parent_id / pick_code / file_name / file_category`
         *    （长名，`file_size` / `user_utime` 还是**字符串**）
         * 缺字段时尽量降级，不抛异常。
         */
        fun fromJson(obj: JsonObject): Cloud115Entry? {
            val name = obj.str("fn") ?: obj.str("file_name") ?: obj.str("n") ?: return null
            val fid = obj.str("fid") ?: obj.str("file_id").orEmpty()
            val parent = obj.str("pid") ?: obj.str("parent_id").orEmpty()
            // fc / file_category：0 = 文件夹、1 = 文件。
            val fc = obj.str("fc") ?: obj.str("file_category")
            val pick = obj.str("pc") ?: obj.str("pick_code").orEmpty()
            val size = obj.long("fs") ?: obj.str("file_size")?.toLongOrNull() ?: obj.long("s") ?: 0L
            val isDir = when (fc) {
                "0" -> true
                "1" -> false
                // fc 缺失时的兜底：目录没有 pickcode、也没有大小。
                // （不要用「扩展名是否为空」判断：目录名带点会让 "Vol.01" 被当成文件。）
                else -> pick.isBlank() && size <= 0L
            }
            val upt = obj.long("upt")
                ?: obj.str("user_utime")?.toLongOrNull()
                ?: obj.long("uet")
                ?: 0L
            return Cloud115Entry(
                name = name,
                isDir = isDir,
                fid = fid,
                parentCid = parent,
                pickCode = pick,
                size = size,
                // upt 是秒级时间戳；已经是大数（ms）时不再乘
                lastModified = if (upt in 1..9_999_999_999L) upt * 1000 else upt,
                sha1 = obj.str("sha1").orEmpty(),
            )
        }

        private fun JsonObject.str(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

        private fun JsonObject.long(key: String): Long? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull()
    }
}

/** 一次列目录的结果（115 单页上限约 1150，OpenList 默认 200）。 */
data class Cloud115Page(
    val entries: List<Cloud115Entry>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)

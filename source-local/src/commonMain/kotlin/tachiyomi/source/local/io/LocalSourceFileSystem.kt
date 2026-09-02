package tachiyomi.source.local.io

import com.hippo.unifile.UniFile

expect class LocalSourceFileSystem {

    fun getBaseDirectory(): UniFile?

    /**
     * 可选存储卷根的绝对路径列表（内部存储 + SD 卡…），第一个为内部存储。
     * 未持有 MANAGE_EXTERNAL_STORAGE（SAF 模式）时返回空列表——SAF 下没有"卷"概念，
     * 根只有一个：用户授权的 tree URI。
     */
    fun getStorageRoots(): List<String>

    fun getFilesInBaseDirectory(): List<UniFile>

    fun getMangaDirectory(name: String): UniFile?

    fun getFilesInMangaDirectory(name: String): List<UniFile>

    /**
     * 取一个 UniFile 的真实文件系统绝对路径（canonical）。file:// 直接取 path；
     * content:// SAF 把 doc id 解码为真实路径。同一物理文件在 SAF 与全权限（MANAGE）
     * 两种模式下都得到相同路径——这是两种模式书签/历史互认的基础。失败返回 null。
     */
    fun realPathOf(uni: UniFile): String?

    /**
     * 把真实绝对路径（[realPathOf] 的结果）映射到当前浏览根下的 UniFile。
     * 文件必须在当前根之下（含跨层），否则返回 null（如跨存储卷、或尚未授权访问）。
     */
    fun resolveUnderBase(canonicalUrl: String): UniFile?

    /**
     * 把真实绝对路径转为相对当前根的路径（以 '/' 连接、无前导斜杠）；不在根下返回 null。
     * 仅供「打开文件位置」这类只需相对段的逻辑使用。
     */
    fun relativeFromBase(canonicalUrl: String): String?
}

package mihon.core.common.archive

// SY --> Komiho Phase7：远程归档的章节 URL 方案常量。
//
// 章节 URL 是「来源无关」的字符串，但 LocalSource（source-local 模块）与 ChapterLoader
// （app 模块）都要判断它是否远程、以及是哪一种远程——而前者看不到 app 层的
// SmbConnectionStore，后者看不到 source-local。把前缀收口在这里（core.common，
// 两侧都依赖），避免同一个字符串常量散落在两个模块里各写一份。
object RemoteScheme {

    /** WebDAV：`webdav:` 或 `webdav://<connId>/<完整URL>`。 */
    const val WEBDAV = "webdav:"

    /** SMB：`smb://<connId>/<共享内相对路径>`。 */
    const val SMB = "smb://"

    /** 是否为远程归档章节（本地文件系统解析会让它们直接失败，必须先分流）。 */
    fun isRemote(chapterUrl: String): Boolean =
        chapterUrl.startsWith(WEBDAV) || chapterUrl.startsWith(SMB)

    /** 是否为 SMB 章节（WEBDAV 之外的另一类远程）。 */
    fun isSmb(chapterUrl: String): Boolean = chapterUrl.startsWith(SMB)
}
// SY <--

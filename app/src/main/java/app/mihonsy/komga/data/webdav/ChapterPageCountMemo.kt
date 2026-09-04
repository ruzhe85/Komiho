package app.mihonsy.komga.data.webdav

// SY --> Komiho Phase4: 章节总页数进程内备忘。
// 本地章节历史/书签行可用磁盘计数（countChapterPages）；WebDAV 等远程章节无本地文件，
// 依赖阅读器加载成功后回填。进程死亡丢失后回落「无总页数」展示，再次阅读即恢复。

/** 章节总页数内存备忘：阅读器写入，历史/书签行读取。 */
object ChapterPageCountMemo {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun put(chapterUrl: String, count: Int) {
        if (count > 0) map[chapterUrl] = count
    }

    fun get(chapterUrl: String): Int = map[chapterUrl] ?: 0
}
// SY <--

package tachiyomi.domain.chapter.service

/**
 * Komiho: 给「本地发现的章节」（同目录下的文件 / 子目录，调用方已按文件名自然序排好）分配章节号。
 *
 * 规则：优先沿用文件名里解析出的编号（`Chapter 12` → 12，保持原有语义）；但**当这批编号随自然序
 * 不是严格递增时**，说明解析丢了信息 —— 最典型的是 `s1_01` / `s2_01`：`ChapterRecognition` 的
 * `unwanted` 正则会把季/卷标记（`s1`、`s2`）当多余标签抹掉，两者都解析成 **1.0**。这种情况下按编号
 * 排序，阅读器的「下一章」会变成 `s1_01 → s2_01 → s1_02 → s2_02`（真机实测）。
 *
 * 所以此时整批改用自然序下标（1..N），保证顺序 = 文件名自然序。
 * 传入的 [sortedNames] 必须与调用方的自然序一致（各调用点都是 `compareToCaseInsensitiveNaturalOrder`）。
 */
object ChapterNumbering {

    fun assign(sortedNames: List<String>, mangaTitle: String): List<Double> {
        val parsed = sortedNames.map {
            ChapterRecognition.parseChapterNumber(mangaTitle, it, -1.0)
        }
        val consistent = parsed.all { it > 0 } && parsed.zipWithNext().all { (a, b) -> a < b }
        return parsed.mapIndexed { index, value ->
            if (consistent) value else index + 1.0
        }
    }
}

package eu.kanade.tachiyomi.util

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder

// SY --> Komiho: 封面选取统一口径 —— 文件名含 "cover"（不分大小写）者优先命中，
// 多个 cover 时取自然序第一；无 cover 命中则回落自然序第一（2.jpg < 10.jpg）。
// 本地浏览封面（LocalCoverFetcher）、SMB 浏览封面（SmbCoverFetcher）、
// SMB 历史/书签封面（SmbCoverCache）三处共用，保证同一本书各处封面一致。
internal fun pickCoverFirstImage(names: List<String>): String? {
    if (names.isEmpty()) return null
    val natural = Comparator<String> { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
    return names.filter { it.contains("cover", ignoreCase = true) }.minWithOrNull(natural)
        ?: names.minWithOrNull(natural)
}
// SY <--

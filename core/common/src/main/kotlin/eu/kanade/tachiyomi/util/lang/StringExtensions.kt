package eu.kanade.tachiyomi.util.lang

import androidx.core.text.parseAsHtml
import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.text.Collator
import java.util.Locale
import kotlin.math.floor

/**
 * Replaces the given string to have at most [count] characters using [replacement] at its end.
 * If [replacement] is longer than [count] an exception will be thrown when `length > count`.
 */
fun String.chop(count: Int, replacement: String = "…"): String {
    return if (length > count) {
        take(count - replacement.length) + replacement
    } else {
        this
    }
}

/**
 * Replaces the given string to have at most [count] characters using [replacement] near the center.
 * If [replacement] is longer than [count] an exception will be thrown when `length > count`.
 */
fun String.truncateCenter(count: Int, replacement: String = "..."): String {
    if (length <= count) {
        return this
    }

    val pieceLength: Int = floor((count - replacement.length).div(2.0)).toInt()

    return "${take(pieceLength)}$replacement${takeLast(pieceLength)}"
}

/**
 * Case-insensitive natural comparator for strings.
 */
fun String.compareToCaseInsensitiveNaturalOrder(other: String): Int {
    val comparator = CaseInsensitiveSimpleNaturalComparator.getInstance<String>()
    return comparator.compare(this, other)
}

/**
 * 文件浏览器用的「数字字母中文拼音首字母」自然排序：
 * - 数字段按数值大小比较（Chapter2 < Chapter10），数字整体排在字母/中文之前；
 * - 字母/中文段用简体中文 Collator，中文按拼音首字母排序（与设备语言无关，统一口径）。
 * 只用于文件浏览器的名称排序，不改章节排序（避免影响章节号推断）。
 */
private val pinyinCollator by lazy {
    Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
        strength = Collator.PRIMARY
    }
}

private data class PinyinToken(val text: String, val isDigit: Boolean)

private fun tokenizeForPinyin(s: String): List<PinyinToken> {
    val tokens = mutableListOf<PinyinToken>()
    var i = 0
    while (i < s.length) {
        val digit = s[i].isDigit()
        var j = i + 1
        while (j < s.length && s[j].isDigit() == digit) j++
        tokens.add(PinyinToken(s.substring(i, j), digit))
        i = j
    }
    return tokens
}

fun String.compareToNaturalPinyin(other: String): Int {
    val a = tokenizeForPinyin(this)
    val b = tokenizeForPinyin(other)
    val n = minOf(a.size, b.size)
    for (k in 0 until n) {
        val x = a[k]
        val y = b[k]
        val cmp = when {
            x.isDigit && y.isDigit -> {
                val nn = x.text.toBigIntegerOrNull()?.compareTo(y.text.toBigIntegerOrNull() ?: BigInteger.ZERO)
                    ?: x.text.compareTo(y.text)
                if (nn != 0) nn else x.text.length.compareTo(y.text.length)
            }
            x.isDigit != y.isDigit -> if (x.isDigit) -1 else 1
            else -> pinyinCollator.compare(x.text, y.text)
        }
        if (cmp != 0) return cmp
    }
    return a.size.compareTo(b.size)
}

/** 供 [kotlin.comparisons.compareBy] 等使用的 [String] 比较器（文件浏览器名称排序）。 */
val naturalPinyinComparator: Comparator<String> = Comparator { a, b -> a.compareToNaturalPinyin(b) }

/**
 * Returns the size of the string as the number of bytes.
 */
fun String.byteSize(): Int {
    return toByteArray(StandardCharsets.UTF_8).size
}

/**
 * Returns a string containing the first [n] bytes from this string, or the entire string if this
 * string is shorter.
 */
fun String.takeBytes(n: Int): String {
    val bytes = toByteArray(StandardCharsets.UTF_8)
    return if (bytes.size <= n) {
        this
    } else {
        bytes.decodeToString(endIndex = n).replace("\uFFFD", "")
    }
}

/**
 * HTML-decode the string
 */
fun String.htmlDecode(): String {
    return this.parseAsHtml().toString()
}

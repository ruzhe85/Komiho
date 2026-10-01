package tachiyomi.core.common.util

fun String.nullIfBlank(): String? = ifBlank { null }

fun String.trimOrNull(): String? = trim().nullIfBlank()

fun Collection<String>.dropBlank(): List<String> = filter { it.isNotBlank() }

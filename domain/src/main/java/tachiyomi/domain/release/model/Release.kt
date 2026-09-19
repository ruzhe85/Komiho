package tachiyomi.domain.release.model

import android.os.Build

/**
 * Contains information about the latest release.
 */
data class Release(
    val version: String,
    val info: String,
    val releaseLink: String,
    private val assets: List<String>,
) {

    /**
     * Get download link of latest release from the assets.
     * @return download link of latest release.
     */
    fun getDownloadLink(): String {
        val apkVariant = when (Build.SUPPORTED_ABIS[0]) {
            "arm64-v8a" -> "-arm64-v8a"
            "armeabi-v7a" -> "-armeabi-v7a"
            "x86" -> "-x86"
            "x86_64" -> "-x86_64"
            else -> ""
        }

        // Komiho (2026-09-19): this used to match "TachiyomiSY$apkVariant-", which
        // never matched our `komiho-<version>-<abi>.apk` assets, so every device
        // silently fell back to assets[0] (right APK only by luck of ordering).
        return assets.find { it.contains("$apkVariant-") }
            ?: assets.firstOrNull().orEmpty()
    }

    /**
     * Assets class containing download url.
     */
    data class Assets(val downloadLink: String)
}

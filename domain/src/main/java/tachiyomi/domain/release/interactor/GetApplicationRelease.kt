package tachiyomi.domain.release.interactor

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_app_check"), 0)
    }

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()

        // Limit checks to once every 3 days at most
        val nextCheckTime = Instant.ofEpochMilli(lastChecked.get()).plus(3, ChronoUnit.DAYS)
        if (!arguments.forceCheck && now.isBefore(nextCheckTime)) {
            return Result.NoNewUpdate
        }

        val release = service.latest(arguments.repository)

        lastChecked.set(now.toEpochMilli())

        // Check if latest version is different from current version
        // SY -->
        val isNewVersion =
            isNewVersion(arguments.isPreview, arguments.syDebugVersion, arguments.versionName, release.version)
        // SY <--
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    // SY -->
    private fun isNewVersion(
        isPreview: Boolean,
        syDebugVersion: String,
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Removes prefixes like "r" or "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        return if (isPreview) {
            // Preview builds: based on releases in "jobobby04/TachiyomiSYPreview" repo
            // tagged as something like "508"
            val currentInt = syDebugVersion.toIntOrNull()
            currentInt != null && newVersion.toInt() > currentInt
        } else {
            // Release builds: based on releases tagged as something like "0.1.2".
            // Komiho (2026-09-19): the old loop indexed `newSemVer[index]` against
            // `oldSemVer` while only ever returning early on "greater", so it both
            // crashed when the new tag had fewer segments (e.g. "1.2" vs "1.1.0")
            // and wrongly reported an update when an earlier segment was already
            // smaller (1.1.9 vs 1.2.0). Pad both sides with 0 and compare strictly.
            val oldVersion = versionName.replace("[^\\d.]".toRegex(), "")

            val newSemVer = newVersion.split(".").mapNotNull { it.toIntOrNull() }
            val oldSemVer = oldVersion.split(".").mapNotNull { it.toIntOrNull() }
            if (newSemVer.isEmpty() || oldSemVer.isEmpty()) return false

            repeat(maxOf(newSemVer.size, oldSemVer.size)) { index ->
                val newPart = newSemVer.getOrElse(index) { 0 }
                val oldPart = oldSemVer.getOrElse(index) { 0 }
                if (newPart > oldPart) return true
                if (newPart < oldPart) return false
            }

            false
        }
    }
    // SY <--

    data class Arguments(
        val isPreview: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        // SY -->
        val syDebugVersion: String,
        // SY <--
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}

package eu.kanade.tachiyomi.util.system

import eu.kanade.tachiyomi.BuildConfig

val isDebugBuildType: Boolean
    get() = BuildConfig.BUILD_TYPE == "debug"

// SY --> syDebugVersion removed with exh; preview build type is no longer distinguishable from release
val isPreviewBuildType: Boolean
    get() = false

val isReleaseBuildType: Boolean
    get() = BuildConfig.BUILD_TYPE == "release"
// SY <--

val isBenchmarkBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE.contains("nonMinified") || BuildConfig.BUILD_TYPE.contains("benchmark")

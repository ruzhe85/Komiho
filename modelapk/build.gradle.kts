plugins {
    alias(mihonx.plugins.android.application)
}

// Komiho (2026-09-19 模型插件化): asset-only model-package APK — **one SoC per APK**.
//
// One Gradle module builds **any** SoC package — the packaging workflow
// (`.github/workflows/build-model-apk.yml`) stages that generation's context binaries and
// generates `assets/models.json` (from the global registry on the `model-sources` branch)
// into `src/main/assets/`, then passes the identity via -P properties:
//
//   ./gradlew :modelapk:assembleRelease \
//     -PmodelId=cn.ruzhe.komiho.model.v79 \
//     -PmodelLabel=Komiho-v79 \
//     -PmodelVersionName=1.0 -PmodelVersionCode=1
//
// The APK contains NO executable code (`android:hasCode="false"`, no components): the
// host discovers it by the reserved applicationId prefix `cn.ruzhe.komiho.model.`, verifies
// its signing certificate against the host's, then reads `assets/models.json` and the
// context binaries through createPackageContext. See NpuModelPluginScanner / Waifu2x.
//
// Signing MUST be the same komiho-release keystore as the host app — the scanner rejects
// model packages whose signature differs, which is what stops a fake package from
// injecting entries into the model picker.

android {
    namespace = "cn.ruzhe.komiho.modelpkg"

    // The mihonx application convention force-enables core library desugaring for every
    // module — that would dex the desugar runtime into this APK, producing classes.dex in
    // what must be a code-free asset package (the workflow fails on classes.dex on
    // purpose). Disable it here; this module has no sources at all.
    compileOptions {
        isCoreLibraryDesugaringEnabled = false
    }

    defaultConfig {
        applicationId = (project.findProperty("modelId") as String?)?.takeIf { it.isNotBlank() }
            ?: "cn.ruzhe.komiho.model.template"

        versionCode = (project.findProperty("modelVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("modelVersionName") as String?) ?: "1.0"

        manifestPlaceholders["modelLabel"] =
            (project.findProperty("modelLabel") as String?)?.takeIf { it.isNotBlank() }
                ?: "Komiho NPU Models"
    }

    signingConfigs {
        create("komihoRelease") {
            val ks = rootProject.file("keystore/komiho-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storeType = "PKCS12"
                storePassword = System.getenv("KOMIHO_RELEASE_KEYSTORE_PASSWORD") ?: "komihorelease123"
                keyAlias = "komiho-release"
                keyPassword = System.getenv("KOMIHO_RELEASE_KEYSTORE_PASSWORD") ?: "komihorelease123"
            }
        }
    }

    buildTypes {
        named("release") {
            // Nothing to shrink — there is no code and the assets are the payload.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("komihoRelease")
        }
    }
}

// The convention also injects the desugar artifact into the coreLibraryDesugaring
// configuration; drop it so nothing can be dexed into the asset-only package.
configurations.matching { it.name == "coreLibraryDesugaring" }.all {
    artifacts.clear()
}

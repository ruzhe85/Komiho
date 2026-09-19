plugins {
    // Komiho: deliberately NOT the mihonx application convention — it force-enables core
    // library desugaring and injects the desugar runtime, which AGP dexes into the APK
    // (~2.2 MB classes.dex) even for a source-less module. This package must be asset-only.
    alias(libs.plugins.android.application)
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
    compileSdk = 36

    defaultConfig {
        applicationId = (project.findProperty("modelId") as String?)?.takeIf { it.isNotBlank() }
            ?: "cn.ruzhe.komiho.model.template"

        minSdk = 26
        targetSdk = 36

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

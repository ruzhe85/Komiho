plugins {
    alias(mihonx.plugins.android.application)
}

// Komiho (2026-09-19 模型插件化): asset-only model-package APK.
//
// One Gradle module builds **any** model family — the packaging workflow
// (`.github/workflows/build-model-apk.yml`) stages the family's context binaries and
// `models.json` into `src/main/assets/` and passes the identity via -P properties:
//
//   ./gradlew :modelapk:assembleRelease \
//     -PmodelId=cn.ruzhe.komiho.model.nomosuni \
//     -PmodelLabel=Komiho-NomosUni \
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

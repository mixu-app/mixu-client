import java.util.Base64

plugins {
    id("mixu.android.application")
    id("mixu.compose")
    id("mixu.quality")
    id("mixu.reproducible")
}

android {
    namespace = "app.mixu.client"

    defaultConfig {
        applicationId = "app.mixu.client"
        versionCode = 2
        versionName = "0.0.2"
    }

    val keystorePath = System.getenv("MIXU_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    val keystoreBase64 = System.getenv("MIXU_KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }
    val resolvedKeystoreFile =
        when {
            keystorePath != null && file(keystorePath).exists() -> file(keystorePath)
            keystoreBase64 != null ->
                runCatching {
                    val decoded =
                        layout.buildDirectory
                            .file("tmp/signing/release.keystore")
                            .get()
                            .asFile
                    decoded.parentFile.mkdirs()
                    decoded.writeBytes(Base64.getDecoder().decode(keystoreBase64.trim()))
                    decoded
                }.getOrNull()
            else -> null
        }
    val keystorePassword = System.getenv("MIXU_KEYSTORE_PASSWORD").orEmpty()
    val keyAlias = System.getenv("MIXU_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "mixu-release"
    val keyPassword =
        System.getenv("MIXU_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: keystorePassword

    signingConfigs {
        if (resolvedKeystoreFile != null && resolvedKeystoreFile.exists()) {
            create("release") {
                storeFile = resolvedKeystoreFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig =
                signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation("app.mixu:mixu-graph")
    implementation("app.mixu:mixu-spoiler")
    implementation("app.mixu:mixu-renderer")
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

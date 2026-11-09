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
        versionCode = 1
        versionName = "0.0.1"
    }

    val keystorePath = System.getenv("MIXU_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    val keystorePassword = System.getenv("MIXU_KEYSTORE_PASSWORD").orEmpty()
    val keyAlias = System.getenv("MIXU_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "mixu-release"
    val keyPassword =
        System.getenv("MIXU_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: keystorePassword

    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
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

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
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

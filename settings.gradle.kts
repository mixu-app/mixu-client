rootProject.name = "mixu-client"

pluginManagement {
    includeBuild("build-logic-src/build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("build-logic-src/gradle/libs.versions.toml"))
        }
    }
}

include(":app")

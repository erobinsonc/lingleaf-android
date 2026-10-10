// `core` is an included build: pure Kotlin, no Android SDK needed, so its
// tests run anywhere with `./gradlew -p core check`.
rootProject.name = "core"

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

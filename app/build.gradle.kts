import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "app.lingleaf"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lingleaf"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        // R8 removes unused code (less to attack) and the log calls (proguard-rules.pro).
        // Renaming hides nothing in an open-source app; shrinking is what counts.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-release.pro")
        }
        // The release code path (R8 on) with the debug key, test hooks and logs, so CI's
        // emulator tour proves the shrunk app still saves, reads and shows everything.
        create("minified") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Full stack traces in the CI log, so failures can be read without the report.
                test.testLogging {
                    events("failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }

    lint {
        abortOnError = true
        // Accessibility problems fail the build.
        error += setOf("ContentDescription", "ClickableViewAccessibility", "KeyboardInaccessibleWidget", "LabelFor")
        // Version bumps are Dependabot's job, not the linter's.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("app.lingleaf:core:0.1.0")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mlkit.translate)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

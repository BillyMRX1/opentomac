plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "dev.opentomac.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.opentomac.android"
        minSdk = 26
        targetSdk = 35
        val semver = "0.5.0" // x-release-please-version
        versionName = semver
        versionCode = semver.split(".").let { (major, minor, patch) ->
            major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.okio)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.security.crypto)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.zxing.android.embedded)
    testImplementation(kotlin("test"))
    debugImplementation(libs.androidx.compose.ui.tooling)
}

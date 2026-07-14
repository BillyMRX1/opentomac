plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    jvmToolchain(17)

    androidTarget()

    jvm()

    macosArm64 {
        binaries.framework {
            baseName = "OpentomacShared"
            // Dynamic so Kotlin/Native links the libsodium cinterop into the framework
            // binary; a static framework would leave those C symbols unresolved in the app.
            isStatic = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.okio)
            implementation(libs.libsodium.bindings)
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

android {
    namespace = "dev.opentomac.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}

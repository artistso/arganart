// Project persistence, settings, encrypted storage (populated in P9).
// At P0 this module contains only the project-format version stamp and the
// storage ports, so that later phases have something concrete to extend.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "art.argan.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}

// Native inference runtime. The ONLY module that pulls in ONNX Runtime.
//
// WHY A SEPARATE MODULE, AND WHY AN ANDROID LIBRARY
// -------------------------------------------------
// `com.microsoft.onnxruntime:onnxruntime-android` is an Android **AAR**. It cannot
// be consumed by a pure-JVM module, so this module must be an Android library —
// and therefore must not be :ai:runtime (which is deliberately pure JVM so it can
// be unit-tested on the host and shared with :core:*).
//
// The split also buys something concrete:
//
//   Nothing depends on this module yet. Because nothing declares it as a
//   dependency, its native .so files are NOT packaged into the APK, and the APK
//   size is unchanged from P0. The runtime cost is paid when a model is actually
//   wired up in P7, not before.
//
// It also keeps rule 4 intact: no `:core:*` module can ever reach native code
// through this module, because the edges below do not permit it.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "art.argan.ai.onnx"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keep only the ABIs we intend to support. ONNX Runtime ships x86_64 for
    // emulators and arm64-v8a for every real 2023+ tablet; 32-bit ARM is
    // deliberately excluded to halve the native payload (P7 decision, recorded
    // here so the choice is visible rather than accidental).
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.onnxruntime.android)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

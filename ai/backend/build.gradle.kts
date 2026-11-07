// INTERFACES + DTOs ONLY. Rule 3: nothing in the product talks to a model
// except through these types, so this module must stay free of implementations.
//
// Deliberately a pure-JVM module (not an Android library): the contract must be
// unit-testable on the host, and its types must be safe to share with :core:*.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // MotionClip/MotionSpec are the shared vocabulary between the UI, the
    // motion engine and MotionBackend.
    api(project(":core:motion"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

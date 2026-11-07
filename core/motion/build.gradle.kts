// Pure-JVM Kotlin module: no UI, no Android types (ARCHITECTURE.md rule 4).
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
    // The motion model is shared with :ai:backend DTOs, which depend on us, not
    // the other way round. :core:motion knows nothing about backends or UI.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

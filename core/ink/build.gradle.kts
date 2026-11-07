// Pure-JVM Kotlin module: no UI, no Android types (ARCHITECTURE.md rule 4).
// Android interop (MotionEvent, GL) lives in :ai:runtime / :feature:* adapters.
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
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

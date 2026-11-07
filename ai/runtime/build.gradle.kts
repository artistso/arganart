// The ONLY module allowed to see the real motion/generation implementations at
// this stage (the procedural MotionBackend lives here). Everything else sees
// :ai:backend only.
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
    api(project(":ai:backend"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

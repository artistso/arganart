// Frame-timing statistics: JankStats feeds it, the debug overlay and CI read it.
//
// Pure JVM on purpose: the budgets in PERFORMANCE.md ("<1% janky frames",
// "median input-to-photon < 16ms") are computed by this module, and a number we
// quote must be arithmetic we can unit-test on the host rather than a figure
// nobody can reproduce.
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

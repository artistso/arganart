// Root build file. No plugins applied here: each module declares exactly the
// plugins it needs, so that the module's nature (pure JVM vs Android vs UI)
// is visible in its own build file and enforceable by tools/check-architecture.py.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Architectural boundary check (ARCHITECTURE.md rules 1-6).
 *
 * Runs on `check` so a violation fails the build locally and in CI, not in review.
 */
val architectureCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies module layering, no-UI-in-core, no-text-prompt, no-network rules."
    workingDir = rootDir
    commandLine("python3", "tools/check-architecture.py")
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(architectureCheck)
    }
}

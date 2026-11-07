pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "arganart"

// P0 module graph. The set and the edges are locked by tools/check-architecture.py.
// Adding a module or a dependency edge is an architectural change: update
// ARCHITECTURE.md, the ALLOWED table in tools/check-architecture.py, and CI.

include(":app")

include(":core:ink")
include(":core:motion")
include(":core:spatial")
include(":core:diagnostics")

include(":ai:backend")
include(":ai:runtime")

include(":feature:canvas")
include(":feature:motion")

include(":data")
include(":design")

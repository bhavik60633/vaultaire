// Vaultaire Android app (milestone M2 onwards). Unaudited beta: no real credentials (Gate G0).
pluginManagement {
    repositories {
        google()
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

rootProject.name = "vaultaire"
include(":app", ":core-bindings")

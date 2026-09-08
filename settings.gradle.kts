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
        // Qualcomm AI Hub / LiteRT Maven (hosted via Google Maven / Maven Central)
        maven { url = uri("https://maven.google.com") }
        // JitPack for any community extensions
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "OmniNode_Hub"
include(":app")

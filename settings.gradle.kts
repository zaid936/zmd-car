pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        // Android Auto built into ZMD Car: the ZMD Auto engine (AGPL-3.0), published by
        // A:\byd\ZMD-Auto with `gradlew :engine:publishReleasePublicationToEngineRepository`.
        maven {
            url = uri("../ZMD-Auto/build/engine-repo")
            content { includeGroup("com.zmd") }
        }
        // libsu (the engine's root helper) is only on JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.topjohnwu.*") }
        }
    }
}

rootProject.name = "CarHub"
include(":app")

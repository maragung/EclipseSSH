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
        // JitPack serves vernacular-vnc (com.github.maragung:vernacular-vnc), which has no
        // Maven Central release. It is a build-time artifact host, not a plugin source, so
        // it belongs here rather than in pluginManagement.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "EclipseSSH"
include(":app")

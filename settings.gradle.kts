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
        // LightNovelReader plugin API + KSP compiler are published here.
        maven { url = uri("https://maven.nariko.org/release") }
    }
}

rootProject.name = "LightNovelReaderWenku8Plus"
include(":plugin")

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
        providers.gradleProperty("robolectricRepository").orNull?.let { sdkRepository ->
            exclusiveContent {
                forRepository { maven { url = uri(sdkRepository) } }
                filter { includeModule("org.robolectric", "android-all-instrumented") }
            }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven {
            name = "robolectricSdkMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
            content { includeModule("org.robolectric", "android-all-instrumented") }
            metadataSources { artifact() }
        }
    }
}
rootProject.name = "OtohaMobile"
include(":core", ":app")

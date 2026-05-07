enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Vector"

include(
    ":app",
    ":daemon",
    ":dex2oat",
    ":external:axml",
    ":external:apache",
    ":hiddenapi:stubs",
    ":hiddenapi:bridge",
    ":legacy",
    ":services:manager-service",
    ":services:daemon-service",
    ":xposed",
    ":zygisk",
)

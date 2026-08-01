val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion

plugins {
    id("java-library")
}

java {
    sourceCompatibility = androidSourceCompatibility
    targetCompatibility = androidTargetCompatibility
    sourceSets {
        main {
            java.srcDirs("manifest-editor/lib/src/main/java")
            resources.srcDirs("manifest-editor/lib/src/main")
        }
    }
}

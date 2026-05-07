val androidSourceCompatibility: JavaVersion by rootProject.extra
val androidTargetCompatibility: JavaVersion by rootProject.extra

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

plugins { id("java-library") }

java {
    sourceSets {
        main {
            java.srcDirs("manifest-editor/lib/src/main/java")
            resources.srcDirs("manifest-editor/lib/src/main")
        }
    }
}

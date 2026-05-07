plugins { alias(libs.plugins.agp.lib) }

android {
    buildFeatures { aidl = true }

    buildTypes { release { isMinifyEnabled = false } }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/java", "../libxposed/service/src/main")
            aidl.srcDirs("src/main/aidl", "../libxposed/interface/src/main/aidl")
        }
    }

    aidlPackagedList += "org/lsposed/lspd/models/Module.aidl"
    namespace = "org.lsposed.lspd.daemonservice"
}

dependencies {
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}

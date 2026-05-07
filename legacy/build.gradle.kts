plugins { alias(libs.plugins.agp.lib) }

android {
    namespace = "org.matrix.vector.legacy"

    androidResources { enable = false }

    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    api(projects.xposed)
    implementation(projects.external.apache)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.daemonService)
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}

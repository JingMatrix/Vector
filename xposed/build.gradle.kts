val versionCodeProvider: Provider<String> by rootProject.extra
val versionNameProvider: Provider<String> by rootProject.extra

plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktfmt)
}

ktfmt { kotlinLangStyle() }

android {
    namespace = "org.matrix.vector.impl"

    androidResources { enable = false }

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "FRAMEWORK_NAME", """"${rootProject.name}"""")
        buildConfigField("String", "VERSION_NAME", """"${versionNameProvider.get()}"""")
        buildConfigField("long", "VERSION_CODE", versionCodeProvider.get())
    }

    sourceSets { named("main") { java.srcDirs("src/main/kotlin", "libxposed/api/src/main/java") } }
}

dependencies {
    implementation(projects.external.axml)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.daemonService)
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}

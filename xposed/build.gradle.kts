@Suppress("UNCHECKED_CAST")
val versionCodeProvider = rootProject.extra["versionCodeProvider"] as Provider<String>
@Suppress("UNCHECKED_CAST")
val versionNameProvider = rootProject.extra["versionNameProvider"] as Provider<String>

plugins {
    alias(libs.plugins.agp.lib)
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

    sourceSets {
        named("main") {
            java.directories.addAll(listOf("src/main/kotlin", "libxposed/api/src/main/java"))
        }
    }
}

dependencies {
    implementation(projects.external.axml)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.daemonService)
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}

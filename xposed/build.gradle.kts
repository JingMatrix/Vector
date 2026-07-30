val versionCodeProvider: Provider<String> by rootProject.extra
val versionNameProvider: Provider<String> by rootProject.extra

plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.ktfmt)
}

ktfmt { kotlinLangStyle() }

// The dispatch guard is a thread-local flag whose correctness lives entirely in where it is raised
// and lowered, and a site that gets it wrong is invisible until a device hangs — which is how #798
// took two rounds to fix. Keep the primitives inside DispatchGuard.kt so the invariant is one file.
val checkDispatchGuard by
    tasks.registering {
        val sources =
            listOf(rootProject.file("xposed/src/main"), rootProject.file("legacy/src/main"))
        inputs.files(sources.map { fileTree(it) })
        outputs.upToDateWhen { true }
        doLast {
            val offenders =
                sources
                    .flatMap { fileTree(it).files }
                    .filter { it.extension == "kt" || it.extension == "java" }
                    .filter { it.name != "DispatchGuard.kt" && it.name != "HookBridge.kt" }
                    .filter { f ->
                        f.readLines().any {
                            it.contains("suspendDispatch") ||
                                it.contains("resumeDispatch") ||
                                it.contains("raiseDispatch")
                        }
                    }
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "The dispatch guard primitives belong to DispatchGuard.kt; call " +
                        "callIntoModule/enterFramework instead. Offenders: " +
                        offenders.joinToString { it.name }
                )
            }
        }
    }

tasks.matching { it.name.startsWith("assemble") }.configureEach { dependsOn(checkDispatchGuard) }

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

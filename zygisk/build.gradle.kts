import java.security.MessageDigest
import org.apache.commons.codec.binary.Hex
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktfmt)
}

ktfmt { kotlinLangStyle() }

val versionCodeProvider: Provider<String> by rootProject.extra
val versionNameProvider: Provider<String> by rootProject.extra
val injectedPackageName: String by rootProject.extra
val injectedPackageUid: Int by rootProject.extra
val defaultManagerPackageName: String by rootProject.extra

android {
    namespace = "org.matrix.vector"

    defaultConfig {
        multiDexEnabled = false
        buildConfigField("int", "HostPackageUid", "${injectedPackageUid}")
        buildConfigField("String", "InjectedPackageName", """"${injectedPackageName}"""")
        buildConfigField("String", "ManagerPackageName", """"${defaultManagerPackageName}"""")

        val flags =
            listOf(
                "-DINJECTED_PACKAGE_NAME='\"${injectedPackageName}\"'",
                "-DINJECTED_PACKAGE_UID=${injectedPackageUid}",
                "-DMANAGER_PACKAGE_NAME='\"${defaultManagerPackageName}\"'",
            )

        externalNativeBuild {
            cmake {
                cFlags.addAll(flags)
                cppFlags.addAll(flags)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }

    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt") } }
}

abstract class Injected @Inject constructor(val moduleDir: String) {
    @get:Inject abstract val factory: ObjectFactory
}

dependencies {
    implementation(projects.hiddenapi.bridge)
    implementation(projects.legacy)
    implementation(projects.services.managerService)
    implementation(projects.services.daemonService)
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}

val zipAll = tasks.register("zipAll") { group = "Vector" }

androidComponents {
    onVariants(selector().all()) { variant ->
        val variantCapped = variant.name.replaceFirstChar { it.uppercase() }
        val variantLowered = variant.name.lowercase()

        // --- Define output locations and file names ---
        // Stage all files in a temporary directory inside 'build' before zipping
        val tempModuleDir = project.layout.buildDirectory.dir("module/${variant.name}")
        val zipFileName =
            "Vector-v${versionNameProvider.get()}-${versionCodeProvider.get()}-$variantCapped.zip"

        // Using Sync ensures that stale files from previous runs are removed.
        val prepareModuleFilesTask =
            tasks.register<Sync>("prepareModuleFiles$variantCapped") {
                group = "Vector Module Packaging"
                dependsOn(
                    "assemble$variantCapped",
                    ":app:package$variantCapped",
                    ":daemon:package$variantCapped",
                    ":dex2oat:externalNativeBuild$variantCapped",
                )
                into(tempModuleDir)
                from("${rootProject.projectDir}/README.md")
                from("$projectDir/module") { exclude("module.prop", "action.sh", "daemon") }
                from("$projectDir/module") {
                    include("module.prop")
                    expand(
                        "versionName" to "v${versionNameProvider.get()}",
                        "versionCode" to versionCodeProvider.get(),
                    )
                }
                from("$projectDir/module") {
                    include("action.sh")
                    val tokens =
                        mapOf(
                            "MANAGER_PACKAGE_NAME" to defaultManagerPackageName,
                            "INJECTED_PACKAGE_NAME" to injectedPackageName,
                        )
                    filter<ReplaceTokens>("tokens" to tokens)
                }
                from("$projectDir/module") {
                    include("daemon")
                    val tokens =
                        mapOf("DEBUG" to if (variantLowered == "debug") "true" else "false")
                    filter<ReplaceTokens>("tokens" to tokens)
                }
                from(project(":app").tasks.getByName("package$variantCapped").outputs) {
                    include("*.apk")
                    rename(".*\\.apk", "manager.apk")
                }
                from(project(":daemon").tasks.getByName("package$variantCapped").outputs) {
                    include("*.apk")
                    rename(".*\\.apk", "daemon.apk")
                }
                into("lib") {
                    val libDir = variantLowered + "/strip${variantCapped}DebugSymbols"
                    from(
                        layout.buildDirectory.dir(
                            "intermediates/stripped_native_libs/$libDir/out/lib"
                        )
                    ) {
                        include("**/libzygisk.so")
                    }
                }
                into("bin") {
                    from(
                        project(":dex2oat")
                            .layout
                            .buildDirectory
                            .dir("intermediates/cmake/$variantLowered/obj")
                    ) {
                        include("**/dex2oat")
                        include("**/liboat_hook.so")
                    }
                }
                val dexOutPath =
                    if (variantLowered == "release")
                        layout.buildDirectory.dir(
                            "intermediates/dex/$variantLowered/minify${variantCapped}WithR8"
                        )
                    else
                        layout.buildDirectory.dir(
                            "intermediates/dex/$variantLowered/mergeDex$variantCapped"
                        )
                into("framework") {
                    from(dexOutPath)
                    rename("classes.dex", "lspd.dex")
                }
                val injected = objects.newInstance<Injected>(tempModuleDir.get().asFile.path)
                doLast {
                    injected.factory.fileTree().from(injected.moduleDir).visit {
                        if (isDirectory) return@visit
                        val md = MessageDigest.getInstance("SHA-256")
                        file.forEachBlock(4096) { bytes, size -> md.update(bytes, 0, size) }
                        File(file.path + ".sha256").writeText(Hex.encodeHexString(md.digest()))
                    }
                }
            }

        val zipTask =
            tasks.register<Zip>("zip${variantCapped}") {
                group = "Vector Module Packaging"
                dependsOn(prepareModuleFilesTask)
                archiveFileName = zipFileName
                destinationDirectory = file("$projectDir/release")
                from(tempModuleDir)
            }

        zipAll.configure { dependsOn(zipTask) }

        // A helper function to create installation tasks for different root providers.
        fun createInstallTasks(rootProvider: String, installCli: String) {
            val pushTask =
                tasks.register<Exec>("push${rootProvider}Module${variantCapped}") {
                    group = "Zygisk Module Installation"
                    description =
                        "Pushes the ${variant.name} build to the device for $rootProvider."
                    dependsOn(zipTask)
                    commandLine(
                        "adb",
                        "push",
                        zipTask.get().archiveFile.get().asFile,
                        "/data/local/tmp",
                    )
                }

            val installTask =
                tasks.register<Exec>("install${rootProvider}${variantCapped}") {
                    group = "Zygisk Module Installation"
                    description = "Installs the ${variant.name} build via $rootProvider."
                    dependsOn(pushTask)
                    commandLine(
                        "adb",
                        "shell",
                        "su",
                        "-c",
                        "$installCli /data/local/tmp/$zipFileName",
                    )
                }
            tasks.register<Exec>("install${rootProvider}AndReboot${variantCapped}") {
                group = "Zygisk Module Installation"
                description = "Installs the ${variant.name} build via $rootProvider and reboots."
                dependsOn(installTask)
                commandLine("adb", "reboot")
            }
        }

        createInstallTasks("Magisk", "magisk --install-module")
        createInstallTasks("Ksu", "ksud module install")
        createInstallTasks("Apatch", "/data/adb/apd module install")
    }
}

evaluationDependsOn(":app")

evaluationDependsOn(":daemon")

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.security.MessageDigest
import org.apache.commons.codec.binary.Hex
import org.apache.tools.ant.filters.ReplaceTokens

// Reading the optimised DEX needs a DEX reader; see checkXResourcesIsolation below.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath(libs.smali.dexlib2) }
}

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.ktfmt)
}

ktfmt { kotlinLangStyle() }

@Suppress("UNCHECKED_CAST")
val versionCodeProvider = rootProject.extra["versionCodeProvider"] as Provider<String>
@Suppress("UNCHECKED_CAST")
val versionNameProvider = rootProject.extra["versionNameProvider"] as Provider<String>
@Suppress("UNCHECKED_CAST")
val versionHashProvider = rootProject.extra["versionHashProvider"] as Provider<String>
val injectedPackageName = rootProject.extra["injectedPackageName"] as String
val injectedPackageUid = rootProject.extra["injectedPackageUid"] as Int
val defaultManagerPackageName = rootProject.extra["defaultManagerPackageName"] as String

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
                    ":manager:package$variantCapped",
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
                        "versionHash" to versionHashProvider.get(),
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
                from(project(":manager").tasks.getByName("package$variantCapped").outputs) {
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
                    rename("classes.dex", "vector.dex")
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

evaluationDependsOn(":manager")

evaluationDependsOn(":daemon")

/**
 * Fails the build when a type whose super class is generated at runtime leaks into a class that
 * does not deal with resources.
 *
 * Some framework types extend super classes that no DEX contains: they are built on the device, so
 * that they can inherit from whichever platform classes it actually provides. A type whose super
 * class does not yet exist cannot be resolved, and the runtime records the failure instead of
 * retrying it, so the damage outlives the window that caused it. The fragility is therefore
 * transitive — every class naming such a type acquires it, and is unusable in the same window and
 * unusable afterwards.
 *
 * The reach is not visible in the source. A whole-program optimiser lowers each lambda to a class
 * of its own and afterwards merges classes of the same shape, so a lambda written in a fragile
 * class can come to share its class with lambdas from anywhere in the program; the reference then
 * sits inside a class that arbitrary code instantiates. Keep rules do not help, because they govern
 * the classes one writes, not the ones an optimiser invents.
 *
 * So the invariant is checked where it is actually decided — in the optimised DEX. Every class that
 * mentions a guarded type must be one of the classes that legitimately implements it. Names are
 * recovered through the mapping file, since by this point the interesting classes are called `k`
 * and `n0`. Adding to [resourceOwners] is a deliberate act: it means a class has been given a
 * reference that only resolves once the device has generated the super class.
 */
val guardedTypes =
    listOf("Landroid/content/res/XResources;", "Landroid/content/res/XResources\$XTypedArray;")

/** The classes allowed to name a guarded type, by their original (pre-optimiser) names. */
val resourceOwners =
    listOf(
        "android.content.res.",
        "de.robv.android.xposed.XposedInit",
        "de.robv.android.xposed.callbacks.XC_InitPackageResources",
        "de.robv.android.xposed.callbacks.XC_LayoutInflated",
        "org.matrix.vector.legacy.LegacyDelegateImpl\$ResourceProxy",
    )

/** Every type named anywhere in [cls] — its shape, its members and its instruction stream. */
fun typesNamedBy(cls: ClassDef): Set<String> {
    val named = mutableSetOf<String>()
    cls.superclass?.let(named::add)
    named += cls.interfaces
    cls.fields.forEach { named += it.type }
    cls.methods.forEach { method ->
        named += method.returnType
        method.parameterTypes.forEach { named += it.toString() }
        method.implementation?.instructions?.forEach { instruction ->
            val reference =
                runCatching { (instruction as? ReferenceInstruction)?.reference }.getOrNull()
            when (reference) {
                is TypeReference -> named += reference.type
                is FieldReference -> {
                    named += reference.definingClass
                    named += reference.type
                }
                is MethodReference -> {
                    named += reference.definingClass
                    named += reference.returnType
                    reference.parameterTypes.forEach { named += it.toString() }
                }
                else -> Unit
            }
        }
    }
    return named
}

/** Residual class name -> original, read from the optimiser's mapping file. */
fun originalNames(mapping: File): Map<String, String> =
    if (!mapping.exists()) emptyMap()
    else
        mapping
            .readLines()
            .filter { !it.startsWith(" ") && it.endsWith(":") && it.contains(" -> ") }
            .associate { line ->
                val (original, residual) = line.dropLast(1).split(" -> ", limit = 2)
                residual to original
            }

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val variantCapped = variant.name.replaceFirstChar { it.uppercase() }
        val dexDir =
            layout.buildDirectory.dir(
                "intermediates/dex/${variant.name}/minify${variantCapped}WithR8"
            )
        val mappingFile = layout.buildDirectory.file("outputs/mapping/${variant.name}/mapping.txt")

        val check =
            tasks.register("checkXResourcesIsolation$variantCapped") {
                group = "verification"
                description =
                    "Rejects references to runtime-super-classed types from unrelated classes."
                dependsOn("minify${variantCapped}WithR8")
                inputs.dir(dexDir)
                inputs.file(mappingFile).optional(true)
                outputs.file(
                    layout.buildDirectory.file("reports/xresources-isolation-${variant.name}.txt")
                )

                doLast {
                    val names = originalNames(mappingFile.get().asFile)
                    val offenders = sortedMapOf<String, MutableSet<String>>()
                    var scanned = 0

                    dexDir
                        .get()
                        .asFile
                        .walkTopDown()
                        .filter { it.extension == "dex" }
                        .forEach { dex ->
                            DexFileFactory.loadDexFile(dex, Opcodes.forApi(27)).classes.forEach {
                                cls ->
                                scanned++
                                val named = typesNamedBy(cls)
                                val hits = guardedTypes.filter { it in named && it != cls.type }
                                if (hits.isEmpty()) return@forEach
                                val residual =
                                    cls.type.removePrefix("L").removeSuffix(";").replace('/', '.')
                                val original = names[residual] ?: residual
                                if (resourceOwners.none { original.startsWith(it) }) {
                                    offenders.getOrPut(
                                        if (original == residual) original
                                        else "$original ($residual)"
                                    ) {
                                        mutableSetOf()
                                    } += hits
                                }
                            }
                        }

                    if (offenders.isNotEmpty()) {
                        throw GradleException(
                            buildString {
                                appendLine(
                                    "These classes name a type whose super class is generated at runtime:"
                                )
                                offenders.forEach { (owner, types) ->
                                    appendLine("  $owner -> ${types.joinToString()}")
                                }
                                appendLine()
                                append(
                                    "Such a type cannot be resolved until the device has built its " +
                                        "super class, and a failed resolution is permanent, so every " +
                                        "class holding the reference is unusable for the life of the " +
                                        "process. A lambda is the usual way one arrives here: it " +
                                        "becomes a class, and classes of the same shape are merged. " +
                                        "Either remove the reference, or add the class to " +
                                        "resourceOwners in zygisk/build.gradle.kts if it genuinely " +
                                        "implements resource hooking."
                                )
                            }
                        )
                    }
                    outputs.files.singleFile.apply {
                        parentFile.mkdirs()
                        writeText("scanned $scanned classes, no stray references\n")
                    }
                }
            }

        tasks.named("zip$variantCapped") { dependsOn(check) }
    }
}

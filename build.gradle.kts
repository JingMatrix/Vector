import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.api.AndroidBasePlugin
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    // Declaring the Kotlin plugin here pins the version on the buildscript classpath for
    // every module. AGP 9 otherwise supplies its own, older Kotlin, and a module that
    // asks for a specific version fails with "already on the classpath with an unknown
    // version". The Compose stack in :manager needs the newer compiler.
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.ktfmt)
}

/** A ValueSource that executes 'git rev-list --count' to get the total commit count. */
abstract class GitCommitCountValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("git", "rev-list", "--count", "refs/remotes/origin/master")
            standardOutput = output
            isIgnoreExitValue = true
        }
        // Return the count if successful, otherwise a default of "1".
        return if (result.exitValue == 0 && output.toString().isNotBlank()) {
            output.toString().trim()
        } else {
            "1"
        }
    }
}

/** A ValueSource that executes 'git tag' to get the latest version tag. */
abstract class GitLatestTagValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("git", "tag", "--list", "--sort=-v:refname")
            standardOutput = output
            isIgnoreExitValue = true
        }
        // If successful, parse the first line. Provide a default if no tags are found.
        return if (result.exitValue == 0 && output.toString().isNotBlank()) {
            output.toString().lineSequence().first().removePrefix("v")
        } else {
            "1.0"
        }
    }
}

/**
 * Which build this is, in one string: what commit it came from and where it was built.
 *
 * The version code is the commit count on origin/master, so every branch build carries master's
 * number: a build flashed from a feature branch and one flashed from master both report "v2.0
 * (3052)" and cannot be told apart on the device. That is not hypothetical — it cost a real
 * investigation to establish which of the two was installed.
 *
 * **The commit always comes first, and what follows always says where.** Both readers of this
 * string want the commit and nothing else — the manager compares it against the SHA a release was
 * cut from to answer "am I running this build", and the status page sets it in a type the rest is
 * not given — and for both it is now simply the head of the string. No rule has to work out which
 * part is which, so none can get it wrong when a repository or a machine turns out to have a hyphen
 * in its name.
 *
 * The separator says which kind of "where" follows, because they mean opposite things about whether
 * the commit describes the binary:
 *
 * *On GitHub Actions*, `93d66473-JingMatrix-Vector` — the commit, then the repository the code came
 * from. Forks build the same code from the same commits, so the hash alone identifies a *revision*
 * and not a *build*, and a fork's artifact was indistinguishable from ours.
 *
 * Both halves are the *head* ones, and neither is read from the environment GitHub sets by default,
 * because on a pull request both defaults name something other than the code that was built:
 *
 * `GITHUB_REPOSITORY` is the repository that *ran* the workflow. A pull request from a fork is
 * built here, so it would stamp `JingMatrix/Vector` onto a branch that has never been near it —
 * which is exactly the artifact the name most needs to distinguish.
 *
 * `HEAD` is worse. For a pull request the runner checks out an ephemeral merge of the branch into
 * the base, so `git rev-parse HEAD` names a commit that exists in no repository, cannot be looked
 * up by anyone who reads it off a device, and is gone once the run is. The commit that was pushed
 * is the one that identifies the build.
 *
 * So the workflow passes both in: `VECTOR_BUILD_REPOSITORY` and `VECTOR_BUILD_COMMIT`, each taken
 * from `github.event.pull_request.head.*` when there is a pull request and from the ordinary
 * default when there is not — outside a pull request the two agree anyway. Absent either, this
 * falls back to what the environment says and to `HEAD`, which covers a workflow too old to set
 * them.
 *
 * *Locally*, the bare `93d66473`, or `93d66473+thinkpad` when the tree has uncommitted changes.
 * That build corresponds to no commit at all, so naming the commit alone would name something the
 * binary does not match — and whoever is looking at it is far better served by the machine it was
 * built on than by the word "dirty", since a device that has a hand-built framework on it usually
 * has exactly one candidate author.
 *
 * `+` rather than `-` for that one, in semver's sense of build metadata: after a `-` is a
 * repository that holds this exact commit, and after a `+` is a change that is in no repository at
 * all. That is the one distinction a reader of the stamp cannot afford to lose — a modified build
 * matches no release, and read as a CI build it would claim to be the release it was merely started
 * from. Neither character can occur inside a host name or an `owner/repo`, so the two shapes stay
 * apart.
 *
 * The dirty check is deliberately not applied on CI. The workflow's own "Write key" step appends
 * the signing credentials to the tracked `gradle.properties` before Gradle starts, so every master
 * and tag build reports a modified tree — every published canary has said `-dirty` for that reason
 * and no other, which sent at least one user looking for a fault that was not there (#815).
 *
 * The result goes in module.prop's human-readable version and in BuildConfig.VERSION_HASH, which
 * the manager shows on the status page. BuildConfig.VERSION_NAME is deliberately left clean.
 */
abstract class GitCommitHashValueSource : ValueSource<String, GitCommitHashValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        /**
         * `owner/repo` when GitHub Actions is building this, empty otherwise.
         *
         * Threaded in as parameters rather than read with `System.getenv` inside [obtain], which
         * the configuration cache does not see and therefore does not invalidate on.
         */
        val buildRepository: Property<String>

        /** The full SHA that was pushed, when CI knows one and it is not what is checked out. */
        val buildCommit: Property<String>
    }

    @get:Inject abstract val execOperations: ExecOperations

    /**
     * Runs [command] and returns its trimmed output, or null if it failed or said nothing.
     *
     * `exec` *throws* when the executable cannot be started at all, which `isIgnoreExitValue` does
     * not cover — `hostname` is not on every machine — so the whole call is wrapped rather than
     * just its exit code inspected.
     */
    private fun capture(vararg command: String): String? =
        runCatching {
                val output = ByteArrayOutputStream()
                val result = execOperations.exec {
                    commandLine(command.toList())
                    standardOutput = output
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
                if (result.exitValue != 0) null else output.toString().trim().ifBlank { null }
            }
            .getOrNull()

    /**
     * The machine's name, reduced to what is safe in a version string.
     *
     * A host name can carry anything the owner typed into it, and this value is interpolated into
     * module.prop and a generated Kotlin string literal. Unknown hosts fall back to "local", which
     * still reads correctly: not a CI build, and not a clean tree.
     */
    private fun hostname(): String {
        val raw = capture("hostname") ?: System.getenv("HOSTNAME") ?: return "local"
        // The short name only. A fully qualified host name is mostly domain, and the domain is the
        // part that is identical across every machine that would ever build this.
        val short = raw.substringBefore('.')
        val safe = short.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return safe.ifBlank { "local" }
    }

    override fun obtain(): String {
        // Abbreviated by git rather than by hand, so a CI build and a local build of the same
        // commit
        // read identically however long this repository's abbreviation happens to be.
        val head = capture("git", "rev-parse", "--short", "HEAD") ?: return "unknown"

        val repository = parameters.buildRepository.getOrElse("")
        if (repository.isBlank()) {
            val dirty = capture("git", "status", "--porcelain", "--untracked-files=no") != null
            return if (dirty) "$head+${hostname()}" else head
        }

        // The pushed commit is an ancestor of the merge that was checked out, so it is in the
        // repository and git will abbreviate it. The truncation is only reached if it somehow is
        // not, and a slightly odd length beats reporting the merge commit or nothing at all.
        val pushed = parameters.buildCommit.getOrElse("").takeIf { it.isNotBlank() }
        val short =
            pushed?.let { capture("git", "rev-parse", "--short", it) ?: it.take(head.length) }
                ?: head
        return short + "-" + repository.replace('/', '-')
    }
}

// Plain vals plus an explicit `extra.set`, rather than the `by extra(...)` delegate: Gradle 9.6
// deprecated that syntax and drops it in Gradle 10, and every one of these declarations printed a
// deprecation warning of its own. The subprojects read them out of `rootProject.extra` by name, so
// the string here and the property name have to stay in step by hand now.
//
// This defers the execution of the git commands and allows Gradle to cache the results.
val versionCodeProvider = providers.of(GitCommitCountValueSource::class.java) {}
val versionHashProvider =
    providers.of(GitCommitHashValueSource::class.java) {
        // Set on every GitHub Actions runner and on nothing else, so the presence of either is
        // the test for "this is a CI build". The workflow's own variables win because they name
        // the branch that was pushed; GitHub's defaults name the run that built it.
        parameters.buildRepository.set(
            providers
                .environmentVariable("VECTOR_BUILD_REPOSITORY")
                .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
                .orElse("")
        )
        parameters.buildCommit.set(providers.environmentVariable("VECTOR_BUILD_COMMIT").orElse(""))
    }
val versionNameProvider = providers.of(GitLatestTagValueSource::class.java) {}

val injectedPackageName = "com.android.shell"
val injectedPackageUid = 2000
val defaultManagerPackageName = "org.matrix.vector.manager"

val androidTargetSdkVersion = 37
val androidMinSdkVersion = 27
val androidBuildToolsVersion = "37.0.0"
val androidCompileSdkVersion = 37
val androidCompileNdkVersion = "29.0.14206865"
val androidSourceCompatibility = JavaVersion.VERSION_21
val androidTargetCompatibility = JavaVersion.VERSION_21

extra.set("versionCodeProvider", versionCodeProvider)

extra.set("versionHashProvider", versionHashProvider)

extra.set("versionNameProvider", versionNameProvider)

extra.set("injectedPackageName", injectedPackageName)

extra.set("injectedPackageUid", injectedPackageUid)

extra.set("defaultManagerPackageName", defaultManagerPackageName)

extra.set("androidTargetSdkVersion", androidTargetSdkVersion)

extra.set("androidMinSdkVersion", androidMinSdkVersion)

extra.set("androidBuildToolsVersion", androidBuildToolsVersion)

extra.set("androidCompileSdkVersion", androidCompileSdkVersion)

extra.set("androidCompileNdkVersion", androidCompileNdkVersion)

extra.set("androidSourceCompatibility", androidSourceCompatibility)

extra.set("androidTargetCompatibility", androidTargetCompatibility)

subprojects {
    plugins.withType(AndroidBasePlugin::class.java) {
        extensions.configure(CommonExtension::class.java) {
            compileSdk = androidCompileSdkVersion
            ndkVersion = androidCompileNdkVersion
            buildToolsVersion = androidBuildToolsVersion

            buildFeatures.buildConfig = true
            externalNativeBuild.cmake {
                version = "3.29.8+"
                buildStagingDirectory = layout.buildDirectory.get().asFile
            }

            defaultConfig.apply {
                minSdk = androidMinSdkVersion
                ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) }

                if (this is ApplicationDefaultConfig) {
                    targetSdk = androidTargetSdkVersion

                    versionCode = versionCodeProvider.get().toInt()
                    versionName = versionNameProvider.get()
                }

                val flags =
                    listOf(
                        "-DVERSION_CODE=${versionCodeProvider.get()}",
                        "-DVERSION_NAME='\"${versionNameProvider.get()}\"'",
                        // parallel_hashmap reaches for <emmintrin.h> whenever __SSE2__ is defined,
                        // and that header's static inline intrinsics arrive twice on the x86 ABIs:
                        // dex_builder.ixx and dex_helper.ixx each include phmap in their global
                        // module fragment, so importing dex_builder gives clang two definitions
                        // with the same mangled name. clang 21 (NDK r29) rejects that outright,
                        // where clang 20 merged them. Turning phmap's SSE2 group scan off costs
                        // nothing on arm, which never had it, and is set for every native module
                        // rather than for dex_builder alone because phmap's layout depends on the
                        // flag -- our own hook_bridge.cpp instantiates the same templates, and two
                        // group sizes in one .so would be an ODR violation the linker cannot see.
                        "-DPHMAP_HAVE_SSE2=0",
                        // phmap refuses to configure with SSSE3 but no SSE2, so both go together.
                        "-DPHMAP_HAVE_SSSE3=0",
                    )

                val args =
                    listOf(
                        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                        "-DVECTOR_ROOT=${rootDir.absolutePath}",
                        // Enforce 16 KB page size alignment for Android 15+ compatibility
                        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    )

                externalNativeBuild {
                    cmake {
                        cFlags.addAll(flags)
                        cppFlags.addAll(flags)
                        arguments.addAll(args)
                    }
                }
            }

            buildTypes.getByName("release").apply {
                externalNativeBuild {
                    cmake {
                        arguments.add(
                            "-DDEBUG_SYMBOLS_PATH=${
                                layout.buildDirectory.dir("symbols").get().asFile.absolutePath
                            }"
                        )
                    }
                }
            }

            lint.apply {
                abortOnError = true
                checkReleaseBuilds = false
            }

            compileOptions.apply {
                sourceCompatibility = androidSourceCompatibility
                targetCompatibility = androidTargetCompatibility
            }
        }
    }
    plugins.withType(JavaPlugin::class.java) {
        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }

    // Java that is not ours to modernise, and that javac otherwise comments on twice per build --
    // once per build type, in both the debug and the release halves of `zipAll`.
    //
    // `:external:apache` and `:external:axml` compile vendored upstream sources (commons-lang and
    // ManifestEditor); `:services:*` compile the libxposed submodule and the AIDL stubs AGP
    // generates, neither of which we write. `:legacy` is the de.robv API surface itself: XResources
    // exists precisely to override `getColor`, `getDrawable` and the rest of the deprecated
    // Resources methods, so every one of those overrides is deliberate and none can be dropped
    // while the legacy API is supported.
    //
    // `-nowarn` alone is not enough. The "Note: Some input files use or override a deprecated API"
    // line is a *mandatory* warning summary, emitted precisely because the lint is off, and only
    // `-XDsuppressNotes` silences it.
    val vendoredJava =
        setOf(
            ":external:apache",
            ":external:axml",
            ":legacy",
            ":services:daemon-service",
            ":services:manager-service",
        )
    if (path in vendoredJava) {
        tasks.withType(JavaCompile::class.java).configureEach {
            options.compilerArgs.addAll(listOf("-nowarn", "-XDsuppressNotes"))
        }
    }
}

tasks.register<KtfmtFormatTask>("format") {
    source = project.fileTree(rootDir)
    include(
        "*.gradle.kts",
        "*/build.gradle.kts",
        "hiddenapi/*/build.gradle.kts",
        "services/*-service/build.gradle.kts",
    )
    // The daemon subproject is stuck on ktfmt's default (Meta) style instead of the
    // kotlinLangStyle() applied everywhere else — the wrong style was set for it, but a
    // bulk reformat would wreck git blame across the module, so it is kept as-is. Exclude
    // its build script here so this task's kotlinLangStyle sweep does not fight
    // :daemon:ktfmtFormat, which formats the daemon (scripts included) in Meta style.
    exclude("daemon/**")
    dependsOn(":daemon:ktfmtFormat")
    dependsOn(":manager:ktfmtFormat")
    dependsOn(":xposed:ktfmtFormat")
    dependsOn(":zygisk:ktfmtFormat")
}

ktfmt { kotlinLangStyle() }

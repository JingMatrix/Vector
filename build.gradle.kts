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
 * Which build this is, in one string: where it was built and from what commit.
 *
 * The version code is the commit count on origin/master, so every branch build carries master's
 * number: a build flashed from a feature branch and one flashed from master both report "v2.0
 * (3052)" and cannot be told apart on the device. That is not hypothetical — it cost a real
 * investigation to establish which of the two was installed.
 *
 * Two shapes, because "where did this binary come from" has two different answers:
 *
 * *On GitHub Actions*, `JingMatrix-Vector-93d66473` — the repository the code came from, then the
 * commit. Forks build the same code from the same commits, so the hash alone identifies a
 * *revision* and not a *build*, and a fork's artifact was indistinguishable from ours.
 *
 * The repository is the *head* one, which is why the workflow passes it in rather than this reading
 * `GITHUB_REPOSITORY` directly: on a pull request from a fork the workflow runs in the base
 * repository, so `GITHUB_REPOSITORY` says `JingMatrix/Vector` for a branch that has never been near
 * it — which is exactly the artifact the name most needs to distinguish. `VECTOR_BUILD_REPOSITORY`
 * carries `github.event.pull_request.head.repo.full_name` when there is one and falls back to
 * `GITHUB_REPOSITORY` otherwise; the fallback here covers a workflow too old to set it.
 *
 * *Locally*, the bare `93d66473`, or `93d66473-thinkpad` when the tree has uncommitted changes.
 * That build corresponds to no commit at all, so naming the commit alone would name something the
 * binary does not match — and whoever is looking at it is far better served by the machine it was
 * built on than by the word "dirty", since a device that has a hand-built framework on it usually
 * has exactly one candidate author.
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
         * Threaded in as a parameter rather than read with `System.getenv` inside [obtain], which
         * the configuration cache does not see and therefore does not invalidate on.
         */
        val githubRepository: Property<String>
    }

    @get:Inject abstract val execOperations: ExecOperations

    /**
     * Runs [command] and returns its trimmed output, or null if it failed or said nothing.
     *
     * `exec` *throws* when the executable cannot be started at all, which `isIgnoreExitValue` does
     * not cover — `hostname` is not on every machine — so the whole call is wrapped rather than just
     * its exit code inspected.
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
        val short = capture("git", "rev-parse", "--short", "HEAD") ?: return "unknown"

        val repository = parameters.githubRepository.getOrElse("")
        if (repository.isNotBlank()) return repository.replace('/', '-') + "-" + short

        val dirty = capture("git", "status", "--porcelain", "--untracked-files=no") != null
        return if (dirty) "$short-${hostname()}" else short
    }
}

// This defers the execution of the git commands and allows Gradle to cache the results.
val versionCodeProvider by extra(providers.of(GitCommitCountValueSource::class.java) {})
val versionHashProvider by
    extra(
        providers.of(GitCommitHashValueSource::class.java) {
            // Set on every GitHub Actions runner and on nothing else, so the presence of either is
            // the test for "this is a CI build". The workflow's own variable wins because it names
            // the repository the branch came from; GITHUB_REPOSITORY names the one that ran.
            parameters.githubRepository.set(
                providers
                    .environmentVariable("VECTOR_BUILD_REPOSITORY")
                    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
                    .orElse("")
            )
        }
    )
val versionNameProvider by extra(providers.of(GitLatestTagValueSource::class.java) {})

val injectedPackageName by extra("com.android.shell")
val injectedPackageUid by extra(2000)
val defaultManagerPackageName by extra("org.matrix.vector.manager")

val androidTargetSdkVersion by extra(37)
val androidMinSdkVersion by extra(27)
val androidBuildToolsVersion by extra("37.0.0")
val androidCompileSdkVersion by extra(37)
val androidCompileNdkVersion by extra("29.0.13113456")
val androidSourceCompatibility by extra(JavaVersion.VERSION_21)
val androidTargetCompatibility by extra(JavaVersion.VERSION_21)

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
}

tasks.register<KtfmtFormatTask>("format") {
    source = project.fileTree(rootDir)
    include(
        "*.gradle.kts",
        "*/build.gradle.kts",
        "hiddenapi/*/build.gradle.kts",
        "services/*-service/build.gradle.kts",
        "buildSrc/src/main/kotlin/**/*.kt",
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

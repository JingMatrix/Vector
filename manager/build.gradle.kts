plugins {
    alias(libs.plugins.agp.app)
    // Kotlin itself comes from AGP 9's built-in support — applying
    // org.jetbrains.kotlin.android is an error since AGP 9.0. Its *version* is taken from
    // the Kotlin plugin on the buildscript classpath, which the root build pins to the
    // catalog's version (declared there with `apply false`). That matters here: Coil 3.5
    // ships class metadata an older compiler refuses to read.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.lsplugin.apksign)
}

ktfmt { kotlinLangStyle() }

kotlin {
    compilerOptions {
        // Material 3 Expressive has not landed in a stable material3 release; the
        // expressive surface is gated behind these annotations even in 1.5.0-alpha24.
        // Opting in once here beats sprinkling @OptIn through every screen.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

// The daemon compiles this module's signing certificate into SignInfo.kt and verifies
// the manager.apk it serves against it at runtime, so :manager must be signed with the
// same key as the rest of the module or InstallerVerifier rejects it.
apksign {
    storeFileProperty = "androidStoreFile"
    storePasswordProperty = "androidStorePassword"
    keyAliasProperty = "androidKeyAlias"
    keyPasswordProperty = "androidKeyPassword"
}

val defaultManagerPackageName: String by rootProject.extra
val injectedPackageName: String by rootProject.extra
val versionHashProvider: Provider<String> by rootProject.extra

android {
    namespace = defaultManagerPackageName

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = defaultManagerPackageName
        // Which build this manager is: the repository on CI, the machine when built locally from
        // a modified tree, and the short commit either way. See GitCommitHashValueSource.
        // The version code is `git rev-list --count origin/master`, so a branch build and the
        // official build of the same depth are indistinguishable by number — and the manager and
        // the daemon are flashed separately, so they can be different builds of the same number.
        // This is what tells them apart, and the status page shows both.
        buildConfigField("String", "VERSION_HASH", """"${versionHashProvider.get()}"""")
        buildConfigField("String", "MANAGER_PACKAGE_NAME", "\"$defaultManagerPackageName\"")
        buildConfigField("String", "INJECTED_PACKAGE_NAME", "\"$injectedPackageName\"")

        // The languages this module is actually translated into, listed from the resource folders
        // that carry our own strings.xml. AssetManager.getLocales() cannot answer this: it reports
        // every locale any dependency ships a resource for — AndroidX alone drags in dozens — plus
        // the pseudo-locales, so a picker built from it offers languages the app has never seen.
        //
        // English is added by hand because it is not in a `values-xx` folder to be found: it lives
        // in `values/`, the base the others fall back to. Scanning alone therefore listed every
        // language the app has *except* the one it is written in — and a picker without English is
        // one that someone who switched to Polish to see how it looked cannot use to switch back.
        val translations =
            (listOf("en") +
                    file("src/main/res")
                        .listFiles()
                        .orEmpty()
                        .filter { it.isDirectory && it.name.startsWith("values-") }
                        .filter { File(it, "strings.xml").exists() }
                        .map { it.name.removePrefix("values-").replace("-r", "-") })
                .sorted()
        buildConfigField("String", "TRANSLATIONS", "\"${translations.joinToString(",")}\"")
    }

    // ic_launcher.xml references @drawable/ic_statue_monochrome, which lives in the
    // daemon's resources. Any name collision between the two resource sets becomes a
    // build error, so keep additions on the daemon side namespaced.
    sourceSets { getByName("main") { res.srcDir("../daemon/src/main/res") } }

    packaging {
        resources {
            excludes += "META-INF/**"
            // Java resources only, so it is inert against the Android artifact, which ships its
            // public suffix list under assets/. Pinning the JVM variant would move that list back
            // to okhttp3/internal/publicsuffix/ and this line would then delete it.
            excludes += "okhttp3/**"
            excludes += "kotlin/**"
            excludes += "**.properties"
            excludes += "**.bin"
        }
    }

    dependenciesInfo.includeInApk = false

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(projects.services.managerService)

    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // The Compose BOM aligns every androidx.compose.* artifact; none of them is
    // pinned individually in the version catalog.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Tooling dependencies, debug builds only, for UI previews.
    debugImplementation(libs.androidx.compose.ui.tooling)
}

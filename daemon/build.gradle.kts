import com.android.build.api.dsl.ApplicationExtension
import com.android.ide.common.signing.KeystoreHelper
import java.io.File
import java.io.PrintStream
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

val defaultManagerPackageName = rootProject.extra["defaultManagerPackageName"] as String
val injectedPackageName = rootProject.extra["injectedPackageName"] as String
val injectedPackageUid = rootProject.extra["injectedPackageUid"] as Int
@Suppress("UNCHECKED_CAST")
val versionCodeProvider = rootProject.extra["versionCodeProvider"] as Provider<String>
@Suppress("UNCHECKED_CAST")
val versionNameProvider = rootProject.extra["versionNameProvider"] as Provider<String>
@Suppress("UNCHECKED_CAST")
val versionHashProvider = rootProject.extra["versionHashProvider"] as Provider<String>

plugins {
  alias(libs.plugins.agp.app)
  alias(libs.plugins.ktfmt)
}

android {
  defaultConfig {
    buildConfigField(
        "String",
        "DEFAULT_MANAGER_PACKAGE_NAME",
        """"$defaultManagerPackageName"""",
    )
    buildConfigField("String", "FRAMEWORK_NAME", """"${rootProject.name}"""")
    buildConfigField("String", "MANAGER_INJECTED_PKG_NAME", """"$injectedPackageName"""")
    buildConfigField("int", "MANAGER_INJECTED_UID", """$injectedPackageUid""")
    buildConfigField("String", "VERSION_NAME", """"${versionNameProvider.get()}"""")
    buildConfigField("long", "VERSION_CODE", versionCodeProvider.get())
    // The version code is the commit count on origin/master, so it is identical for a branch
    // build and a master build. The hash is what tells a bug report which one it came from.
    buildConfigField("String", "VERSION_HASH", """"${versionHashProvider.get()}"""")

    val cliToken = UUID.randomUUID()
    // Inject the MSB and LSB as Long constants
    buildConfigField("Long", "CLI_TOKEN_MSB", "${cliToken.mostSignificantBits}L")
    buildConfigField("Long", "CLI_TOKEN_LSB", "${cliToken.leastSignificantBits}L")
  }

  buildTypes {
    all { externalNativeBuild { cmake { arguments += "-DANDROID_ALLOW_UNDEFINED_SYMBOLS=true" } } }
    release {
      isMinifyEnabled = true
      proguardFiles("proguard-rules.pro")
    }
  }

  externalNativeBuild { cmake { path("src/main/jni/CMakeLists.txt") } }

  namespace = "org.matrix.vector.daemon"
}

/**
 * Generates a `SignInfo.kt` embedding the manager APK's signing certificate, used by the daemon to
 * verify the manager's signature at runtime.
 */
abstract class GenerateSignInfoTask : DefaultTask() {
  @get:Input @get:Optional abstract val storeType: Property<String>

  @get:Input @get:Optional abstract val storeFilePath: Property<String>

  @get:Input @get:Optional abstract val storePassword: Property<String>

  @get:Input @get:Optional abstract val keyPassword: Property<String>

  @get:Input @get:Optional abstract val keyAlias: Property<String>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val outSrc = outputDir.get().file("org/matrix/vector/daemon/utils/SignInfo.kt").asFile
    outSrc.parentFile.mkdirs()
    val certificateInfo =
        KeystoreHelper.getCertificateInfo(
            storeType.orNull,
            storeFilePath.orNull?.let { File(it) },
            storePassword.orNull,
            keyPassword.orNull,
            keyAlias.orNull,
        )

    PrintStream(outSrc)
        .print(
            """
            |package org.matrix.vector.daemon.utils
            |
            |object SignInfo {
            |    @JvmField
            |    val CERTIFICATE = byteArrayOf(${
                certificateInfo.certificate.encoded.joinToString(",")
            })
            |}"""
                .trimMargin()
        )
  }
}

androidComponents {
  onVariants { variant ->
    val variantCapped = variant.name.replaceFirstChar { it.uppercase() }
    val variantLowered = variant.name.lowercase()

    val signInfoTask =
        tasks.register<GenerateSignInfoTask>("generate${variantCapped}SignInfo") {
          dependsOn(":manager:validateSigning${variantCapped}")
          val sign =
              rootProject
                  .project(":manager")
                  .extensions
                  .getByType(ApplicationExtension::class.java)
                  .buildTypes
                  .named(variantLowered)
                  .get()
                  .signingConfig
          storeType.set(sign?.storeType)
          storeFilePath.set(sign?.storeFile?.absolutePath)
          storePassword.set(sign?.storePassword)
          keyPassword.set(sign?.keyPassword)
          keyAlias.set(sign?.keyAlias)
        }

    variant.sources.kotlin?.addGeneratedSourceDirectory(
        signInfoTask,
        GenerateSignInfoTask::outputDir,
    )
  }
}

dependencies {
  implementation(libs.agp.apksig)
  implementation(libs.gson)
  implementation(libs.picocli)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(projects.external.apache)
  implementation(projects.hiddenapi.bridge)
  implementation(projects.services.daemonService)
  implementation(projects.services.managerService)
  compileOnly(libs.androidx.annotation)
  compileOnly(projects.hiddenapi.stubs)
  testImplementation("junit:junit:4.13.2")
}

import com.android.build.api.dsl.ApplicationExtension
import com.android.ide.common.signing.KeystoreHelper
import java.io.PrintStream
import java.util.UUID

val defaultManagerPackageName: String by rootProject.extra
val injectedPackageName: String by rootProject.extra
val injectedPackageUid: Int by rootProject.extra
val versionCodeProvider: Provider<String> by rootProject.extra
val versionNameProvider: Provider<String> by rootProject.extra

plugins {
  alias(libs.plugins.agp.app)
  alias(libs.plugins.kotlin)
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

android.applicationVariants.all {
  val variantCapped = name.replaceFirstChar { it.uppercase() }
  val variantLowered = name.lowercase()

  val outSrcDir = layout.buildDirectory.dir("generated/source/signInfo/${variantLowered}").get()
  val signInfoTask =
      tasks.register("generate${variantCapped}SignInfo") {
        dependsOn(":app:validateSigning${variantCapped}")
        val sign =
            rootProject
                .project(":app")
                .extensions
                .getByType(ApplicationExtension::class.java)
                .buildTypes
                .named(variantLowered)
                .get()
                .signingConfig
        val outSrc = file("$outSrcDir/org/matrix/vector/daemon/utils/SignInfo.kt")
        outputs.file(outSrc)
        doLast {
          outSrc.parentFile.mkdirs()
          val certificateInfo =
              KeystoreHelper.getCertificateInfo(
                  sign?.storeType,
                  sign?.storeFile,
                  sign?.storePassword,
                  sign?.keyPassword,
                  sign?.keyAlias,
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
                      .trimMargin())
        }
      }
  // registeoJavaGeneratingTask(signInfoTask, outSrcDir.asFile)

  kotlin.sourceSets.getByName(variantLowered) { kotlin.srcDir(signInfoTask.map { outSrcDir }) }
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
}

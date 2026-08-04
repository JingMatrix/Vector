package org.matrix.vector.daemon.utils

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import org.matrix.vector.ipc.IFrameworkInstallReceiver
import org.matrix.vector.ipc.IManagerService

private const val TAG = "VectorRootInstaller"

/**
 * Which root implementation is managing this device, and how to flash through it.
 *
 * The detection mirrors NeoZygisk's `root_impl` module, deliberately: that is the code deciding
 * whether Vector loads at all on this device, and a manager that disagreed with it about which root
 * is in charge would be reporting on a different device than the one it is running on. Where a
 * version floor can be read at all it is NeoZygisk's own, so "too old to flash through" means the
 * same thing in both places.
 *
 * Detection is by binary rather than by NeoZygisk's ioctl/prctl route, which needs a JNI hop for a
 * question asked once — and the binary has to exist anyway to do the flashing. The cost of that
 * choice is visible in [detectKernelSu], which cannot read a version at all.
 *
 * A binary that exists but *fails* is not an implementation: this device carries a leftover
 * `/data/adb/magisk/magisk` from a previous root manager, and it exits 1 with "Cannot connect to
 * daemon". Requiring a clean exit is what stops that from being reported as a second root
 * implementation and turning a working KernelSU device into ROOT_MULTIPLE.
 */
object RootImplementation {

  /**
   * NeoZygisk's floors, from its root build.gradle.kts. Below these it will not load.
   *
   * There is deliberately no KernelSU floor here: its version code is not reachable from a shell,
   * and [detectKernelSu] explains why not checking it is correct rather than merely convenient.
   */
  private const val MIN_MAGISK = 26402
  private const val MIN_APATCH = 10762

  /**
   * Where each implementation keeps its binary.
   *
   * Tried by absolute path as well as by name because the daemon does not inherit a login shell's
   * PATH: it is started by the root implementation's own init stage, and on some of them PATH holds
   * nothing but /system/bin. Falling back to the well-known locations turns "we could not detect
   * root" into "there really is no root" for the cases that matter.
   */
  private val MAGISK_PATHS = listOf("magisk", "/data/adb/magisk/magisk")
  private val KSUD_PATHS = listOf("ksud", "/data/adb/ksud")
  private val APD_PATHS = listOf("apd", "/data/adb/apd")

  /** Detected once: three process spawns is not something to repeat on every screen open. */
  private val detected: Detection by lazy { detect() }

  /**
   * [binary] is the path detection actually got an answer from, and the one the flash then uses.
   *
   * Not re-derived at install time: `ksud` may be on the daemon's PATH or only at /data/adb/ksud,
   * and the version probe already established which. Guessing again invites the flash to fail on a
   * device where detection succeeded.
   */
  data class Detection(val implementation: Int, val version: String?, val binary: String? = null)

  val implementation: Int
    get() = detected.implementation

  private fun detect(): Detection {
    val magisk = detectMagisk()
    val ksu = detectKernelSu()
    val apatch = detectApatch()

    val found = listOfNotNull(magisk, ksu, apatch)
    if (found.size > 1) {
      // Not a failure to detect — a device with two root implementations installed, where
      // flashing through either is a coin toss about which one owns the module tree.
      Log.w(TAG, "Multiple root implementations: ${found.joinToString { it.version ?: "?" }}")
      return Detection(IManagerService.ROOT_MULTIPLE, found.joinToString { it.version ?: "?" })
    }

    val only = found.firstOrNull() ?: return Detection(IManagerService.ROOT_NONE, null)
    Log.i(TAG, "Root implementation: ${only.version} via ${only.binary}")
    return only
  }

  /** Null when this implementation is not present; otherwise which it is and where it lives. */
  private fun detectMagisk(): Detection? {
    val (binary, raw) = run(MAGISK_PATHS, "-V") ?: return null
    val code = raw.trim().toIntOrNull() ?: return null
    val name = run(MAGISK_PATHS, "-v")?.second?.trim()?.lineSequence()?.firstOrNull()
    val supported = code >= MIN_MAGISK
    return Detection(
        if (supported) IManagerService.ROOT_MAGISK else IManagerService.ROOT_TOO_OLD,
        "Magisk ${name ?: code}",
        binary,
    )
  }

  /**
   * KernelSU, which cannot be version-checked from a shell.
   *
   * `ksud -V` prints a *build hash*, not a version code — measured on a KernelSU device it answers
   * `ksud 64e3761d`. An earlier version of this took the first run of digits out of that, read
   * `64`, compared it against the 10940 floor and declared the device too old to flash on — which
   * would have disabled the entire feature on exactly the devices it works on. The version code
   * lives behind KernelSU's prctl/ioctl interface, which is why NeoZygisk reaches for it and why
   * this cannot.
   *
   * So presence is the whole test, and that is sound rather than a shrug: NeoZygisk refuses to load
   * on a KernelSU older than its floor, so a daemon that is running at all is running under one new
   * enough. The check this cannot perform has already been performed, one layer down.
   */
  private fun detectKernelSu(): Detection? {
    val (binary, raw) = run(KSUD_PATHS, "-V") ?: return null
    val build = raw.trim().substringAfter("ksud ").trim()
    return Detection(IManagerService.ROOT_KERNELSU, "KernelSU ($build)", binary)
  }

  /**
   * APatch. `apd -V` prints "apd <code>", so the second field is the version — NeoZygisk's parse.
   *
   * When that field is not a number, this reports the implementation as present and usable rather
   * than absent. Refusing to flash because *our parser* did not recognise a version string would be
   * refusing on the evidence of our own code rather than on the state of the device — which is the
   * mistake the KernelSU branch above was making.
   */
  private fun detectApatch(): Detection? {
    val (binary, raw) = run(APD_PATHS, "-V") ?: return null
    val output = raw.trim()
    val code = output.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
    return when {
      code == null -> Detection(IManagerService.ROOT_APATCH, "APatch ($output)", binary)
      code >= MIN_APATCH -> Detection(IManagerService.ROOT_APATCH, "APatch $code", binary)
      else -> Detection(IManagerService.ROOT_TOO_OLD, "APatch $code", binary)
    }
  }

  /**
   * First candidate that starts and exits cleanly wins, returned with the path that worked.
   *
   * A non-zero exit reads as absent, which is what keeps a stale Magisk binary from a previous root
   * manager out of the results.
   */
  private fun run(candidates: List<String>, vararg args: String): Pair<String, String>? {
    for (path in candidates) {
      val result =
          runCatching {
                val process = ProcessBuilder(listOf(path) + args).redirectErrorStream(false).start()
                val output =
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                if (process.waitFor() == 0 && output.isNotBlank()) path to output else null
              }
              .getOrNull()
      if (result != null) return result
    }
    return null
  }

  /**
   * The command that installs a module zip, for the implementation in charge.
   *
   * These are the same three the project's gradle install tasks use, so a zip that flashes from a
   * developer's machine flashes the same way from the device.
   */
  private fun installCommand(zipPath: String): List<String>? {
    val binary = detected.binary ?: return null
    return when (implementation) {
      IManagerService.ROOT_MAGISK -> listOf(binary, "--install-module", zipPath)
      IManagerService.ROOT_KERNELSU -> listOf(binary, "module", "install", zipPath)
      IManagerService.ROOT_APATCH -> listOf(binary, "module", "install", zipPath)
      else -> null
    }
  }

  /**
   * Runs the installer, handing every line to [onLine] and to the daemon's log.
   *
   * Both, not either: the screen is where a user reads a failure, and the log is where a
   * maintainer reads it afterwards from a bug report — including the case where the flash left the
   * device unable to boot the manager at all.
   *
   * Blocks until the installer exits. The caller runs it off the binder thread.
   */
  fun install(zipPath: String, onLine: (String) -> Unit): Int {
    val zip = File(zipPath)
    if (!zip.isFile || !zip.canRead()) {
      val message = "Refusing to flash $zipPath: not a readable file"
      Log.e(TAG, message)
      onLine(message)
      return IFrameworkInstallReceiver.INSTALL_NO_SUCH_FILE
    }

    val command =
        installCommand(zipPath)
            ?: run {
              val message = "No usable root implementation to flash through (code $implementation)"
              Log.e(TAG, message)
              onLine(message)
              return IFrameworkInstallReceiver.INSTALL_NO_ROOT
            }

    Log.i(TAG, "Flashing ${zip.name} with: ${command.joinToString(" ")}")
    onLine("$ ${command.joinToString(" ")}")

    return runCatching {
          // Merged, because an installer's diagnostics go to stderr and its progress to stdout,
          // and reading them on two threads would interleave them in an order that is not the
          // order they happened in.
          val process = ProcessBuilder(command).redirectErrorStream(true).start()
          BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { line ->
              Log.i(TAG, line)
              onLine(line)
            }
          }
          val exit = process.waitFor()
          Log.i(TAG, "Installer exited with $exit")
          exit
        }
        .getOrElse {
          Log.e(TAG, "Installer could not be started", it)
          onLine("Could not start the installer: ${it.message}")
          IFrameworkInstallReceiver.INSTALL_NOT_EXECUTED
        }
  }
}

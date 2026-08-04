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
 * Detection is by binary: the binary has to exist to do the flashing anyway, and asking it is one
 * process spawn for a question asked once. The version it reports is quoted back to the user and
 * nothing more — whether the zygisk loader will run on this device is the loader's own decision,
 * taken before the daemon exists, so a daemon that is running has already passed it.
 *
 * A binary that exists but *fails* is not an implementation: a device can carry a leftover
 * `/data/adb/magisk/magisk` from a previous root manager, and it exits 1 with "Cannot connect to
 * daemon". Requiring a clean exit is what stops that from being reported as a second root
 * implementation and turning a working KernelSU device into ROOT_MULTIPLE.
 */
object RootImplementation {

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
    return Detection(IManagerService.ROOT_MAGISK, "Magisk ${name ?: code}", binary)
  }

  /**
   * KernelSU. `ksud -V` prints a *build hash* rather than a version code — on a real device it
   * answers `ksud 64e3761d` — so what is quoted back to the user is that hash.
   */
  private fun detectKernelSu(): Detection? {
    val (binary, raw) = run(KSUD_PATHS, "-V") ?: return null
    val build = raw.trim().substringAfter("ksud ").trim()
    return Detection(IManagerService.ROOT_KERNELSU, "KernelSU ($build)", binary)
  }

  /**
   * APatch. `apd -V` prints "apd <code>", so the second field is the version; when it is not a
   * number the whole line is quoted instead, because a parser that did not recognise a version
   * string says nothing about the device.
   */
  private fun detectApatch(): Detection? {
    val (binary, raw) = run(APD_PATHS, "-V") ?: return null
    val output = raw.trim()
    val code = output.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
    return Detection(IManagerService.ROOT_APATCH, "APatch ${code ?: "($output)"}", binary)
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

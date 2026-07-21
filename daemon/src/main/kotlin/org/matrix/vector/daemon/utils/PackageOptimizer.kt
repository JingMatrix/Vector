package org.matrix.vector.daemon.utils

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import org.matrix.vector.daemon.system.packageManager

/**
 * Backs the manager's "Re-optimize" action: drops an app's stale ART profiles and rebuilds its
 * `speed-profile` dexopt artifacts.
 *
 * Both steps were historically hidden `IPackageManager` binder calls (`clearApplicationProfileData`
 * / `performDexOptMode`). They are present through Android 16 (the newest AOSP source available) but
 * dropped from `framework.jar` on Android 17, where the binder calls throw `NoSuchMethodError` (see
 * #781). Since Android 14, dexopt and profiles are handled by the ART Service, which exposes the
 * same operations through its shell (`cmd package art clear-app-profiles` / `cmd package compile`)
 * independently of those binder methods. We therefore use the shell on Android 14+, falling back to
 * the binder call, and use the binder path directly on older releases.
 */
object PackageOptimizer {

  private const val TAG = "VectorOptimizer"

  private val backend: Backend =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
          FallbackBackend(primary = ShellBackend, fallback = BinderBackend)
      else BinderBackend

  /**
   * Re-optimizes [packageName]: clears its profiles, then forces a profile-guided recompile.
   *
   * `speed-profile` only AOT-compiles methods recorded in the app's reference profile, so clearing
   * first prevents the recompile from re-baking a profile captured before the module set changed.
   */
  fun optimize(packageName: String): Boolean {
    // Best-effort clear: on failure the recompile merely reuses an older profile, which still beats
    // aborting the whole action.
    runCatching { backend.clearProfiles(packageName) }
        .onFailure { Log.e(TAG, "Failed to clear profiles for $packageName", it) }
    return runCatching { backend.compile(packageName) }
        .onFailure { Log.e(TAG, "Failed to optimize $packageName", it) }
        .getOrDefault(false)
  }

  /** Each operation returns whether it succeeded. */
  private interface Backend {
    fun clearProfiles(packageName: String): Boolean

    fun compile(packageName: String): Boolean
  }

  /**
   * Runs [primary], falling back to [fallback] whenever an operation returns false or throws.
   *
   * On Android 14+ the primary is the ART Service shell and the fallback is the binder call. The
   * binder methods are still present on Android 14/15/16, so the fallback is valid there; on 17 they
   * are gone, but the shell succeeds so the fallback is never reached.
   */
  private class FallbackBackend(private val primary: Backend, private val fallback: Backend) :
      Backend {
    override fun clearProfiles(packageName: String): Boolean =
        firstSuccess({ primary.clearProfiles(packageName) }, { fallback.clearProfiles(packageName) })

    override fun compile(packageName: String): Boolean =
        firstSuccess({ primary.compile(packageName) }, { fallback.compile(packageName) })

    private fun firstSuccess(vararg attempts: () -> Boolean): Boolean =
        attempts.any { runCatching(it).getOrDefault(false) }
  }

  /** Android 14+: dexopt and profiles are owned by the ART Service, reachable through its shell. */
  private object ShellBackend : Backend {
    override fun clearProfiles(packageName: String): Boolean =
        exec("cmd package art clear-app-profiles $packageName").first == 0

    override fun compile(packageName: String): Boolean {
      val (exitCode, output) = exec("cmd package compile -m speed-profile -f $packageName")
      return exitCode == 0 && output.contains("Success")
    }

    private fun exec(command: String): Pair<Int, String> {
      val process = Runtime.getRuntime().exec(command)
      val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
      return process.waitFor() to output
    }
  }

  /** Pre-14, and the Android 14+ fallback: the hidden `IPackageManager` binder calls. */
  private object BinderBackend : Backend {
    override fun clearProfiles(packageName: String): Boolean {
      val pm = packageManager ?: return false
      pm.clearApplicationProfileData(packageName)
      return true
    }

    override fun compile(packageName: String): Boolean =
        packageManager?.performDexOptMode(
            packageName,
            false, // useJitProfiles
            "speed-profile",
            true,
            true,
            null) == true
  }
}

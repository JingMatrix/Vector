package org.matrix.vector.manager
import android.util.Log

import android.os.IBinder
import kotlin.system.exitProcess
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.di.ServiceLocator

/**
 * The one entry point the framework reaches by reflection.
 *
 * `ParasiticManagerHooker.sendBinderToManager` loads `<managerPackage>.Constants` out of the
 * injected dex and invokes the static `setBinder(IBinder)` below. Nothing inside this APK calls it,
 * so R8 must be told to keep both — see `proguard-rules.pro`. Renaming this class or the method
 * breaks the handshake silently, at runtime, with no compile error anywhere.
 */
object Constants {
    /**
     * The only tag the manager logs under, and it is not arbitrary.
     *
     * `logcat.cpp` routes any tag beginning `Vector` into the daemon's **verbose** stream, so with
     * verbose logging on everything logged here appears in the Verbose tab beside the daemon's own
     * lines and travels in the zip export — the place a reader already looks. A file-local tag
     * would be ordinary Android practice and would land nowhere; there are none in this app.
     *
     * A message is `area: lowercase phrase naming the operation and its subject`, where the area
     * is one of ipc, dns, apps, modules, backup, restore, scope, store, update, feed, auth,
     * status, framework, logs, actions, report, splash. The subject matters: "modules: enable of
     * $packageName failed" can be acted on, "failed to enable module" cannot.
     *
     * The `Throwable` is always the last argument — never `e.message`, which discards the stack,
     * and never `Log.getStackTraceString`, which discards the level. Nothing secret is ever
     * interpolated: no OAuth token, no SAF `Uri` beyond its authority, no third-party query
     * string.
     *
     * Levels are `e` when something the user asked for did not happen and nothing else will
     * explain it, `w` for a degraded path recovered from, and `i` for a one-off milestone worth
     * having in a bug report — counts, versions, the endpoint chosen. `d` and `v` are not added,
     * because release builds ship them.
     *
     * A `CancellationException` is never logged. Navigating away from a screen cancels its scope,
     * and a log that fires every time someone presses back is a log nobody reads; any
     * `runCatching` or broad `catch` that can see one rethrows or skips it first.
     */
    const val TAG = "VectorManager"

    @JvmStatic
    fun setBinder(binder: IBinder): Boolean {
        ServiceLocator.bind(ILSPManagerService.Stub.asInterface(binder))

        try {
            // If the daemon dies the manager is holding a dead binder and every screen would
            // silently show empty state, which reads as "you have no modules" rather than "the
            // framework is gone". Exiting is blunt but honest.
            binder.linkToDeath(
                {
                    Log.w(TAG, "ipc: daemon binder died, manager exiting")
                    exitProcess(0)
                },
                0,
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "ipc: linkToDeath on the daemon binder failed, exiting the manager process",
                e,
            )
            exitProcess(0)
        }

        return binder.isBinderAlive
    }
}

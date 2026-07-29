package org.matrix.vector.manager

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
     * Nothing logs with it directly. [logE], [logW] and [logI] hold it, and the conventions for
     * what a message says and which level it says it at are documented on them.
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
                    logW("ipc: daemon binder died, manager exiting")
                    exitProcess(0)
                },
                0,
            )
        } catch (e: Exception) {
            logE("ipc: linkToDeath on the daemon binder failed, exiting the manager process", e)
            exitProcess(0)
        }

        return binder.isBinderAlive
    }
}

package org.matrix.vector.impl.core

import android.os.Binder
import android.os.Bundle
import android.os.Process
import java.util.concurrent.Executors
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.IHotReloadOutcomeReceiver
import org.matrix.vector.ipc.IProcessChannel
import org.matrix.vector.util.Log

private const val TAG = "VectorProcessChannel"

/**
 * This process's end of the only channel the daemon has for calling in.
 *
 * Handed over once while the framework bootstraps, before any module is loaded and carrying no
 * module identity - which is what lets system_server have one too, since its modules load before
 * the daemon's module cache exists.
 */
object VectorProcessChannel : IProcessChannel.Stub() {

    /**
     * One thread, so reloads in this process are serialised even across modules, and so the
     * incoming oneway transaction returns at once. Running the cycle on the binder thread that
     * delivered it would hold one of this app's binder threads for as long as the module's
     * onHotReloading cares to take.
     */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vector-hot-reload") }

    override fun hotReload(
        modulePackageName: String?,
        extras: Bundle?,
        module: LoadedModule?,
        receiver: IHotReloadOutcomeReceiver?,
    ) {
        // The daemon is the only caller this binder was ever handed to, but it runs as the system
        // uid rather than as root, and this object lives in an app process - so the check is worth
        // stating rather than assuming. Nothing else may drive a module's lifecycle.
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID && caller != 0) {
            Log.w(TAG, "Refusing a hot reload request from uid $caller")
            return
        }

        worker.execute {
            val outcome = VectorModuleManager.hotReload(modulePackageName, extras, module)
            runCatching { receiver?.onOutcome(outcome) }
                .onFailure { Log.w(TAG, "Cannot report the hot reload outcome", it) }
        }
    }
}

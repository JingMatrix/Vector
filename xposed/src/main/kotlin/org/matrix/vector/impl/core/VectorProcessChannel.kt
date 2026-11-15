package org.matrix.vector.impl.core

import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.IHotReloadOutcomeReceiver
import org.matrix.vector.ipc.IProcessChannel
import org.matrix.vector.ipc.HotReloadOutcome
import org.matrix.vector.util.Log
import io.github.libxposed.service.IXposedService
import java.util.concurrent.ThreadFactory
import org.matrix.vector.impl.utils.closeDexHandles

private const val TAG = "VectorProcessChannel"

/** The daemon's process channel. Its worker exists only while there are reloads to perform. */
object VectorProcessChannel : IProcessChannel.Stub() {
    private val worker =
        OnDemandSerialExecutor(
            ThreadFactory { Thread(it, "vector-hot-reload") },
            { Log.e(TAG, "Unexpected hot reload dispatch failure", it) },
        )

    override fun hotReload(
        modulePackageName: String?,
        extras: Bundle?,
        module: LoadedModule?,
        receiver: IHotReloadOutcomeReceiver?,
    ) {
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID && caller != 0) {
            closeDexHandles(module?.code?.preLoadedDexes)?.let {
                Log.w(TAG, "Cannot close rejected DEX handles", it)
            }
            Log.w(TAG, "Refusing a hot reload request from uid $caller")
            return
        }

        try {
            worker.execute { performReload(modulePackageName, extras, module, receiver) }
        } catch (failure: Throwable) {
            // execute throws only before accepting the task, so no generation changed here.
            val outcome = failed(failure, generationChanged = false)
            finish(module, receiver, outcome)
        }
    }

    private fun performReload(
        packageName: String?,
        extras: Bundle?,
        module: LoadedModule?,
        receiver: IHotReloadOutcomeReceiver?,
    ) {
        var before: ClassLoader? = null
        var entered = false
        val outcome =
            try {
                before = packageName?.let { VectorModuleManager.getModuleClassLoader(it) }
                entered = true
                VectorModuleManager.hotReload(packageName, extras, module)
            } catch (failure: Throwable) {
                // VectorModuleManager normally returns its own outcome. Keep the outer dispatch
                // failure honest too, including an exception after a generation was committed.
                val after = packageName?.let { VectorModuleManager.getModuleClassLoader(it) }
                failed(failure, generationChanged = entered && before !== after)
            }
        finish(module, receiver, outcome)
    }

    private fun finish(
        module: LoadedModule?,
        receiver: IHotReloadOutcomeReceiver?,
        outcome: HotReloadOutcome,
    ) {
        // The loader already closes consumed SharedMemory objects. close() is idempotent on
        // those objects; this also covers unsupported, refused and never-started requests.
        // These are receiver-owned Binder copies, never the daemon's cached originals.
        closeDexHandles(module?.code?.preLoadedDexes)?.let { failure ->
            outcome.status = IXposedService.HOT_RELOAD_FAILED
            outcome.refused = false
            outcome.message = "DEX handle cleanup failed: ${describe(failure)}"
            // Preserve generationChanged: a cleanup failure does not undo installed hooks.
            Log.w(TAG, "Cannot close hot reload DEX handles", failure)
        }
        try {
            receiver?.onOutcome(outcome)
        } catch (failure: Throwable) {
            Log.w(TAG, "Cannot report the hot reload outcome", failure)
        }
        // No task/receiver/module is retained in a completed queue slot. Once the batch ends,
        // the thread returns normally; do not interrupt callbacks or shut down shared workers.
    }

    private fun failed(failure: Throwable, generationChanged: Boolean) =
        HotReloadOutcome().apply {
            status = IXposedService.HOT_RELOAD_FAILED
            message = describe(failure)
            refused = false
            this.generationChanged = generationChanged
        }

    private fun describe(failure: Throwable) =
        "${failure.javaClass.name}: ${failure.message ?: "no message"}"
}

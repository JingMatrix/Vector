package org.matrix.vector.impl.core

import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.IProcessChannel
import org.matrix.vector.ipc.IFrameworkService
import org.matrix.vector.ipc.IVectorDaemon
import org.matrix.vector.util.Log

/**
 * Singleton client for managing IPC communication with the injected manager service. Handles Binder
 * death gracefully and ensures safe remote execution.
 */
object VectorServiceClient : IFrameworkService {

    private const val TAG = "VectorServiceClient"

    private class Connection(val service: IFrameworkService) : IBinder.DeathRecipient {
        val binder: IBinder = service.asBinder()
        var restored = false

        override fun binderDied() {
            synchronized(VectorServiceClient) {
                // A queued death notification from the old daemon must not clear its replacement.
                if (connection === this) connection = null
            }
            unlink()
        }

        fun unlink() {
            runCatching { binder.unlinkToDeath(this, 0) }
        }
    }

    @Volatile private var connection: Connection? = null
    private val service: IFrameworkService?
        get() = connection?.service

    var processName: String = ""
        private set

    @Synchronized
    fun init(appService: IFrameworkService?, niceName: String) {
        if (connection != null || appService == null) return
        if (connect(appService, niceName)) {
            connection?.let { it.restored = attachChannel(it.service, VectorProcessChannel) }
        }
    }

    @Synchronized
    private fun connect(appService: IFrameworkService, niceName: String): Boolean {
        val next = Connection(appService)
        return runCatching {
            next.binder.linkToDeath(next, 0)
            check(next.binder.isBinderAlive) { "Framework service already died" }
            val previous = connection
            connection = next
            processName = niceName
            previous?.unlink()
            true
        }
            .getOrElse {
                next.unlink()
                Log.e(TAG, "Cannot connect framework service in process: $niceName", it)
                false
            }
    }

    /** Rebind existing objects; never run module entry points or install hooks a second time. */
    @Synchronized
    fun reconnectSystemServer(appService: IFrameworkService, daemon: IVectorDaemon): Boolean {
        if (service?.asBinder() != appService.asBinder() && !connect(appService, "system"))
            return false
        val current = connection ?: return false
        if (!current.binder.isBinderAlive) return false
        if (current.restored) return true
        // An interrupted restore is retried by the existing bridge send loop. Successful module
        // subscriptions are idempotent; no module entry points or hook installation are replayed.
        if (!VectorModuleManager.reconnectSystemServerServices(daemon)) return false
        current.restored = attachChannel(current.service, VectorProcessChannel)
        return current.restored
    }

    override fun attachProcessChannel(channel: IProcessChannel?) {
        service?.let { attachChannel(it, channel) }
    }

    private fun attachChannel(service: IFrameworkService, channel: IProcessChannel?): Boolean {
        try {
            service.attachProcessChannel(channel)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach the process channel", t)
            return false
        }
    }

    override fun isLogMuted(): Boolean {
        return runCatching { service?.isLogMuted == true }.getOrDefault(false)
    }

    override fun getLegacyModules(): List<LoadedModule> {
        return runCatching { service?.legacyModules }.getOrNull() ?: emptyList()
    }

    override fun getModules(): List<LoadedModule> {
        return runCatching { service?.modules }.getOrNull() ?: emptyList()
    }

    override fun getPrefsPath(packageName: String): String? {
        return runCatching { service?.getPrefsPath(packageName) }.getOrNull()
    }

    override fun openManagerApk(): ParcelFileDescriptor? {
        return runCatching { service?.openManagerApk() }.getOrNull()
    }

    override fun requestManagerService(): IBinder? {
        return runCatching { service?.requestManagerService() }.getOrNull()
    }

    override fun asBinder(): IBinder? {
        return service?.asBinder()
    }
}

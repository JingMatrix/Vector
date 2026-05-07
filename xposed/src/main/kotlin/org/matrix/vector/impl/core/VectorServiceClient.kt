package org.matrix.vector.impl.core

import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.util.Utils.Log

/**
 * Singleton client for managing IPC communication with the injected manager service. Handles Binder
 * death gracefully and ensures safe remote execution.
 */
object VectorServiceClient : ILSPApplicationService, IBinder.DeathRecipient {

    private const val TAG = "VectorServiceClient"

    private var service: ILSPApplicationService? = null
    var processName: String = ""
        private set

    @Synchronized
    fun init(appService: ILSPApplicationService?, niceName: String) {
        val binder = appService?.asBinder()
        if (service == null && binder != null) {
            runCatching {
                    service = appService
                    processName = niceName
                    binder.linkToDeath(this, 0)
                }
                .onFailure {
                    Log.e(TAG, "Failed to link to death for service in process: $niceName", it)
                    service = null
                }
        }
    }

    override fun isLogMuted(): Boolean {
        return runCatching { service?.isLogMuted == true }.getOrDefault(false)
    }

    override fun getLegacyModulesList(): List<Module> {
        return runCatching { service?.legacyModulesList }.getOrNull() ?: emptyList()
    }

    override fun getModulesList(): List<Module> {
        return runCatching { service?.modulesList }.getOrNull() ?: emptyList()
    }

    override fun getPrefsPath(packageName: String): String? {
        return runCatching { service?.getPrefsPath(packageName) }.getOrNull()
    }

    override fun requestInjectedManagerBinder(binder: List<IBinder>): ParcelFileDescriptor? {
        return runCatching { service?.requestInjectedManagerBinder(binder) }.getOrNull()
    }

    override fun asBinder(): IBinder? {
        return service?.asBinder()
    }

    override fun binderDied() {
        service?.asBinder()?.unlinkToDeath(this, 0)
        service = null
    }
}

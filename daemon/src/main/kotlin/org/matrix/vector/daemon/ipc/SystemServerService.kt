package org.matrix.vector.daemon.ipc

import android.os.Build
import android.os.IBinder
import android.os.IServiceCallback
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.service.ILSPSystemServerService
import org.matrix.vector.daemon.*
import org.matrix.vector.daemon.system.getSystemServiceManager

private const val TAG = "VectorSystemServer"

object SystemServerService : ILSPSystemServerService.Stub(), IBinder.DeathRecipient {

  private var proxyServiceName: String? = null
  private var originService: IBinder? = null

  var systemServerRequested = false

  fun registerProxyService(serviceName: String) {
    // Register as the service name early to setup an IPC for `system_server`.
    Log.d(TAG, "Registering bridge service for `system_server` with name `$serviceName`.")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val callback =
          object : IServiceCallback.Stub() {
            // The IServiceCallback will tell us when the real Android service is ready,
            // allowing us to capture it and then naturally stop intercepting traffic.
            override fun onRegistration(name: String, binder: IBinder?) {
              if (name == serviceName && binder != null && binder !== this@SystemServerService) {
                Log.d(TAG, "Intercepted system service registration with name `$name`")
                originService = binder
                runCatching { binder.linkToDeath(this@SystemServerService, 0) }
              }
            }

            override fun asBinder(): IBinder = this
          }
      runCatching {
            getSystemServiceManager().registerForNotifications(serviceName, callback)
            ServiceManager.addService(serviceName, this)
            proxyServiceName = serviceName
          }
          .onFailure { Log.e(TAG, "Failed to register IServiceCallback", it) }
    }
  }

  override fun requestApplicationService(
      uid: Int,
      pid: Int,
      processName: String,
      heartBeat: IBinder?
  ): ILSPApplicationService? {
    if (uid != 1000 || heartBeat == null || processName != "system") return null
    systemServerRequested = true

    // Return the ApplicationService singleton if successfully registered
    return if (ApplicationService.registerHeartBeat(uid, pid, processName, heartBeat)) {
      ApplicationService
    } else null
  }

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    originService?.let {
      // This is unlikely to happen unless system_server restarts / crashes, since we intentionally
      // discard our proxy upon later replacements in registerProxyService.
      Log.d(TAG, "Forwarding request to real `$proxyServiceName` service.")
      return it.transact(code, data, reply, flags)
    }

    when (code) {
      BRIDGE_TRANSACTION_CODE -> {
        val uid = data.readInt()
        val pid = data.readInt()
        val processName = data.readString() ?: ""
        val heartBeat = data.readStrongBinder()

        val service = requestApplicationService(uid, pid, processName, heartBeat)
        if (service != null) {
          reply?.writeNoException()
          reply?.writeStrongBinder(service.asBinder())
          return true
        }
        return false
      }
      DEX_TRANSACTION_CODE,
      OBFUSCATION_MAP_TRANSACTION_CODE -> {
        return ApplicationService.onTransact(code, data, reply, flags)
      }
      else -> {
        return super.onTransact(code, data, reply, flags)
      }
    }
  }

  override fun binderDied() {
    originService?.unlinkToDeath(this, 0)
    originService = null
  }
}

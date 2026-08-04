package org.matrix.vector.daemon.ipc

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IServiceCallback
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log
import org.matrix.vector.ipc.IFrameworkService
import org.matrix.vector.daemon.*
import org.matrix.vector.daemon.system.getSystemServiceManager

private const val TAG = "VectorSystemServer"

/**
 * The daemon's end of the one handshake system_server gets.
 *
 * A plain [Binder] rather than an AIDL stub on purpose. system_server never holds an interface for
 * this - it reaches the daemon by transacting [BRIDGE_TRANSACTION_CODE] on whatever binder the
 * hijacked service name resolves to, which [onTransact] answers directly. An AIDL interface here
 * would generate a dispatch table nothing ever entered, and would have to state a descriptor that
 * nothing ever checks.
 */
object SystemServerService : Binder(), IBinder.DeathRecipient {

  private var proxyServiceName: String? = null
  private var originService: IBinder? = null

  var systemServerRequested = false

  fun registerProxyService(serviceName: String) {
    // Register as the service name early to setup an IPC for `system_server`.
    Log.d(TAG, "Registering bridge service for `system_server` with name `$serviceName`.")

    // `IServiceManager.registerForNotifications` is only available since Android R.
    // On older platforms we simply let the real service replace our proxy in servicemanager.
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
      runCatching { getSystemServiceManager().registerForNotifications(serviceName, callback) }
          .onFailure { Log.e(TAG, "Failed to register IServiceCallback", it) }
    }

    // The Zygisk module polls this name during `system_server` specialization,
    // so it must be claimed on every supported platform.
    runCatching {
          ServiceManager.addService(serviceName, this)
          proxyServiceName = serviceName
        }
        .onFailure { Log.e(TAG, "Failed to register proxy service `$serviceName`", it) }
  }

  /**
   * Registers system_server and answers with its framework service, or null if this is not
   * system_server. Only ever called from [onTransact] below.
   */
  private fun attachProcess(
      uid: Int,
      pid: Int,
      processName: String,
      processLifeToken: IBinder?
  ): IFrameworkService? {
    if (uid != 1000 || processLifeToken == null || processName != "system") return null
    systemServerRequested = true

    // Return the FrameworkService singleton if successfully registered
    return if (FrameworkService.registerHeartBeat(uid, pid, processName, processLifeToken)) {
      FrameworkService
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
        val processLifeToken = data.readStrongBinder()

        val service = attachProcess(uid, pid, processName, processLifeToken)
        if (service != null) {
          reply?.writeNoException()
          reply?.writeStrongBinder(service.asBinder())
          return true
        }
        return false
      }
      DEX_TRANSACTION_CODE,
      OBFUSCATION_MAP_TRANSACTION_CODE -> {
        return FrameworkService.onTransact(code, data, reply, flags)
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

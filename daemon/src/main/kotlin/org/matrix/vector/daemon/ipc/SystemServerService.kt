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
 * The Android R half of [SystemServerService.registerProxyService], in a class of its own on
 * purpose.
 *
 * `android.os.IServiceCallback` does not exist before Android R, and ART resolves the superclass of
 * every type a method mentions when it verifies that method, not when the branch mentioning it
 * runs. Spelling the callback out inside [SystemServerService] therefore made the daemon's very
 * first call fail to resolve `IServiceCallback$Stub` on Android 9 and log a NoClassDefFoundError,
 * even though the version gate meant that code was never executed (issue #925). Behind a separate
 * class the resolution is deferred to this object's initialization, which the gate skips on older
 * platforms.
 */
private object ServiceRegistrationWatcher {

  fun watch(serviceName: String) {
    val callback =
        object : IServiceCallback.Stub() {
          // The IServiceCallback will tell us when the real Android service is ready,
          // allowing us to capture it and then naturally stop intercepting traffic.
          override fun onRegistration(name: String, binder: IBinder?) {
            if (name == serviceName && binder != null) {
              SystemServerService.adoptOriginService(name, binder)
            }
          }

          override fun asBinder(): IBinder = this
        }
    runCatching { getSystemServiceManager().registerForNotifications(serviceName, callback) }
        .onFailure { Log.e(TAG, "Failed to register IServiceCallback", it) }
  }
}

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
      ServiceRegistrationWatcher.watch(serviceName)
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
   * Adopts the real system service once servicemanager reports its registration, so that
   * [onTransact] starts forwarding to it. Only ever called from [ServiceRegistrationWatcher].
   */
  internal fun adoptOriginService(name: String, binder: IBinder) {
    if (binder === this) return
    Log.d(TAG, "Intercepted system service registration with name `$name`")
    originService = binder
    runCatching { binder.linkToDeath(this, 0) }
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

    // Latched only once the registration has actually succeeded, not on the way in. It used to be
    // set immediately after the gate above, so a registration that then failed — registerHeartBeat
    // answers false when the life token cannot be linked to death — still read as attached. The
    // symptom was the worst kind: the manager's status page reported the framework as present in
    // system_server while no module hooking the system ever loaded, which sends a reader looking
    // at their module instead of at the injection.
    if (!FrameworkService.registerHeartBeat(uid, pid, processName, processLifeToken)) return null
    systemServerRequested = true
    return FrameworkService
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
      OBFUSCATION_MAP_TRANSACTION_CODE,
      INLINE_HOOK_TRANSACTION_CODE -> {
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

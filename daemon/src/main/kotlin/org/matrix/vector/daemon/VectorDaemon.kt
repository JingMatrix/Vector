package org.matrix.vector.daemon

import android.app.ActivityManager
import android.app.ActivityThread
import android.content.Context
import android.ddm.DdmHandleAppName
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.ServiceManager
import android.os.SystemProperties
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.env.CliSocketServer
import org.matrix.vector.daemon.env.Dex2OatServer
import org.matrix.vector.daemon.env.LogcatMonitor
import org.matrix.vector.daemon.ipc.BRIDGE_TRANSACTION_CODE
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.SystemServerService
import org.matrix.vector.daemon.utils.applyNotificationWorkaround

private const val TAG = "VectorDaemon"
private const val ACTION_SEND_BINDER = 1

object VectorDaemon {
  private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
    Log.e(TAG, "Caught fatal coroutine exception in background task!", throwable)
  }

  // Dispatchers.IO: Uses the shared background thread pool.
  // SupervisorJob(): Ensures one failing task doesn't kill the whole daemon.
  val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
  val bridgeServiceName = "activity"

  /**
   * Whether a `system_server` was already serving the moment this daemon started, which is how
   * Zygisk injecting into a booted system rather than into a fresh fork shows up on our side. The
   * boot it missed then has to be replayed, and it has to be met under another name.
   *
   * Set on the main thread during [main] and cleared by the death recipient on a binder thread,
   * which is what the `@Volatile` is for.
   */
  @Volatile var startedAfterSystemServer = false

  /**
   * The name the module looks the daemon up under while system_server is specialized. `serial` is
   * free at boot, because system_server registers it only much later, so a binder found there can
   * only be ours. On a booted system the real service holds it, and a lookup returning that is
   * indistinguishable from a successful rendezvous, so a name of our own is used instead.
   *
   * Computed from [startedAfterSystemServer] rather than stored beside it, so that the single
   * place a restart changes its mind about the rendezvous is the place the name is read from.
   */
  val proxyServiceName: String
    get() = if (startedAfterSystemServer) "serial_vector" else "serial"

  /**
   * The `activity` binder this daemon last linked its death recipient through.
   *
   * A death notification lives exactly as long as the `BinderProxy` it was registered on. Once
   * [sendToBridge] returns nothing else refers to that proxy, and when ART collects it the kernel
   * registration goes with it, silently: the daemon then sleeps through the next system_server
   * restart, never re-claims the rendezvous, and the module that comes back finds nothing to meet.
   * Holding the proxy here is what lets a daemon outlive the system_server it was started with.
   * The link is dropped through this field too, so the reference that keeps the notification
   * alive is the same one that releases it.
   */
  @Volatile private var bridgeBinder: IBinder? = null

  @JvmStatic
  fun main(args: Array<String>) {
    if (!FileSystem.tryLock()) kotlin.system.exitProcess(0)

    // `activity` is system_server's own, registered long after the specialization in which the
    // module comes looking for us, so at boot nothing answers it and a binder there means someone
    // booted without us. checkService is that question by its own name: does servicemanager hold
    // this one now.
    startedAfterSystemServer = ServiceManager.checkService(bridgeServiceName) != null

    Log.i(
        TAG,
        "Vector daemon started: afterSystemServer=$startedAfterSystemServer, " +
            "proxy=$proxyServiceName")
    // The hash is here rather than in the version the manager prints: Home should stay readable,
    // but every saved bug report should say exactly which commit produced the daemon that wrote it.
    Log.i(
        TAG,
        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "commit ${BuildConfig.VERSION_HASH}")

    Thread.setDefaultUncaughtExceptionHandler { _, e ->
      Log.e(TAG, "Uncaught exception in Daemon", e)
      kotlin.system.exitProcess(1)
    }

    // Setup Main Looper
    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
    @Suppress("DEPRECATION") Looper.prepareMainLooper()

    // Squat on the proxy service name immediately, which creates the early IPC channel of
    // FrameworkService for our Zygisk module during system_server specialization.
    SystemServerService.registerProxyService(proxyServiceName)

    // Start Environmental Daemons
    LogcatMonitor.start()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Dex2OatServer.start()
    CliSocketServer.start()

    // Preload Framework DEX in the background
    scope.launch { FileSystem.getPreloadDex(ConfigCache.state.isDexObfuscateEnabled) }

    // Initializes system frameworks inside the daemon process
    ActivityThread.systemMain()
    DdmHandleAppName.setAppName("org.matrix.vector.daemon", 0)

    // Wait for Android core services
    waitForSystemService("package")
    waitForSystemService("activity") // current bridgeServiceName
    waitForSystemService(Context.USER_SERVICE)
    waitForSystemService(Context.APP_OPS_SERVICE)

    applyNotificationWorkaround()

    // Read this before `sendToBridge`, which leaves the main thread at euid 1000: the config
    // database lives under a directory only root can enter, so the first process to open it has
    // to do so while we still have root. On a successful injection a binder thread opens it for
    // us during specialization, but when the injection fails nothing else has, and the daemon
    // used to die here on an unreadable preference.
    val isVerboseLog = ManagerService.isVerboseLogEnabled()

    // Setup IPC channel for applications by injecting DaemonService binder
    sendToBridge(VectorService.asBinder(), false)

    if (!isVerboseLog) {
      LogcatMonitor.stopVerbose()
    }

    Looper.loop()
    throw RuntimeException("Main thread loop unexpectedly exited")
  }

  private fun waitForSystemService(name: String) = runBlocking {
    while (ServiceManager.getService(name) == null) {
      Log.i(TAG, "Waiting system service: $name for 1s")
      delay(1000)
    }
  }

  // The bridge is setup in `system_server` via Zygisk API
  @Suppress("DEPRECATION")
  private fun sendToBridge(binder: IBinder, isRestart: Boolean) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "sendToBridge MUST run on the main thread!"
    }

    Os.seteuid(0)

    runCatching {
          var bridgeService: IBinder?
          if (isRestart) Log.w(TAG, "system_server restarted...")

          while (true) {
            bridgeService = ServiceManager.getService(bridgeServiceName)
            if (bridgeService?.pingBinder() == true) break
            Log.i(TAG, "`$bridgeServiceName` service not ready, waiting 1s...")
            Thread.sleep(1000)
          }

          // Setup death recipient to handle system_server crashes
          val deathRecipient =
              object : IBinder.DeathRecipient {
                override fun binderDied() {
                  Log.w(TAG, "System Server died! Clearing caches and re-injecting...")
                  bridgeBinder?.unlinkToDeath(this, 0)
                  bridgeBinder = null
                  clearSystemCaches()
                  SystemServerService.binderDied() // Cleanup old references
                  // Nothing will look the invented name up again, so stop holding it.
                  if (startedAfterSystemServer) SystemServerService.unregisterProxyService()
                  // Whatever this daemon was started into, the replacement is forked by a zygote
                  // that already carries our hooks, so it arrives the way one does at boot: it
                  // looks us up under `serial`, which its predecessor has just freed by dying,
                  // and it broadcasts a boot of its own that there is no need to replay.
                  startedAfterSystemServer = false
                  // And the question of whether a module reached system_server is about the one
                  // ahead of us, not the one that died with ours inside it.
                  SystemServerService.systemServerRequested = false
                  // Re-claim the service name immediately to ensure that when system_server
                  // restarts, our proxy is already there for the Zygisk module to find.
                  SystemServerService.registerProxyService(proxyServiceName)
                  ManagerService.guard = null // Remove dead guard
                  Handler(Looper.getMainLooper()).post { sendToBridge(binder, true) }
                }
              }
          bridgeService.linkToDeath(deathRecipient, 0)
          bridgeBinder = bridgeService // see [bridgeBinder]: the link is only as alive as this

          // Try sending the Binder payload (up to 3 times)
          var success = false
          for (i in 0 until 3) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
              data.writeInt(ACTION_SEND_BINDER)
              data.writeStrongBinder(binder)
              success = bridgeService.transact(BRIDGE_TRANSACTION_CODE, data, reply, 0) == true
              reply.readException()
              if (success) break
            } finally {
              data.recycle()
              reply.recycle()
            }
            Log.w(TAG, "No response from bridge, retrying...")
            Thread.sleep(1000)
          }

          if (success) {
            Log.i(TAG, "Successfully injected Vector IPC binder for applications.")
            // Only the name we invented for a late injection: `serial` is a real service name,
            // and system_server registering it is what takes our proxy out of servicemanager.
            if (startedAfterSystemServer) SystemServerService.unregisterProxyService()
          } else {
            // system_server is not restarted here. Restarting it has never been shown to
            // recover an injection: the module's hooks go in during specialization, long
            // before the bridge that would be restarted over, so a handshake failing this
            // late is a bug in the injection or the bootstrap. A restart hides that bug
            // until someone fixes it, which is the argument against keeping one.
            //
            // It is not free to keep around either. Every system_server start is a boot to
            // PackageWatchdog, which escalates a mitigation count that lives on disk and
            // never goes down, and far enough along that ladder the mitigation stops being
            // a reboot: the device is sent to recovery to be asked whether to erase itself.
            //
            // The two cases are still worth telling apart for whoever reads the log afterwards.
            // `systemServerRequested` is latched from inside `postServerSpecialize`, so a live
            // `activity` with nothing behind it means no module arrived at all.
            if (!SystemServerService.systemServerRequested) {
              Log.e(TAG, "No Vector module reached system_server; leaving it running")
            } else {
              Log.e(TAG, "Vector reached system_server but its bridge never answered")
            }
          }
        }
        .onFailure { Log.e(TAG, "Error during injecting DaemonService", it) }
    Os.seteuid(1000)
  }

  private fun clearSystemCaches() {
    Log.i(TAG, "Clearing ServiceManager and ActivityManager caches...")
    runCatching {
          // Clear ServiceManager.sServiceManager
          var field = ServiceManager::class.java.getDeclaredField("sServiceManager")
          field.isAccessible = true
          field.set(null, null)

          // Clear ServiceManager.sCache
          field = ServiceManager::class.java.getDeclaredField("sCache")
          field.isAccessible = true
          val sCache = field.get(null)
          if (sCache is MutableMap<*, *>) {
            sCache.clear()
          }

          // Clear ActivityManager.IActivityManagerSingleton
          field = ActivityManager::class.java.getDeclaredField("IActivityManagerSingleton")
          field.isAccessible = true
          val singleton = field.get(null)
          if (singleton != null) {
            val mInstanceField =
                Class.forName("android.util.Singleton").getDeclaredField("mInstance")
            mInstanceField.isAccessible = true
            synchronized(singleton) { mInstanceField.set(singleton, null) }
          }
        }
        .onFailure { Log.w(TAG, "Failed to clear system caches via reflection", it) }
  }

  /**
   * Brings the framework down and up without rebooting the device.
   *
   * `system_server` is forked from the *primary* zygote, so restarting that is what restarts the
   * framework — on a 64/32 device the primary init service is still called `zygote` (it runs
   * app_process64) and `zygote_secondary` is the 32-bit one.
   *
   * Everything on screen dies with it. The caller is expected to have said so first, which is
   * also what tells this apart from a boot loop — see [forgetDeliberateBoot].
   */
  fun softReboot() {
    Log.w(TAG, "Soft reboot: restarting the primary zygote")
    Dex2OatServer.onSoftReboot()
    forgetDeliberateBoot()
    SystemProperties.set("ctl.restart", "zygote")
  }

  /**
   * Takes the boot we are about to cause back out of PackageWatchdog's count.
   *
   * It counts every system_server start alike, having no way to tell one that was asked for from
   * one a crash loop produced, and each time enough of them land close together it raises a
   * mitigation count that is kept on disk and never lowered. Far enough along that ladder the
   * mitigation stops being a reboot: the device goes to recovery to be asked whether to erase
   * itself. A soft reboot does not clear the window count either, because init and its
   * properties live through one, so deliberate restarts pile up exactly as failures would.
   *
   * Zeroing the window count immediately before restarting cancels the single tick we are about
   * to add and nothing else. A real loop around us starts counting again from here and still
   * trips, which is the point: we only vouch for the boot we asked for ourselves.
   */
  private fun forgetDeliberateBoot() {
    // Two names for one counter: RescueParty read `sys.rescue_boot_count` directly until the
    // CrashRecovery mainline module moved it behind generated accessors and onto the
    // `crashrecovery.` prefix. Both are cleared because the daemon supports both eras, and
    // writing the name a platform does not use costs nothing.
    for (property in arrayOf("crashrecovery.rescue_boot_count", "sys.rescue_boot_count")) {
      runCatching { SystemProperties.set(property, "0") }
          .onFailure { Log.w(TAG, "Could not clear `$property` before restarting", it) }
    }
  }
}

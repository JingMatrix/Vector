package org.matrix.vector.daemon.ipc

import android.content.ComponentName
import android.content.Context
import android.content.IIntentSender
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.VersionedPackage
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SELinux
import android.os.SystemProperties
import android.util.Log
import android.view.IWindowManager
import hidden.HiddenApiBridge
import io.github.libxposed.service.IXposedService
import java.io.File
import java.util.concurrent.CountDownLatch
import org.matrix.vector.ipc.DeviceUser
import org.matrix.vector.ipc.IFrameworkInstallReceiver
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.ipc.ModuleLoadFailure
import org.matrix.vector.ipc.ScopeEntry
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.VectorDaemon
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.env.Dex2OatServer
import org.matrix.vector.daemon.env.LogcatMonitor
import org.matrix.vector.daemon.system.*
import org.matrix.vector.daemon.utils.InstallerVerifier
import org.matrix.vector.daemon.utils.PackageOptimizer
import org.matrix.vector.daemon.utils.RootImplementation
import org.matrix.vector.daemon.utils.applyXspaceWorkaround
import org.matrix.vector.daemon.utils.getRealUsers
import rikka.parcelablelist.ParcelableListSlice

private const val TAG = "VectorManagerService"

object ManagerService : IManagerService.Stub() {

  /** AOSP's switch for the synthesised launcher entries Android 10 introduced. */
  private const val SHOW_HIDDEN_ICON_APPS = "show_hidden_icon_apps_enabled"


  private var managerPid = -1
  private var pendingManager = false

  private var managerIntent: Intent? = null

  var guard: ManagerGuard? = null
    internal set

  class ManagerGuard(private val binder: IBinder, val pid: Int, val uid: Int) :
      IBinder.DeathRecipient {
    // system_server dispatches the 3-argument callback up to Android 16 and the
    // 4-argument one from Android 17 on.
    private val connection =
        object : android.app.IServiceConnection.Stub() {
          override fun connected(name: ComponentName?, service: IBinder?, dead: Boolean) {}

          override fun connected(
              name: ComponentName?,
              service: IBinder?,
              session: android.app.IBinderSession?,
              dead: Boolean
          ) {}
        }

    init {
      ManagerService.guard = this
      runCatching {
            binder.linkToDeath(this, 0)
            applyXspaceWorkaround(connection)
          }
          .onFailure {
            Log.e(TAG, "ManagerGuard initialization failed", it)
            ManagerService.guard = null
          }
    }

    override fun binderDied() {
      runCatching {
        binder.unlinkToDeath(this, 0)
        activityManager?.unbindService(connection)
      }
      ManagerService.guard = null
    }
  }

  @Synchronized
  fun preStartManager(): Boolean {
    Log.v(TAG, "Pre-start parasitic manager.")
    pendingManager = true
    managerPid = -1
    return true
  }

  @Synchronized
  fun tryRegisterManagerProcess(pid: Int, uid: Int, processName: String): Boolean {
    if (ConfigCache.isManager(uid) && processName == BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME) {
      if (pendingManager) {
        Log.v(TAG, "Parasitic manager registered.")
        pendingManager = false
      } else {
        Log.v(TAG, "Starting user-installed manager process.")
      }
      managerPid = pid
      return true
    }
    return false
  }

  fun postStartManager(pid: Int): Boolean = pid == managerPid

  private fun getManagerIntent(): Intent? {
    if (managerIntent != null) return managerIntent
    runCatching {
          var intent =
              Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_INFO)
                setPackage(BuildConfig.MANAGER_INJECTED_PKG_NAME)
              }
          var ris = packageManager?.queryIntentActivitiesCompat(intent, intent.type, 0, 0)

          if (ris.isNullOrEmpty()) {
            intent.removeCategory(Intent.CATEGORY_INFO)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            ris = packageManager?.queryIntentActivitiesCompat(intent, intent.type, 0, 0)
          }

          if (ris.isNullOrEmpty()) {
            val pkgInfo =
                packageManager?.getPackageInfoCompat(
                    BuildConfig.MANAGER_INJECTED_PKG_NAME, PackageManager.GET_ACTIVITIES, 0)
            val activity = pkgInfo?.activities?.firstOrNull { it.processName == it.packageName }
            if (activity != null) {
              intent =
                  Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(activity.packageName, activity.name)
                  }
            } else return null
          } else {
            val activity = ris.first().activityInfo
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.component = ComponentName(activity.packageName, activity.name)
          }

          intent.categories?.clear()
          intent.addCategory("${BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME}.LAUNCH_MANAGER")
          intent.setPackage(BuildConfig.MANAGER_INJECTED_PKG_NAME)
          managerIntent = Intent(intent)
        }
        .onFailure { Log.e(TAG, "Failed to build manager intent", it) }
    return managerIntent
  }

  fun openManager(withData: Uri?) {
    val intent = getManagerIntent() ?: return
    val launchIntent = Intent(intent).apply { data = withData }
    // Negative results are `ActivityManager.START_*` errors, the positive ones are all successes.
    val result = activityManager?.startActivityAsUserCompat(launchIntent, 0) ?: -1
    if (result < 0) Log.e(TAG, "Failed to open manager: $result")
  }

  /** Fixes permissions for the WebView cache. */
  private fun fixWebViewPermissions(file: File, targetUid: Int) {
    if (!file.exists()) return

    // Set the SELinux label that allows apps to read/write shared xposed data
    SELinux.setFileContext(file.absolutePath, "u:object_r:xposed_file:s0")

    // Change ownership to the target UID (e.g., 2000)
    runCatching { android.system.Os.chown(file.absolutePath, targetUid, targetUid) }
        .onFailure { Log.e(TAG, "Failed to chown ${file.path}", it) }

    // Recurse into directories
    if (file.isDirectory) {
      file.listFiles()?.forEach { fixWebViewPermissions(it, targetUid) }
    }
  }

  private fun ensureWebViewPermission() {
    val targetUid = BuildConfig.MANAGER_INJECTED_UID
    runCatching {
          val pkgInfo =
              packageManager?.getPackageInfoCompat(BuildConfig.MANAGER_INJECTED_PKG_NAME, 0, 0)
                  ?: return@runCatching

          val dataDir =
              HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(pkgInfo.applicationInfo)
          val cacheDir = File(dataDir, "cache")

          if (!cacheDir.exists()) cacheDir.mkdirs()
          fixWebViewPermissions(cacheDir, targetUid)
        }
        .onFailure { Log.w(TAG, "WebView permission fix failed", it) }
  }

  fun obtainManagerBinder(heartbeat: IBinder, pid: Int, uid: Int): IBinder {
    ManagerGuard(heartbeat, pid, uid)
    if (ConfigCache.isManager(uid)) {
      ensureWebViewPermission()
    }
    return this
  }

  fun isRunningManager(pid: Int, uid: Int): Boolean =
      pid == managerPid && ConfigCache.isManager(uid)

  override fun getProtocolVersion() = IManagerService.PROTOCOL_VERSION

  override fun getLibxposedApiVersion() = IXposedService.LIB_API

  override fun getFrameworkVersionCode() = BuildConfig.VERSION_CODE

  override fun getFrameworkVersionName() = BuildConfig.VERSION_NAME

  override fun getBuildStamp(): String? = BuildConfig.VERSION_HASH.takeIf { it.isNotBlank() }

  override fun getInstalledPackagesFromAllUsers(
      flags: Int,
      filterNoProcess: Boolean
  ): ParcelableListSlice<PackageInfo> {
    return ParcelableListSlice(
        packageManager?.getInstalledPackagesFromAllUsers(flags, filterNoProcess) ?: emptyList())
  }

  override fun getEnabledModules() = ModuleDatabase.enabledModules().toList()

  /**
   * The unloadable map, as a list of rows.
   *
   * The map is exactly what the pair this replaced sent one key and one lookup at a time, so the
   * conversion is the whole of the merge. A reason of 0 is never stored — [ConfigCache] only ever
   * writes one of the three failures — so the AIDL's promise that 0 never travels holds without a
   * filter here.
   */
  override fun getModuleLoadFailures(): List<ModuleLoadFailure> =
      ConfigCache.state.unloadable.map { (pkgName, why) ->
        ModuleLoadFailure().apply {
          packageName = pkgName
          reason = why
        }
      }

  override fun setModuleEnabled(packageName: String, enabled: Boolean) =
      if (enabled) ModuleDatabase.enableModule(packageName)
      else ModuleDatabase.disableModule(packageName)

  override fun setModuleScope(packageName: String, scope: MutableList<ScopeEntry>) =
      ModuleDatabase.setModuleScope(packageName, scope)

  override fun getModuleScope(packageName: String) = ModuleDatabase.getModuleScope(packageName)

  // Reports the setting, not the setting OR'd with the build type. It used to be
  // `|| BuildConfig.DEBUG`, which made the value unwritable on a debug daemon: the manager could
  // never read false, so its switch snapped back on every tap and had to be greyed out. The OR was
  // redundant anyway — `isVerboseLogEnabled()` already defaults to true — so a debug build still
  // logs verbosely out of the box, and now a developer can also turn it off.
  override fun isVerboseLogEnabled() = PreferenceStore.isVerboseLogEnabled()

  override fun setVerboseLogEnabled(enabled: Boolean) {
    PreferenceStore.setVerboseLog(enabled)
    if (isVerboseLogEnabled()) LogcatMonitor.startVerbose() else LogcatMonitor.stopVerbose()
  }

  override fun getLogParts(verbose: Boolean): List<String> = FileSystem.listLogParts(verbose)

  override fun getLogPart(verbose: Boolean, name: String): ParcelFileDescriptor? =
      FileSystem.openLogPart(verbose, name)?.let {
        ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
      }

  /**
   * The part being written on one of the two streams.
   *
   * The two calls this replaces were not symmetric: only the modules one asked
   * [LogcatMonitor.checkLogFile] to re-open a descriptor the reader had lost. That asymmetry is
   * kept exactly as it was rather than tidied away, because levelling it either way changes when a
   * lost descriptor is repaired, and that is a decision about the log rather than about this
   * merge.
   */
  override fun getLiveLogPart(verbose: Boolean): ParcelFileDescriptor? {
    if (!verbose) LogcatMonitor.checkLogFile()
    val file = if (verbose) LogcatMonitor.getVerboseLog() else LogcatMonitor.getModulesLog()
    return file?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
  }

  override fun startNewLogPart(verbose: Boolean) {
    LogcatMonitor.refresh(verbose)
  }

  override fun forceStopPackage(packageName: String, userId: Int) {
    activityManager?.forceStopPackage(packageName, userId)
  }

  override fun softReboot() = VectorDaemon.softReboot()

  /**
   * The flashed manager APK, verified, for the manager to install as an ordinary app.
   *
   * The same file and the same check as [FrameworkService.openManagerApk], which
   * serves it to the host process for injection — one APK, one signature gate, whichever way it
   * leaves the module directory.
   */
  override fun getManagerApk(): ParcelFileDescriptor? =
      runCatching {
            InstallerVerifier.verifyInstallerSignature(FileSystem.managerApkPath.toString())
            ParcelFileDescriptor.open(
                FileSystem.managerApkPath.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
          }
          .onFailure { Log.e(TAG, "Failed to open or verify manager APK", it) }
          .getOrNull()

  override fun reboot() {
    powerManager?.reboot(false, null, false)
  }

  override fun uninstallPackage(packageName: String, userId: Int): Boolean {
    val latch = CountDownLatch(1)
    var result = false

    val sender =
        object : IIntentSender.Stub() {
          override fun send(
              code: Int,
              intent: Intent,
              resolvedType: String?,
              whitelistToken: IBinder?,
              finishedReceiver: android.content.IIntentReceiver?,
              requiredPermission: String?,
              options: Bundle?
          ) {
            val status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            result = status == PackageInstaller.STATUS_SUCCESS
            latch.countDown()
          }

          override fun send(
              code: Int,
              intent: Intent,
              resolvedType: String?,
              finishedReceiver: android.content.IIntentReceiver?,
              requiredPermission: String?,
              options: Bundle?
          ): Int {
            send(code, intent, resolvedType, null, finishedReceiver, requiredPermission, options)
            return 0
          }
        }

    // Using reflection to wrap the AIDL stub into an Android IntentSender
    val intentSender =
        runCatching {
              val constructor =
                  IntentSender::class.java.getDeclaredConstructor(IIntentSender::class.java)
              constructor.isAccessible = true
              constructor.newInstance(sender)
            }
            .getOrNull() ?: return false

    val pkg = VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST)
    val allUsers = userId == IManagerService.ALL_USERS
    val flag = if (allUsers) 0x00000002 else 0 // DELETE_ALL_USERS flag

    runCatching {
          packageManager
              ?.packageInstaller
              ?.uninstall(pkg, "android", flag, intentSender, if (allUsers) 0 else userId)
        }
        .onFailure {
          return false
        }

    latch.await()
    return result
  }

  override fun isSepolicyLoaded() =
      SELinux.checkSELinuxAccess(
          "u:r:dex2oat:s0", "u:object_r:dex2oat_exec:s0", "file", "execute_no_trans")

  override fun getUsers(): List<DeviceUser> {
    return userManager?.getRealUsers()?.map {
      DeviceUser().apply {
        id = it.id
        name = it.name
      }
    } ?: emptyList()
  }

  override fun isSystemServerAttached() = SystemServerService.systemServerRequested

  override fun startActivityAsUser(intent: Intent, userId: Int, noUserSwitch: Boolean): Int {
    if (!noUserSwitch) {
      val currentUser = activityManager?.currentUser
      val parent = userManager?.getProfileParent(userId)?.id ?: userId
      if (currentUser != null && currentUser.id != parent) {
        if (activityManager?.switchUser(parent) == false) return -1
        val wm =
            IWindowManager.Stub.asInterface(
                android.os.ServiceManager.getService(Context.WINDOW_SERVICE))
        wm?.lockNow(null)
      }
    }
    return activityManager?.startActivityAsUserCompat(intent, userId) ?: -1
  }

  override fun queryIntentActivitiesAsUser(
      intent: Intent,
      flags: Int,
      userId: Int
  ): ParcelableListSlice<ResolveInfo> {
    return ParcelableListSlice(
        packageManager?.queryIntentActivitiesCompat(intent, intent.type, flags, userId)
            ?: emptyList())
  }

  override fun isDex2OatInliningDisabled() =
      SystemProperties.get("dalvik.vm.dex2oat-flags").contains("--inline-max-code-units=0")

  /**
   * Android 10 and later synthesise a launcher entry for an installed app that declares none, and
   * `show_hidden_icon_apps_enabled` is the switch for that: 1 shows them, 0 leaves them hidden.
   *
   * Read and written by running `settings`, which is neither laziness nor a shortcut. Two in-process
   * routes were tried on a Pixel 6 running Android 17 and both are closed to this process:
   *
   *  * The original code called `IContentProvider.call` with the pre-Android-12 signature, the one
   *    without an `AttributionSource`. That method has not existed since Android 12, so every press
   *    threw `NoSuchMethodError` — logged at warning level, swallowed, and the switch moved anyway.
   *  * Going through `ActivityThread.getSystemContext().getContentResolver()` then fails at the
   *    other end: `SecurityException: Unable to find app for caller … when getting content provider
   *    settings`. The daemon has an ActivityThread but no application record, so the system will
   *    not hand it a provider.
   *
   * The command is stable across versions in a way that the hidden binder interface demonstrably is
   * not, and the daemon is root, so it is entitled to run it. This codebase already shells out for
   * module installs and for dex2oat.
   */
  override fun setForcedLauncherIcons(force: Boolean) {
    runCatching { settingsCommand("put", if (force) "1" else "0") }
        .onFailure { Log.w(TAG, "setForcedLauncherIcons failed", it) }
  }

  override fun isForcedLauncherIcons(): Boolean =
      runCatching {
            // Unset must read as the platform default of 1, not as "off" — otherwise the switch
            // shows the opposite of what the system is doing on every device where nobody has
            // touched it.
            settingsCommand("get")?.trim().let { it.isNullOrEmpty() || it == "null" || it == "1" }
          }
          .getOrDefault(true)

  private fun settingsCommand(verb: String, value: String? = null): String? {
    val command = buildList {
      add("settings")
      add(verb)
      add("global")
      add(SHOW_HIDDEN_ICON_APPS)
      value?.let { add(it) }
    }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    return output.ifBlank { null }
  }

  override fun writeBugReport(zipFd: ParcelFileDescriptor) {
    FileSystem.getLogs(zipFd)
  }


  override fun isStatusNotificationEnabled() = PreferenceStore.isStatusNotificationEnabled()

  override fun setStatusNotificationEnabled(enable: Boolean) {
    val isEnabled = isStatusNotificationEnabled()
    PreferenceStore.setStatusNotification(enable)
    if (isEnabled && !enable) {
      NotificationManager.cancelStatusNotification()
    }
    if (!isEnabled && enable) {
      NotificationManager.notifyStatusNotification()
    }
  }

  override fun optimizePackage(packageName: String) = PackageOptimizer.optimize(packageName)

  override fun getDex2OatWrapperState() =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Dex2OatServer.compatibility else 0

  override fun setIncludeNewApps(packageName: String, enabled: Boolean) =
      ModuleDatabase.setIncludeNewApps(packageName, enabled)

  override fun getIncludeNewApps(packageName: String) = ModuleDatabase.getIncludeNewApps(packageName)

  override fun getRootImplementation() = RootImplementation.implementation

  override fun getRootImplementationVersion() = RootImplementation.version

  override fun installFrameworkZip(zipPath: String, receiver: IFrameworkInstallReceiver) {
    // Off the binder thread: a flash takes seconds to minutes, and holding a binder thread for its
    // duration starves everything else the manager asks of the daemon meanwhile — including the
    // log reads the install screen is doing to show what is happening.
    Thread {
          val exit =
              RootImplementation.install(zipPath) { line ->
                runCatching { receiver.onLine(line) }
                    .onFailure {
                      // The manager went away mid-flash. Keep installing — stopping now would
                      // leave the module tree half-written — and keep logging, which is the only
                      // record left.
                      Log.w(TAG, "Install receiver is gone; continuing", it)
                    }
              }
          runCatching { receiver.onFinished(exit) }
              .onFailure { Log.w(TAG, "Could not report install result", it) }
        }
        .apply {
          name = "vector-framework-install"
          start()
        }
  }
}

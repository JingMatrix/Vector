package org.matrix.vector.daemon

import android.app.IApplicationThread
import android.content.Context
import android.content.IIntentReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import hidden.HiddenApiBridge
import io.github.libxposed.service.IXposedScopeCallback
import kotlinx.coroutines.launch
import org.lsposed.lspd.models.Application
import org.lsposed.lspd.service.IDaemonService
import org.lsposed.lspd.service.ILSPApplicationService
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.data.ProcessScope
import org.matrix.vector.daemon.ipc.ApplicationService
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.ModuleService
import org.matrix.vector.daemon.system.*

private const val TAG = "VectorService"

object VectorService : IDaemonService.Stub() {

  private var bootCompleted = false
  @Suppress("DEPRECATION")
  private val ACTION_SECRET_CODE =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) TelephonyManager.ACTION_SECRET_CODE
      else Telephony.Sms.Intents.SECRET_CODE_ACTION

  override fun dispatchSystemServerContext(
      appThread: IBinder?,
      activityToken: IBinder?,
  ) {
    appThread?.let { SystemContext.appThread = IApplicationThread.Stub.asInterface(it) }
    SystemContext.token = activityToken

    // Initialize OS Observers using Coroutines for the dispatch blocks
    registerReceivers()

    if (VectorDaemon.isLateInject) {
      Log.i(TAG, "Late injection detected. Forcing boot completed event.")
      dispatchBootCompleted()
    }
  }

  override fun requestApplicationService(
      uid: Int,
      pid: Int,
      processName: String,
      heartBeat: IBinder
  ): ILSPApplicationService? {
    if (Binder.getCallingUid() != 1000) {
      Log.w(TAG, "Unauthorized requestApplicationService call")
      return null
    }
    if (ApplicationService.hasRegister(uid, pid)) return null

    val scope = ProcessScope(processName, uid)
    if (!ManagerService.tryRegisterManagerProcess(pid, uid, processName) &&
        ConfigCache.shouldSkipProcess(scope)) {
      Log.d(TAG, "Skipped $processName/$uid")
      return null
    }

    return if (ApplicationService.registerHeartBeat(uid, pid, processName, heartBeat)) {
      ApplicationService
    } else null
  }

  override fun preStartManager() = ManagerService.preStartManager()

  private fun createReceiver() =
      object : IIntentReceiver.Stub() {
        override fun performReceive(
            intent: Intent,
            resultCode: Int,
            data: String?,
            extras: Bundle?,
            ordered: Boolean,
            sticky: Boolean,
            sendingUser: Int
        ) {
          VectorDaemon.scope.launch {
            when (intent.action) {
              Intent.ACTION_LOCKED_BOOT_COMPLETED -> dispatchBootCompleted()
              Intent.ACTION_CONFIGURATION_CHANGED -> dispatchConfigurationChanged()
              NotificationManager.openManagerAction -> ManagerService.openManager(intent.data)
              ACTION_SECRET_CODE -> ManagerService.openManager(intent.data)
              NotificationManager.moduleScopeAction -> dispatchModuleScope(intent)
              else -> dispatchPackageChanged(intent)
            }
          }

          // Critical for ordered broadcasts to avoid freezing the system queue
          if (!ordered && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
          runCatching {
                val appThread = SystemContext.appThread
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                  activityManager?.finishReceiver(
                      appThread?.asBinder(), resultCode, data, extras, false, intent.flags)
                } else {
                  activityManager?.finishReceiver(
                      this, resultCode, data, extras, false, intent.flags)
                }
              }
              .onFailure { Log.e(TAG, "finishReceiver failed", it) }
        }
      }

  private fun registerReceivers() {
    val configFilter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)

    val packageFilter =
        IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_CHANGED)
          addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
          addDataScheme("package")
        }

    val uidFilter = IntentFilter(Intent.ACTION_UID_REMOVED)

    val bootFilter =
        IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED).apply {
          priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }

    val openManagerNoDataFilter = IntentFilter(NotificationManager.openManagerAction)

    val openManagerDataFilter =
        IntentFilter(NotificationManager.openManagerAction).apply {
          addDataScheme("module")
          addDataScheme("android_secret_code")
        }

    val scopeFilter =
        IntentFilter(NotificationManager.moduleScopeAction).apply { addDataScheme("module") }

    val secretCodeFilter =
        IntentFilter().apply {
          addDataScheme("android_secret_code")
          addDataAuthority("5776733", null)
        }

    // Define strict Android 14+ flags and the system-only BRICK permission
    val notExported = Context.RECEIVER_NOT_EXPORTED
    val exported = Context.RECEIVER_EXPORTED
    val brickPerm = "android.permission.BRICK" // Restrict senders to Android system only

    // userId = 0 => USER_SYSTEM
    activityManager?.registerReceiverCompat(
        createReceiver(), configFilter, brickPerm, 0, notExported)
    // userId = -1 => USER_ALL
    activityManager?.registerReceiverCompat(
        createReceiver(), packageFilter, brickPerm, -1, notExported)
    activityManager?.registerReceiverCompat(createReceiver(), uidFilter, brickPerm, -1, notExported)
    activityManager?.registerReceiverCompat(createReceiver(), bootFilter, brickPerm, 0, notExported)

    activityManager?.registerReceiverCompat(
        createReceiver(), openManagerNoDataFilter, brickPerm, 0, notExported)
    activityManager?.registerReceiverCompat(
        createReceiver(), openManagerDataFilter, brickPerm, 0, notExported)
    activityManager?.registerReceiverCompat(
        createReceiver(), scopeFilter, brickPerm, 0, notExported)

    // Only the secret dialer code needs to be exported so the phone app can trigger it
    activityManager?.registerReceiverCompat(
        createReceiver(),
        secretCodeFilter,
        "android.permission.CONTROL_INCALL_EXPERIENCE",
        0,
        exported)

    // UID Observer
    val uidObserver =
        object : android.app.IUidObserver.Stub() {
          override fun onUidActive(uid: Int) = ModuleService.uidStarts(uid)

          override fun onUidCachedChanged(uid: Int, cached: Boolean) {
            if (!cached) ModuleService.uidStarts(uid)
          }

          override fun onUidIdle(uid: Int, disabled: Boolean) = ModuleService.uidStarts(uid)

          override fun onUidGone(uid: Int, disabled: Boolean) = ModuleService.uidGone(uid)
        }

    val which =
        HiddenApiBridge.ActivityManager_UID_OBSERVER_ACTIVE() or
            HiddenApiBridge.ActivityManager_UID_OBSERVER_GONE() or
            HiddenApiBridge.ActivityManager_UID_OBSERVER_IDLE() or
            HiddenApiBridge.ActivityManager_UID_OBSERVER_CACHED()

    activityManager?.registerUidObserver(
        uidObserver, which, HiddenApiBridge.ActivityManager_PROCESS_STATE_UNKNOWN(), "android")
    Log.d(TAG, "Registered all OS Receivers and UID Observers")
  }

  private fun dispatchBootCompleted() {
    bootCompleted = true
    Log.d(TAG, "BOOT_COMPLETED event received.")
    if (PreferenceStore.isStatusNotificationEnabled()) {
      NotificationManager.notifyStatusNotification()
    }
  }

  private fun dispatchConfigurationChanged() {
    Log.d(TAG, "CONFIGURATION_CHANGED event received.")

    if (!bootCompleted) return
    if (PreferenceStore.isStatusNotificationEnabled()) {
      NotificationManager.notifyStatusNotification()
    } else {
      NotificationManager.cancelStatusNotification()
    }
  }

  private const val EXTRA_REMOVED_FOR_ALL_USERS = "android.intent.extra.REMOVED_FOR_ALL_USERS"
  private const val EXTRA_USER_HANDLE = "android.intent.extra.user_handle"
  private const val ACTION_MANAGER_NOTIFICATION =
      "${BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME}.NOTIFICATION"
  private const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000
  private const val FLAG_RECEIVER_FROM_SHELL = 0x00400000

  private fun dispatchPackageChanged(intent: Intent) {
    val action = intent.action ?: return
    val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
    val userId = intent.getIntExtra(EXTRA_USER_HANDLE, uid / PER_USER_RANGE)
    val isRemovedForAllUsers = intent.getBooleanExtra(EXTRA_REMOVED_FOR_ALL_USERS, false)

    val uri = intent.data
    val moduleName = uri?.schemeSpecificPart ?: ConfigCache.getModuleByUid(uid)?.packageName

    Log.d(TAG, "dispatchPackageChanged $action $moduleName [$uid]")

    val appInfo =
        moduleName?.let {
          packageManager
              ?.getPackageInfoCompat(it, MATCH_ALL_FLAGS or PackageManager.GET_META_DATA, 0)
              ?.applicationInfo
        }
    var isXposedModule =
        appInfo != null &&
            (appInfo.metaData?.containsKey("xposedminversion") == true ||
                ConfigCache.getModuleApkPath(appInfo) != null)

    when (action) {
      Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
        if (moduleName != null) {
          // When a package is gone, we can't check metadata.
          // If it's gone for everyone, wipe the package from all users in the DB.
          // Otherwise, only wipe it for the user that just uninstalled it.
          val targetUser = if (isRemovedForAllUsers) null else userId
          PreferenceStore.deleteModulePrefs(moduleName, userId, group = null)
          if (isRemovedForAllUsers && ModuleDatabase.removeModule(moduleName)) {
            // If it was in our DB and we successfully removed it, we treat it as an Xposed module.
            isXposedModule = true
          }
        }
      }
      Intent.ACTION_PACKAGE_ADDED,
      Intent.ACTION_PACKAGE_CHANGED -> {
        if (isXposedModule && moduleName != null && appInfo != null) {
          // Update the database with the new APK path if it's an Xposed module
          isXposedModule =
              ModuleDatabase.updateModuleApkPath(
                  moduleName, ConfigCache.getModuleApkPath(appInfo), false)
        } else {
          if (ConfigCache.state.scopes.keys.any { it.uid == uid }) {
            // If not a module, but it's an app that was previously a "scope" (target)
            // for a module, we need to refresh the cache.
            ConfigCache.requestCacheUpdate()
          }

          if (action == Intent.ACTION_PACKAGE_ADDED &&
              !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) &&
              moduleName != null) {

            ConfigCache.getAutoIncludeModules().forEach { xposedModule ->
              val scopeList = ConfigCache.getModuleScope(xposedModule) ?: mutableListOf()

              val newScope =
                  Application().apply {
                    this.packageName = moduleName
                    this.userId = userId
                  }

              scopeList.add(newScope)
              if (!ModuleDatabase.setModuleScope(xposedModule, scopeList)) {
                Log.e(TAG, "Failed to auto-include $moduleName for $xposedModule")
              }
            }
          }
        }
      }
      Intent.ACTION_UID_REMOVED -> {
        // If the UID being removed was a module or a scoped app, refresh the cache.
        if (isXposedModule || ConfigCache.state.scopes.keys.any { it.uid == uid }) {
          ConfigCache.requestCacheUpdate()
        }
      }
    }

    // Special handling if the app being changed is the Vector Manager itself.
    val isRemovedAction =
        action == Intent.ACTION_PACKAGE_FULLY_REMOVED || action == Intent.ACTION_UID_REMOVED
    if (moduleName == BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME && userId == 0) {
      Log.d(TAG, "Manager updated")
      ConfigCache.updateManager(isRemovedAction)
    }

    // Notify the manager (foreground) that a package state changed so it can refresh its view.
    if (moduleName != null) {
      val notifyIntent =
          Intent(ACTION_MANAGER_NOTIFICATION).apply {
            putExtra(Intent.EXTRA_INTENT, intent)
            putExtra("android.intent.extra.PACKAGES", moduleName)
            putExtra(Intent.EXTRA_USER, userId)
            putExtra("isXposedModule", isXposedModule)
            addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND or FLAG_RECEIVER_FROM_SHELL)
          }

      // Send to both the parasitic manager and the standalone manager
      listOf(BuildConfig.MANAGER_INJECTED_PKG_NAME, BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME)
          .forEach { pkg ->
            activityManager?.broadcastIntentCompat(Intent(notifyIntent).setPackage(pkg))
          }
    }

    // If an actual Xposed module was updated (not removed), show a system notification.
    if (moduleName != null && isXposedModule && !isRemovedAction && !isRemovedForAllUsers) {
      val scopes = ConfigCache.getModuleScope(moduleName) ?: emptyList()
      val isSystemModule = scopes.any { it.packageName == "system" }
      val isEnabled = ManagerService.enabledModules().contains(moduleName)

      NotificationManager.notifyModuleUpdated(moduleName, userId, isEnabled, isSystemModule)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun dispatchModuleScope(intent: Intent) {
    val data = intent.data ?: return
    val extras = intent.extras ?: return
    val callbackBinder = extras.getBinder("callback") ?: return
    if (!callbackBinder.isBinderAlive) return

    val authority = data.encodedAuthority ?: return
    val parts = authority.split(":", limit = 2)
    if (parts.size != 2) return
    val packageName = parts[0]
    val userId = parts[1].toIntOrNull() ?: return

    val scopePackageName = data.path?.substring(1) ?: return // remove leading '/'
    val action = data.getQueryParameter("action") ?: return

    val iCallback = IXposedScopeCallback.Stub.asInterface(callbackBinder)
    runCatching {
          val appInfo = packageManager?.getPackageInfoCompat(scopePackageName, 0, userId)
          if (appInfo == null) {
            iCallback.onScopeRequestFailed("Package not found")
            return
          }
          when (action) {
            "approve" -> {
              val scopes = ConfigCache.getModuleScope(packageName) ?: mutableListOf()
              if (scopes.none { it.packageName == scopePackageName && it.userId == userId }) {
                scopes.add(
                    Application().apply {
                      this.packageName = scopePackageName
                      this.userId = userId
                    })
                ModuleDatabase.setModuleScope(packageName, scopes)
              }
              iCallback.onScopeRequestApproved(listOf(scopePackageName))
            }
            "deny" -> iCallback.onScopeRequestFailed("Request denied by user")
            "delete" -> iCallback.onScopeRequestFailed("Request timeout")
            "block" -> {
              val blocked =
                  PreferenceStore.getModulePrefs("lspd", 0, "config")["scope_request_blocked"]
                      as? Set<String> ?: emptySet()
              PreferenceStore.updateModulePref(
                  "lspd", 0, "config", "scope_request_blocked", blocked + packageName)
              iCallback.onScopeRequestFailed("Request blocked by configuration")
            }
          }
        }
        .onFailure { runCatching { iCallback.onScopeRequestFailed(it.message) } }

    NotificationManager.cancelNotification(
        NotificationManager.SCOPE_CHANNEL_ID, packageName, userId)
  }
}

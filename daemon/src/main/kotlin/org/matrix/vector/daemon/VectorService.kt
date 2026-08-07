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
import android.telephony.TelephonyManager
import android.util.Log
import hidden.HiddenApiBridge
import io.github.libxposed.service.IXposedScopeCallback
import kotlinx.coroutines.launch
import org.matrix.vector.ipc.ScopeEntry
import org.matrix.vector.ipc.IVectorDaemon
import org.matrix.vector.ipc.IFrameworkService
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.data.ProcessScope
import org.matrix.vector.daemon.ipc.FrameworkService
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.ModuleAppService
import org.matrix.vector.daemon.system.*

private const val TAG = "VectorService"

object VectorService : IVectorDaemon.Stub() {

  private var bootCompleted = false

  /**
   * What the dialer broadcasts when a secret code is entered, which changed name in Q.
   *
   * The pre-Q action is spelled out rather than taken from `Telephony.Sms.Intents`, whose constant
   * only became public API in 28 while this daemon runs from 27. It is the same string either way:
   * on 8.1 the platform broadcast it from the hidden `TelephonyIntents.SECRET_CODE_ACTION`, which
   * carries exactly this value, and the public constant that followed was deprecated in 29 when
   * `TelephonyManager.ACTION_SECRET_CODE` replaced it.
   */
  private val ACTION_SECRET_CODE =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) TelephonyManager.ACTION_SECRET_CODE
      else "android.provider.Telephony.SECRET_CODE"

  /** Dial *#*#832867#*#* ("VECTOR" on the keypad) to open the manager. */
  private const val SECRET_CODE = "832867"

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

  override fun attachProcess(
      uid: Int,
      pid: Int,
      processName: String,
      heartBeat: IBinder
  ): IFrameworkService? {
    if (Binder.getCallingUid() != 1000) {
      Log.w(TAG, "Unauthorized attachProcess call")
      return null
    }
    if (FrameworkService.hasRegister(uid, pid)) return null

    val scope = ProcessScope(processName, uid)
    if (!ManagerService.tryRegisterManagerProcess(pid, uid, processName) &&
        ConfigCache.shouldSkipProcess(scope)) {
      Log.d(TAG, "Skipped $processName/$uid")
      return null
    }

    return if (FrameworkService.registerHeartBeat(uid, pid, processName, heartBeat)) {
      FrameworkService
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
        IntentFilter(ACTION_SECRET_CODE).apply {
          addDataScheme("android_secret_code")
          addDataAuthority(SECRET_CODE, null)
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
          override fun onUidActive(uid: Int) = ModuleAppService.uidStarts(uid)

          override fun onUidCachedChanged(uid: Int, cached: Boolean) {
            if (!cached) ModuleAppService.uidStarts(uid)
          }

          override fun onUidIdle(uid: Int, disabled: Boolean) = ModuleAppService.uidStarts(uid)

          override fun onUidGone(uid: Int, disabled: Boolean) = ModuleAppService.uidGone(uid)
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
          // The one preference of a module that is not stored under the module. "Never ask again"
          // writes the package into a set belonging to "lspd", so the line above — which deletes
          // what is filed under the module's own name — walks straight past it, and the package
          // stayed blocked after it was uninstalled. Nothing else ever removes it: the CLI does not
          // know the key and the manager offers no way back, so a module blocked by a mis-tap could
          // never ask again on that device, and reinstalling it did not help.
          //
          // Asked of every package that goes for good, not only of modules, and deliberately so:
          // `moduleName` here is whatever the broadcast named, and once a package is fully removed
          // its metadata is gone, so this branch cannot tell a module from anything else —
          // `isXposedModule` is decided below, and only for one that was still in our database. A
          // package that was never blocked costs the one read of the "lspd" config row that
          // [unblockScopeRequests] starts with, and nothing else.
          if (isRemovedForAllUsers) unblockScopeRequests(moduleName)
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

            ModuleDatabase.modulesIncludingNewApps().forEach { xposedModule ->
              val scopeList = ModuleDatabase.getModuleScope(xposedModule) ?: mutableListOf()

              val newScope =
                  ScopeEntry().apply {
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
      val scopes = ModuleDatabase.getModuleScope(moduleName) ?: emptyList()
      val isSystemModule = scopes.any { it.packageName == "system" }
      val isEnabled = ManagerService.getEnabledModules().contains(moduleName)

      NotificationManager.notifyModuleUpdated(moduleName, userId, isEnabled, isSystemModule)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun dispatchModuleScope(intent: Intent) {
    val data = intent.data ?: return
    val extras = intent.extras ?: return
    val callbackBinder = extras.getBinder("callback") ?: return

    val authority = data.encodedAuthority ?: return
    val parts = authority.split(":", limit = 2)
    if (parts.size != 2) return
    val packageName = parts[0]
    val userId = parts[1].toIntOrNull() ?: return

    // Everything the one prompt asked about, in the order it listed them. ',' cannot occur in a
    // package name, so this is the list the module named and not a guess at it.
    val scopePackageNames =
        data.path?.substring(1)?.split(",")?.filter { it.isNotEmpty() } ?: return // strip '/'
    if (scopePackageNames.isEmpty()) return
    val action = data.getQueryParameter("action") ?: return

    // One prompt reaches this receiver from four places — its three buttons and its delete intent
    // — and a swipe or the one-hour timeout fires the delete intent whether or not a button was
    // pressed first. Answering the module twice, an approval followed by a spurious "Request
    // timeout", would be worse than the dismissal never reaching it, so whichever of the four
    // arrives first is the one that answers and the rest are dropped. The request is identified by
    // the module, its user and the set of packages it asked for; the action is deliberately not
    // part of that, since the whole point is that a second *different* action must not answer
    // again.
    if (!NotificationManager.claimScopeAnswer(packageName, userId, scopePackageNames)) {
      Log.d(
          TAG,
          "Ignoring $action of ${scopePackageNames.joinToString()} for $packageName:" +
              " already answered")
      return
    }

    // A prompt outlives the process that asked for it: it sits for an hour, and the app the module
    // is running inside can be killed at any point in that hour. Nothing is dropped for that any
    // more. There used to be a `!callbackBinder.isBinderAlive` return above the claim, exempting
    // only "never ask again", and it did more than lose an answer nobody was listening for: an
    // approval the user had already given was thrown away, and because the return sat above the
    // claim and the cancel, the prompt stayed on screen with live buttons that did nothing for the
    // rest of the hour. An approval is a decision about the module's scope and is written down
    // whether or not the module is there to hear it; deny and the timeout have nothing to record
    // but still have a prompt to take down. What actually fails against a dead module is the
    // callback below, and that is caught where it is made.
    val iCallback = IXposedScopeCallback.Stub.asInterface(callbackBinder)
    runCatching {
          // Answered before the requested package is looked up at all, because "never ask again" is
          // a decision about the *module* and not about the package this particular prompt happened
          // to name. It used to sit in the when below, under the lookup, so a prompt naming a
          // package that no longer resolves — uninstalled or disabled while the prompt was up,
          // hidden by a profile owner, or never installed for this user — returned at "Package not
          // found" and never reached blockScopeRequests: the user said stop asking, the prompt came
          // down, and the module was free to ask again a second later.
          if (action == "block") {
            blockScopeRequests(packageName)
            // The preference only stops the *next* request. A module that asked three times has a
            // prompt up for each, so without this the user says "never ask again" and is left
            // looking at two more questions, both still approvable. Each withdrawn request is
            // answered in its own right, because each of them was asked in its own right.
            NotificationManager.withdrawScopeRequests(packageName).forEach { pending ->
              runCatching { pending.onScopeRequestFailed("Request blocked by configuration") }
            }
            // Last, and caught, because this is the only call here that reaches into the module's
            // process: it may be gone by now, and neither the preference write nor the withdrawal
            // above must be lost to a dead binder. They can fail in their own ways — the write goes
            // through a SQLite transaction — which is why the whole branch runs inside runCatching
            // as well.
            runCatching { iCallback.onScopeRequestFailed("Request blocked by configuration") }
            return@runCatching
          }

          when (action) {
            "approve" -> {
              // "system" is the framework and not a package: it names system_server, which belongs
              // to no package and resolves for nobody. The lookup below therefore came back null
              // for every framework prompt, and the approval the user had just given was answered
              // "Package not found" — the request was closed, its notification cancelled, and no
              // row written. The normalisation to user 0 further down could never once have run.
              //
              // Package by package rather than all-or-nothing: the prompt may have been up for an
              // hour and one of the packages it named can have been uninstalled in the meantime,
              // which is no reason to throw away the user's answer about the rest.
              //
              // Only under "approve", because only an approval has to name something real. Deny
              // and the timeout used to be refused here too, so dismissing a prompt for a package
              // that had since been uninstalled told the module "Package not found" when what had
              // actually happened was that the user turned it down.
              val granted =
                  scopePackageNames.filter {
                    it == "system" || packageManager?.getPackageInfoCompat(it, 0, userId) != null
                  }
              if (granted.isEmpty()) {
                // Logged, because until now this said nothing anywhere: the module was told
                // "Package not found", the user was told nothing at all, and the daemon kept no
                // record that the press had even arrived. The framework-scope failure above was
                // invisible for exactly that reason.
                Log.w(
                    TAG,
                    "None of ${scopePackageNames.joinToString()} resolve for user $userId;" +
                        " refusing the scope request of $packageName")
                // Leaving the whole function here skipped the cancel below, which used to be
                // merely untidy and is now a prompt nobody can use: the request has been answered,
                // so every later press of its buttons is dropped. The request is over either way,
                // so the notification goes with it.
                iCallback.onScopeRequestFailed("Package not found")
                return@runCatching
              }
              val scopes = ModuleDatabase.getModuleScope(packageName) ?: mutableListOf()
              var added = false
              granted.forEach { scopePackageName ->
                // Compared against where the row will land, not against the user who asked: the
                // framework is stored under user 0 whoever requested it, so for "system" this test
                // never matched and every approval appended a duplicate and rewrote the whole
                // table.
                val storedUserId = if (scopePackageName == "system") 0 else userId
                val present =
                    scopes.any { it.packageName == scopePackageName && it.userId == storedUserId }
                if (!present) {
                  scopes.add(
                      ScopeEntry().apply {
                        this.packageName = scopePackageName
                        this.userId = storedUserId
                      })
                  added = true
                }
              }
              // One write for the whole prompt, and none at all when the user approved what the
              // module already had. `setModuleScope` replaces the module's rows wholesale and
              // enables the module on the way through, so writing per package would rewrite the
              // table once per package — leaving a window after each in which the scope is only
              // partly what was agreed to — and writing unconditionally would let a module enable
              // itself by asking again for what it has.
              if (added) ModuleDatabase.setModuleScope(packageName, scopes)
              Log.i(TAG, "Approved ${granted.joinToString()} for $packageName on user $userId")
              // The packages that were granted, which is what the list in this callback is for. A
              // module comparing it against what it asked for can see what it did not get.
              iCallback.onScopeRequestApproved(granted)
            }
            "deny" -> iCallback.onScopeRequestFailed("Request denied by user")
            "delete" -> iCallback.onScopeRequestFailed("Request timeout")
          }
        }
        // onScopeRequestFailed declares @NonNull, and Throwable.message is frequently null.
        .onFailure { runCatching { iCallback.onScopeRequestFailed(it.message ?: it.toString()) } }

    // Only this one request goes; a module that asked more than once has a prompt still open for
    // each of its other requests, and they are answered on their own.
    NotificationManager.cancelScopeRequest(packageName, userId, scopePackageNames)
  }

  /**
   * The modules that may not ask for scope again.
   *
   * Filed under "lspd" rather than under the module it names, because it records the user's decision
   * about a module rather than that module's own configuration. That is also why uninstalling a
   * module does not take it away — `deleteModulePrefs` deletes what is filed under the module's own
   * name — and why [unblockScopeRequests] has to exist.
   */
  @Suppress("UNCHECKED_CAST")
  private fun blockedScopeRequests(): Set<String> =
      PreferenceStore.getModulePrefs("lspd", 0, "config")["scope_request_blocked"] as? Set<String>
          ?: emptySet()

  private fun blockScopeRequests(packageName: String) {
    PreferenceStore.updateModulePref(
        "lspd", 0, "config", "scope_request_blocked", blockedScopeRequests() + packageName)
  }

  /**
   * Lets an uninstalled module ask again if it comes back.
   *
   * "Never ask again" is a button in a notification, one tap from Approve, and nothing anywhere
   * undid it: the socket CLI does not know the key, the manager offers no control for it, and the
   * uninstall path missed it. A module blocked by a mis-tap was blocked on that device forever, and
   * reinstalling it changed nothing. Uninstalling is a deliberate enough act to count as taking it
   * back — and a module that is gone has no decision left to honour.
   */
  private fun unblockScopeRequests(packageName: String) {
    val blocked = blockedScopeRequests()
    if (packageName !in blocked) return
    PreferenceStore.updateModulePref(
        "lspd", 0, "config", "scope_request_blocked", blocked - packageName)
    Log.i(TAG, "$packageName was uninstalled; it may ask for scope again if it returns")
  }
}

package org.matrix.vector.daemon.ipc

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.service.HookedProcess
import io.github.libxposed.service.IHotReloadCallback
import io.github.libxposed.service.IXposedScopeCallback
import io.github.libxposed.service.IXposedService
import java.io.Serializable
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.matrix.vector.ipc.HotReloadOutcome
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.IHotReloadOutcomeReceiver
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.NotificationManager
import org.matrix.vector.daemon.system.ProcessFreezer
import org.matrix.vector.daemon.system.PER_USER_RANGE
import org.matrix.vector.daemon.system.activityManager

private const val TAG = "VectorModuleAppService"

/**
 * A module's service as its own **app** sees it — libxposed's `IXposedService`.
 *
 * One of two services a module gets, and the name says which. [InjectedModuleService] is the other:
 * the same module seen from inside a process it was injected into. They deliberately differ in what
 * they allow — a module app may write its remote files, a hooked process may only read them,
 * because a hooked process runs as the app it was injected into rather than as the module — so
 * which one a reader is looking at has to be legible from the class name.
 *
 * See `IModuleService.aidl` for the other side of that distinction.
 */
class ModuleAppService(private val loadedModule: LoadedModule) : IXposedService.Stub() {

  companion object {
    // Per-target serialization lives on the target itself; this only keeps one slow target from
    // delaying another.
    private val hotReloadExecutor =
        Executors.newCachedThreadPool { r -> Thread(r, "vector-hot-reload") }

    // How long a target gets to answer. Generous, because the whole point is that the callee runs
    // module code - but finite, because binder is not, and a target left in RELOADING answers every
    // later request with IN_PROGRESS for as long as the process lives.
    private const val RELOAD_TIMEOUT_SECONDS = 30L

    private val uidSet = ConcurrentHashMap.newKeySet<Int>()
    private val serviceMap =
        Collections.synchronizedMap(WeakHashMap<LoadedModule, ModuleAppService>())

    fun uidClear() {
      uidSet.clear()
    }

    fun uidStarts(uid: Int) {
      if (uidSet.add(uid)) {
        val module = ConfigCache.getModuleByUid(uid)
        if (module?.code?.legacy == false) {
          val service = serviceMap.getOrPut(module) { ModuleAppService(module) }
          service.sendBinder(uid)
        }
      }
    }

    fun uidGone(uid: Int) {
      uidSet.remove(uid)
    }

    // Drives the same cycle as a service request, so onHotReloading can still refuse it.
    fun autoHotReload(module: LoadedModule) {
      if (!module.code.autoHotReload) return
      val service = serviceMap.getOrPut(module) { ModuleAppService(module) }
      FrameworkService.staleHotReloadTargets(module.packageName).forEach { target ->
        if (target.hotReloadable && FrameworkService.beginHotReload(target)) {
          Log.d(TAG, "Auto hot reloading ${module.packageName} in ${target.processName}")
          hotReloadExecutor.execute { service.runHotReload(target, null, null) }
        }
      }
    }
  }

  /**
   * Forges a ContentProvider call to force the module's target app process to receive this Binder
   * IPC endpoint without standard Context.bindService() limits.
   */
  private fun sendBinder(uid: Int) {
    val name = loadedModule.packageName
    runCatching {
          val userId = uid / PER_USER_RANGE
          val authority = name + AUTHORITY_SUFFIX
          val provider =
              activityManager?.getContentProviderExternal(authority, userId, null, null)?.provider

          if (provider == null) {
            Log.d(TAG, "No service provider for $name")
            return
          }

          val extra = Bundle().apply { putBinder("binder", asBinder()) }
          val reply: Bundle? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                provider.call(
                    AttributionSource.Builder(1000).setPackageName("android").build(),
                    authority,
                    SEND_BINDER,
                    null,
                    extra)
              } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                provider.call("android", null, authority, SEND_BINDER, null, extra)
              } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                provider.call("android", authority, SEND_BINDER, null, extra)
              } else {
                provider.call("android", SEND_BINDER, null, extra)
              }

          if (reply != null) Log.d(TAG, "Sent module binder to $name")
          else Log.w(TAG, "Failed to send module binder to $name")
        }
        .onFailure { Log.w(TAG, "Failed to send module binder for uid $uid", it) }
  }

  private fun ensureModule(): Int {
    val appId = Binder.getCallingUid() % PER_USER_RANGE
    if (loadedModule.appId != appId) {
      throw RemoteException(
          "Module ${loadedModule.packageName} is not for uid ${Binder.getCallingUid()}")
    }
    return Binder.getCallingUid() / PER_USER_RANGE
  }

  override fun getApiVersion() = ensureModule().let { IXposedService.LIB_API }

  override fun getFrameworkName() = ensureModule().let { BuildConfig.FRAMEWORK_NAME }

  /**
   * The whole version, not just its name.
   *
   * The interface promises a module "the framework version" as a string, and what goes in it is
   * this implementation's to decide. "2.0" was true and useless: the number that identifies a
   * build is the commit count, and even that is shared by every branch built at the same depth, so
   * a module author reading a bug report could not tell which framework produced it. The manager's
   * status page grew the exact build for that reason; a module author receives bug reports too.
   *
   * The parenthesised group stays purely numeric and [getFrameworkVersionCode] still answers with
   * the number on its own, so nothing that wants to *compare* versions has any reason to parse
   * this string.
   */
  override fun getFrameworkVersion() =
      ensureModule().let {
        buildString {
          append(BuildConfig.VERSION_NAME)
          append(" (").append(BuildConfig.VERSION_CODE).append(")")
          BuildConfig.VERSION_HASH.takeIf { hash -> hash.isNotBlank() }
              ?.let { hash -> append(" ").append(hash) }
        }
      }

  override fun getFrameworkVersionCode() = ensureModule().let { BuildConfig.VERSION_CODE }

  override fun getFrameworkProperties(): Long {
    ensureModule()
    var prop = IXposedService.PROP_CAP_SYSTEM or IXposedService.PROP_CAP_REMOTE
    if (ConfigCache.state.isDexObfuscateEnabled)
        prop = prop or IXposedService.PROP_RT_API_PROTECTION
    return prop
  }

  override fun getScope(): List<String> {
    val userId = ensureModule()
    // The caller's own user, and the framework row that belongs to none. The scope set is one set
    // for the whole module, but the other two calls on this interface are not: [requestScope] asks
    // for the caller's user and [removeScope] gives back the caller's user. Returning every row
    // meant a copy in user 11 was shown user 0's packages, which it could neither have asked for
    // nor give back - the removal is keyed on its own user and would match nothing.
    //
    // The scope table has one row per (app, user), so a module held by several users saw the same
    // package repeatedly. A scope is a set of package names.
    return ModuleDatabase.getModuleScope(loadedModule.packageName)
        ?.filter { it.userId == userId || it.packageName == "system" }
        ?.map { it.packageName }
        ?.distinct() ?: emptyList()
  }

  override fun requestScope(packages: List<String>, callback: IXposedScopeCallback) {
    val userId = ensureModule()
    if (packages.isEmpty()) {
      // Nothing was asked for, so the request is trivially satisfied. Returning without touching
      // the callback would leave the module waiting forever.
      callback.onScopeRequestApproved(emptyList())
      return
    }
    // A module that fixed its own scope in module.prop does not get to ask for more of it at
    // runtime. Prompting the user here would make "fixed" mean nothing.
    ConfigCache.staticScopeOf(loadedModule.packageName)?.let { claimed ->
      val beyond = packages.filterNot { claimed.contains(it) }
      if (beyond.isNotEmpty()) {
        callback.onScopeRequestFailed(
            "This module declares a static scope, so ${beyond.joinToString()} cannot be added")
        return
      }
    }
    if (!PreferenceStore.isScopeRequestBlocked(loadedModule.packageName)) {
      packages.forEach { pkg ->
        NotificationManager.requestModuleScope(loadedModule.packageName, userId, pkg, callback)
      }
    } else {
      callback.onScopeRequestFailed("Scope request blocked by user configuration")
    }
  }

  override fun removeScope(packages: List<String>) {
    val userId = ensureModule()
    packages.forEach { pkg ->
      runCatching { ModuleDatabase.removeModuleScope(loadedModule.packageName, pkg, userId) }
          .onFailure { Log.e(TAG, "Error removing scope for $pkg", it) }
    }
  }

  override fun getRunningTargets(): List<HookedProcess> {
    val userId = ensureModule()
    return FrameworkService.getHotReloadTargets(loadedModule.packageName, userId)
  }

  override fun hotReloadModule(targetId: Long, data: Bundle?, callback: IHotReloadCallback?) {
    // The user id matters as much as the app id here: ensureModule only proves the caller shares
    // the module's app id, which every copy of it does. The copies are one module and one APK, but
    // they are separate apps with separate uids and separate preferences, and the boundary that
    // keeps a module out of a user that never installed it applies to reloading too. Without this,
    // the copy in user 11 could reload user 0's processes.
    val userId = ensureModule()
    // SecurityException is reserved by the AIDL for exactly these two conditions, so it must not be
    // raised for anything else on this path - a module-thrown SecurityException in particular has
    // to reach the caller as a FAILED result, not as "invalid target id".
    val target =
        FrameworkService.getHotReloadTarget(targetId, loadedModule.packageName, userId)
            ?: throw SecurityException("Target $targetId is not a target of ${loadedModule.packageName}")

    if (!target.hotReloadable) {
      // Hot reload is specified only for modules declaring exactly one Java entry class.
      report(callback, IXposedService.HOT_RELOAD_UNSUPPORTED, "Module has no single Java entry class")
      return
    }

    if (!FrameworkService.beginHotReload(target)) {
      report(callback, IXposedService.HOT_RELOAD_IN_PROGRESS, "A reload is already running")
      return
    }

    // The AIDL asks implementations to validate and enqueue promptly and report through the
    // callback. Running the cycle inline would pin this binder thread for its whole duration and
    // ANR a module app that called from its main thread.
    hotReloadExecutor.execute { runHotReload(target, data, callback) }
  }

  private fun runHotReload(
      target: FrameworkService.HotReloadTarget,
      data: Bundle?,
      callback: IHotReloadCallback?,
  ) {
    var status = IXposedService.HOT_RELOAD_FAILED
    var message: String? = "Hot reload did not run"
    var refreeze: (() -> Unit)? = null
    var loadedVersion: Long? = null
    val answered = CountDownLatch(1)
    var outcome: HotReloadOutcome? = null

    try {
      val binder = FrameworkService.getHotReloadBinder(target)
      if (binder == null) {
        status = IXposedService.HOT_RELOAD_UNSUPPORTED
        message = "Process ${target.processName} has no hot reload entry point"
        return
      }
      val newModule = ConfigCache.state.modules[loadedModule.packageName]
      if (newModule == null) {
        status = IXposedService.HOT_RELOAD_UNSUPPORTED
        message = "No installed generation of ${loadedModule.packageName} to load"
        return
      }

      // A cached target is usually frozen, and a transaction to a frozen process is not delivered.
      // Thawing first is what keeps that case from being reported as a refusal. A device with no
      // app freezer at all - anything before the cgroup v2 freezer - is the ordinary path, not a
      // failure, so a null here only means "nothing to do".
      refreeze = ProcessFreezer.thaw(target.pid)
      if (ProcessFreezer.isFrozen(target.pid)) {
        // Say so now rather than spending the timeout on a transaction that will not be delivered.
        // Not a refusal either: the message is what tells the two apart.
        status = IXposedService.HOT_RELOAD_FAILED
        message = "Process ${target.processName} is frozen and could not be thawed"
        return
      }

      val callbackStub =
          object : IHotReloadOutcomeReceiver.Stub() {
            override fun onOutcome(result: HotReloadOutcome?) {
              outcome = result
              answered.countDown()
            }
          }
      binder.hotReload(loadedModule.packageName, data, newModule, callbackStub)

      // Bounded, because the callee runs arbitrary module code and binder has no timeout of its
      // own: without this a module that never returns from onHotReloading would leave the target
      // RELOADING for the life of the process, and every later request would answer IN_PROGRESS.
      if (!answered.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        status =
            if (FrameworkService.isProcessRegistered(target)) IXposedService.HOT_RELOAD_FAILED
            else IXposedService.HOT_RELOAD_PROCESS_DIED
        message =
            if (status == IXposedService.HOT_RELOAD_PROCESS_DIED) {
              "Process ${target.processName} died during hot reload"
            } else {
              "Process ${target.processName} did not answer within ${RELOAD_TIMEOUT_SECONDS}s"
            }
        return
      }

      val answer =
          outcome
              ?: run {
                status = IXposedService.HOT_RELOAD_FAILED
                message = "Process ${target.processName} answered with nothing"
                return
              }

      status = answer.status
      // Whether the generation was swapped is not the same question as whether the reload
      // succeeded: onHotReloaded runs after the swap is committed, so a throw from it leaves the
      // process on the new code and still reports FAILED. Recording the version the target is
      // actually running is what keeps getRunningTargets() honest about it.
      if (answer.generationChanged) loadedVersion = newModule.versionCode
      // A null message is reserved for a refusal, so anything else gets one supplied.
      message =
          answer.message
              ?: if (status == IXposedService.HOT_RELOAD_FAILED && !answer.refused) {
                "Hot reload failed without a diagnostic message"
              } else {
                null
              }
    } catch (t: Throwable) {
      // Deliberately not keyed on DeadObjectException: a frozen-but-alive target answers a
      // transaction with exactly that, so the exception type says nothing about whether the process
      // is gone. The heartbeat registry does - it is driven by a DeathRecipient.
      val gone = !FrameworkService.isProcessRegistered(target)
      status =
          if (gone) IXposedService.HOT_RELOAD_PROCESS_DIED else IXposedService.HOT_RELOAD_FAILED
      message =
          if (gone) "Process ${target.processName} died during hot reload"
          else "${t.javaClass.name}: ${t.message ?: "no message"}"
      Log.e(TAG, "Hot reload of ${loadedModule.packageName} failed", t)
    } finally {
      refreeze?.invoke()
      FrameworkService.endHotReload(target, stateFor(status), loadedVersion)
      report(callback, status, message)
    }
  }

  private fun stateFor(status: Int): Int =
      when (status) {
        IXposedService.HOT_RELOAD_SUCCEEDED -> HookedProcess.TARGET_STATE_UP_TO_DATE
        IXposedService.HOT_RELOAD_FAILED -> HookedProcess.TARGET_STATE_FAILED
        // Unsupported and process-died say nothing about the generation the target is running, so
        // the reported state falls back to comparing versions.
        else -> HookedProcess.TARGET_STATE_UP_TO_DATE
      }

  private fun report(callback: IHotReloadCallback?, status: Int, message: String?) {
    runCatching { callback?.onHotReloadResult(status, message) }
        .onFailure { Log.w(TAG, "Cannot deliver hot reload result to ${loadedModule.packageName}", it) }
  }

  override fun requestRemotePreferences(group: String): Bundle {
    val userId = ensureModule()
    return Bundle().apply {
      putSerializable(
          "map",
          PreferenceStore.getModulePrefs(loadedModule.packageName, userId, group) as Serializable)
    }
  }

  @Suppress("DEPRECATION")
  override fun updateRemotePreferences(group: String, diff: Bundle) {
    val userId = ensureModule()
    val values = mutableMapOf<String, Any?>()

    // RemotePreferences.Editor always writes this key, and sets it for edit().clear(). Ignoring it
    // left every key the module app just cleared in place.
    if (diff.getBoolean("clear", false)) {
      PreferenceStore.deleteModulePrefs(loadedModule.packageName, userId, group)
    }

    diff.getSerializable("delete")?.let { deletes ->
      (deletes as Set<*>).forEach { values[it as String] = null }
    }
    diff.getSerializable("put")?.let { puts ->
      (puts as Map<*, *>).forEach { (k, v) -> values[k as String] = v }
    }

    runCatching {
          PreferenceStore.updateModulePrefs(loadedModule.packageName, userId, group, values)
          (loadedModule.service as? InjectedModuleService)
              ?.onUpdateRemotePreferences(group, userId, diff)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemotePreferences(group: String) {
    val userId = ensureModule()
    PreferenceStore.deleteModulePrefs(loadedModule.packageName, userId, group)
    // Hooked processes hold an in-process cache of the group; without this they keep serving the
    // deleted values until their process restarts.
    (loadedModule.service as? InjectedModuleService)
        ?.onUpdateRemotePreferences(group, userId, Bundle().apply { putBoolean("clear", true) })
  }

  override fun listRemoteFiles(): Array<String> {
    val userId = ensureModule()
    return runCatching {
          FileSystem.resolveModuleDir(
                  loadedModule.packageName, "files", userId, Binder.getCallingUid())
              .toFile()
              .list() ?: emptyArray()
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun openRemoteFile(path: String): ParcelFileDescriptor {
    val userId = ensureModule()
    FileSystem.ensureModuleFilePath(path)
    return runCatching {
          val file =
              FileSystem.resolveModuleDir(
                      loadedModule.packageName, "files", userId, Binder.getCallingUid())
                  .resolve(path)
                  .toFile()
          ParcelFileDescriptor.open(
              file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemoteFile(path: String): Boolean {
    val userId = ensureModule()
    FileSystem.ensureModuleFilePath(path)
    return runCatching {
          FileSystem.resolveModuleDir(
                  loadedModule.packageName, "files", userId, Binder.getCallingUid())
              .resolve(path)
              .toFile()
              .delete()
        }
        .getOrElse { throw RemoteException(it.message) }
  }
}

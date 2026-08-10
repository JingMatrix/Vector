package org.matrix.vector.daemon.ipc

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
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

    /**
     * The uids whose module app is holding a binder we handed it.
     *
     * A binder belongs to the *process* that received it, but a uid can outlive any one of its
     * processes: an app with a `:remote` or crash-handler process, or a shared user id, keeps its
     * uid alive when the process we served is reaped, so no [uidGone] arrives and the replacement
     * process would be refused here forever. That was unreachable while the reference below pinned
     * every module app at foreground priority and nothing ever reaped it. Giving the reference back
     * makes it the ordinary case, so entries are also dropped by [linkDelivery] when the process
     * that took the binder dies.
     *
     * Recorded on a *successful* send rather than on the attempt: a failed send leaves nothing on
     * the other side, and treating it as delivered meant the one module that most needed another
     * attempt never got one.
     */
    private val uidSet = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Which send is running for a uid right now, so the three observer callbacks agree on one.
     *
     * The uid alone was not enough to say that. [uidGone] deliberately drops the marker rather
     * than wait for a send that may never return, so from that moment a replacement process can
     * start a second send while the first is still blocked — and the first, on its way out, would
     * remove the marker the second is holding, letting a *third* start behind it, and would then
     * commit its own dead process's result over the second's. The value is the attempt that owns
     * the uid: a send touches nothing unless the token it was handed is still the one here.
     */
    private val sending = ConcurrentHashMap<Int, Any>()

    /**
     * What tells [uidSet] that a delivery is over: the provider binder we spoke to, and the
     * recipient watching it. Held because a `DeathRecipient` nothing references is one the runtime
     * may collect before it ever fires.
     */
    private val deliveries = ConcurrentHashMap<Int, Pair<IBinder, IBinder.DeathRecipient>>()

    /**
     * Held wherever [uidSet] and [deliveries] change hands: the three places that hand a uid over
     * are a send committing its result, [uidGone], and the death of the process that took the
     * binder, and each of them reads one of the two fields to decide what to do to the other.
     * Both are individually atomic, which is what made the gap between them easy to miss — a send
     * that tested its ownership and was then overtaken by [uidGone] before it added the uid left
     * the uid marked as served with nothing serving it, and the replacement process was refused at
     * the top of [uidStarts] with no later uid edge to come.
     *
     * Nothing that blocks runs under it: the send itself is outside, and `linkToDeath` is a local
     * call.
     */
    private val deliveryLock = Any()

    private val serviceMap =
        Collections.synchronizedMap(WeakHashMap<LoadedModule, ModuleAppService>())

    /**
     * Consecutive failed sends per uid, and when the last one was.
     *
     * A module app that dies before it can publish its provider is not a transient failure to be
     * retried at the speed of the uid observer. It happens — an app that crashes on start, or one
     * another module deliberately kills, as in #889 where a module in a third module's scope took
     * its host down on every launch — and the delivery below *starts the process*, so retrying is
     * not a passive act: it feeds the very loop it is failing on. Fourteen starts in seventy-six
     * seconds were observed that way, six of them ours.
     *
     * Per uid and not per package, because `getModuleByUid` matches on the app id: one module
     * installed for two users is one `LoadedModule` under two uids, and keying by name would let a
     * crash-looping copy in a work profile throttle the healthy copy in user 0, and let either
     * one's success wipe the other's run.
     *
     * Once [MAX_CONSECUTIVE_BINDER_FAILURES] have piled up the retries are throttled to one per
     * [BINDER_RETRY_COOLDOWN_MS] — the count is held at the ceiling rather than reset by the
     * attempt that the cooldown lets through, or the ceiling would simply be re-climbed and three
     * more attempts allowed every minute for ever. A run is forgotten after
     * [BINDER_FAILURE_RUN_MS] without a failure, so an occasional one never accumulates. Throttled
     * rather than abandoned, and cleared by the first success, because the app may simply have been
     * mid-update or out of memory; a module written off for good on three failures would be a worse
     * bug than the one this is fixing.
     */
    private val binderFailures = ConcurrentHashMap<Int, FailureRun>()

    private class FailureRun(val count: Int, val atElapsed: Long)

    private const val MAX_CONSECUTIVE_BINDER_FAILURES = 3
    private const val BINDER_RETRY_COOLDOWN_MS = 60_000L
    private const val BINDER_FAILURE_RUN_MS = 10 * BINDER_RETRY_COOLDOWN_MS

    // The delivery blocks in getContentProviderExternal until the app publishes its provider or
    // AMS gives up on it, and it runs from an IUidObserver callback - one binder thread, serving
    // every uid transition on the device. A module app that never publishes therefore stalls the
    // delivery of every *other* module's binder behind it: eight and a half seconds, measured, on
    // a device where one module app was crash-looping. One thread per module keeps that local.
    private val binderExecutor =
        Executors.newCachedThreadPool { r -> Thread(r, "vector-module-binder") }

    fun uidClear() {
      uidSet.clear()
    }

    fun uidStarts(uid: Int) {
      if (uid in uidSet) return
      // What identifies this attempt for as long as it runs, and what every later step of it is
      // tested against: see [sending].
      val attempt = Any()
      if (sending.putIfAbsent(uid, attempt) != null) return
      val module = ConfigCache.getModuleByUid(uid)
      if (module?.code?.legacy != false) {
        sending.remove(uid, attempt)
        return
      }
      if (isThrottled(uid)) {
        sending.remove(uid, attempt)
        return
      }
      val service = serviceMap.getOrPut(module) { ModuleAppService(module) }
      // Off the observer thread, and never inline: see [binderExecutor]. Caught, because a uid
      // left in [sending] by a rejected submission is one this never looks at again.
      runCatching {
            binderExecutor.execute {
              try {
                val delivered = service.sendBinder(uid)
                if (delivered != null) {
                  // Only the attempt that still owns the uid may say the module has its service.
                  // An abandoned one spoke to a process the uid has already outlived, and marking
                  // the uid served on its word is what refuses the process that replaced it.
                  synchronized(deliveryLock) {
                    if (sending[uid] === attempt) {
                      uidSet.add(uid)
                      binderFailures.remove(uid)
                      linkDelivery(uid, delivered)
                    }
                  }
                } else {
                  // Counted whether or not this attempt still owns the uid, unlike the success
                  // above: a module app that dies while the platform waits for its provider is
                  // exactly what the throttle is for, and it is also exactly what takes the
                  // ownership away. A failure dropped for being stale is one the restart loop
                  // never has to pay for.
                  recordFailure(uid, module.packageName)
                }
              } finally {
                sending.remove(uid, attempt)
              }
            }
          }
          .onFailure {
            sending.remove(uid, attempt)
            Log.w(TAG, "Could not schedule the binder delivery for ${module.packageName}", it)
          }
    }

    /**
     * Watches the process that took the binder, so [uidSet] forgets the uid when it dies.
     *
     * [uidGone] is not enough on its own — it only fires when the *uid* has no processes left —
     * and this is what makes a second delivery to a restarted module app possible. A death
     * recipient on a proxy is not a client of anything, so unlike the provider reference it puts
     * no floor under the process's priority.
     *
     * Called under [deliveryLock], by the attempt that owns the uid.
     */
    private fun linkDelivery(uid: Int, provider: IBinder) {
      val recipient =
          object : IBinder.DeathRecipient {
            override fun binderDied() {
              // This delivery's own entry, not merely this uid's. A death notification for the
              // process we served can arrive after a replacement process has taken a binder of its
              // own — the notification is queued when the process dies, not when we get to it —
              // and forgetting the uid then sends the module a second copy of a service it already
              // holds, starting a process to do it.
              synchronized(deliveryLock) {
                if (deliveries.remove(uid, provider to this)) uidSet.remove(uid)
              }
            }
          }
      runCatching {
            provider.linkToDeath(recipient, 0)
            deliveries.put(uid, provider to recipient)?.let { (old, previous) ->
              runCatching { old.unlinkToDeath(previous, 0) }
            }
          }
          // Already dead, which is an answer in itself: whatever took the binder is gone, so the
          // uid must not stay marked as served.
          .onFailure { uidSet.remove(uid) }
    }

    /** True while a uid has spent its attempts and its cooldown has not elapsed. */
    private fun isThrottled(uid: Int): Boolean {
      val run = binderFailures[uid] ?: return false
      if (run.count < MAX_CONSECUTIVE_BINDER_FAILURES) return false
      return SystemClock.elapsedRealtime() - run.atElapsed < BINDER_RETRY_COOLDOWN_MS
    }

    private fun recordFailure(uid: Int, modulePkg: String) {
      var crossed = false
      // Read-modify-write in one step, and not merely as a precaution: an attempt abandoned by
      // [uidGone] and the replacement that took the uid from it can both be counting here for the
      // same uid, which is the case a plain get-then-put would lose.
      binderFailures.compute(uid) { _, previous ->
        val now = SystemClock.elapsedRealtime()
        val count =
            when {
              // A run is forgotten only after a long quiet spell, not after one cooldown. Forgetting
              // it at the cooldown meant the attempt the cooldown let through reset the count, so
              // the ceiling was re-climbed and three more attempts allowed every minute, for ever.
              previous == null || now - previous.atElapsed >= BINDER_FAILURE_RUN_MS -> 1
              // Held at the ceiling rather than growing without bound: what the number decides is
              // only whether we are throttled, and pinning it here is what makes the cooldown mean
              // one attempt rather than another three.
              else -> minOf(previous.count + 1, MAX_CONSECUTIVE_BINDER_FAILURES)
            }
        crossed = count == MAX_CONSECUTIVE_BINDER_FAILURES && (previous?.count ?: 0) < count
        FailureRun(count, now)
      }
      // Once, on the way past the ceiling. The failures themselves are already logged one by one
      // in sendBinder; what is worth saying here is that we have stopped trying, which is the part
      // a reader chasing a module that never receives its service cannot otherwise see.
      if (crossed) {
        Log.w(
            TAG,
            "$modulePkg/$uid failed to take its binder $MAX_CONSECUTIVE_BINDER_FAILURES times in" +
                " a row; retrying at most once every ${BINDER_RETRY_COOLDOWN_MS / 1000}s")
      }
    }

    fun uidGone(uid: Int) {
      synchronized(deliveryLock) {
        uidSet.remove(uid)
        // A send that never returns — `provider.call` runs the module's own onServiceBind, with no
        // deadline — would otherwise leave the uid here for the life of the daemon, and every later
        // delivery for it refused at the top of uidStarts. Giving the uid up rather than waiting is
        // what lets the process that replaces this one be served at once; the attempt token is what
        // stops the send we walked away from committing over it.
        sending.remove(uid)
        deliveries.remove(uid)?.let { (binder, recipient) ->
          runCatching { binder.unlinkToDeath(recipient, 0) }
        }
      }
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
   *
   * Called only from [uidStarts], on [binderExecutor] rather than on the uid observer, because
   * `getContentProviderExternal` blocks until the app publishes its provider or the platform gives
   * up waiting for it.
   *
   * @return the provider binder of the process that took it, or null if nobody did. The caller
   *   counts the failures — nothing else distinguishes "the app has its service" from "we asked and
   *   nobody answered", and conflating the two is what let a module app that dies on every launch
   *   be started again a second later, for as long as it kept dying — and watches the binder, so
   *   that the process dying is what makes the next one eligible.
   */
  private fun sendBinder(uid: Int): IBinder? {
    val name = loadedModule.packageName
    val userId = uid / PER_USER_RANGE
    val authority = name + AUTHORITY_SUFFIX
    // Identifies our reference to the provider so it can be given back, which it never was. That
    // reference counts as a live client of the provider — `ContentProviderRecord`'s
    // `hasConnectionOrHandle` is `!connections.isEmpty() || hasExternalProcessHandles()`, and the
    // second half counts external references with and without a token — and the platform draws two
    // conclusions from a live client.
    //
    // The host is pinned. `OomAdjuster.computeOomAdjLSP` raises a process publishing such a
    // provider to FOREGROUND_APP_ADJ and PROCESS_STATE_IMPORTANT_FOREGROUND, recorded as
    // `adjType=ext-provider`. So this held every module app on the device at foreground priority
    // for as long as it lived, never cached and never trimmed — and a uid kept out of the
    // background is a uid that keeps being reported active, which is what wakes the delivery again.
    //
    // And the host is restarted. A process that dies while a provider of its is still launching is
    // restarted for it, `MAX_RETRY_COUNT` = 3 times per provider record, after which the record is
    // dropped and the platform gives up. Taking the reference again builds a fresh record with the
    // count back at zero, so re-acquiring on every uid callback is what turned the platform's
    // bounded retry into an unbounded one — see the throttle in [binderFailures].
    //
    // A real token rather than null also buys a death link: the platform builds a handle object
    // around it and releases the reference itself if we die. A null token is only a counter, with
    // nothing to link, which is why the old leak was permanent rather than merely long.
    val token = Binder()
    return runCatching {
          // The tag argument arrived in Q, replacing the three-argument form rather than
          // overloading it, so each side of that line is a NoSuchMethodError on the other. The
          // branch was in the Java daemon and was lost in the Kotlin rewrite (#597), which means
          // no modern module has been handed its service on 8.1 or 9 since — swallowed, because
          // the error lands in the runCatching below and reads as an ordinary failed delivery.
          val provider =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityManager?.getContentProviderExternal(authority, userId, token, "vector")
              } else {
                activityManager?.getContentProviderExternal(authority, userId, token)
              }?.provider

          if (provider == null) {
            Log.d(TAG, "No service provider for $name")
            return@runCatching null
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

          if (reply != null) {
            Log.d(TAG, "Sent module binder to $name")
            provider.asBinder()
          } else {
            Log.w(TAG, "Failed to send module binder to $name")
            null
          }
        }
        .onFailure { Log.w(TAG, "Failed to send module binder for uid $uid", it) }
        // Unconditionally, and not only when a provider came back. The platform registers the
        // external client *before* it waits for the app to publish, and the two returns that
        // matter here — the app died while launching, and the wait timed out — come after that
        // registration with the reference still held. Those are exactly the returns a module app
        // that dies on every start produces, so releasing only on success would have left the
        // restart loop this method exists to stop completely intact.
        //
        // Asking when nothing was registered is not free of consequence, only of harm. If the app
        // is not running there is no record and the platform returns quietly; if it is, the record
        // exists under this authority whether or not our acquire got as far as registering, and
        // the platform logs that something tried to remove an external reference it does not have.
        // A line in its log against the loop this stops is the right side of that trade.
        .also { releaseProvider(authority, token, userId) }
        .getOrNull()
  }

  /**
   * Gives back the reference [sendBinder] took, whatever became of the call in between.
   *
   * The user id has to be named, and can be from Q. The plain form is all that API 27 and 28 have,
   * and there the platform resolves the name against the *caller's* user, which is the daemon's:
   * a reference taken for a module in a secondary user cannot be given back at all, and asking
   * anyway would decrement the token-less counter of whatever record user 0 has under that name.
   * So on those two releases a secondary user's reference is left to the token instead — the
   * platform links the handle to the token's death, so the reference goes when the daemon does.
   */
  private fun releaseProvider(authority: String, token: Binder, userId: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && userId != 0) {
      Log.d(TAG, "Cannot release the reference for $authority in user $userId before Q")
      return
    }
    runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityManager?.removeContentProviderExternalAsUser(authority, token, userId)
          } else {
            activityManager?.removeContentProviderExternal(authority, token)
          }
        }
        .onFailure { Log.w(TAG, "Failed to release the provider reference for $authority", it) }
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

  /**
   * One request, one question, one answer.
   *
   * The AIDL hands over a list and takes a single [IXposedScopeCallback] for it, and the javadoc on
   * the client's `OnScopeEventListener` says its listener runs "when the request is completed" —
   * singular — with `onScopeRequestApproved` taking the *packages* that were approved. This used to
   * put one prompt per package on screen, each answered in its own right, so a module asking for
   * three packages made the user answer three questions and then fired that one listener three
   * times. A module that took the first answer as the answer acted on a third of it.
   *
   * So the whole list goes up as one prompt and Approve answers for all of it. What the user gives
   * away in one press is what the prompt lists, which is why it lists all of them rather than a
   * count, and why the packages are sorted and deduplicated first: it is a set that is being agreed
   * to, the same set asked for twice is the same question, and `NotificationManager` identifies a
   * prompt by the set it names.
   */
  override fun requestScope(packages: List<String>, callback: IXposedScopeCallback) {
    val userId = ensureModule()
    val requested = packages.distinct().sorted()
    if (requested.isEmpty()) {
      // Nothing was asked for, so the request is trivially satisfied. Returning without touching
      // the callback would leave the module waiting forever.
      callback.onScopeRequestApproved(emptyList())
      return
    }
    // A module that fixed its own scope in module.prop does not get to ask for more of it at
    // runtime. Prompting the user here would make "fixed" mean nothing.
    ConfigCache.staticScopeOf(loadedModule.packageName)?.let { claimed ->
      val beyond = requested.filterNot { claimed.contains(it) }
      if (beyond.isNotEmpty()) {
        callback.onScopeRequestFailed(
            "This module declares a static scope, so ${beyond.joinToString()} cannot be added")
        return
      }
    }
    if (!PreferenceStore.isScopeRequestBlocked(loadedModule.packageName)) {
      NotificationManager.requestModuleScope(loadedModule.packageName, userId, requested, callback)
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

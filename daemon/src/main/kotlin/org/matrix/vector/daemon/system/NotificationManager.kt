package org.matrix.vector.daemon.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.service.IXposedScopeCallback
import java.util.UUID
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.R
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.utils.FakeContext

private const val TAG = "VectorNotificationManager"
private const val STATUS_CHANNEL_ID = "vector_status"
private const val UPDATED_CHANNEL_ID = "vector_module_updated"
private const val STATUS_NOTIF_ID = BuildConfig.MANAGER_INJECTED_UID

/**
 * How long a scope request stays on screen before the platform takes it down for us and fires its
 * delete intent, which reports the timeout back to the module.
 *
 * An hour, as it was when the prompt was first written. A module that asked and was ignored gets a
 * definite answer eventually instead of a callback that never fires.
 */
private const val SCOPE_REQUEST_TIMEOUT_MS = 60L * 60 * 1000

/**
 * How many prompts one module may have waiting for an answer at once.
 *
 * Nothing else bounds them: these are enqueued as "android", which NotificationManagerService
 * exempts from its per-package limit, and they are IMPORTANCE_HIGH, so a module calling
 * `IXposedService.requestScope` in a loop would get a heads-up prompt per call, each sitting for
 * [SCOPE_REQUEST_TIMEOUT_MS]. It is a *call* that costs a place, not a package: one request is one
 * prompt however many packages it names, which is what makes a single Approve able to answer for
 * all of them.
 *
 * Sixteen because it is far above anything an honest module asks for in one go, and low enough that
 * the worst a module can do to the shade is a screenful. It bounds what is *unanswered*, not what
 * may be asked over time: answering a prompt frees its place at once, so a module that asks a few
 * questions and waits for them never meets it. A module that does meet it is told so rather than
 * left waiting, because a callback that never fires is the failure this whole path exists to avoid.
 */
private const val MAX_OPEN_SCOPE_REQUESTS_PER_MODULE = 16

object NotificationManager {
  val openManagerAction = UUID.randomUUID().toString()
  val moduleScopeAction = UUID.randomUUID().toString()

  val SCOPE_CHANNEL_ID = "vector_module_scope"

  private val nm: android.app.INotificationManager? by
      SystemService(
          Context.NOTIFICATION_SERVICE, android.app.INotificationManager.Stub::asInterface)
  private val opPkg =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "android" else "com.android.settings"

  private fun createChannels() {
    val context = FakeContext()
    val list =
        listOf(
            NotificationChannel(
                    STATUS_CHANNEL_ID,
                    context.getString(R.string.status_channel_name),
                    android.app.NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
            NotificationChannel(
                    UPDATED_CHANNEL_ID,
                    context.getString(R.string.module_updated_channel_name),
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                .apply { setShowBadge(false) },
            NotificationChannel(
                    SCOPE_CHANNEL_ID,
                    context.getString(R.string.scope_channel_name),
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                .apply { setShowBadge(false) })
    runCatching {
          nm?.createNotificationChannelsForPackage(
              "android", 1000, android.content.pm.ParceledListSlice(list))
        }
        .onFailure { Log.e(TAG, "Failed to create notification channels", it) }
  }

  private fun getBitmap(id: Int): Bitmap {
    val r = FileSystem.resources
    var res = r.getDrawable(id, r.newTheme())
    if (res is BitmapDrawable) {
      return res.bitmap
    } else {
      if (res is AdaptiveIconDrawable) {
        res = LayerDrawable(arrayOf(res.background, res.foreground))
      }
      val bitmap =
          Bitmap.createBitmap(res.intrinsicWidth, res.intrinsicHeight, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      res.setBounds(0, 0, canvas.width, canvas.height)
      res.draw(canvas)
      return bitmap
    }
  }

  private fun getNotificationIcon(): Icon {
    return Icon.createWithBitmap(getBitmap(R.drawable.ic_statue_monochrome))
  }

  fun notifyStatusNotification() {
    val context = FakeContext()
    val intent = Intent(openManagerAction).apply { setPackage("android") }
    val pi =
        PendingIntent.getBroadcast(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val notif =
        Notification.Builder(context, STATUS_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.vector_running_notification_title))
            .setContentText(context.getString(R.string.vector_running_notification_content))
            .setSmallIcon(getNotificationIcon())
            .setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
            .apply { extras.putString("android.substName", BuildConfig.FRAMEWORK_NAME) }

    createChannels()
    runCatching {
      nm?.enqueueNotificationWithTag("android", opPkg, null, STATUS_NOTIF_ID, notif, 0)
    }
  }

  fun cancelStatusNotification() {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        nm?.cancelNotificationWithTag("android", "android", null, STATUS_NOTIF_ID, 0)
      } else {
        nm?.cancelNotificationWithTag("android", null, STATUS_NOTIF_ID, 0)
      }
    }
  }

  /**
   * What tells one scope request apart from another.
   *
   * The platform identifies a notification by (package, tag, id, user), and everything posted here
   * is posted as "android", so the tag and the id are the only room we have. Tagging with the
   * module package alone meant a module that asked for three packages posted three notifications
   * that each replaced the one before it: only the last request was ever answerable, the earlier
   * ones were never granted and their callbacks were never called at all. One module running under
   * two users collided in exactly the same way.
   *
   * The requested packages are named as a set rather than one at a time, because one call to
   * `requestScope` is one prompt. Canonical order is the caller's job — see `ModuleAppService`,
   * which sorts — so that the same request asked twice lands on the same tag and replaces its own
   * prompt instead of stacking a second copy of the same question.
   */
  private fun scopeTag(modulePkg: String, moduleUserId: Int, scopePkgs: List<String>) =
      "$modulePkg:$moduleUserId:${scopePkgs.joinToString(",")}"

  /** Cancels what we posted under [tag]; the id is derived from it exactly as it is on enqueue. */
  private fun cancelByTag(tag: String) {
    runCatching {
          val notifId = tag.hashCode()
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            nm?.cancelNotificationWithTag("android", "android", tag, notifId, 0)
          } else {
            nm?.cancelNotificationWithTag("android", tag, notifId, 0)
          }
        }
        .onFailure { Log.e(TAG, "Failed to cancel notification $tag", it) }
  }

  /**
   * Takes down the prompt for one (module, user, requested set) once it has been answered.
   *
   * It has to name the requested set, because a module that asked twice for different sets has a
   * prompt for each and answering one of them must not clear the other.
   */
  fun cancelScopeRequest(modulePkg: String, moduleUserId: Int, scopePkgs: List<String>) =
      cancelByTag(scopeTag(modulePkg, moduleUserId, scopePkgs))

  /**
   * The "not activated yet" half of [notifyModuleUpdated], which is the half that can go stale.
   *
   * Only the package, because the one place that knows the notice has become wrong —
   * `ModuleDatabase.enableModule`, reached from the manager, the socket CLI and a backup restore —
   * is given a package name and nothing else.
   */
  private fun notActivatedTag(modulePkg: String) = "$modulePkg:not-activated"

  /**
   * Takes down the "module is not activated yet" notice for [modulePkg], because it now is.
   *
   * Deliberately not the *other* thing [notifyModuleUpdated] posts. "Module updated, force stop and
   * restart the apps in its scope" is still true after the module has been enabled — nothing in the
   * daemon knows whether the user has restarted those apps — so it is left alone, and the two are
   * kept under separate tags so that this cancel cannot reach it. Tagging both the same way meant
   * editing a module's scope silently erased a restart reminder the user had not acted on yet.
   */
  fun cancelModuleUpdated(modulePkg: String) = cancelByTag(notActivatedTag(modulePkg))

  /**
   * The scope prompts that are on screen and still unanswered, oldest first.
   *
   * Bookkeeping for the receiver, but it lives beside the prompt because only what was posted can
   * be answered and only the poster knows what that is. Two things need it.
   *
   * One prompt reaches the receiver from four places — its three buttons and its delete intent —
   * and a swipe or the one-hour timeout fires the delete intent whether or not a button was pressed
   * first. Claiming here is what makes the first arrival the one that answers, so a module cannot
   * be told its request was approved and then that it timed out.
   *
   * And "never ask again" has to be able to take down the module's *other* prompts, which it can
   * only do if something remembers they are up.
   *
   * Bounded twice, and neither bound may be silent. Nothing in here is ever "long abandoned": every
   * prompt carries [SCOPE_REQUEST_TIMEOUT_MS], whose expiry fires the delete intent and claims the
   * entry, so an entry that is still here is a live question in front of the user. Losing one
   * without answering it is therefore not harmless — [claim] would refuse its buttons, so Approve
   * would write nothing, "never ask again" would block nothing and the module would be told
   * nothing, all while the notification stayed on screen for up to an hour. So [post] hands back
   * whatever it had to give up, and the caller takes it off the screen and fails its callback.
   *
   * [MAX_OPEN_SCOPE_REQUESTS_PER_MODULE] is the bound that does the work, and it refuses the *new*
   * prompt rather than dropping an old one, because the questions already in front of the user are
   * the ones worth keeping. [MAX_ENTRIES] is only a backstop against an unbounded number of
   * modules in a daemon that runs as long as the device does; it sits far above the per-module
   * ceiling precisely so that one module's burst cannot reach as far as another module's prompts,
   * which is what a shared sixty-four entries let it do.
   */
  private object OutstandingScopeRequests {
    private const val MAX_ENTRIES = 512

    /** One live question, and when it was asked; see [countOf] for what the time is for. */
    private class Open(val callback: IXposedScopeCallback, val postedAt: Long)

    private val open = LinkedHashMap<String, Open>()

    /** A prompt that left [open] unanswered: it has to come off the screen and be told so. */
    class Abandoned(val tag: String, val callback: IXposedScopeCallback)

    /**
     * How many prompts [modulePkg] still has open, under any user. The ':' matters here for the
     * same reason it does in [claimAllOf].
     *
     * Anything older than [SCOPE_REQUEST_TIMEOUT_MS] is not counted, because by then the platform
     * has taken the prompt down whatever else happened to it. An entry is removed when its prompt
     * is answered or dismissed, and both of those reach us — but neither is guaranteed: an enqueue
     * the platform drops, or a cancellation that never fires the delete intent, leaves a question
     * nobody will ever answer holding one of the module's places. Without the age, a module could
     * be locked out of asking for the rest of the boot by prompts that are not on screen. The entry
     * itself is left alone: if its buttons somehow still arrive, they should still work.
     */
    private fun countOf(modulePkg: String): Int {
      val stillOnScreen = SystemClock.elapsedRealtime() - SCOPE_REQUEST_TIMEOUT_MS
      return open.count { (tag, entry) ->
        tag.startsWith("$modulePkg:") && entry.postedAt > stillOnScreen
      }
    }

    /**
     * Registers the prompt for [tag] before it goes up.
     *
     * @return the prompts that had to be given up to make room, which the caller must abandon with
     *   no lock held, or null when [tag] is the one that must not go up at all because its module
     *   is already holding [MAX_OPEN_SCOPE_REQUESTS_PER_MODULE] unanswered prompts.
     */
    @Synchronized
    fun post(tag: String, callback: IXposedScopeCallback): List<Abandoned>? {
      // Re-posting under a tag replaces what was there: a prompt going up is unanswered by
      // definition, whatever became of the last one that asked the same thing. The one it replaces
      // is dropped without an answer on purpose — it is the same question, of the same module,
      // still on screen under the same tag, and telling the module its request failed would fire
      // the listener it is about to be asked with. For the same reason it does not count as a new
      // prompt against the ceiling: the shade gains nothing.
      val replaced = open.remove(tag) != null
      // A package name cannot contain a ':', so the first segment of the tag is the module the
      // ceiling belongs to. Per module rather than per (module, user), because all of these are
      // enqueued into the same shade whichever user the module runs as.
      val modulePkg = tag.substringBefore(':')
      if (!replaced && countOf(modulePkg) >= MAX_OPEN_SCOPE_REQUESTS_PER_MODULE) return null
      open[tag] = Open(callback, SystemClock.elapsedRealtime())
      val abandoned = mutableListOf<Abandoned>()
      while (open.size > MAX_ENTRIES) {
        val oldest = open.keys.first()
        abandoned += Abandoned(oldest, open.remove(oldest)!!.callback)
      }
      return abandoned
    }

    /** Takes the right to answer one prompt; null when something already has. */
    @Synchronized fun claim(tag: String): IXposedScopeCallback? = open.remove(tag)?.callback

    /** Takes every prompt [modulePkg] still has up, in one go, so nothing can answer them after. */
    @Synchronized
    fun claimAllOf(modulePkg: String): Map<String, IXposedScopeCallback> {
      // The ':' matters: without it "com.foo" would claim the prompts of "com.foobar" as well.
      val mine = open.filterKeys { it.startsWith("$modulePkg:") }
      mine.keys.forEach { open.remove(it) }
      return mine.mapValues { it.value.callback }
    }
  }

  /**
   * Claims the right to answer the prompt for one (module, user, requested set).
   *
   * @return true for the first caller, false for every later one — the module's
   *   [IXposedScopeCallback] must be called exactly once per request.
   */
  fun claimScopeAnswer(modulePkg: String, moduleUserId: Int, scopePkgs: List<String>) =
      OutstandingScopeRequests.claim(scopeTag(modulePkg, moduleUserId, scopePkgs)) != null

  /**
   * Withdraws every prompt [modulePkg] still has on screen and hands back their callbacks, so the
   * caller can tell each of those requests it will not be granted.
   *
   * What makes "never ask again" mean what it says. A module that asked three times has a prompt
   * for each of those requests; answering the user's "stop asking" by leaving two more questions on
   * screen — both still approvable — would be answering it with the opposite.
   *
   * They are claimed before they are cancelled, and that order is load-bearing, though not for the
   * reason this comment used to give: a cancel we ask for ourselves never fires the delete intent.
   * `cancelNotificationWithTag` ends in NotificationManagerService's `cancelNotification` with its
   * `sendDelete` argument hard-coded false and the reason `REASON_APP_CANCEL`; only a user's swipe
   * (`onNotificationClear`) and the `setTimeoutAfter` expiry (`REASON_TIMEOUT`) pass `sendDelete`
   * true. What the ordering really defends against is the window the cancel itself opens: it is a
   * binder round trip into system_server that only *schedules* the removal on a handler, so these
   * prompts keep live Approve, Deny and "never ask again" buttons for a while after we have asked
   * for them to go — a tap already in flight, or a swipe or the hourly timeout landing at the same
   * moment, would otherwise answer a request we are in the middle of withdrawing. Claiming first
   * makes every one of those arrive at a closed door.
   *
   * Each withdrawn request is handed back on its own, and that is now one failure per
   * `requestScope` call rather than one per package. It used to be per package, which put a module
   * in an awkward spot: `requestScope` supplies one callback for the whole list, those failures all
   * landed on the same binder, and the shipped client library does not collapse them — its
   * `OnScopeEventListener` wrapper calls the listener every time and merely drops its map entry
   * afterwards, so a module written to the singular javadoc ("invoked when the request is
   * completed") ran its handler once per package. Batching the prompt is what fixed that, here and
   * everywhere else on this path: the answer is now shaped like the question the module asked.
   */
  fun withdrawScopeRequests(modulePkg: String): List<IXposedScopeCallback> {
    val withdrawn = OutstandingScopeRequests.claimAllOf(modulePkg)
    withdrawn.keys.forEach { cancelByTag(it) }
    return withdrawn.values.toList()
  }

  /**
   * Takes a prompt off the screen and tells its module that request is over, for the cases where
   * nobody asked us to: it was given up to make room, or it never made it onto the screen at all.
   *
   * Both halves are binder calls — one into the notification manager, one into the module — so this
   * is deliberately reached with no lock of [OutstandingScopeRequests] held. The callback is
   * `oneway`, but the module it points at can be dead by now, and that must not stop the rest of a
   * batch from being cleaned up.
   */
  private fun abandonScopeRequest(dropped: OutstandingScopeRequests.Abandoned, reason: String) {
    cancelByTag(dropped.tag)
    runCatching { dropped.callback.onScopeRequestFailed(reason) }
        .onFailure { Log.w(TAG, "Could not tell ${dropped.tag} that its request was dropped", it) }
  }

  fun requestModuleScope(
      modulePkg: String,
      moduleUserId: Int,
      scopePkgs: List<String>,
      callback: IXposedScopeCallback
  ) {
    val tag = scopeTag(modulePkg, moduleUserId, scopePkgs)
    // Registered before the notification is built, let alone posted: the buttons are live from the
    // moment the platform accepts it, and a prompt the receiver does not know about is one whose
    // answer it drops. Registering is also what enforces the ceiling, so there is no point
    // rendering an icon for a prompt that is not going up.
    val abandoned = OutstandingScopeRequests.post(tag, callback)
    if (abandoned == null) {
      Log.w(
          TAG,
          "$modulePkg is already waiting on $MAX_OPEN_SCOPE_REQUESTS_PER_MODULE scope prompts;" +
              " not asking about ${scopePkgs.joinToString()}")
      // Refused, not ignored. The module is told rather than left holding a callback that can
      // never fire, and the message names what did not make it so a module developer can see it.
      runCatching {
            callback.onScopeRequestFailed(
                "Too many scope requests are already waiting for an answer from the user," +
                    " so ${scopePkgs.joinToString()} was not asked about")
          }
          .onFailure { Log.w(TAG, "Could not tell $modulePkg its request was refused", it) }
      return
    }
    abandoned.forEach {
      Log.w(TAG, "Giving up the scope prompt ${it.tag}: too many are open across all modules")
      abandonScopeRequest(
          it, "Scope request dropped: too many are waiting for an answer on this device")
    }

    val context = FakeContext()
    val userName = userManager?.getUserName(moduleUserId) ?: moduleUserId.toString()

    fun createActionIntent(actionParams: String, requestCode: Int): PendingIntent {
      val intent =
          Intent(moduleScopeAction).apply {
            setPackage("android")
            data =
                Uri.Builder()
                    .scheme("module")
                    .encodedAuthority("$modulePkg:$moduleUserId")
                    // The whole list, because one request is one prompt and one answer. ',' is a
                    // legal path character and cannot occur in a package name, so the receiver can
                    // split it back apart; it is also what keeps two requests naming different sets
                    // on separate PendingIntents, which are identified by their intent and not by
                    // the extras that carry the callback.
                    .encodedPath(scopePkgs.joinToString(","))
                    .appendQueryParameter("action", actionParams)
                    .build()
            putExtras(Bundle().apply { putBinder("callback", callback.asBinder()) })
          }
      return PendingIntent.getBroadcast(
          context,
          requestCode,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    val notif =
        Notification.Builder(context, SCOPE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.xposed_module_request_scope_title))
            .setContentText(
                context.getString(
                    R.string.xposed_module_request_scope_content,
                    modulePkg,
                    userName,
                    scopePkgs.joinToString()))
            .setSmallIcon(getNotificationIcon())
            .addAction(
                Notification.Action.Builder(
                        null,
                        context.getString(R.string.scope_approve),
                        createActionIntent("approve", 4))
                    .build())
            .addAction(
                Notification.Action.Builder(
                        null, context.getString(R.string.scope_deny), createActionIntent("deny", 5))
                    .build())
            .addAction(
                Notification.Action.Builder(
                        null,
                        context.getString(R.string.never_ask_again),
                        createActionIntent("block", 6))
                    .build())
            // Swiping the prompt away, or leaving it alone until it expires, has to answer the
            // module too. Without a delete intent the "delete" branch of dispatchModuleScope was
            // unreachable and a dismissed prompt left the module's IXposedScopeCallback waiting
            // for an answer that could no longer arrive from anywhere. It takes a request code of
            // its own because that is half of what identifies a PendingIntent: 4, 5 and 6 are the
            // buttons above, and 1 and 3 the status and module-updated notifications.
            .setDeleteIntent(createActionIntent("delete", 7))
            .setTimeoutAfter(SCOPE_REQUEST_TIMEOUT_MS)
            .setAutoCancel(true)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(
                        context.getString(
                            R.string.xposed_module_request_scope_content,
                            modulePkg,
                            userName,
                            // The whole list, wrapped over as many lines as it takes. Approve
                            // answers for all of it at once, so all of it is what the user is
                            // agreeing to; the collapsed line above is one line whatever we put in
                            // it, and this is where a request naming more packages than fit there
                            // becomes readable. Comma-separated rather than one per line because
                            // the string this fills is a sentence and the list sits mid-way
                            // through it.
                            scopePkgs.joinToString())))
            .build()
            .apply { extras.putString("android.substName", BuildConfig.FRAMEWORK_NAME) }

    createChannels()
    val enqueued =
        runCatching {
              val service = checkNotNull(nm) { "the notification manager is not available" }
              service.enqueueNotificationWithTag("android", opPkg, tag, tag.hashCode(), notif, 0)
            }
            .onFailure { Log.e(TAG, "Failed to post the scope prompt $tag", it) }
            .isSuccess
    if (!enqueued) {
      // The registration above outlives a failed enqueue, and it would then hold one of the
      // module's places against a prompt that is not on screen and that nothing can ever answer —
      // a module could wedge itself out of asking again on the strength of prompts the user never
      // saw. Give the place back and tell the module instead.
      OutstandingScopeRequests.claim(tag)?.let { pending ->
        runCatching { pending.onScopeRequestFailed("Could not show the scope request") }
      }
    }
  }

  fun notifyModuleUpdated(
      modulePackageName: String,
      moduleUserId: Int,
      enabled: Boolean,
      systemModule: Boolean
  ) {
    val context = FakeContext()
    val userName = userManager?.getUserName(moduleUserId) ?: moduleUserId.toString()

    val title =
        context.getString(
            if (enabled) {
              if (systemModule) R.string.xposed_module_updated_notification_title_system
              else R.string.xposed_module_updated_notification_title
            } else R.string.module_is_not_activated_yet)

    val content =
        context.getString(
            if (enabled) {
              if (systemModule) R.string.xposed_module_updated_notification_content_system
              else R.string.xposed_module_updated_notification_content
            } else {
              if (moduleUserId == 0) R.string.module_is_not_activated_yet_main_user_detailed
              else R.string.module_is_not_activated_yet_multi_user_detailed
            },
            modulePackageName,
            userName)

    // Which user the link opens, which is not necessarily the one whose update raised this.
    //
    // Every notice this function posts is enqueued for user 0 — see the call at the end — so the
    // reader tapping one is standing in user 0 whichever user's PACKAGE_REPLACED fired it. A link
    // naming a secondary user therefore lands them somewhere they cannot see: a module installed
    // in a private space as well as the main user raised two of these, the tag is the package
    // alone so the second overwrote the first, and the survivor pointed into a profile that is
    // locked more often than not. The scope editor then listed that profile's apps, which for a
    // locked private space is nothing at all, and blamed a filter nobody had set.
    //
    // Preferring user 0 gives up nothing, because this id does not choose a configuration. A
    // module is one package and one APK for the whole device with one scope set and one enabled
    // flag; what the id selects is only which user's apps are offered as targets. So the same
    // rule applies to the "not activated yet" half, where the act waiting to be done — turning
    // the module on — is likewise device-wide.
    //
    // The triggering user is kept when the module is not in user 0 at all, which is a module
    // that lives only in a secondary profile. There is no better answer for that one, and the
    // notice is at least still about somewhere the module exists.
    val linkUserId =
        if (packageManager?.isPackageAvailable(modulePackageName, 0, true) == true) 0
        else moduleUserId

    val intent =
        Intent(openManagerAction).apply {
          setPackage("android")
          data =
              Uri.Builder()
                  .scheme("module")
                  .encodedAuthority("$modulePackageName:$linkUserId")
                  .build()
        }
    val pi =
        PendingIntent.getBroadcast(
            context, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val notif =
        Notification.Builder(context, UPDATED_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(getNotificationIcon())
            .setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .build()
            .apply { extras.putString("android.substName", BuildConfig.FRAMEWORK_NAME) }

    createChannels()
    // The two notices this function posts are told apart, because only one of them can be made
    // wrong by something the user does later: "not activated yet" stops being true the moment the
    // module is activated, and [cancelModuleUpdated] takes it down from there; "force stop the apps
    // in its scope" stays true until the user does it, which nothing here can observe. One tag for
    // both meant activating a module also erased the restart reminder.
    //
    // Neither carries the user id, and that is deliberate: the cancel is reached from
    // ModuleDatabase.enableModule, which is given a package name and nothing more. The price is
    // that a module installed for two users shows one notice rather than two — which is what it did
    // before this as well. The collision with the scope prompt is gone either way, now that a scope
    // tag carries its ":user:target" suffix.
    //
    // What that price used to include, and no longer does: the two raisings overwrite each other,
    // so the surviving notice carried whichever user's link happened to be written last. That is
    // what [linkUserId] above is for — the tag stays user-free and the destination stops depending
    // on the order two broadcasts arrived in.
    val tag = if (enabled) modulePackageName else notActivatedTag(modulePackageName)
    runCatching {
      nm?.enqueueNotificationWithTag("android", opPkg, tag, tag.hashCode(), notif, 0)
    }
  }
}

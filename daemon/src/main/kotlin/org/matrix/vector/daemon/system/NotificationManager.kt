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
   */
  private fun scopeTag(modulePkg: String, moduleUserId: Int, scopePkg: String) =
      "$modulePkg:$moduleUserId:$scopePkg"

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
   * Takes down the prompt for one (module, user, requested package) once it has been answered.
   *
   * It has to name the requested package, because a module asking for several has one prompt per
   * package and answering one of them must not clear the rest.
   */
  fun cancelScopeRequest(modulePkg: String, moduleUserId: Int, scopePkg: String) =
      cancelByTag(scopeTag(modulePkg, moduleUserId, scopePkg))

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
   * Bounded because this daemon runs for as long as the device does and a module may ask as often
   * as it likes. Sixty-four is far more than can plausibly be on screen at once, and evicting the
   * oldest only makes a long-abandoned prompt unanswerable, which is what it already is.
   */
  private object OutstandingScopeRequests {
    private const val MAX_ENTRIES = 64
    private val open = LinkedHashMap<String, IXposedScopeCallback>()

    @Synchronized
    fun post(tag: String, callback: IXposedScopeCallback) {
      // Re-posting under a tag replaces what was there: a prompt going up is unanswered by
      // definition, whatever became of the last one that asked the same thing.
      open.remove(tag)
      open[tag] = callback
      while (open.size > MAX_ENTRIES) open.remove(open.keys.first())
    }

    /** Takes the right to answer one prompt; null when something already has. */
    @Synchronized fun claim(tag: String): IXposedScopeCallback? = open.remove(tag)

    /** Takes every prompt [modulePkg] still has up, in one go, so nothing can answer them after. */
    @Synchronized
    fun claimAllOf(modulePkg: String): Map<String, IXposedScopeCallback> {
      // The ':' matters: without it "com.foo" would claim the prompts of "com.foobar" as well.
      val mine = open.filterKeys { it.startsWith("$modulePkg:") }
      mine.keys.forEach { open.remove(it) }
      return mine
    }
  }

  /**
   * Claims the right to answer the prompt for one (module, user, requested package).
   *
   * @return true for the first caller, false for every later one — the module's
   *   [IXposedScopeCallback] must be called exactly once per request.
   */
  fun claimScopeAnswer(modulePkg: String, moduleUserId: Int, scopePkg: String) =
      OutstandingScopeRequests.claim(scopeTag(modulePkg, moduleUserId, scopePkg)) != null

  /**
   * Withdraws every prompt [modulePkg] still has on screen and hands back their callbacks, so the
   * caller can tell each of those requests it will not be granted.
   *
   * What makes "never ask again" mean what it says. A module that asked for three packages now has
   * a prompt for each of them; answering the user's "stop asking" by leaving two more questions on
   * screen — both still approvable — would be answering it with the opposite. They are claimed
   * before they are cancelled, so a delete intent arriving from the cancel cannot answer them a
   * second time.
   */
  fun withdrawScopeRequests(modulePkg: String): List<IXposedScopeCallback> {
    val withdrawn = OutstandingScopeRequests.claimAllOf(modulePkg)
    withdrawn.keys.forEach { cancelByTag(it) }
    return withdrawn.values.toList()
  }

  fun requestModuleScope(
      modulePkg: String,
      moduleUserId: Int,
      scopePkg: String,
      callback: IXposedScopeCallback
  ) {
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
                    .encodedPath(scopePkg)
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
                    R.string.xposed_module_request_scope_content, modulePkg, userName, scopePkg))
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
                            scopePkg)))
            .build()
            .apply { extras.putString("android.substName", BuildConfig.FRAMEWORK_NAME) }

    createChannels()
    val tag = scopeTag(modulePkg, moduleUserId, scopePkg)
    // Registered before it is posted, not after: the buttons are live from the moment the platform
    // accepts the notification, and a prompt the receiver does not know about is one whose answer
    // it drops.
    OutstandingScopeRequests.post(tag, callback)
    runCatching { nm?.enqueueNotificationWithTag("android", opPkg, tag, tag.hashCode(), notif, 0) }
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

    val intent =
        Intent(openManagerAction).apply {
          setPackage("android")
          data =
              Uri.Builder()
                  .scheme("module")
                  .encodedAuthority("$modulePackageName:$moduleUserId")
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
    val tag = if (enabled) modulePackageName else notActivatedTag(modulePackageName)
    runCatching {
      nm?.enqueueNotificationWithTag("android", opPkg, tag, tag.hashCode(), notif, 0)
    }
  }
}

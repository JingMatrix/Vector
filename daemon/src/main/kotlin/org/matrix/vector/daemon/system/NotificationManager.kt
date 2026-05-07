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
    return Icon.createWithBitmap(getBitmap(R.drawable.ic_notification))
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

  fun cancelNotification(channel: String, modulePkg: String, moduleUserId: Int) {
    runCatching {
          // We use the module package name's hash code as the notification ID
          // to match how we enqueued it in requestModuleScope and notifyModuleUpdated.
          val notifId = modulePkg.hashCode()

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            nm?.cancelNotificationWithTag("android", "android", modulePkg, notifId, 0)
          } else {
            nm?.cancelNotificationWithTag("android", modulePkg, notifId, 0)
          }
        }
        .onFailure { Log.e(TAG, "Failed to cancel notification", it) }
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
    runCatching {
      nm?.enqueueNotificationWithTag("android", opPkg, modulePkg, modulePkg.hashCode(), notif, 0)
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
    runCatching {
      nm?.enqueueNotificationWithTag(
          "android", opPkg, modulePackageName, modulePackageName.hashCode(), notif, 0)
    }
  }
}

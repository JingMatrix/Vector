package org.matrix.vector.manager.data.repository

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.manager.Constants
import org.matrix.vector.manager.R

/**
 * The launcher entry for a manager that is not installed.
 *
 * Vector normally runs *parasitically*: the manager APK is injected into `com.android.shell`, so
 * nothing about it is installed and the launcher has nothing to show. There is no icon to tap, and
 * short of dialling the secret code or going through the root manager's action button, no way to
 * open it at all — which is what #815 reported, and it is not a bug so much as a feature that was
 * never carried over when the manager was rewritten in Compose.
 *
 * A pinned shortcut is the answer the platform gives for this: the host publishes it, the launcher
 * keeps it, and it survives the manager not existing as a package. What it points at is an ordinary
 * activity of the host, marked with the [category] below — [ParasiticManagerSystemHooker] watches
 * `resolveActivity` for exactly that category and rewrites the resolution to run the manager's code
 * in its own process. Drop the category and the shortcut opens whatever the host would have opened.
 *
 * None of this applies once the manager is installed as an app, where the launcher has a real icon
 * of its own; every entry point here is guarded on [isParasitic].
 */
object LaunchShortcut {

    /**
     * Stable across versions, because the launcher keys the pinned copy on it.
     *
     * Changing this string does not move an existing shortcut, it orphans it: the old one stays on
     * the home screen pointing at whatever it was built with, and [update] can no longer find it.
     */
    private const val ID = "org.matrix.vector.manager.shortcut"

    /** What [ParasiticManagerSystemHooker] matches on to redirect the activity. */
    private val category = "${BuildConfig.MANAGER_PACKAGE_NAME}.LAUNCH_MANAGER"

    /** True when this manager is injected into the host rather than installed. */
    fun isParasitic(context: Context): Boolean =
        context.packageName == BuildConfig.INJECTED_PACKAGE_NAME

    /**
     * Whether the launcher accepts pin requests at all.
     *
     * Most do. Some third-party ones, and some very cut-down OEM ones, do not — and a button that
     * silently does nothing is worse than one that is not offered, so the caller asks first.
     */
    fun isSupported(context: Context): Boolean =
        runCatching { manager(context)?.isRequestPinShortcutSupported == true }
            .onFailure { Log.w(Constants.TAG, "actions: pin support query failed", it) }
            .getOrDefault(false)

    fun isPinned(context: Context): Boolean =
        runCatching { manager(context)?.pinnedShortcuts.orEmpty().any { it.id == ID } }
            .onFailure { Log.w(Constants.TAG, "actions: pinned shortcut query failed", it) }
            .getOrDefault(false)

    /**
     * Asks the launcher to pin the shortcut, calling [onPinned] if and when it does.
     *
     * Returns whether the request was *accepted*, which is not whether the shortcut was pinned: the
     * launcher puts its own dialog in front of the user and may take a while, or never come back at
     * all if they dismiss it. [onPinned] is the only report that it landed, and it is delivered
     * through a broadcast the platform sends — guarded by a permission no ordinary app holds, so a
     * third party cannot forge the confirmation.
     */
    fun request(context: Context, onPinned: () -> Unit): Boolean {
        if (!isParasitic(context)) return false
        val shortcut = build(context) ?: return false
        return runCatching {
                manager(context)?.requestPinShortcut(shortcut, callback(context, onPinned)) == true
            }
            .onFailure { Log.e(Constants.TAG, "actions: pin shortcut request failed", it) }
            .getOrDefault(false)
    }

    /**
     * Refreshes a shortcut that is already on the home screen.
     *
     * The label and the icon are copied into the launcher when the shortcut is pinned, so a manager
     * that changes either would otherwise be represented by the previous build's for as long as the
     * shortcut lives. A no-op when nothing is pinned.
     */
    fun update(context: Context) {
        if (!isParasitic(context) || !isPinned(context)) return
        val shortcut = build(context) ?: return
        runCatching { manager(context)?.updateShortcuts(listOf(shortcut)) }
            .onFailure { Log.w(Constants.TAG, "actions: pinned shortcut update failed", it) }
    }

    private fun manager(context: Context): ShortcutManager? =
        context.getSystemService(ShortcutManager::class.java)

    private fun build(context: Context): ShortcutInfo? {
        val intent = launchIntent(context) ?: return null
        val builder =
            ShortcutInfo.Builder(context, ID)
                .setShortLabel(context.getString(R.string.app_name))
                .setIntent(intent)
        icon(context)?.let { builder.setIcon(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Q and later want the publishing activity named, and the host has no launcher entry to
            // name. AppDetailsActivity is the one the platform synthesises for precisely that case
            // — every package has it, and it is what the system itself uses to stand for a package
            // with nothing to launch.
            builder.setActivity(ComponentName(context.packageName, APP_DETAILS_ACTIVITY))
        }
        return builder.build()
    }

    /**
     * An activity of the host, tagged so the framework turns it into the manager.
     *
     * The host is `com.android.shell`, which has no launcher entry, so the usual
     * `getLaunchIntentForPackage` answers null and the fallback picks any activity that runs in the
     * package's own process. Which one hardly matters: the resolution is intercepted before it is
     * used. What matters is that the intent resolves to *something* in the host package, because
     * the hook only rewrites a result that came back pointing there.
     */
    private fun launchIntent(context: Context): Intent? {
        val pm = context.packageManager
        val pkg = context.packageName
        val intent =
            pm.getLaunchIntentForPackage(pkg)
                ?: runCatching {
                        pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                            .activities
                            ?.firstOrNull { it.processName == it.packageName }
                            ?.let {
                                Intent(Intent.ACTION_MAIN)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .setComponent(ComponentName(pkg, it.name))
                            }
                    }
                    .onFailure { Log.e(Constants.TAG, "actions: no host activity to point at", it) }
                    .getOrNull()
                ?: return null

        // CATEGORY_LAUNCHER and the rest belong to whatever the host activity was for. Left on,
        // they are matched against the manager's own filter after redirection and can fail it.
        intent.categories?.clear()
        intent.addCategory(category)
        intent.setPackage(pkg)
        return intent
    }

    /**
     * The app icon, flattened for the launcher.
     *
     * `Icon.createWithResource` would name a resource in the *host's* package, where it does not
     * exist — parasitically this app's resources are loaded from an APK the system knows nothing
     * about. The bitmap has to be rendered here and shipped by value.
     */
    private fun icon(context: Context): Icon? =
        runCatching {
                val drawable =
                    ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                        ?: return@runCatching null
                if (drawable is BitmapDrawable) {
                    return@runCatching Icon.createWithAdaptiveBitmap(drawable.bitmap)
                }

                // An adaptive icon draws nothing through `Drawable.draw` until it is given bounds,
                // and reports its layers separately; stacking them keeps the mask off, which is
                // what `createWithAdaptiveBitmap` wants — the launcher applies its own.
                val flat: Drawable =
                    if (drawable is AdaptiveIconDrawable)
                        LayerDrawable(
                            listOfNotNull(drawable.background, drawable.foreground).toTypedArray()
                        )
                    else drawable
                val width = flat.intrinsicWidth.takeIf { it > 0 } ?: ICON_FALLBACK_PX
                val height = flat.intrinsicHeight.takeIf { it > 0 } ?: ICON_FALLBACK_PX
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                flat.setBounds(0, 0, canvas.width, canvas.height)
                flat.draw(canvas)
                Icon.createWithAdaptiveBitmap(bitmap)
            }
            .onFailure { Log.w(Constants.TAG, "actions: shortcut icon could not be rendered", it) }
            .getOrNull()

    /**
     * A one-shot receiver the platform pings once the launcher has pinned the shortcut.
     *
     * The action is a fresh UUID rather than a constant, so two requests cannot answer each other,
     * and the receiver requires `CREATE_USERS` of the sender: that permission is held by the system
     * and by nothing a user can install, which makes the broadcast unforgeable by a third party
     * that has guessed the action. It unregisters itself on the first matching delivery.
     */
    @SuppressLint("InlinedApi") // RECEIVER_EXPORTED is a constant; the flags overload is API 26.
    private fun callback(context: Context, onPinned: () -> Unit): IntentSender? =
        runCatching {
                val action = UUID.randomUUID().toString()
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(received: Context, intent: Intent) {
                            if (intent.action != action) return
                            context.unregisterReceiver(this)
                            onPinned()
                        }
                    }
                context.registerReceiver(
                    receiver,
                    IntentFilter(action),
                    CONFIRMATION_PERMISSION,
                    null, // the main thread
                    Context.RECEIVER_EXPORTED,
                )
                PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(action),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    .intentSender
            }
            .onFailure { Log.w(Constants.TAG, "actions: pin confirmation receiver failed", it) }
            .getOrNull()
}

/** The synthesised entry every package has, used as the shortcut's publishing activity on Q+. */
private const val APP_DETAILS_ACTIVITY = "android.app.AppDetailsActivity"

/** Held by the system and by nothing installable, so only the platform can confirm a pin. */
private const val CONFIRMATION_PERMISSION = "android.permission.CREATE_USERS"

/** Only reached by a drawable that reports no intrinsic size; an adaptive icon always does. */
private const val ICON_FALLBACK_PX = 108

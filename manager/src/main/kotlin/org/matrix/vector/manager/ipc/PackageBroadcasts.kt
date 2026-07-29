package org.matrix.vector.manager.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.manager.logW
import org.matrix.vector.manager.data.model.PER_USER_RANGE

sealed class PackageEvent {
    data class Added(val packageName: String, val userId: Int) : PackageEvent()

    data class Removed(val packageName: String, val userId: Int, val fullyRemoved: Boolean) :
        PackageEvent()

    data class Changed(val packageName: String, val userId: Int) : PackageEvent()
}

/**
 * Package installs, removals and updates, as a flow.
 *
 * The receiver exists only while the flow is collected. `ServiceLocator` collects it on a scope that
 * lasts as long as the process, which is what keeps the manager's lists from going stale.
 *
 * This process's own user and nobody else's — an install in a work profile or a secondary user is
 * never delivered here. Those arrive through [daemonPackageEventsFlow], which is collected
 * alongside this one.
 */
fun Context.packageEventsFlow(): Flow<PackageEvent> = callbackFlow {
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                // The uid these broadcasts carry names the same user, so it stands in for a
                // sender that leaves the id out.
                val userId =
                    intent.getIntExtra(
                        EXTRA_USER_HANDLE,
                        intent.getIntExtra(Intent.EXTRA_UID, 0) / PER_USER_RANGE,
                    )

                when (intent.action) {
                    // An update to an existing package produces a REMOVED for the old copy, an
                    // ADDED carrying EXTRA_REPLACING, and a REPLACED of its own. The last two say
                    // the same thing — the package is installed now — so both map to Added, and
                    // the duplicate costs a collector nothing beyond a repeated invalidation.
                    Intent.ACTION_PACKAGE_REPLACED,
                    Intent.ACTION_PACKAGE_ADDED -> {
                        trySend(PackageEvent.Added(packageName, userId))
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        val fullyRemoved = intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)
                        trySend(PackageEvent.Removed(packageName, userId, fullyRemoved))
                    }
                    Intent.ACTION_PACKAGE_CHANGED -> {
                        trySend(PackageEvent.Changed(packageName, userId))
                    }
                }
            }
        }

    val filter =
        IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }

    registerReceiver(receiver, filter)

    awaitClose { unregisterReceiver(receiver) }
}

/**
 * The same events as [packageEventsFlow], but for every user on the device.
 *
 * A dynamic receiver hears its own user's package broadcasts and nobody else's; the rest take
 * `registerReceiverForAllUsers` and `INTERACT_ACROSS_USERS`. The parasitic host holds those, being
 * `com.android.shell`; a standalone install holds neither, so a receiver built on them would work
 * in one of the two shapes this app runs in and not the other. An app installed into a work profile
 * or a secondary user therefore reached the manager not at all — nothing dropped the all-users app
 * list, so the scope editor for a module in that profile showed every app except the one that had
 * just been installed, and no refresh anywhere would ever have brought it in.
 *
 * The daemon registers its own package receiver for `USER_ALL` and re-broadcasts what it saw to
 * both packages the manager can be running as. This is that re-broadcast, and it is the only way
 * another user's installs are heard about here.
 */
fun Context.daemonPackageEventsFlow(): Flow<PackageEvent> = callbackFlow {
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Extras from a stranger are not guaranteed to unparcel in this process — one
                // naming a class the manager does not have throws right here, on the main thread,
                // and kills the manager rather than whoever sent it. Since the registration below
                // has to accept strangers, reading them is guarded.
                val event =
                    runCatching { intent.daemonPackageEvent() }
                        .onFailure {
                            logW("ipc: unreadable package notification", it)
                        }
                        .getOrNull()
                if (event != null) trySend(event)
            }
        }

    // Exported, and it has to be: the sender is the daemon, running as another uid, and since
    // SDK 34 a dynamic receiver registered with neither export flag throws at registration. The
    // action is guessable and no permission is demanded of the sender, so any app on the device
    // can forge one of these. That is acceptable for exactly one reason: a delivery makes the
    // manager drop a cache and ask the daemon for it again, and nothing else. Nothing is believed
    // on the strength of the payload — the collector does not even read which package an event
    // names, and the "isXposedModule" flag the daemon also sends is deliberately never read here,
    // because whether a package is a module is settled by inspecting its APK.
    val registered =
        runCatching {
                ContextCompat.registerReceiver(
                    this@daemonPackageEventsFlow,
                    receiver,
                    IntentFilter(ACTION_DAEMON_NOTIFICATION),
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }
            // Throwing here would fail this flow, and the merge it is collected in would take the
            // platform source down with it — losing every user's package events, on a scope whose
            // failure ends the process, to save the one this flow adds.
            .onFailure { logW("ipc: daemon package notifications unavailable", it) }
            .isSuccess

    awaitClose { if (registered) unregisterReceiver(receiver) }
}

/**
 * The daemon's notification, read back with the types it was actually written with.
 *
 * Not one of these extras can be read with the accessor its name implies, which is worth knowing
 * before someone corrects it: the package arrives as a single `String` under
 * [Intent.EXTRA_PACKAGES], whose documented type is `String[]`, so `getStringArrayExtra` answers
 * null, and the user arrives as a plain `Int` under [Intent.EXTRA_USER], whose documented type is
 * `UserHandle`, so `getParcelableExtra` answers null and the id quietly becomes 0 — the one user
 * this whole flow exists to see past. What the platform sent is wrapped whole under
 * [Intent.EXTRA_INTENT], and only its action says what happened.
 */
private fun Intent.daemonPackageEvent(): PackageEvent? {
    val packageName = getStringExtra(Intent.EXTRA_PACKAGES) ?: return null
    val userId = getIntExtra(Intent.EXTRA_USER, 0)
    val wrapped = IntentCompat.getParcelableExtra(this, Intent.EXTRA_INTENT, Intent::class.java)

    return when (wrapped?.action) {
        Intent.ACTION_PACKAGE_ADDED -> PackageEvent.Added(packageName, userId)
        Intent.ACTION_PACKAGE_CHANGED -> PackageEvent.Changed(packageName, userId)
        // Both removals the daemon forwards mean the package is really gone: the transient
        // PACKAGE_REMOVED an update produces is not among the actions it listens for, and
        // UID_REMOVED arrives once the uid itself has been reclaimed. Neither is the removal half
        // of an update, which is the only thing `fullyRemoved` is there to tell apart.
        Intent.ACTION_PACKAGE_FULLY_REMOVED,
        Intent.ACTION_UID_REMOVED -> PackageEvent.Removed(packageName, userId, fullyRemoved = true)
        else -> null
    }
}

/**
 * The action the daemon sends its package notification under.
 *
 * Built from the standalone manager's package name rather than from whatever this process happens
 * to be called, because the manager is usually not running as itself: parasitically it lives inside
 * `com.android.shell`, and an action derived from that would be one nobody ever sends. The daemon
 * builds this string from its own `DEFAULT_MANAGER_PACKAGE_NAME` and sends it to both hosts, so the
 * two sides stay in step only as long as both keep deriving it from that same Gradle value.
 */
private const val ACTION_DAEMON_NOTIFICATION = "${BuildConfig.MANAGER_PACKAGE_NAME}.NOTIFICATION"

/**
 * `Intent.EXTRA_USER_HANDLE`, which is hidden.
 *
 * The public `EXTRA_USER` is a `UserHandle` parcelable, so reading it as an int always answers the
 * default. The id these broadcasts actually carry is under this name.
 *
 * Only the platform's broadcasts, mind: the daemon's notification is the other way round and puts a
 * plain int under `EXTRA_USER`, so [daemonPackageEvent] reads that key and not this one.
 */
private const val EXTRA_USER_HANDLE = "android.intent.extra.user_handle"

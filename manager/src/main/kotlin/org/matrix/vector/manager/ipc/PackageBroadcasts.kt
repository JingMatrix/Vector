package org.matrix.vector.manager.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 * `Intent.EXTRA_USER_HANDLE`, which is hidden.
 *
 * The public `EXTRA_USER` is a `UserHandle` parcelable, so reading it as an int always answers the
 * default. The id these broadcasts actually carry is under this name.
 */
private const val EXTRA_USER_HANDLE = "android.intent.extra.user_handle"

package org.matrix.vector.manager.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
                val userId = intent.getIntExtra(Intent.EXTRA_USER, 0)

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

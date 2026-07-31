package org.matrix.vector.manager.ipc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import org.matrix.vector.manager.Constants

/**
 * Commits [session] and suspends until the platform says what became of it.
 *
 * The verdict arrives as a broadcast, and the receiver is registered here rather than declared:
 * parasitically the manager's manifest is never installed, so a declared receiver would never fire.
 * `STATUS_PENDING_USER_ACTION` is not terminal — it means the system is asking the user, and the
 * real status follows their answer. [onPrompt] is the caller's chance to say so on screen, and
 * [promptFailure] is what to log if the prompt cannot be started.
 *
 * **The UUID in the action is what keeps the verdict ours, and below API 33 nothing else can.** A
 * registered receiver has no exported flag before then, so anything installed can broadcast to one
 * whose action it knows. `ContextCompat.registerReceiver` only appears to answer that: below 33 it
 * stands in for the missing flag by demanding `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
 * of this process — a signature permission declared by a manifest that parasitically was never
 * installed, looked up under the host's package name — so it threw instead of registering, and
 * every install on API 27..32 failed before it began. Requiring a permission of the *sender* is no
 * better: a `PendingIntent` broadcast is sent as whoever created it, so that is this process, and
 * no permission is held both under the host and standalone.
 *
 * A forged verdict is worth ruling out rather than merely tidy. A fake `STATUS_SUCCESS` reports an
 * install that never happened and skips the caller's `abandonSession`; a fake
 * `STATUS_PENDING_USER_ACTION` hands us an arbitrary intent to start, and parasitically we would
 * start it as `com.android.shell`. The session id is not a secret to lean on either — the platform
 * announces every new session to every app in the user, and asks no permission to listen.
 */
suspend fun Context.commitForResult(
    session: PackageInstaller.Session,
    sessionId: Int,
    promptFailure: String,
    onPrompt: () -> Unit = {},
): Pair<Int, String?> = suspendCancellableCoroutine { continuation ->
    val action = "$RESULT_ACTION.$sessionId.${UUID.randomUUID()}"
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(received: Context, intent: Intent) {
                if (intent.action != action) return
                val status =
                    intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    )
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    onPrompt()
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        ?.let { confirm ->
                            runCatching {
                                    startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                                .onFailure { Log.e(Constants.TAG, promptFailure, it) }
                        }
                    return
                }
                runCatching { unregisterReceiver(this) }
                if (continuation.isActive) {
                    continuation.resumeWith(
                        Result.success(
                            status to intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        )
                    )
                }
            }
        }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, IntentFilter(action))
    }
    continuation.invokeOnCancellation { runCatching { unregisterReceiver(receiver) } }

    val flags =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    // The package restriction names the host parasitically, and has to: the receiver belongs to
    // this process, so a broadcast confined to the manager's own package would reach nobody.
    val pending =
        PendingIntent.getBroadcast(this, sessionId, Intent(action).setPackage(packageName), flags)
    session.commit(pending.intentSender)
}

/** Only ever a prefix; the session id and a UUID follow. */
private const val RESULT_ACTION = "org.matrix.vector.manager.INSTALL_RESULT"

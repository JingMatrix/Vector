package org.matrix.vector.manager.ipc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW

/**
 * Asks for an install that replaces whatever copy of the package is already on the device.
 *
 * `MODE_FULL_INSTALL` does not say that, and parasitically nothing else does either.
 * `PackageInstallerService.createSessionInternal` sets `INSTALL_REPLACE_EXISTING` itself for every
 * ordinary caller, and takes a separate branch for `SHELL_UID` and `ROOT_UID` which adds
 * `INSTALL_FROM_ADB` and leaves the rest of the flags as they came. `pm install` sets the flag in
 * its own argument parsing, which is why an adb install still replaces and why `-r` is accepted and
 * ignored; a caller of the framework API gets no such help. Under the host the manager *is* that
 * uid, so `PackageManagerService` treats a module the device already has as a first install and
 * fails it with `INSTALL_FAILED_ALREADY_EXISTS: Attempt to re-install <package> without first
 * uninstalling`. That branch reads the same from API 27, this app's minimum, to AOSP main, so
 * updating a module through the store has never worked parasitically on any release, and neither
 * has updating an installed manager from the host.
 *
 * Standalone this changes nothing, because the platform has already set the flag by the time a
 * session exists — which is why failing to set it is worth no more than a warning. The field is
 * `@hide` but greylisted (`@UnsupportedAppUsage` carrying no `maxTargetSdk`), so the reflection is
 * permitted in both modes rather than only under the platform-signed host.
 */
fun PackageInstaller.SessionParams.requestReplaceExisting() {
    runCatching {
            val flags = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
            flags.setInt(this, flags.getInt(this) or INSTALL_REPLACE_EXISTING)
        }
        .onFailure { logW("ipc: install session could not request a replace", it) }
}

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
                                .onFailure { logE(promptFailure, it) }
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

/** `PackageManager.INSTALL_REPLACE_EXISTING`, `@hide` like the field it belongs in. */
private const val INSTALL_REPLACE_EXISTING = 0x00000002

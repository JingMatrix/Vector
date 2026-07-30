package org.matrix.vector.manager.data.repository

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.manager.Constants
import org.matrix.vector.manager.ipc.DaemonClient

/** Where installing the manager as an app has got to. */
sealed interface ManagerInstallStep {

    data object Idle : ManagerInstallStep

    data object Installing : ManagerInstallStep

    /** Installed. The launcher now has a real Vector icon, and this process is still the host. */
    data object Done : ManagerInstallStep

    /**
     * [signatureConflict] is the one failure the reader can act on.
     *
     * A copy of the manager signed with a different key is already on the device, and the platform
     * will not replace it — which happens to anyone who flashed a build from CI over one they built
     * themselves, since the two are signed with different debug keys. Every other failure gets a
     * flat "could not be installed", because naming a cause we cannot act on only invites the
     * reader to try the same thing again.
     */
    data class Failed(val reason: String?, val signatureConflict: Boolean = false) :
        ManagerInstallStep
}

/**
 * Installs Vector's own manager as an ordinary app.
 *
 * The framework does not need this — the manager runs perfectly well injected into
 * `com.android.shell`, which is the default and what most people should stay on. It is offered
 * because the parasitic arrangement costs the manager a few things a normal app has: a launcher
 * icon, a place in the app list, per-app settings, notification permission that survives a reboot.
 * Some launchers also refuse to pin the shortcut [LaunchShortcut] would otherwise create, and on
 * those this is the only way to get an icon at all.
 *
 * What it costs the other way is worth knowing and is said on the screen that offers it: installed,
 * the manager is an ordinary app with ordinary permissions, so installing a module goes through the
 * system's `REQUEST_INSTALL_PACKAGES` prompt instead of happening silently under the host's
 * `INSTALL_PACKAGES`.
 *
 * The daemon already expects this: `ConfigCache` resolves `org.matrix.vector.manager`, verifies its
 * signature, and remembers its UID, so an installed manager is granted the same binder as the
 * injected one and needs no further arrangement.
 */
class ManagerInstaller(private val context: Context, private val daemon: DaemonClient) {

    private val _state = MutableStateFlow<ManagerInstallStep>(ManagerInstallStep.Idle)
    val state: StateFlow<ManagerInstallStep> = _state.asStateFlow()

    /** Clears a finished result so the button returns to its resting state. */
    fun acknowledge() {
        _state.value = ManagerInstallStep.Idle
    }

    /**
     * Removes the copy that is refusing the install, from every user.
     *
     * Through the daemon rather than through this app: the manager is not the installer of record
     * for that package, and parasitically it is the host, which has no business uninstalling apps
     * on its own account. Every user, because a copy left behind in another profile refuses the
     * install exactly as loudly as one in this profile — which is how this device got here.
     */
    suspend fun removeConflicting(): Boolean {
        val removed =
            daemon.uninstallPackage(BuildConfig.MANAGER_PACKAGE_NAME, ALL_USERS).getOrDefault(false)
        if (removed) _state.value = ManagerInstallStep.Idle
        else Log.w(Constants.TAG, "actions: could not remove the conflicting manager")
        return removed
    }

    /** True once `org.matrix.vector.manager` is a package on this device. */
    fun isInstalled(): Boolean =
        runCatching {
                context.packageManager.getPackageInfo(BuildConfig.MANAGER_PACKAGE_NAME, 0)
                true
            }
            .getOrDefault(false)

    /**
     * Fetches the flashed manager APK from the daemon and installs it.
     *
     * The APK is streamed straight from the daemon's descriptor into the install session, with no
     * copy in between: the manager has nowhere to put a copy that the package installer could read
     * anyway, and parasitically it has no `FileProvider` to serve one from.
     */
    suspend fun install(): Boolean =
        withContext(Dispatchers.IO) {
            _state.value = ManagerInstallStep.Installing

            val apk =
                withTimeoutOrNull(APK_TIMEOUT_MS) { daemon.getManagerApk().getOrNull() }
            if (apk == null) {
                // Either the daemon is gone, or it refused: the APK is missing from the module
                // directory or its signature is not the one this framework was built to accept.
                Log.e(Constants.TAG, "actions: the daemon served no manager APK to install")
                _state.value = ManagerInstallStep.Failed(null)
                return@withContext false
            }

            val packageInstaller = context.packageManager.packageInstaller
            var sessionId = -1
            var succeeded = false
            try {
                val size = apk.statSize.takeIf { it > 0 } ?: -1L
                val params =
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                        .apply {
                            // Pinned, and the platform fails an install whose staged APK disagrees
                            // with it. A daemon serving something else cannot install it as Vector.
                            setAppPackageName(BuildConfig.MANAGER_PACKAGE_NAME)
                            if (size > 0) setSize(size)
                        }
                sessionId = packageInstaller.createSession(params)

                packageInstaller.openSession(sessionId).use { session ->
                    session.openWrite(WRITE_NAME, 0, size).use { out ->
                        FileInputStream(apk.fileDescriptor).use { input -> input.copyTo(out) }
                        out.flush()
                        session.fsync(out)
                    }
                    val (status, message) = commit(session, sessionId)
                    succeeded = status == PackageInstaller.STATUS_SUCCESS
                    if (!succeeded) {
                        Log.w(
                            Constants.TAG,
                            "actions: manager install failed, status $status: $message",
                        )
                    }
                    _state.value =
                        if (succeeded) ManagerInstallStep.Done
                        else
                            ManagerInstallStep.Failed(
                                message,
                                // STATUS_FAILURE_CONFLICT covers more than a signature clash, so
                                // the platform's own reason decides. It is not localised and is
                                // never shown; it is only matched on here and logged above.
                                signatureConflict =
                                    status == PackageInstaller.STATUS_FAILURE_CONFLICT &&
                                        message?.contains(SIGNATURE_CONFLICT) == true,
                            )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(Constants.TAG, "actions: manager install failed", e)
                _state.value = ManagerInstallStep.Failed(e.message)
            } finally {
                runCatching { apk.close() }
                // A session left staged holds the bytes written so far, and they accumulate.
                if (!succeeded && sessionId != -1) {
                    runCatching { packageInstaller.abandonSession(sessionId) }
                }
            }
            succeeded
        }

    /**
     * Commits the session and waits for the platform's verdict.
     *
     * Registered at runtime rather than declared, because parasitically nothing in this app's
     * manifest exists and a declared receiver would never fire. `STATUS_PENDING_USER_ACTION` is not
     * terminal — it means the system is asking, and the real status follows the answer. It should
     * not arise here: the host holds `INSTALL_PACKAGES`, so the commit is silent. It is handled
     * anyway, because the same code runs from a manager that is already installed and updating
     * itself, where the prompt is exactly what the platform will do.
     */
    private suspend fun commit(
        session: PackageInstaller.Session,
        sessionId: Int,
    ): Pair<Int, String?> = suspendCancellableCoroutine { continuation ->
        val action = "$RESULT_ACTION.$sessionId"
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(received: Context, intent: Intent) {
                    val status =
                        intent.getIntExtra(
                            PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE,
                        )
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        IntentCompat.getParcelableExtra(
                                intent,
                                Intent.EXTRA_INTENT,
                                Intent::class.java,
                            )
                            ?.let { confirm ->
                                runCatching {
                                        context.startActivity(
                                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                    .onFailure { e ->
                                        Log.e(
                                            Constants.TAG,
                                            "actions: manager install prompt could not be started",
                                            e,
                                        )
                                    }
                            }
                        return
                    }
                    runCatching { context.unregisterReceiver(this) }
                    if (continuation.isActive) {
                        continuation.resumeWith(
                            Result.success(
                                status to
                                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            )
                        )
                    }
                }
            }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                else 0
        val pending =
            PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(action).setPackage(context.packageName),
                flags,
            )
        session.commit(pending.intentSender)
    }

    private companion object {
        const val WRITE_NAME = "manager.apk"
        const val RESULT_ACTION = "org.matrix.vector.manager.INSTALL_MANAGER_RESULT"

        /** What the platform calls it in `EXTRA_STATUS_MESSAGE`; see PackageManagerException. */
        const val SIGNATURE_CONFLICT = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

        /**
         * How long the daemon gets to hand over the APK.
         *
         * The binder call is synchronous and the daemon verifies a 20-odd megabyte signature before
         * answering, so it is not instant — but it is also the one step here with no failure of its
         * own to report. Without a bound, a daemon that never answers leaves the row spinning for
         * the life of the process, which is exactly what it did.
         */
        const val APK_TIMEOUT_MS = 30_000L

        /** `ManagerService.uninstallPackage` reads -1 as "every user". */
        const val ALL_USERS = -1
    }
}

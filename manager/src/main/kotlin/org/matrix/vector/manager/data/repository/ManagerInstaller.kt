package org.matrix.vector.manager.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.manager.data.model.ManagerCopy
import org.matrix.vector.manager.data.model.versionCodeCompat
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.ipc.commitForResult
import org.matrix.vector.manager.ipc.requestReplaceExisting

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

    /**
     * The last digest comparison, and the copy it was made against.
     *
     * Hashing two APKs of twenty-odd megabytes is not something to repeat every time somebody opens
     * the status page — which is every arrival at Home as well, since both screens refresh presence
     * and each holds its own ViewModel. This installer is the process-wide singleton both reach
     * through, so remembering the verdict here answers all of them.
     *
     * It is keyed on when the installed copy was last written rather than cleared by hand. That
     * expires the verdict on exactly the events that could change it — an install, an update, a
     * reinstall of the very same bytes — including the ones that happen while this app is not
     * running, and it needs no invalidation call at the far end of a code path that might forget.
     */
    @Volatile private var comparison: Comparison? = null

    /**
     * Holds the comparison to one at a time.
     *
     * Home and the status page each hold their own ViewModel and the first composition refreshes
     * presence from both within milliseconds of each other, which is early enough that neither has
     * written [comparison] by the time the other looks. Without this they would both go and hash
     * eighty megabytes between them for the one answer.
     */
    private val comparing = Mutex()

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
        else logW("actions: could not remove the conflicting manager")
        return removed
    }

    /**
     * What can be said about the installed copy without hashing anything.
     *
     * Whether the package exists is one `getPackageInfo`, and its version code comes back on the
     * same object, so both are cheap enough to answer on the main thread, which is where the
     * presence refresh asks. Whether two copies wearing the same number hold the same bytes is not,
     * so the verdict [refreshInstalledManager] last reached for this very copy is repeated until it
     * is asked again, and a copy nothing is yet known about reads as [ManagerCopy.Present] — the
     * same "installed, with nothing said against it" a failed comparison gives.
     *
     * Repeating the verdict is what keeps the row still. Without it every arrival at the screen
     * would show a plain check for as long as the digest takes and then flip to a reinstall button.
     */
    fun installedManager(): ManagerCopy {
        val installed = installedPackage() ?: return ManagerCopy.Absent
        // The cheap half of the comparison is redone rather than remembered. It costs one field of
        // a `PackageInfo` already in hand, and a divergence the numbers alone can see is the one
        // this screen meets most often — an install left behind by an older framework — so it would
        // be a shame to show it a check for as long as a digest takes and then take the check away.
        if (installed.versionCodeCompat != BuildConfig.VERSION_CODE.toLong()) {
            return ManagerCopy.Diverged
        }
        val known = comparison
        return if (known != null && known.installedAt == installed.lastUpdateTime) known.verdict
        else ManagerCopy.Present
    }

    /**
     * Compares the installed copy of the manager with the build this one is running.
     *
     * The version code goes first because it is free, and a disagreement there is already the
     * answer. Agreement is not: the code is `git rev-list --count origin/master`, so a build made
     * on a branch and the official build at that same depth wear the same number while being
     * different binaries entirely. That is the case worth spending a digest on, and the only one —
     * the whole point of taking one is to separate two copies the numbers call identical.
     *
     * What this manager is running is the canonical side. Parasitically its dex comes out of the
     * daemon's own module APK, which is the same file `getManagerApk` hands over, so `BuildConfig`
     * here describes the copy the daemon would install and the fetch is only needed for its bytes.
     *
     * **A comparison that could not be made is not a mismatch.** A dead daemon, a refused APK, an
     * unreadable install: each leaves [ManagerCopy.Present], because the reader would be sent to
     * replace a perfectly good copy on the strength of a check that never ran. Only a completed
     * comparison that came out different says so, and only that is remembered — a failure is left
     * unremembered on purpose, so that a daemon which comes back is asked again.
     */
    suspend fun refreshInstalledManager(): ManagerCopy {
        // Installed rather than parasitic, this manager *is* the copy in question: the package it
        // would compare itself against is itself, and the daemon's module APK is then a third file
        // that is allowed to differ without anything being wrong. Nothing renders the answer in
        // that mode either — the card that asks is drawn only parasitically — so the cheap answer
        // is the whole answer.
        if (!LaunchShortcut.isParasitic(context)) return installedManager()
        return comparing.withLock { compare() }
    }

    /** The comparison itself, off the drawing thread and one at a time; see [comparing]. */
    private suspend fun compare(): ManagerCopy =
        withContext(Dispatchers.IO) {
            val installed = installedPackage() ?: return@withContext ManagerCopy.Absent
            if (installed.versionCodeCompat != BuildConfig.VERSION_CODE.toLong()) {
                return@withContext ManagerCopy.Diverged
            }

            val known = comparison
            if (known != null && known.installedAt == installed.lastUpdateTime) {
                return@withContext known.verdict
            }

            // The daemon's copy first, dearer though the round trip is. A daemon that is gone
            // refuses it at once, and that is much the likeliest reason this comparison cannot be
            // made: presence is refreshed whether or not there is a binder, and the row showing the
            // answer is drawn disabled without one. Hashing the local copy first would spend twenty
            // megabytes of reads, on every arrival at the screen, to arrive at the same nothing.
            val ours = canonicalDigest()
            if (ours == null) {
                logW("actions: the daemon served no manager APK to compare against")
                return@withContext ManagerCopy.Present
            }
            // `sourceDir` is the whole of the installed copy — this installer stages one APK and
            // never any splits, so there is nothing else of it to fold in.
            val source = installed.applicationInfo?.sourceDir
            val theirs = source?.let { path -> sha256 { FileInputStream(path) } }
            if (theirs == null) {
                logW("actions: the installed manager could not be read, so it cannot be compared")
                return@withContext ManagerCopy.Present
            }

            val verdict =
                if (theirs.contentEquals(ours)) ManagerCopy.Present else ManagerCopy.Diverged
            comparison = Comparison(installed.lastUpdateTime, verdict)
            verdict
        }

    /** The installed manager as the package manager sees it, or null when there is none. */
    private fun installedPackage(): PackageInfo? =
        runCatching { context.packageManager.getPackageInfo(BuildConfig.MANAGER_PACKAGE_NAME, 0) }
            .getOrNull()

    /**
     * Digests the APK the daemon would install, and closes the descriptor it came on.
     *
     * A fresh `getManagerApk` rather than anything [install] holds: that descriptor is read to its
     * end and closed by the install itself, and there is no rewinding it.
     */
    private suspend fun canonicalDigest(): ByteArray? {
        val apk =
            withTimeoutOrNull(APK_TIMEOUT_MS) { daemon.getManagerApk().getOrNull() } ?: return null
        return try {
            sha256 { FileInputStream(apk.fileDescriptor) }
        } finally {
            runCatching { apk.close() }
        }
    }

    /**
     * The SHA-256 of a stream, or null if it could not be read to its end.
     *
     * A chunk at a time, because the manager APK runs to tens of megabytes and neither side of this
     * comparison has any business sitting in this process's heap — parasitically that heap belongs
     * to `com.android.shell`, which is not sized for it. The stream is opened inside so that a file
     * that cannot be opened is the same null as one that cannot be read, and closed on every path.
     */
    private fun sha256(open: () -> InputStream): ByteArray? =
        runCatching {
                open().use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DIGEST_CHUNK)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                    digest.digest()
                }
            }
            .getOrNull()

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
                logE("actions: the daemon served no manager APK to install")
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
                            // Updating an installed manager from the host is a replace, and
                            // parasitically the platform does not make it one for us.
                            requestReplaceExisting()
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
                        logW("actions: manager install failed, status $status: $message")
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
                logE("actions: manager install failed", e)
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
     * `STATUS_PENDING_USER_ACTION` should not arise here — the host holds `INSTALL_PACKAGES`, so
     * the commit is silent — but it is handled anyway, because the same code runs from a manager
     * that is already installed and updating itself, where the prompt is exactly what the platform
     * will do.
     *
     * @see commitForResult
     */
    private suspend fun commit(
        session: PackageInstaller.Session,
        sessionId: Int,
    ): Pair<Int, String?> =
        context.commitForResult(
            session,
            sessionId,
            promptFailure = "actions: manager install prompt could not be started",
        )

    /**
     * A completed comparison, and the copy it was made against.
     *
     * @property installedAt `PackageInfo.lastUpdateTime` of the copy that was compared, which is
     *   what makes this verdict expire when that copy is replaced rather than outlive it.
     */
    private data class Comparison(val installedAt: Long, val verdict: ManagerCopy)

    private companion object {
        const val WRITE_NAME = "manager.apk"

        /** How much of an APK is held at once while it is being hashed. */
        const val DIGEST_CHUNK = 64 * 1024

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

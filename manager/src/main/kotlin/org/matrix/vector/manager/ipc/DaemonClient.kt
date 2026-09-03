package org.matrix.vector.manager.ipc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.matrix.vector.ipc.IFrameworkInstallReceiver
import android.content.Intent
import android.content.pm.ActivityInfo
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW

/**
 * Every call the manager makes to the daemon, as coroutines.
 *
 * A Binder transaction is synchronous and the daemon on the other end can be slow, busy or gone, so
 * none of this is allowed to happen on the thread that draws.
 */
class DaemonClient(private val serviceState: StateFlow<IManagerService?>) {

    val service: IManagerService?
        get() = serviceState.value

    val isAlive: Boolean
        get() = service?.asBinder()?.isBinderAlive == true

    /**
     * Runs one daemon transaction on the IO dispatcher and reports its outcome as a [Result], so an
     * unreachable or refusing daemon is a value the caller can render rather than a thrown
     * exception.
     */
    private suspend fun <T> runIpc(block: (IManagerService) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            // Read the binder once: it comes from a StateFlow the daemon can change underneath us,
            // so checking one value for liveness and calling another is a race with the daemon
            // dying.
            val binder = service
            if (binder == null || binder.asBinder()?.isBinderAlive != true) {
                return@withContext Result.failure(IllegalStateException("Daemon is not active"))
            }
            try {
                Result.success(block(binder))
            } catch (e: Exception) {
                // Deliberately broad. A SecurityException, an IllegalArgumentException, or a
                // RuntimeException thrown while unparcelling a large ParcelableListSlice are all
                // reachable here, and any of them escaping fails the calling coroutine — which for
                // an unhandled failure in a viewModelScope means the process goes down.
                logW("ipc: daemon transaction failed", e)
                Result.failure(e)
            }
        }

    suspend fun getLibxposedApiVersion(): Result<Int> = runIpc { it.libxposedApiVersion }

    suspend fun getEnabledModules(): Result<List<String>> = runIpc { it.enabledModules
    }

    /**
     * The activity that would open for this package, or null when it has none.
     *
     * Three categories, in order. A module that declares the Xposed settings category is naming its
     * companion — the screen its author wrote to configure it — and for a module that wins.
     * `CATEGORY_INFO` comes next: it is what an app declares when it has a screen worth opening but
     * deliberately keeps out of the launcher, which is the common shape for a module.
     * `CATEGORY_LAUNCHER` last.
     *
     * Resolved as the package's own user throughout, and through the daemon: the manager's own
     * package manager cannot see another profile's activities.
     */
    suspend fun findAppUi(
        packageName: String,
        userId: Int,
        /**
         * True for a module, where the companion screen is the point.
         *
         * No default: the caller that asks whether a companion exists and the caller that opens it
         * have to be asking the same question, or the manager offers a button that resolves to
         * nothing.
         */
        companionFirst: Boolean,
    ): Result<ActivityInfo?> = runIpc { service ->
        val categories = buildList {
            if (companionFirst) add(XPOSED_MODULE_SETTINGS_CATEGORY)
            add(Intent.CATEGORY_INFO)
            add(Intent.CATEGORY_LAUNCHER)
        }
        categories
            .asSequence()
            .mapNotNull { category ->
                val intent =
                    Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(packageName)
                service.queryIntentActivitiesAsUser(intent, 0, userId)?.list?.firstOrNull()
            }
            .firstOrNull()
            ?.activityInfo
    }

    /**
     * Opens that screen.
     *
     * Whether to suppress the daemon's user switch is decided here, from the resolved activity's
     * `FLAG_SHOW_FOR_ALL_USERS` — see [startActivityAsUser] for what the switch does and why an
     * activity that shows for every user must not trigger one.
     *
     * Returns false when the package has no such screen, which is an answer rather than a failure.
     */
    suspend fun openAppUi(
        packageName: String,
        userId: Int,
        /** As in [findAppUi], and for the same reason it has no default there. */
        companionFirst: Boolean,
    ): Result<Boolean> {
        val resolved = findAppUi(packageName, userId, companionFirst)
        val target = resolved.getOrNull()
        if (target == null) {
            logE(
                "ipc: open resolved no activity for $packageName in user $userId " +
                    "(companionFirst=$companionFirst)",
                resolved.exceptionOrNull(),
            )
            return Result.success(false)
        }
        return runIpc { service ->
            val code =
                service.startActivityAsUser(
                    Intent(Intent.ACTION_MAIN)
                        .setClassName(target.packageName, target.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    userId,
                    (target.flags and FLAG_SHOW_FOR_ALL_USERS) != 0,
                )
            // The daemon hands back the activity manager's own start code, so a refusal reaches the
            // caller rather than a flat `true`: a refused user switch (-1), a disabled or
            // unexported activity, an activity that has gone since it was resolved. Reporting any
            // of those as "opened" leaves the screen silent with nothing in front of it. A start
            // succeeded when the code is 0 to 99, which is what `ActivityManager` tests itself in
            // `isStartResultSuccessful`; those codes are @hide, so the band is written out. Fatal
            // refusals occupy -100 to -1, and 100 to 199 is the non-fatal error band —
            // START_SWITCHES_CANCELED (100), START_RETURN_LOCK_TASK_MODE_VIOLATION (101),
            // START_ABORTED (102) — where nothing came up either.
            val started = code in 0..99
            if (!started) {
                logE(
                    "ipc: ${target.packageName}/${target.name} refused by the activity manager " +
                        "in user $userId (code $code)",
                )
            }
            started
        }
    }

    /**
     * Modules the daemon could not load, though they are installed and enabled, keyed by package
     * with one of `IManagerService.MODULE_LOAD_*` as the value.
     *
     * The daemon keeps what the user asked for separately from what it can actually load, and the
     * two can disagree — an APK whose path will not resolve, a DEX the loader refuses. A module
     * listed here is still switched on; it is the loading that failed, and saying so is the only
     * way the screen can tell that apart from "switched off".
     *
     * A map because that is what the daemon holds and what the caller wants. It used to be a list
     * of names plus one transaction per name to ask why, which forced the caller to seed each
     * entry with a placeholder reason before the second round could overwrite it — so a dropped
     * transaction reported a module as missing its APK, which nothing had established.
     */
    suspend fun getModuleLoadFailures(): Result<Map<String, Int>> = runIpc { service ->
        service.moduleLoadFailures.associate { it.packageName to it.reason }
    }

    suspend fun setModuleEnabled(packageName: String, enable: Boolean): Result<Boolean> = runIpc {
        it.setModuleEnabled(packageName, enable)
    }

    suspend fun getBuildStamp(): Result<String?> = runIpc { it.buildStamp }

    suspend fun getFrameworkVersionName(): Result<String> = runIpc { it.frameworkVersionName }

    suspend fun getFrameworkVersionCode(): Result<Long> = runIpc { it.frameworkVersionCode }

    suspend fun getInstalledPackagesFromAllUsers(
        flags: Int,
        filterNoProcess: Boolean,
    ): Result<List<android.content.pm.PackageInfo>> = runIpc { it.getInstalledPackagesFromAllUsers(flags, filterNoProcess).list
    }

    suspend fun setModuleScope(
        packageName: String,
        applications: List<org.matrix.vector.ipc.ScopeEntry>,
    ): Result<Boolean> = runIpc { it.setModuleScope(packageName, applications) }

    /**
     * A module's configured scope.
     *
     * The AIDL answers null for the framework's own pseudo-module row, which is not a module and has
     * no scope — and null is emphatically not an empty scope: reading a refusal as "no rows" and
     * writing that back is how a scope gets erased. AIDL's Java backend emits no nullability
     * annotations, so that null arrives as an unchecked platform type; it is turned into a failure
     * here so the caller's existing error path takes it rather than a `List` that is null at
     * runtime.
     */
    suspend fun getModuleScope(
        packageName: String
    ): Result<List<org.matrix.vector.ipc.ScopeEntry>> = runIpc {
        it.getModuleScope(packageName)
            ?: throw IllegalArgumentException("$packageName has no scope to read")
    }

    suspend fun isStatusNotificationEnabled(): Result<Boolean> = runIpc {
        it.isStatusNotificationEnabled
    }

    suspend fun setStatusNotificationEnabled(enabled: Boolean): Result<Unit> = runIpc {
        it.setStatusNotificationEnabled(enabled)
    }

    suspend fun isVerboseLogEnabled(): Result<Boolean> = runIpc { it.isVerboseLogEnabled }

    suspend fun setVerboseLogEnabled(enabled: Boolean): Result<Unit> = runIpc { it.setVerboseLogEnabled(enabled)
    }

    /**
     * Every package opted into ART inline-hook invalidation, sorted.
     *
     * Empty against a daemon too old to answer the call, in which case the manager shows none.
     */
    suspend fun getInvalidateArtInlineHookPackages(): Result<List<String>> = runIpc {
        it.invalidateArtInlineHookPackages.orEmpty()
    }

    /**
     * Sets whether a package invalidates Vector's native ART inline hooks after injection.
     *
     * [Result] carries the daemon's own answer: it stores the choice and reports whether the write
     * landed, so a blank package name or a refused write reaches the caller rather than reading as a
     * silent success.
     */
    suspend fun setInvalidateArtInlineHooks(packageName: String, enabled: Boolean): Result<Boolean> =
        runIpc { it.setInvalidateArtInlineHooks(packageName, enabled) }

    /**
     * The rotated parts the daemon still holds for one of the two logs, oldest first.
     *
     * Empty against a daemon too old to answer the call, in which case the manager shows the live
     * part alone.
     */
    suspend fun getLogParts(verbose: Boolean): Result<List<String>> = runIpc {
        it.getLogParts(verbose).orEmpty()
    }

    suspend fun getLogPart(
        verbose: Boolean,
        name: String,
    ): Result<android.os.ParcelFileDescriptor?> = runIpc { it.getLogPart(verbose, name) }

    /**
     * The part currently being written, or `null` when the daemon has not opened one yet.
     *
     * The AIDL returns a platform type, so the nullability is spelled out in the type parameter:
     * "the daemon is unreachable" and "there is no log file yet" are different situations, the Logs
     * screen renders them differently, and a `Result<ParcelFileDescriptor>` would collapse them.
     */
    suspend fun getLiveLogPart(verbose: Boolean): Result<android.os.ParcelFileDescriptor?> = runIpc {
        it.getLiveLogPart(verbose)
    }

    /**
     * Closes the part being written and opens a fresh one. Nothing is deleted.
     *
     * `Result<Unit>` because there is nothing truthful to answer: the daemon asks its log reader to
     * rotate by writing a sentinel and never learns whether it acted. The call used to answer a
     * constant `true`, which the Logs screen read as a success signal — so a rotation that never
     * happened was reported as one that had. Success here means the daemon took the request.
     */
    suspend fun startNewLogPart(verbose: Boolean): Result<Unit> = runIpc {
        it.startNewLogPart(verbose)
    }

    suspend fun forceStopPackage(packageName: String, userId: Int): Result<Unit> = runIpc { it.forceStopPackage(packageName, userId)
    }

    suspend fun reboot(): Result<Unit> = runIpc { it.reboot() }

    suspend fun uninstallPackage(packageName: String, userId: Int): Result<Boolean> = runIpc { it.uninstallPackage(packageName, userId)
    }

    suspend fun isSepolicyLoaded(): Result<Boolean> = runIpc { it.isSepolicyLoaded }

    suspend fun getUsers(): Result<List<org.matrix.vector.ipc.DeviceUser>> = runIpc { it.users
    }

    suspend fun isSystemServerAttached(): Result<Boolean> = runIpc { it.isSystemServerAttached }

    suspend fun isDex2OatInliningDisabled(): Result<Boolean> = runIpc {
        it.isDex2OatInliningDisabled
    }

    suspend fun getDex2OatWrapperState(): Result<Int> = runIpc { it.dex2OatWrapperState }

    suspend fun optimizePackage(packageName: String): Result<Boolean> = runIpc {
        it.optimizePackage(packageName)
    }

    /**
     * Writes the daemon's bug report into [zipFd].
     *
     * More than the logs: the daemon adds tombstones, ANR traces, both crash directories, a full
     * logcat and dmesg, the module database and the resolved scopes.
     */
    suspend fun writeBugReportTo(zipFd: android.os.ParcelFileDescriptor): Result<Unit> = runIpc {
        it.writeBugReport(zipFd)
    }

    /**
     * Starts an activity as another user.
     *
     * [noUserSwitch] is not decoration. Without it, and whenever the current user is not already the
     * target's profile parent, the daemon switches the device to that parent and locks the screen
     * before starting the activity — right for an activity that exists in one profile only, and a
     * startling thing to do to someone who pressed "open" on a module whose window shows for every
     * user anyway. The resolved activity's `FLAG_SHOW_FOR_ALL_USERS` says which case this is.
     */
    suspend fun startActivityAsUser(
        intent: android.content.Intent,
        userId: Int,
        noUserSwitch: Boolean,
    ): Result<Int> = runIpc { it.startActivityAsUser(intent, userId, noUserSwitch) }

    /** Restarts the framework without rebooting the device. Everything on screen goes with it. */
    suspend fun softReboot(): Result<Unit> = runIpc { it.softReboot() }

    /**
     * The flashed manager APK, for installing the manager as an ordinary app.
     *
     * Null against a daemon too old to answer the call, and null when the daemon has the call but
     * refused — the APK is missing from the module directory, or its signature is not the one this
     * framework accepts. Both leave the offer to install unusable, and neither is worth telling
     * apart on screen.
     */
    suspend fun getManagerApk(): Result<android.os.ParcelFileDescriptor?> = runIpc {
        it.managerApk
    }

    /**
     * Whether apps that declare no launcher entry are given one anyway.
     *
     * True is the platform default, and is what the daemon answers on a device where nothing has
     * ever set it.
     */
    suspend fun isForcedLauncherIcons(): Result<Boolean> = runIpc { it.isForcedLauncherIcons }

    suspend fun setForcedLauncherIcons(force: Boolean): Result<Unit> = runIpc {
        it.setForcedLauncherIcons(force)
    }

    suspend fun getIncludeNewApps(packageName: String): Result<Boolean> = runIpc { it.getIncludeNewApps(packageName)
    }

    /**
     * Returns whether the daemon stored it, which is not the same as whether the call arrived.
     *
     * `ModuleDatabase.setIncludeNewApps` answers false when no row was updated — the package is
     * not a known module, or it is the framework itself — and the switch has to follow that answer
     * rather than assume the write landed.
     */
    suspend fun setIncludeNewApps(packageName: String, enable: Boolean): Result<Boolean> = runIpc {
        it.setIncludeNewApps(packageName, enable)
    }

    suspend fun getRootImplementation(): Result<Int> = runIpc { it.rootImplementation }

    /**
     * Starts a flash and returns as soon as the daemon has accepted it.
     *
     * Deliberately not wrapped into a suspend-until-finished call: the result arrives on [receiver]
     * over minutes, and a coroutine suspended across a reboot-inducing operation is a coroutine
     * that never resumes. The caller keeps the receiver alive for as long as it wants the output.
     */
    suspend fun installFrameworkZip(
        zipPath: String,
        receiver: IFrameworkInstallReceiver,
    ): Result<Unit> = runIpc { it.installFrameworkZip(zipPath, receiver) }
}

/**
 * `ActivityInfo.FLAG_SHOW_FOR_ALL_USERS`, which is hidden.
 *
 * An activity carrying it displays whichever user is current, so opening it needs no user switch.
 */
private const val FLAG_SHOW_FOR_ALL_USERS = 0x0400

/**
 * How an Xposed module has advertised its settings screen since the original framework.
 *
 * A module that hides its launcher icon still needs somewhere to be configured from, and this is
 * where it says so.
 */
private const val XPOSED_MODULE_SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"

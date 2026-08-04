package org.matrix.vector.manager.demo

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import org.matrix.vector.ipc.DeviceUser
import org.matrix.vector.ipc.IFrameworkInstallReceiver
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.ipc.ModuleLoadFailure
import org.matrix.vector.ipc.ScopeEntry
import rikka.parcelablelist.ParcelableListSlice
import org.matrix.vector.manager.data.model.versionCodeCompat

/**
 * The daemon, as a script.
 *
 * The fake sits at the *binder*, which is the boundary between this app and the privileged system,
 * and is the reason a demo mode is worth having at all: everything from here inwards — DaemonClient,
 * the repositories, the view models, the status derivation that turns three booleans into an issue
 * list — runs exactly as it does in production. What is faked is what the device says about itself,
 * not what the manager concludes. A bug in the concluding is still visible.
 *
 * Two other seams were considered and rejected. Faking a repository would have meant the status
 * derivation never ran, which is the code most likely to be wrong. Faking a view model would have
 * meant testing the screen against a pipeline that was not there — and the bugs this week lived in
 * the pipeline, not the screen.
 *
 * Anything the scenario does not have an opinion about is delegated to the real daemon when one is
 * connected, so the module list, the app list and the logs stay real while the framework's health
 * is a lie. With no daemon present the delegating calls return empties rather than throwing, so the
 * demo is still usable on a device with no Vector installed.
 *
 * Subclassing `Stub()` rather than proxying the interface is deliberate on both counts: DaemonClient
 * checks `asBinder().isBinderAlive`, which only a real Binder answers — and a new AIDL method breaks
 * this file's compilation, which is the point. A fake that silently kept working while the daemon
 * grew a new question would quietly stop covering it.
 */
class FakeManagerService(
    private val scenario: DemoScenario,
    private val real: IManagerService?,
) : IManagerService.Stub() {

    /**
     * What each package's version was when the scenario started.
     *
     * The scenario claims everything is out of date, and something has to decide when to stop
     * claiming it. Recording the first answer per package and comparing against it means the lie
     * ends exactly when the package actually changes — so an install performed by the manager is
     * visible in the manager, which is the behaviour worth testing here.
     */
    private val baselineVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun stall() {
        if (scenario.stallMillis > 0) Thread.sleep(scenario.stallMillis)
    }

    /**
     * Neither scripted nor delegated.
     *
     * This class *is* this build's `Stub`, so the generation it answers to is the one this file was
     * compiled against — the same answer the daemon of this build gives. Passing the real daemon's
     * number through would report a peer's protocol for a peer that is not the one on the other end
     * of these transactions.
     */
    override fun getProtocolVersion(): Int = IManagerService.PROTOCOL_VERSION

    // ---- what the scenario exists to lie about ------------------------------------------------

    override fun isSepolicyLoaded(): Boolean {
        stall()
        return scenario.sepolicyLoaded
    }

    override fun isSystemServerAttached(): Boolean {
        stall()
        return scenario.systemServerAttached
    }

    override fun isDex2OatInliningDisabled(): Boolean {
        stall()
        return scenario.dex2OatInliningDisabled
    }

    override fun getDex2OatWrapperState(): Int = scenario.dex2OatWrapperState

    override fun getLibxposedApiVersion(): Int =
        scenario.libxposedApiVersion.takeIf { it != DemoScenario.PASS_THROUGH }
            ?: real?.libxposedApiVersion
            ?: 0

    override fun getFrameworkVersionCode(): Long =
        scenario.frameworkVersionCode.takeIf { it != DemoScenario.PASS_THROUGH.toLong() }
            ?: real?.frameworkVersionCode
            ?: 0L

    override fun getRootImplementation(): Int = scenario.rootImplementation

    /**
     * Passed through, because a scenario that lied about the build stamp would be testing the
     * *mismatch* warning rather than the states this harness exists for. Add a field here when
     * there is a scenario that needs one.
     */
    override fun getBuildStamp(): String? = real?.buildStamp


    /**
     * A flash, without a flash.
     *
     * Emits on its own thread and never blocks the caller, because the real one does not either —
     * a screen that only works when the lines arrive on the binder thread would pass here and hang
     * on a device.
     */
    override fun installFrameworkZip(zipPath: String?, receiver: IFrameworkInstallReceiver?) {
        if (receiver == null) return
        Thread {
                fun say(line: String) {
                    runCatching { receiver.onLine(line) }
                    Thread.sleep(220)
                }
                when (scenario.install) {
                    DemoScenario.InstallScript.NO_ROOT -> {
                        runCatching {
                            receiver.onFinished(IFrameworkInstallReceiver.INSTALL_NO_ROOT)
                        }
                    }
                    DemoScenario.InstallScript.SUCCEEDS -> {
                        say("- Target: $zipPath")
                        say("- Extracting module files")
                        say("- Device is arm64-v8a API 36")
                        say("- Installing Vector")
                        say("- Setting permissions")
                        say("- Done. Reboot to apply.")
                        runCatching { receiver.onFinished(0) }
                    }
                    DemoScenario.InstallScript.FAILS_PARTWAY -> {
                        say("- Target: $zipPath")
                        say("- Extracting module files")
                        say("- Device is arm64-v8a API 36")
                        say("- Installing Vector")
                        say("! Failed to copy zygisk binary: No space left on device")
                        runCatching { receiver.onFinished(1) }
                    }
                }
            }
            .start()
    }

    // ---- everything else is the real device, when there is one ---------------------------------

    /**
     * The installed package list, optionally rewritten to look old.
     *
     * This one call is where "is there an update for this module" is really decided: the catalogue
     * says what the newest version is, and the comparison is against what this returns. Reporting
     * a low version here is therefore the whole of the "modules are out of date" scenario, and it
     * has the property that makes these scenarios worth having — nothing downstream is faked. The
     * catalogue is the real one, the releases are real, the APK that gets installed is real, and so
     * is the install.
     *
     * The rewrite is applied to every package rather than to modules alone, because telling them
     * apart means opening APKs and this is the daemon's side of the wire, where that answer is not
     * known. The visible cost is that the demo's module rows all read `0.1-demo` — which is the
     * signal that the scenario is on, and no worse than the arbitrary number it replaces.
     */
    override fun getInstalledPackagesFromAllUsers(
        flags: Int,
        filterNoProcess: Boolean,
    ): ParcelableListSlice<PackageInfo> {
        val actual =
            real?.getInstalledPackagesFromAllUsers(flags, filterNoProcess)
                ?: return ParcelableListSlice(emptyList())
        if (scenario.moduleVersions == DemoScenario.ModuleVersionScript.REAL) return actual
        // Mutated in place: these are already unparcelled copies belonging to this process, not the
        // daemon's own objects.
        val rewritten =
            actual.list.map { info ->
                val baseline = baselineVersions.putIfAbsent(info.packageName, info.versionCodeCompat)
                if (baseline != null && baseline != info.versionCodeCompat) {
                    // This one has genuinely changed under us since the scenario started, which
                    // for a demo means the manager just installed it. Reporting the truth from
                    // here is what makes this a test rather than a picture: the row has to stop
                    // being out of date, the count has to drop, and the panel has to notice
                    // without being left and re-entered. Keep lying and the install always looks
                    // like it did nothing.
                    return@map info
                }
                info.also {
                    it.setVersionCodeCompat(1)
                    it.versionName = "0.1-demo"
                }
            }
        return ParcelableListSlice(rewritten)
    }

    override fun getEnabledModules(): MutableList<String> = real?.enabledModules ?: mutableListOf()

    /**
     * The empty list is the whole answer for a device with nothing wrong: a module absent from it
     * loaded, so no daemon means nothing to report rather than a state to invent.
     */
    override fun getModuleLoadFailures(): MutableList<ModuleLoadFailure> =
        real?.moduleLoadFailures ?: mutableListOf()

    override fun setModuleEnabled(packageName: String?, enabled: Boolean): Boolean =
        real?.setModuleEnabled(packageName, enabled) ?: false

    override fun setModuleScope(packageName: String?, scope: MutableList<ScopeEntry>?): Boolean =
        real?.setModuleScope(packageName, scope) ?: false

    /**
     * Null is handed on rather than flattened, because the daemon answers it only for the
     * framework's own pseudo-module row, which is not the same answer as a module with nothing
     * scoped to it — and a fake that collapsed the two would hide a refusal from the very code this
     * demo exists to exercise. The empty list is the no-daemon answer alone.
     */
    override fun getModuleScope(packageName: String?): MutableList<ScopeEntry>? =
        if (real == null) mutableListOf() else real.getModuleScope(packageName)

    override fun isVerboseLogEnabled(): Boolean = real?.isVerboseLogEnabled ?: false

    override fun setVerboseLogEnabled(enabled: Boolean) {
        real?.setVerboseLogEnabled(enabled)
    }

    override fun getLiveLogPart(verbose: Boolean): ParcelFileDescriptor? =
        real?.getLiveLogPart(verbose)

    override fun getLogParts(verbose: Boolean): MutableList<String> =
        real?.getLogParts(verbose) ?: mutableListOf()

    override fun getLogPart(verbose: Boolean, name: String?): ParcelFileDescriptor? =
        real?.getLogPart(verbose, name)

    /**
     * Delegated, so the offer to install the manager is live or dead exactly as it really is.
     *
     * Null when there is no daemon behind the demo, which is the same answer the real one gives
     * when it cannot serve the APK — and the status page renders that case rather than crashing.
     */
    override fun getManagerApk(): ParcelFileDescriptor? = real?.managerApk

    override fun getFrameworkVersionName(): String? = real?.frameworkVersionName

    override fun startNewLogPart(verbose: Boolean) {
        real?.startNewLogPart(verbose)
    }

    override fun forceStopPackage(packageName: String?, userId: Int) {
        real?.forceStopPackage(packageName, userId)
    }

    /** Not delegated. A demo build must not be able to reboot the device by accident. */
    override fun reboot() = Unit

    override fun uninstallPackage(packageName: String?, userId: Int): Boolean =
        real?.uninstallPackage(packageName, userId) ?: false

    override fun getUsers(): MutableList<DeviceUser> = real?.users ?: mutableListOf()

    override fun startActivityAsUser(intent: Intent?, userId: Int, noUserSwitch: Boolean): Int =
        // -1, not 0: the AIDL documents 0..99 as "the activity started", so a benign-looking
        // 0 would report a successful start with no daemon behind it.
        real?.startActivityAsUser(intent, userId, noUserSwitch) ?: -1

    override fun queryIntentActivitiesAsUser(
        intent: Intent?,
        flags: Int,
        userId: Int,
    ): ParcelableListSlice<ResolveInfo> =
        real?.queryIntentActivitiesAsUser(intent, flags, userId) ?: ParcelableListSlice(emptyList())

    override fun softReboot() {
        // Deliberately inert: a demo that could restart the framework would take the phone down
        // with it, and every screen this scenario exists to show would go with it.
    }

    override fun isForcedLauncherIcons(): Boolean = real?.isForcedLauncherIcons ?: true

    override fun setForcedLauncherIcons(force: Boolean) {
        real?.setForcedLauncherIcons(force)
    }

    override fun writeBugReport(zipFd: ParcelFileDescriptor?) {
        real?.writeBugReport(zipFd)
    }

    override fun optimizePackage(packageName: String?): Boolean =
        real?.optimizePackage(packageName) ?: false

    // `?: true` to match the daemon, whose PreferenceStore reads this one `?: true` when nobody has
    // set it — the same reason isForcedLauncherIcons above answers true. A fallback here is not a
    // failed read: it is handed upstream as a *successful* answer, so answering false would leave
    // the status page's switch — and the ManagerPresence field HomeViewModel fills from the same
    // call — showing the opposite of what an untouched device with a real daemon behind it says.
    override fun isStatusNotificationEnabled(): Boolean = real?.isStatusNotificationEnabled ?: true

    override fun setStatusNotificationEnabled(enabled: Boolean) {
        real?.setStatusNotificationEnabled(enabled)
    }

    override fun getIncludeNewApps(packageName: String?): Boolean =
        real?.getIncludeNewApps(packageName) ?: false

    override fun setIncludeNewApps(packageName: String?, enable: Boolean): Boolean =
        real?.setIncludeNewApps(packageName, enable) ?: false
}

/**
 * The write half of [versionCodeCompat], which exists only here.
 *
 * `setLongVersionCode` is API 28 and the app's minimum is 27, so below that the deprecated `int`
 * field is the field. Nothing in the real manager ever writes a version code -- only this fake,
 * which rewrites the daemon's answers to script the demo.
 */
@Suppress("DEPRECATION")
private fun PackageInfo.setVersionCodeCompat(value: Long) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode = value
    else versionCode = value.toInt()
}

package org.matrix.vector.manager.demo

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.ParcelFileDescriptor
import org.lsposed.lspd.IFrameworkInstallCallback
import org.lsposed.lspd.ILSPManagerService
import org.lsposed.lspd.models.Application
import org.lsposed.lspd.models.UserInfo
import rikka.parcelablelist.ParcelableListSlice

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
    private val real: ILSPManagerService?,
) : ILSPManagerService.Stub() {

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

    // ---- what the scenario exists to lie about ------------------------------------------------

    override fun isSepolicyLoaded(): Boolean {
        stall()
        return scenario.sepolicyLoaded
    }

    override fun systemServerRequested(): Boolean {
        stall()
        return scenario.systemServerRequested
    }

    override fun dex2oatFlagsLoaded(): Boolean {
        stall()
        return scenario.dex2oatFlagsLoaded
    }

    override fun getDex2OatWrapperCompatibility(): Int = scenario.dex2oatCompatibility

    override fun getXposedApiVersion(): Int =
        scenario.xposedApiVersion.takeIf { it != DemoScenario.PASS_THROUGH }
            ?: real?.xposedApiVersion
            ?: 0

    override fun getXposedVersionCode(): Long =
        scenario.xposedVersionCode.takeIf { it != DemoScenario.PASS_THROUGH.toLong() }
            ?: real?.xposedVersionCode
            ?: 0L

    override fun getRootImplementation(): Int = scenario.rootImplementation

    /**
     * Passed through, because a scenario that lied about the commit would be testing the *mismatch*
     * warning rather than the states this harness exists for. Add a field here when there is a
     * scenario that needs one.
     */
    override fun getFrameworkCommit(): String? = real?.frameworkCommit

    override fun getRootImplementationVersion(): String? = scenario.rootVersion

    /**
     * A flash, without a flash.
     *
     * Emits on its own thread and never blocks the caller, because the real one does not either —
     * a screen that only works when the lines arrive on the binder thread would pass here and hang
     * on a device.
     */
    override fun installFrameworkZip(zipPath: String?, callback: IFrameworkInstallCallback?) {
        if (callback == null) return
        Thread {
                fun say(line: String) {
                    runCatching { callback.onLine(line) }
                    Thread.sleep(220)
                }
                when (scenario.install) {
                    DemoScenario.InstallScript.NO_ROOT -> {
                        runCatching { callback.onFinished(ILSPManagerService.INSTALL_NO_ROOT) }
                    }
                    DemoScenario.InstallScript.SUCCEEDS -> {
                        say("- Target: $zipPath")
                        say("- Extracting module files")
                        say("- Device is arm64-v8a API 36")
                        say("- Installing Vector")
                        say("- Setting permissions")
                        say("- Done. Reboot to apply.")
                        runCatching { callback.onFinished(0) }
                    }
                    DemoScenario.InstallScript.FAILS_PARTWAY -> {
                        say("- Target: $zipPath")
                        say("- Extracting module files")
                        say("- Device is arm64-v8a API 36")
                        say("- Installing Vector")
                        say("! Failed to copy zygisk binary: No space left on device")
                        runCatching { callback.onFinished(1) }
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
                val baseline = baselineVersions.putIfAbsent(info.packageName, info.longVersionCode)
                if (baseline != null && baseline != info.longVersionCode) {
                    // This one has genuinely changed under us since the scenario started, which
                    // for a demo means the manager just installed it. Reporting the truth from
                    // here is what makes this a test rather than a picture: the row has to stop
                    // being out of date, the count has to drop, and the panel has to notice
                    // without being left and re-entered. Keep lying and the install always looks
                    // like it did nothing.
                    return@map info
                }
                info.also {
                    it.longVersionCode = 1
                    it.versionName = "0.1-demo"
                }
            }
        return ParcelableListSlice(rewritten)
    }

    override fun enabledModules(): Array<String> = real?.enabledModules() ?: emptyArray()

    override fun getUnloadableModules(): Array<String> =
        real?.unloadableModules ?: emptyArray()

    override fun getModuleLoadState(packageName: String?): Int =
        real?.getModuleLoadState(packageName) ?: ILSPManagerService.MODULE_LOAD_OK

    override fun enableModule(packageName: String?): Boolean =
        real?.enableModule(packageName) ?: false

    override fun disableModule(packageName: String?): Boolean =
        real?.disableModule(packageName) ?: false

    override fun setModuleScope(packageName: String?, scope: MutableList<Application>?): Boolean =
        real?.setModuleScope(packageName, scope) ?: false

    override fun getModuleScope(packageName: String?): MutableList<Application> =
        real?.getModuleScope(packageName) ?: mutableListOf()

    override fun isVerboseLog(): Boolean = real?.isVerboseLog ?: false

    override fun setVerboseLog(enabled: Boolean) {
        real?.setVerboseLog(enabled)
    }

    override fun getVerboseLog(): ParcelFileDescriptor? = real?.verboseLog

    override fun getModulesLog(): ParcelFileDescriptor? = real?.modulesLog

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

    override fun getXposedVersionName(): String? = real?.xposedVersionName

    override fun clearLogs(verbose: Boolean): Boolean = real?.clearLogs(verbose) ?: false

    override fun getPackageInfo(packageName: String?, flags: Int, uid: Int): PackageInfo? =
        real?.getPackageInfo(packageName, flags, uid)

    override fun forceStopPackage(packageName: String?, userId: Int) {
        real?.forceStopPackage(packageName, userId)
    }

    /** Not delegated. A demo build must not be able to reboot the device by accident. */
    override fun reboot() = Unit

    override fun uninstallPackage(packageName: String?, userId: Int): Boolean =
        real?.uninstallPackage(packageName, userId) ?: false

    override fun getUsers(): MutableList<UserInfo> = real?.users ?: mutableListOf()

    override fun installExistingPackageAsUser(packageName: String?, userId: Int): Int =
        real?.installExistingPackageAsUser(packageName, userId) ?: 0

    override fun startActivityAsUserWithFeature(intent: Intent?, userId: Int): Int =
        real?.startActivityAsUserWithFeature(intent, userId) ?: 0

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

    override fun forcedLauncherIcons(): Boolean = real?.forcedLauncherIcons() ?: true

    override fun setForcedLauncherIcons(force: Boolean) {
        real?.setForcedLauncherIcons(force)
    }

    override fun getLogs(zipFd: ParcelFileDescriptor?) {
        real?.getLogs(zipFd)
    }

    override fun restartFor(intent: Intent?) {
        real?.restartFor(intent)
    }

    override fun optimizePackage(packageName: String?): Boolean =
        real?.optimizePackage(packageName) ?: false

    override fun enableStatusNotification(): Boolean = real?.enableStatusNotification() ?: false

    override fun setEnableStatusNotification(enable: Boolean) {
        real?.setEnableStatusNotification(enable)
    }

    override fun getIncludeNewApps(packageName: String?): Boolean =
        real?.getIncludeNewApps(packageName) ?: false

    override fun setIncludeNewApps(packageName: String?, enable: Boolean): Boolean =
        real?.setIncludeNewApps(packageName, enable) ?: false
}

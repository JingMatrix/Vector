package org.lsposed.lspd;

import rikka.parcelablelist.ParcelableListSlice;
import org.lsposed.lspd.models.UserInfo;
import org.lsposed.lspd.models.Application;
import org.lsposed.lspd.IFrameworkInstallCallback;


interface ILSPManagerService {
    const int DEX2OAT_OK = 0;
    const int DEX2OAT_CRASHED = 1;
    const int DEX2OAT_MOUNT_FAILED = 2;
    const int DEX2OAT_SELINUX_PERMISSIVE = 3;
    const int DEX2OAT_SEPOLICY_INCORRECT = 4;

    /**
     * Which root implementation is managing this device.
     *
     * The failure values are kept apart rather than collapsed into one "unsupported", because they
     * need different sentences from the manager: nothing is installed, what is installed is too
     * old to flash through, or two implementations are fighting and flashing through either would
     * be a guess. NeoZygisk draws exactly these distinctions, and the manager is reporting on the
     * same device state.
     *
     * ROOT_UNKNOWN takes 0 because 0 is also what a binder proxy hands back for a transaction the
     * daemon does not implement. ROOT_NONE used to sit there, so a daemon too old to answer was
     * read as "no root installed", and the manager told a rooted user to go and install the root
     * manager they were already running.
     */
    const int ROOT_UNKNOWN = 0;
    const int ROOT_NONE = 1;
    const int ROOT_TOO_OLD = 2;
    const int ROOT_MULTIPLE = 3;
    const int ROOT_MAGISK = 4;
    const int ROOT_KERNELSU = 5;
    const int ROOT_APATCH = 6;

    /** Nothing was flashed: no usable root implementation. Distinct from any installer exit code. */
    const int INSTALL_NO_ROOT = -1;
    /** The installer binary could not be started at all. */
    const int INSTALL_NOT_EXECUTED = -2;
    /** The zip named by the manager does not exist or is not readable by the daemon. */
    const int INSTALL_NO_SUCH_FILE = -3;

    ParcelableListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess) = 2;

    String[] enabledModules() = 3;

    boolean enableModule(String packageName) = 4;

    boolean disableModule(String packageName) = 5;

    boolean setModuleScope(String packageName, in List<Application> scope) = 6;

    List<Application> getModuleScope(String packageName) = 7;

    boolean isVerboseLog() = 11;

    void setVerboseLog(boolean enabled) = 12;

    ParcelFileDescriptor getVerboseLog() = 16;

    ParcelFileDescriptor getModulesLog() = 17;

    /**
     * The rotated log parts the daemon still holds, oldest first, as bare file names.
     *
     * getVerboseLog()/getModulesLog() only ever hand over the part being written. The daemon keeps
     * ten, so on a device that has been logging for an hour most of the history was unreachable.
     */
    List<String> getLogParts(boolean verbose) = 53;

    /** Opens one part by the name getLogParts() returned. Any other name is refused. */
    ParcelFileDescriptor getLogPart(boolean verbose, String name) = 54;

    long getXposedVersionCode() = 18;

    String getXposedVersionName() = 19;

    int getXposedApiVersion() = 20;

    boolean clearLogs(boolean verbose) = 21;

    PackageInfo getPackageInfo(String packageName, int flags, int uid) = 22;

    void forceStopPackage(String packageName, int userId) = 23;

    void reboot() = 24;

    boolean uninstallPackage(String packageName, int userId) = 25;

    boolean isSepolicyLoaded() = 26;

    List<UserInfo> getUsers() = 27;

    int installExistingPackageAsUser(String packageName, int userId) = 28;

    boolean systemServerRequested() = 29;

    int startActivityAsUserWithFeature(in Intent intent,  int userId) = 30;

    ParcelableListSlice<ResolveInfo> queryIntentActivitiesAsUser(in Intent intent, int flags, int userId) = 31;

    boolean dex2oatFlagsLoaded() = 32;

    /**
     * Whether to force a launcher entry for apps that declare none.
     *
     * Android 10 and later synthesise one; `show_hidden_icon_apps_enabled` decides whether they
     * appear. The argument used to mean the opposite of the manager's own label, and the write
     * itself has been failing on Android 12 and later since the hidden method it used changed
     * shape. Both are fixed together, so the name states the direction: true shows the icons.
     */
    void setForcedLauncherIcons(boolean force) = 33;

    void getLogs(in ParcelFileDescriptor zipFd) = 34;

    void restartFor(in Intent intent) = 35;

    boolean optimizePackage(String packageName) = 40;

    int getDex2OatWrapperCompatibility() = 44;

    boolean enableStatusNotification() = 47;

    void setEnableStatusNotification(boolean enable) = 48;

    boolean getIncludeNewApps(String packageName) = 51;

    boolean setIncludeNewApps(String packageName, boolean enable) = 52;

    /** One of the ROOT_* constants. Detected once and cached, as the detection shells out. */
    int getRootImplementation() = 55;

    /** What the root implementation calls itself, for the manager to quote. Null when unknown. */
    String getRootImplementationVersion() = 56;

    /**
     * Flashes a module zip through whatever root implementation is managing the device.
     *
     * The daemon already runs as root, so this execs the installer directly rather than going
     * through `su` — the same commands the project's own gradle install tasks use. Output is
     * streamed to [callback] *and* written to the daemon's log, so a flash that failed on a device
     * that is now unbootable can still be read out of a saved bug report.
     *
     * Returns immediately; the work runs on a daemon thread and reports through [callback].
     */
    void installFrameworkZip(String zipPath, IFrameworkInstallCallback callback) = 57;

    /**
     * Which build this daemon is, or null when it was not recorded.
     *
     * The version code is the commit count on origin/master, so a branch build and the official
     * build of the same count are indistinguishable by number alone. This is what tells them apart.
     *
     * Not a bare hash, despite the name: it is the build stamp, which names where the build came
     * from as well as what commit it was made from — `93d66473-JingMatrix-Vector` from CI,
     * `93d66473` from a clean local tree, `93d66473+thinkpad` from a modified one. The commit
     * always leads, so a caller that wants it takes the head and not the whole string; `-` is
     * followed by the repository that holds that commit, `+` by the machine holding changes that
     * no repository does.
     */
    String getFrameworkCommit() = 58;

    /** The module loads, as far as the framework is concerned. */
    const int MODULE_LOAD_OK = 0;

    /** Installed and enabled, but no APK path could be resolved for it. */
    const int MODULE_LOAD_NO_APK = 1;

    /**
     * Installed and enabled, and the framework still would not load it.
     *
     * Deliberately not more specific. The loader refuses a zip that will not parse, an APK with no
     * init files and one with no module classes in the same breath, and naming any single one of
     * those would be a guess.
     */
    const int MODULE_LOAD_UNUSABLE = 2;

    /**
     * Built against libxposed API 100, which this framework no longer loads.
     *
     * The one refusal the loader can name, and the one the reader can act on: the module is not
     * broken, it is old, and only its author can move it forward. It used to arrive as
     * MODULE_LOAD_UNUSABLE, which reads as "your module is broken".
     */
    const int MODULE_LOAD_UNSUPPORTED_API = 3;

    /**
     * Modules that are enabled and installed, and that the framework still cannot load.
     *
     * The daemon holds two notions of a module: the configuration, which is what the user asked
     * for, and the realisation — the resolved APK and parsed DEX it hands to a forking process.
     * They can legitimately disagree, and the difference used to be thrown away: such a module
     * simply appeared to be off, having switched itself off for reasons nobody could see. This is
     * that difference, so the manager can say what happened.
     */
    String[] getUnloadableModules() = 59;

    /** Why [getUnloadableModules] lists this one; MODULE_LOAD_OK when it does not. */
    int getModuleLoadState(String packageName) = 60;

    /** The current state of [setForcedLauncherIcons]; true is the platform default. */
    boolean forcedLauncherIcons() = 61;

    /**
     * Restarts the framework without rebooting the device — the "soft reboot".
     *
     * The only way to stop and start the system framework, which is what "force stop" would mean
     * for it. Every app on screen goes with it.
     */
    void softReboot() = 62;

    /**
     * The manager APK the module was flashed with, opened read-only, or null when it cannot be.
     *
     * For installing the manager as an ordinary app. The manager cannot read this file itself:
     * parasitically it runs as the host, whose UID has no business in the module directory, and
     * standalone it is the very thing being replaced. The daemon verifies the signature before
     * handing the descriptor over, so what comes back is the APK this framework would accept as its
     * own manager and not whatever happens to sit at that path.
     */
    ParcelFileDescriptor getManagerApk() = 63;
}

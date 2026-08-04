package org.matrix.vector.ipc;

import org.matrix.vector.ipc.ModuleCode;
import org.matrix.vector.ipc.IModuleService;

/**
 * A module as the daemon hands it to one injected process: who it is, the code to run, and the way
 * back to the daemon that the module's own API calls travel over.
 *
 * <p>Also the payload of a hot reload. {@code IProcessChannel#hotReload} carries a second one of
 * these for a module the process already has, and swapping generations is what the process does
 * with it.</p>
 */
parcelable LoadedModule {
    /** The module app's package name, which is the module's identity everywhere. */
    String packageName;

    /** The module app's app id (uid without the user component). */
    int appId;

    /**
     * The module app's version code, as PackageManager reports it - or 0 when it was not available.
     * system_server is served its modules before PackageManager is published, so its targets start
     * at 0 and the daemon backfills them once the module cache is built; 0 therefore means unknown
     * rather than old, and no target is reported STALE on the strength of it.
     *
     * <p>This is what tells a running target apart from what is installed: a process still running
     * the code of an older version code is {@code STALE}, and is what a hot reload exists to bring
     * forward. Reaches the module app as {@code HookedProcess.loadedVersionCode}, where the API
     * documents it as diagnostic only.</p>
     */
    long versionCode;

    /** Path to the module APK, used to build the native library search path. */
    String apkPath;

    /** The generation of code to load. */
    ModuleCode code;

    /**
     * The module app's own ApplicationInfo, as PackageManager reported it to the daemon.
     *
     * <p>Carried rather than looked up because the process receiving it usually cannot: it runs as
     * the app it was injected into, and system_server is served before PackageManager is published
     * at all. Reaches the module as {@code XposedInterface#getModuleApplicationInfo}, whose name
     * carries the fact worth keeping: it describes the <b>module</b> and never the process it is
     * running in.</p>
     */
    ApplicationInfo applicationInfo;

    /** What {@code XposedInterface}'s remote preferences and remote files calls go through. */
    IModuleService service;
}

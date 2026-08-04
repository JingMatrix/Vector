package org.matrix.vector.manager.demo

import org.matrix.vector.ipc.IManagerService

/**
 * A device state the manager cannot otherwise be shown.
 *
 * Only states that need a *broken or unusual system* are here. Anything reachable by using the app
 * normally — an empty search, airplane mode, a module with nothing selected — is deliberately
 * absent: those get found the first day a build is in anyone's hands, and scripting them would be
 * upkeep with no return.
 *
 * Everything downstream of the binder runs for real against these values: the status derivation,
 * the issue list, the update logic, the install screen. This scripts what the *device* says, not
 * what the UI shows, so a bug in how the manager reacts is still a bug you can see here.
 */
data class DemoScenario(
    val id: String,
    val title: String,
    val summary: String,

    /** False binds nothing at all, which is how the framework reads as not activated. */
    val connected: Boolean = true,

    /** Delay on every status call. Non-zero is the only way to hold "Checking…" still. */
    val stallMillis: Long = 0,
    val sepolicyLoaded: Boolean = true,
    val systemServerAttached: Boolean = true,
    val dex2OatInliningDisabled: Boolean = true,
    val dex2OatWrapperState: Int = IManagerService.DEX2OAT_OK,

    /**
     * What the framework claims to implement.
     *
     * Lowering it is how a module becomes incompatible without fabricating a module: the real ones
     * on the device declare a real minimum, and the framework simply stops meeting it.
     */
    val libxposedApiVersion: Int = -1,
    val frameworkVersionCode: Long = -1,
    val rootImplementation: Int = IManagerService.ROOT_MAGISK,
    val install: InstallScript = InstallScript.SUCCEEDS,

    /**
     * What version the device claims its installed modules are.
     *
     * The same trick the framework update uses, one level down: whether a module is out of date is
     * decided by comparing the store catalogue against the version the *daemon* reports, so
     * reporting an old one turns every module the catalogue knows into an update. The catalogue,
     * the releases and the APKs are all genuinely the store's — the only lie is the number this
     * device claims to be on, which is the one thing that cannot be arranged without keeping a
     * stack of outdated module APKs around to install.
     */
    val moduleVersions: ModuleVersionScript = ModuleVersionScript.REAL,
) {

    /** Whether installed module versions are passed through or rewritten. */
    enum class ModuleVersionScript {
        REAL,
        OUTDATED,
    }

    /**
     * How a flash behaves when the install screen asks for one.
     *
     * Reachable through this seam after all, which was not obvious: whether an update *exists* is
     * decided by comparing the release list against the installed version code — and that version
     * comes from the daemon, not from GitHub. Reporting an old one is enough to make a real
     * release look like an update, so the whole flow can be exercised without faking any network
     * traffic. The release list itself is genuinely GitHub's, which makes this closer to the real
     * thing than a canned one would be.
     */
    enum class InstallScript {
        SUCCEEDS,

        /**
         * Fails after output has already been streamed.
         *
         * The one worth having: a flash that fails *before* it starts is a message, but a flash
         * that dies halfway leaves the module tree in whatever state the installer reached, and
         * that is the case the screen has to report usefully.
         */
        FAILS_PARTWAY,

        /** Refused outright, with no output. */
        NO_ROOT,
    }

    companion object {
        /** -1 means "whatever the real daemon says", so a scenario only lies where it means to. */
        const val PASS_THROUGH = -1
    }
}

/**
 * The menu.
 *
 * Ordered by how hard the state is to reach honestly, not by severity: the ones at the top cannot
 * be produced on a working phone at all.
 */
val DEMO_SCENARIOS: List<DemoScenario> =
    listOf(
        DemoScenario(
            id = "healthy",
            title = "Healthy",
            summary = "Pass everything through to the real daemon. The way back.",
        ),
        DemoScenario(
            id = "sepolicy",
            title = "SELinux policy not loaded",
            summary = "Degraded, one cause. Needs a root implementation that skipped our rules.",
            sepolicyLoaded = false,
        ),
        DemoScenario(
            id = "system-server",
            title = "System framework injection failed",
            summary = "Degraded, one cause. Normally needs another root module interfering.",
            systemServerAttached = false,
        ),
        DemoScenario(
            id = "dex2oat",
            title = "Dex optimizer wrapper unavailable",
            summary = "Degraded, one cause. Needs system properties removed or changed.",
            dex2OatInliningDisabled = false,
            dex2OatWrapperState = IManagerService.DEX2OAT_MOUNT_FAILED,
        ),
        DemoScenario(
            id = "all-issues",
            title = "All three causes at once",
            summary = "Whether the issue list reads as a list or as a wall.",
            sepolicyLoaded = false,
            systemServerAttached = false,
            dex2OatInliningDisabled = false,
            dex2OatWrapperState = IManagerService.DEX2OAT_SEPOLICY_INCORRECT,
        ),
        DemoScenario(
            id = "inactive",
            title = "Framework not activated",
            summary = "No daemon at all. Every screen that needs one has to say so.",
            connected = false,
        ),
        DemoScenario(
            id = "checking",
            title = "Checking, held still",
            summary = "The transient state on arrival, stalled for eight seconds.",
            stallMillis = 8_000,
        ),
        DemoScenario(
            id = "api-too-old",
            title = "Framework below what modules need",
            summary = "API 82. Installed modules that need more become incompatible.",
            libxposedApiVersion = 82,
        ),
        DemoScenario(
            id = "root-none",
            title = "No root implementation",
            summary = "Nothing to flash through. The install path must refuse, not fail.",
            rootImplementation = IManagerService.ROOT_NONE,
            install = DemoScenario.InstallScript.NO_ROOT,
        ),
        DemoScenario(
            id = "root-multiple",
            title = "Two root implementations fighting",
            summary = "Flashing through either would be a guess, and must be named as such.",
            rootImplementation = IManagerService.ROOT_MULTIPLE,
            install = DemoScenario.InstallScript.NO_ROOT,
        ),
        DemoScenario(
            id = "root-ksu",
            title = "KernelSU",
            summary = "The install path quotes the implementation it found.",
            rootImplementation = IManagerService.ROOT_KERNELSU,
        ),
        DemoScenario(
            id = "root-apatch",
            title = "APatch",
            summary = "As above, third implementation.",
            rootImplementation = IManagerService.ROOT_APATCH,
        ),
        DemoScenario(
            id = "update-available",
            title = "An update is available",
            summary = "Reports version 1, so a real release becomes an update. Shows the picker.",
            frameworkVersionCode = 1,
        ),
        DemoScenario(
            id = "install-fails",
            title = "Flash that dies halfway",
            summary = "Output already streamed, then a non-zero exit. The case that bites.",
            frameworkVersionCode = 1,
            install = DemoScenario.InstallScript.FAILS_PARTWAY,
        ),
        DemoScenario(
            id = "modules-outdated",
            title = "Every module is out of date",
            summary =
                "Reports old versions, so the store is ahead of all of them. Installs are real.",
            moduleVersions = DemoScenario.ModuleVersionScript.OUTDATED,
        ),
    )

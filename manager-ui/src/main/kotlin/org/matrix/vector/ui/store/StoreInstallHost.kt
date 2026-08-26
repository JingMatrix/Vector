package org.matrix.vector.ui.store

import kotlinx.coroutines.flow.StateFlow

/**
 * The store-driven install capability, injected into the shared Details screen.
 *
 * Vector wraps its module installer, its post-install bookkeeping and its device APK inspection here.
 * An app that cannot install modules from the store (LSPatch) passes null, and the shared screen
 * collapses to open-in-browser links: no install bar, no asset picker, no confirm dialog, and the
 * Information tab falls back to the catalogue's declared scope.
 */
interface StoreInstallHost {
    /** Live install progress; drives the install bar and the resting button label. */
    val installState: StateFlow<InstallStep>

    /** Whether the platform installs silently (shell-mode) — decides the confirm dialog's hint. */
    val silentInstall: Boolean

    /** Device-side scope of the installed copy, for the Information tab (empty when unknown). */
    val installedScope: StateFlow<List<String>>

    /** Whether the installed copy is a legacy-framework module (its scope names are swapped). */
    val installedIsLegacy: StateFlow<Boolean>

    /** The user id of the installed copy, for the configuration screen; null when nothing is installed. */
    val installedUserId: StateFlow<Int?>

    /** Download and install [asset], recording it against [releaseVersion]. */
    fun install(asset: ReleaseAsset, releaseVersion: RepoVersion?)

    /** Clear a finished or failed result back to idle. */
    fun acknowledge()
}

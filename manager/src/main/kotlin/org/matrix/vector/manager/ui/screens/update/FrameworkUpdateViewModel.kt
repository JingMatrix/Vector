package org.matrix.vector.manager.ui.screens.update

import org.matrix.vector.manager.data.repository.ReleaseDirection
import org.matrix.vector.manager.data.github.FrameworkRelease
import org.matrix.vector.manager.data.github.ZipVariant
import org.matrix.vector.manager.data.github.CanaryArtifact
import kotlinx.coroutines.flow.combine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.data.repository.FlashStep
import org.matrix.vector.manager.data.repository.FrameworkUpdateState
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW

/** Which root implementation is in charge, and whether it can be flashed through. */
data class RootState(val code: Int = ILSPManagerService.ROOT_UNKNOWN, val version: String? = null) {

    // Named implementations only. ROOT_UNKNOWN is also what a binder proxy returns for a
    // transaction the daemon does not implement, so it has to refuse rather than guess at an
    // installer to hand the zip to.
    val canFlash: Boolean
        get() =
            code == ILSPManagerService.ROOT_MAGISK ||
                code == ILSPManagerService.ROOT_KERNELSU ||
                code == ILSPManagerService.ROOT_APATCH

    /**
     * The sentence to show when flashing is not possible.
     *
     * Null when it is, and four different strings when it is not — "nothing installed", "too old",
     * "two of them" and "this daemon does not say" need four different actions from the reader, and
     * collapsing them into one "unsupported" would tell someone with two root managers to go
     * install a root manager.
     */
    @androidx.compose.runtime.Composable
    fun label(): String? =
        when (code) {
            ILSPManagerService.ROOT_TOO_OLD ->
                androidx.compose.ui.res.stringResource(
                    org.matrix.vector.manager.R.string.update_root_too_old
                )
            ILSPManagerService.ROOT_MULTIPLE ->
                androidx.compose.ui.res.stringResource(
                    org.matrix.vector.manager.R.string.update_root_multiple
                )
            ILSPManagerService.ROOT_NONE ->
                androidx.compose.ui.res.stringResource(
                    org.matrix.vector.manager.R.string.update_no_root
                )
            ILSPManagerService.ROOT_UNKNOWN ->
                androidx.compose.ui.res.stringResource(
                    org.matrix.vector.manager.R.string.update_root_unknown
                )
            else -> null
        }
}

class FrameworkUpdateViewModel : ViewModel() {

    private val daemon = ServiceLocator.daemon
    private val updates = ServiceLocator.frameworkUpdates
    private val installer = ServiceLocator.frameworkInstaller

    val update: StateFlow<FrameworkUpdateState> = updates.state

    val flash: StateFlow<FlashStep> = installer.state

    private val settings = ServiceLocator.settings

    private val explicit = MutableStateFlow<Long?>(null)

    /**
     * The release the screen is about.
     *
     * Defaults to whatever is worth offering — the update if there is one, otherwise the newest
     * build on this reader's channel, which is usually the installed one — and follows an explicit
     * choice once made. Held as a version code rather than the object so a refresh that returns
     * fresh instances does not silently drop the selection.
     *
     * The pin is resolved against the whole catalogue, both channels, while the defaults stay on
     * the reader's own. Asking is not the same as being offered: someone on a release build who
     * opened the canary list and pressed install on a row has named the build they want, and
     * looking that number up in the release-only list finds nothing and quietly hands them the
     * stable release instead.
     */
    val selected: StateFlow<FrameworkRelease?> =
        combine(update, explicit) { state, pinned ->
                pinned?.let { code -> state.catalog.firstOrNull { it.versionCode == code } }
                    ?: state.available
                    ?: state.history.firstOrNull()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The builds the version picker lists.
     *
     * The reader's own channel, unless the page is sitting on a canary — then all of them. A page
     * opened from the canary list is a page about prereleases, and a picker that answered it with
     * the stable list would offer no way back to the build the reader had just been looking at.
     */
    val history: StateFlow<List<FrameworkRelease>> =
        combine(update, selected) { state, release ->
                if (release?.isCanary == true) state.catalog else state.history
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Where the selected release sits relative to what is running. */
    val direction: StateFlow<ReleaseDirection> =
        combine(update, selected) { state, release ->
                when {
                    release == null -> ReleaseDirection.Installed
                    release.versionCode > state.installedVersionCode -> ReleaseDirection.Newer
                    release.versionCode < state.installedVersionCode -> ReleaseDirection.Older
                    else -> ReleaseDirection.Installed
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ReleaseDirection.Installed,
            )

    fun select(release: FrameworkRelease) {
        explicit.value = release.versionCode
    }

    /**
     * Select by number, for a caller that has a build rather than a release.
     *
     * The canary list arrives here naming the build it was showing. It holds no [FrameworkRelease]
     * — it reads the same prereleases through a different shape — but CI tags every canary
     * `canary-<versionCode>`, so the number is the one thing both sides already agree on. Pinning
     * it before the list has loaded is fine: [selected] resolves the number against the catalogue
     * whenever that arrives.
     */
    fun select(versionCode: Long) {
        explicit.value = versionCode
    }

    /**
     * The zip the user has chosen, or the release's own default.
     *
     * Falls back rather than refusing when the remembered variant is not in this release: a
     * release that published only one build must still be installable by someone whose last
     * choice was the other one.
     */
    val chosenZip: StateFlow<CanaryArtifact?> =
        combine(selected, settings.updateVariant) { release, variant ->
                val zips = release?.zips.orEmpty()
                zips.firstOrNull { it.variant.key == variant } ?: release?.defaultZip
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun chooseVariant(variant: ZipVariant) = settings.setUpdateVariant(variant.key)

    /**
     * Everything the installer has said, straight from the installer.
     *
     * Not a copy kept alive on this scope, the way it used to be: the flash outlives the screen
     * now, and a screen that comes back has to find the log it left rather than an empty one that
     * fills in a frame later.
     */
    val lines: StateFlow<List<String>> = installer.lines

    private val _root = MutableStateFlow(RootState())
    val root: StateFlow<RootState> = _root.asStateFlow()

    init {
        viewModelScope.launch {
            // Two logs for the four requests these blocks make. They all fail from the same
            // unreachable binder, so only the two that decide what the screen says are recorded;
            // the root version and the framework commit take their default in silence.
            val code =
                daemon.getRootImplementation().getOrElse { e ->
                    logW("update: root implementation unreadable, screen will say it is unknown", e)
                    ILSPManagerService.ROOT_UNKNOWN
                }
            val version = daemon.getRootImplementationVersion().getOrNull()
            _root.value = RootState(code, version)
        }
        viewModelScope.launch {
            val installed =
                daemon.getXposedVersionCode().getOrElse { e ->
                    logW("update: installed framework version unavailable, update check skipped", e)
                    0L
                }
            updates.refresh(installed, daemon.getFrameworkCommit().getOrNull())
        }
    }

    /**
     * Asks for the flash. Nothing here waits on it.
     *
     * The installer runs it on a scope of its own, because this one goes when the screen does: the
     * view model is scoped to the nav entry, so a back gesture during a flash used to cancel the
     * wait for the exit code and leave the bar spinning for the life of the process over an install
     * the daemon had quietly finished.
     */
    fun flash() {
        val zip =
            chosenZip.value
                ?: run {
                    logE(
                        "update: flash pressed with no zip selected, " +
                            "release=${selected.value?.tag}",
                    )
                    return
                }
        val url =
            zip.downloadUrl
                ?: run {
                    logE("update: flash pressed but zip ${zip.name} has no download url")
                    return
                }
        installer.start(url, zip.sizeInBytes, zip.name)
    }

    /**
     * Calls off the download, and only the download.
     *
     * The flash behind it has no equivalent and is not meant to: once the daemon has started an
     * installer there is nothing left to call off, and the wait for its exit code is the one thing
     * the installer's own scope exists to protect. A transfer is the other case entirely — it can
     * be tens of megabytes on a connection the reader is paying for, and one that runs to the end
     * after they have changed their mind goes straight on to flash the build they turned down.
     */
    fun cancelDownload() = installer.cancelDownload()

    /**
     * Clears a flash that has finished, one way or the other.
     *
     * A result stays up until it is read, which is the whole point of the installer outliving the
     * screen — but it has to be possible to put it down again, or a build that was installed and
     * not restarted right away hides the picker behind itself until the process dies.
     */
    fun acknowledge() = installer.acknowledge()

    suspend fun reboot() {
        daemon.reboot().onFailure { logE("update: reboot request failed", it) }
    }
}

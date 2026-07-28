package org.matrix.vector.manager.ui.screens.update
import android.util.Log
import org.matrix.vector.manager.Constants

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
     * known build, which is usually the installed one — and follows an explicit choice once made.
     * Held as a version code rather than the object so a refresh that returns fresh instances does
     * not silently drop the selection.
     */
    val selected: StateFlow<FrameworkRelease?> =
        combine(update, explicit) { state, pinned ->
                val list = state.history
                pinned?.let { code -> list.firstOrNull { it.versionCode == code } }
                    ?: state.available
                    ?: list.firstOrNull()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
     * it before the list has loaded is fine: [selected] resolves the number against the history
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

    val lines: StateFlow<List<String>> =
        installer.lines.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _root = MutableStateFlow(RootState())
    val root: StateFlow<RootState> = _root.asStateFlow()

    init {
        viewModelScope.launch {
            // Two logs for the four requests these blocks make. They all fail from the same
            // unreachable binder, so only the two that decide what the screen says are recorded;
            // the root version and the framework commit take their default in silence.
            val code =
                daemon.getRootImplementation().getOrElse { e ->
                    Log.w(
                        Constants.TAG,
                        "update: root implementation unreadable, screen will say it is unknown",
                        e,
                    )
                    ILSPManagerService.ROOT_UNKNOWN
                }
            val version = daemon.getRootImplementationVersion().getOrNull()
            _root.value = RootState(code, version)
        }
        viewModelScope.launch {
            val installed =
                daemon.getXposedVersionCode().getOrElse { e ->
                    Log.w(
                        Constants.TAG,
                        "update: installed framework version unavailable, update check skipped",
                        e,
                    )
                    0L
                }
            updates.refresh(installed, daemon.getFrameworkCommit().getOrNull())
        }
    }

    fun flash() {
        val zip =
            chosenZip.value
                ?: run {
                    Log.e(
                        Constants.TAG,
                        "update: flash pressed with no zip selected, " +
                            "release=${selected.value?.tag}",
                    )
                    return
                }
        val url =
            zip.downloadUrl
                ?: run {
                    Log.e(
                        Constants.TAG,
                        "update: flash pressed but zip ${zip.name} has no download url",
                    )
                    return
                }
        viewModelScope.launch { installer.flash(url, zip.sizeInBytes, zip.name) }
    }

    suspend fun reboot() {
        daemon.reboot().onFailure { Log.e(Constants.TAG, "update: reboot request failed", it) }
    }
}

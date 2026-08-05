package org.matrix.vector.manager.ui.screens.repo

import kotlinx.coroutines.flow.map
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.matrix.vector.manager.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matrix.vector.manager.data.model.OnlineModule
import org.matrix.vector.manager.data.model.Release
import org.matrix.vector.manager.data.model.ReleaseAsset
import org.matrix.vector.manager.data.model.RepoVersion
import org.matrix.vector.manager.data.model.StoreInstall
import org.matrix.vector.manager.data.model.versionCodeCompat
import org.matrix.vector.manager.data.repository.InstallStep
import org.matrix.vector.manager.data.repository.ModuleInstaller
import org.matrix.vector.manager.data.repository.RepoRepository
import org.matrix.vector.manager.data.repository.SettingsRepository

/** How the second, richer fetch is going. The page is readable in all three states. */
enum class DetailFetch {
    Loading,
    Loaded,
    Unavailable,
}

/**
 * Everything the detail page shows.
 *
 * [module] is never null once the catalogue is in memory, because the list entry seeds it. That is
 * the whole design of this screen: the catalogue already carries the description, the summary, the
 * scope, the collaborators and the newest release *with its APK*, so the page can paint — and be
 * installed from — before any request is made, and a failed request costs the README rather than
 * the page.
 */
data class RepoDetailsState(
    val module: OnlineModule? = null,
    val releases: List<Release> = emptyList(),
    val installed: RepoVersion? = null,
    val latest: RepoVersion? = null,
    val fetch: DetailFetch = DetailFetch.Loading,
    val channel: StoreChannel = StoreChannel.Stable,
    /** What the Store last installed for this module, if the Store is what installed it. */
    val storeInstall: StoreInstall? = null,
) {
    /**
     * As `StoreEntry.upgradable`, minus the mute: this page is a module the reader went looking for.
     *
     * The note is honoured here as well, and has to be. It is the one thing that keeps this badge
     * from disagreeing with the list that led to it — see [StoreInstall].
     */
    val upgradable: Boolean
        get() =
            installed != null &&
                latest != null &&
                storeInstall?.satisfies(latest, installed) != true &&
                latest.upgradableOver(installed.versionCode, installed.versionName)

    /** As `StoreEntry.sameVersion`: what the bar may call the offer, not whether to make it. */
    val sameVersion: Boolean
        get() = latest?.sameVersionAs(installed) == true
}

class RepoDetailsViewModel(
    private val packageName: String,
    private val repository: RepoRepository,
    private val installer: ModuleInstaller,
    private val settings: SettingsRepository,
    /** Installs outlive this screen; see [install]. */
    private val backgroundScope: CoroutineScope,
) : ViewModel() {

    /**
     * What the installed copy of this module says it hooks, read from its own APK.
     *
     * The catalogue's `scope` is optional metadata and most authors omit it — 510 of the 814
     * entries served today carry none — so the information panel says "not declared" for the
     * majority of modules. For a module that is *installed*, though, the authoritative list is
     * right there in the APK, in `META-INF/xposed/scope.list` for a modern module or the
     * `xposedscope` metadata for a legacy one, and this app already knows how to read it: that is
     * how the scope editor knows what a module asked for.
     *
     * So the catalogue is preferred when it has an answer — it describes the *published* module
     * rather than whichever build happens to be installed — and this fills the silence when it
     * does not.
     */
    private val _installedScope = MutableStateFlow<List<String>>(emptyList())
    val installedScope: StateFlow<List<String>> = _installedScope.asStateFlow()

    /**
     * Whether the copy on this device is a legacy module, which decides how to read the
     * *catalogue's* scope.
     *
     * The two generations spell the framework differently — see
     * `ModuleDetection.swapLegacyFrameworkNames` — and the catalogue entry is written in whichever
     * vocabulary its module belongs to. Nothing in the payload says which that is, so the installed
     * copy is the only thing that can answer it, and only for a module that is installed at all.
     *
     * False while the module is absent, which is the honest answer rather than a safe one: with
     * nothing on the device to inspect there is no way to know, and guessing would relabel a
     * target on the strength of nothing. The consequence is a legacy module whose catalogue names
     * `android` reading as `android` until it is installed and as `system` afterwards — visibly
     * odd, and less misleading than the alternative, which is asserting one of the two at random.
     */
    private val _installedIsLegacy = MutableStateFlow(false)
    val installedIsLegacy: StateFlow<Boolean> = _installedIsLegacy.asStateFlow()

    private fun readInstalledScope() {
        viewModelScope.launch(Dispatchers.IO) {
            val packageManager = ServiceLocator.context.packageManager
            val info =
                runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
                    ?: return@launch
            val appInfo = info.applicationInfo ?: return@launch
            val manifest =
                ServiceLocator.moduleDetection.inspect(
                    appInfo,
                    packageManager,
                    info.versionCodeCompat,
                    info.lastUpdateTime,
                )
            _installedScope.value = manifest.scope
            _installedIsLegacy.value = manifest.isLegacy
        }
    }

    private val _detail = MutableStateFlow<OnlineModule?>(null)
    private val _fetch = MutableStateFlow(DetailFetch.Loading)

    val installState: StateFlow<InstallStep> = installer.state

    /** Whether this module has been told to stop reporting updates. */
    val updatesMuted: StateFlow<Boolean> =
        settings.mutedUpdates
            .map { packageName in it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setUpdatesMuted(muted: Boolean) = settings.setUpdatesMuted(packageName, muted)

    /**
     * The two preferences this page reads, as one value.
     *
     * Paired rather than passed separately because `combine` takes five flows and this page already
     * watches five things of its own.
     */
    private data class Preferences(val channel: StoreChannel, val storeInstall: StoreInstall?)

    private fun preferences(): Flow<Preferences> =
        combine(settings.updateChannel, settings.storeInstalls) { channelPreference, installs ->
            Preferences(StoreChannel.of(channelPreference), installs[packageName])
        }

    val state: StateFlow<RepoDetailsState> =
        combine(
                repository.catalog,
                _detail,
                _fetch,
                repository.installedVersions,
                preferences(),
            ) { catalog, detail, fetch, installed, preferences ->
                val seed = catalog.modules.firstOrNull { it.name == packageName }
                val module = detail ?: seed
                val channel = preferences.channel
                RepoDetailsState(
                    module = module,
                    releases = releasesFor(module, channel),
                    installed = installed[packageName],
                    latest = latestFor(module, channel),
                    fetch = fetch,
                    channel = channel,
                    storeInstall = preferences.storeInstall,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepoDetailsState())

    init {
        fetchDetails()
        readInstalledScope()
    }

    fun fetchDetails() {
        viewModelScope.launch {
            _fetch.value = DetailFetch.Loading
            val fetched = repository.details(packageName)
            // A failure is not an error screen. The seeded entry is still on display; all that is
            // missing is the README and the older releases, and the page says so quietly.
            _detail.value = fetched ?: _detail.value
            _fetch.value = if (fetched != null) DetailFetch.Loaded else DetailFetch.Unavailable
        }
    }

    /**
     * Downloads and installs [asset].
     *
     * Deliberately **not** on `viewModelScope`. Navigating back would cancel the transfer halfway
     * through, and the user has already consented to this install — leaving the screen is not a
     * change of mind. The installer's state is a single shared flow, so coming back re-attaches to
     * the progress that kept running.
     */
    fun install(asset: ReleaseAsset, release: RepoVersion?) {
        backgroundScope.launch {
            if (!installer.install(packageName, asset)) return@launch
            // The version has to come from this read rather than from the platform directly: it is
            // the one the offer is compared against. See RepoRepository.readInstalled.
            val installed = repository.readInstalled()[packageName]
            if (release != null && installed != null) {
                settings.noteStoreInstall(packageName, StoreInstall(release, installed))
            }
        }
    }

    fun acknowledgeInstall() = installer.acknowledge()

    /**
     * Which releases belong to the current channel.
     *
     * Resolved by [releasesOn] rather than here, so that what this tab lists, what the update badge
     * in the Store list compares against, and what the install bar downloads are one rule with one
     * implementation rather than three that can disagree on the prerelease channel.
     */
    private fun releasesFor(module: OnlineModule?, channel: StoreChannel): List<Release> =
        module?.releasesOn(channel).orEmpty()

    private fun latestFor(module: OnlineModule?, channel: StoreChannel): RepoVersion? =
        module?.latestOn(channel)
}

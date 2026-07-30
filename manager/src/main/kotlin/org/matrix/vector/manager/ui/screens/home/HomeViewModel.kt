package org.matrix.vector.manager.ui.screens.home
import android.util.Log
import org.matrix.vector.manager.Constants
import kotlinx.coroutines.CancellationException

import org.matrix.vector.manager.data.repository.FrameworkUpdateState
import org.matrix.vector.manager.data.repository.LaunchShortcut
import org.matrix.vector.manager.data.repository.ManagerInstallStep
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.random.Random
import kotlinx.coroutines.launch
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.data.github.CommunityFeed
import org.matrix.vector.manager.data.github.GitHubAuth
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.ui.components.FrameworkState

/** A specific reason the framework is degraded, so the UI never has to say merely "something". */
enum class HealthIssue {
    SepolicyNotLoaded,
    SystemServerNotInjected,
    Dex2oatWrapperBroken,
}

data class FrameworkStatus(
    val state: FrameworkState = FrameworkState.Checking,
    val versionName: String? = null,
    val versionCode: Long = 0,
    val apiVersion: Int? = null,
    val issues: List<HealthIssue> = emptyList(),
    val dex2oatCompatibility: Int = ILSPManagerService.DEX2OAT_OK,
    val sepolicyLoaded: Boolean = false,
    val systemServerInjected: Boolean = false,
    /**
     * Which build the running framework is: where it came from and from what commit.
     *
     * The version code is a commit count, so it cannot tell a branch build from the official build
     * of the same depth — and the framework and the manager are flashed separately, so they are
     * not always the same build. Naming both is the difference between a bug report that can be
     * placed and one that cannot. A CI build reads `JingMatrix-Vector-93d66473`, a clean local one
     * the bare hash, and a local build from a modified tree adds the machine that made it.
     */
    val commit: String? = null,
) {
    val versionLabel: String?
        get() = versionName?.let { if (versionCode > 0) "$it ($versionCode)" else it }
}

/**
 * How the manager can be reached on this device.
 *
 * Parasitically it is not installed, so the launcher has nothing to show — which is what #815
 * reported. Four routes lead back in: a pinned shortcut, an installed copy, the status
 * notification, and the two that need no setup at all, the dialer code and the root manager's
 * action button. The first three are what the status page offers, and the fields below decide
 * which are worth offering — any one of them already solves it, and a launcher that refuses pin
 * requests rules the first out entirely.
 */
data class ManagerPresence(
    /** Injected into the host rather than installed. False leaves nothing here to offer. */
    val parasitic: Boolean = true,
    val shortcutSupported: Boolean = false,
    val shortcutPinned: Boolean = false,
    val installed: Boolean = false,
    /**
     * The status notification is a way in, not only a status.
     *
     * Its content intent opens the manager — see the daemon's NotificationManager — so a device
     * showing it is a device with a tap-sized route back, and it is on by default. Leaving it out
     * of this made the first-launch prompt claim there was no way back in to a reader who was
     * looking at one.
     */
    val notificationEnabled: Boolean = false,
    /** One of the ILSPManagerService.ROOT_* constants, for naming the action button's owner. */
    val rootImplementation: Int = 0,
) {
    /** True when opening the manager currently depends on remembering how. */
    val unreachable: Boolean
        get() = parasitic && !shortcutPinned && !installed && !notificationEnabled
}

data class DeviceInfo(
    val androidRelease: String = Build.VERSION.RELEASE ?: "",
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val device: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
    val abi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "",
)

class HomeViewModel(
    private val daemon: DaemonClient,
    private val github: GitHubRepository,
    private val auth: GitHubAuth,
) : ViewModel() {

    val signInState: StateFlow<org.matrix.vector.manager.data.github.SignInState> = auth.state

    val openLinksExternally: StateFlow<Boolean> = ServiceLocator.settings.openLinksExternally

    val headerAmbience: StateFlow<String> = ServiceLocator.settings.headerAmbience

    val isSignInConfigured: Boolean
        get() = auth.isConfigured

    fun signIn() {
        viewModelScope.launch { auth.signIn() }
    }

    fun signOut() {
        auth.signOut()
        // The rate limit changes with the token, so the feed is worth re-reading.
        refreshFeed(GitHubRepository.Freshness.Force)
    }

    fun cancelSignIn() = auth.cancel()

    private val _status = MutableStateFlow(FrameworkStatus())
    val status: StateFlow<FrameworkStatus> = _status.asStateFlow()

    private val _feed = MutableStateFlow(CommunityFeed())
    val feed: StateFlow<CommunityFeed> = _feed.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val device = DeviceInfo()

    // --- how the manager can be reached -------------------------------------------------------
    // Above `init` on purpose: a Kotlin class body initialises top to bottom, so the refresh in
    // `init` would run against a `_presence` that does not exist yet.

    private val _presence = MutableStateFlow(ManagerPresence())

    /**
     * How this manager can be opened, and how it currently is.
     *
     * Read from the launcher and the package manager rather than remembered, because both can
     * change while the app is not running: a shortcut can be dragged off the home screen, and the
     * manager can be installed or uninstalled from anywhere.
     */
    val presence: StateFlow<ManagerPresence> = _presence.asStateFlow()

    val managerInstall: StateFlow<ManagerInstallStep> = ServiceLocator.managerInstaller.state

    /** Set once the reader has said they do not want to be offered a launcher icon again. */
    val launcherPromptDismissed: StateFlow<Boolean>
        get() = ServiceLocator.settings.launcherPromptDismissed

    fun refreshPresence() {
        val context = ServiceLocator.context
        _presence.update {
            // Everything here is answered locally, so it stays synchronous and the first frame is
            // already right. The notification and the root implementation come from the daemon and
            // are folded in as they arrive, leaving whatever was last known in the meantime.
            it.copy(
                parasitic = LaunchShortcut.isParasitic(context),
                shortcutSupported = LaunchShortcut.isSupported(context),
                shortcutPinned = LaunchShortcut.isPinned(context),
                installed = ServiceLocator.managerInstaller.isInstalled(),
            )
        }
        viewModelScope.launch {
            val root = daemon.getRootImplementation().getOrNull()
            if (root != null) _presence.update { it.copy(rootImplementation = root) }
        }
    }

    /** Removes the copy of the manager whose signature is refusing the install. */
    fun removeConflictingManager() {
        viewModelScope.launch {
            ServiceLocator.managerInstaller.removeConflicting()
            refreshPresence()
        }
    }

    /**
     * Asks the launcher to pin a Vector icon.
     *
     * Returns whether the request was accepted, not whether an icon appeared: the launcher puts its
     * own confirmation in front of the user, and may never come back. When it does,
     * [refreshPresence] runs and the row that offered this reports that it is done.
     */
    fun requestShortcut(): Boolean =
        LaunchShortcut.request(ServiceLocator.context) { refreshPresence() }

    fun installManagerApp() {
        viewModelScope.launch {
            ServiceLocator.managerInstaller.install()
            refreshPresence()
        }
    }

    fun acknowledgeManagerInstall() = ServiceLocator.managerInstaller.acknowledge()

    fun dismissLauncherPrompt() = ServiceLocator.settings.dismissLauncherPrompt()

    init {
        refreshPresence()
        // The binder may arrive after this ViewModel exists — injection order is not ours to
        // control — so status is re-derived whenever it changes rather than read once in init.
        viewModelScope.launch {
            ServiceLocator.service.collect { service ->
                refreshStatus(service)
                // Both switches on the status page hold the daemon's state rather than ours, and
                // this is the moment there is a daemon to ask.
                if (service != null) refreshToggles()
            }
        }
        // Opening Home is not a reason to talk to GitHub. The page renders from disk every time
        // and only occasionally goes and checks — the window it shows changes a few times a week
        // at most, and the user's battery and their share of an anonymous rate limit are worth
        // more than redrawing identical rows. Pull-to-refresh is always there when they do want it.
        val checkNow = Random.nextFloat() < REVALIDATE_PROBABILITY
        refreshFeed(
            if (checkNow) GitHubRepository.Freshness.Revalidate
            else GitHubRepository.Freshness.Cached
        )
        viewModelScope.launch {
            // drop(1): the value on subscription is the window already rendered.
            ServiceLocator.settings.activityWindowMonths.drop(1).collect {
                _windowChanged.value = true
                // From disk, not from GitHub. The archive already holds the commits; the window is
                // only a view of it, so re-cutting it costs nothing and the feed answers on the
                // frame after the sheet closes.
                _feed.value = github.load(GitHubRepository.Freshness.Cached)
            }
        }
    }

    private suspend fun refreshStatus(service: ILSPManagerService?) {
        if (service == null || !daemon.isAlive) {
            _status.value = FrameworkStatus(state = FrameworkState.Inactive)
            return
        }

        val versionName = daemon.getXposedVersionName().getOrNull()
        val commit = daemon.getFrameworkCommit().getOrNull()
        val versionCode =
            daemon
                .getXposedVersionCode()
                .onFailure { e ->
                    Log.w(
                        Constants.TAG,
                        "status: framework version code unavailable, update check skipped",
                        e,
                    )
                }
                .getOrDefault(0L)
        val api = daemon.getXposedApiVersion().getOrNull()

        // One line for both, because they fail together on a wedged binder and only these two
        // defaults synthesise a red HealthIssue card.
        val sepolicyResult = daemon.isSepolicyLoaded()
        val systemServerResult = daemon.systemServerRequested()
        val healthFailure = sepolicyResult.exceptionOrNull() ?: systemServerResult.exceptionOrNull()
        if (healthFailure != null && healthFailure !is CancellationException) {
            Log.w(
                Constants.TAG,
                "status: framework health read failed, defaulting to sepolicy/system_server " +
                    "not loaded",
                healthFailure,
            )
        }
        val sepolicy = sepolicyResult.getOrDefault(false)
        val systemServer = systemServerResult.getOrDefault(false)
        val dex2oat =
            daemon.getDex2OatWrapperCompatibility().getOrDefault(ILSPManagerService.DEX2OAT_OK)
        val dex2oatFlags = daemon.dex2oatFlagsLoaded().getOrDefault(true)

        val issues = buildList {
            if (!sepolicy) add(HealthIssue.SepolicyNotLoaded)
            if (!systemServer) add(HealthIssue.SystemServerNotInjected)
            // The wrapper and the property are alternatives, not a pair: the daemon deletes
            // `dalvik.vm.dex2oat-flags` when it mounts the wrapper over dex2oat and sets it when
            // it unmounts, so either route suppresses the inlining. A wrapper that is not OK
            // therefore only costs anything when the flag did not load either.
            if (dex2oat != ILSPManagerService.DEX2OAT_OK && !dex2oatFlags) {
                add(HealthIssue.Dex2oatWrapperBroken)
            }
        }

        _status.value =
            FrameworkStatus(
                state = if (issues.isEmpty()) FrameworkState.Active else FrameworkState.Degraded,
                commit = commit,
                versionName = versionName,
                versionCode = versionCode,
                apiVersion = api,
                issues = issues,
                dex2oatCompatibility = dex2oat,
                sepolicyLoaded = sepolicy,
                systemServerInjected = systemServer,
            )

        if (versionCode > 0) {
            viewModelScope.launch { ServiceLocator.frameworkUpdates.refresh(versionCode, commit) }
        }
    }

    // --- filtering the rail by author ---------------------------------------------------------

    /**
     * The logins whose commits are being shown, lower-cased; empty means everyone.
     *
     * A set rather than a single login because collaboration is the thing this screen is about:
     * two people's rails side by side answers "what did we do together", which one person's rail
     * cannot. Emptying it is the only way out of filter mode, so there is exactly one way back to
     * the whole history and it is the same gesture that got you here.
     */
    private val _authorFilter = MutableStateFlow<Set<String>>(emptySet())
    val authorFilter: StateFlow<Set<String>> = _authorFilter.asStateFlow()

    fun toggleAuthorFilter(login: String) {
        val key = login.lowercase()
        _authorFilter.update { if (key in it) it - key else it + key }
    }

    fun clearAuthorFilter() {
        _authorFilter.value = emptySet()
    }

    /**
     * The rail, laid out: commits with their elapsed-time gaps, month boundaries, named silences,
     * and the marker showing where the reader's own build sits in the history.
     */
    val feedItems: StateFlow<List<org.matrix.vector.manager.data.github.FeedItem>> =
        combine(_feed, _status, _authorFilter) { feed, status, filter ->
                // The framework's version when the daemon is up, otherwise this manager's own.
                // Both are `git rev-list --count origin/master` on the same repository, so either
                // locates a build on the timeline correctly — and without the fallback the marker
                // would never appear at all while the daemon is not answering.
                val installed =
                    if (status.versionCode > 0) status.versionCode
                    else org.matrix.vector.manager.BuildConfig.VERSION_CODE.toLong()
                org.matrix.vector.manager.data.github.FeedLayout.build(
                    feed.filteredBy(filter),
                    installed,
                )
            }
            // Off the main thread, and this is not a precaution. `stateIn(viewModelScope, …)`
            // collects on the main dispatcher, and laying the rail out — filtering, grouping by
            // month, measuring every gap — is a full pass over an archive that runs to thousands
            // of commits. On the main thread a filter toggle freezes the very frame that is meant
            // to acknowledge the touch.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- framework toggles ------------------------------------------------------------------
    // Properties of the *framework* rather than of the app, so they belong on the screen that
    // reports the framework's state rather than in a settings screen of their own.

    private val _statusNotification = MutableStateFlow(false)
    val statusNotification: StateFlow<Boolean> = _statusNotification.asStateFlow()

    /**
     * Whether a newer framework build exists, on the channel this device is actually on.
     *
     * Refreshed off the back of the status read rather than on its own timer: the version code it
     * compares against comes from the same daemon call, and asking GitHub before we know what we
     * are running would compare against zero.
     */
    val frameworkUpdate: StateFlow<FrameworkUpdateState> = ServiceLocator.frameworkUpdates.state

    // True is the platform's own default for `show_hidden_icon_apps_enabled`, so the switch shows
    // what the system is doing on a device where nobody has touched it rather than reading "off"
    // until the daemon answers.
    private val _hiddenIcon = MutableStateFlow(true)
    val hiddenIcon: StateFlow<Boolean> = _hiddenIcon.asStateFlow()

    private suspend fun refreshToggles() {
        _statusNotification.value =
            daemon
                .enableStatusNotification()
                .onFailure { e ->
                    Log.w(Constants.TAG, "status: notification toggle unreadable, showing off", e)
                }
                .getOrDefault(false)
        // Read rather than assumed: this one is a global system setting, so anything on the device
        // can have moved it since the manager last wrote it.
        _hiddenIcon.value =
            daemon
                .forcedLauncherIcons()
                .onFailure { e ->
                    Log.w(Constants.TAG, "status: launcher-icon toggle unreadable, showing on", e)
                }
                .getOrDefault(true)
        // The notification is one of the ways into the manager, so what the card offers has to
        // follow the same value the switch above it shows.
        _presence.update { it.copy(notificationEnabled = _statusNotification.value) }
    }

    fun setStatusNotification(enabled: Boolean) {
        viewModelScope.launch {
            daemon
                .setEnableStatusNotification(enabled)
                .onSuccess {
                    _statusNotification.value = enabled
                    _presence.update { it.copy(notificationEnabled = enabled) }
                }
                .onFailure { e ->
                    Log.e(
                        Constants.TAG,
                        "framework: setting the status notification to $enabled failed",
                        e,
                    )
                }
        }
    }

    fun setForcedLauncherIcons(force: Boolean) {
        viewModelScope.launch {
            daemon.setForcedLauncherIcons(force).onSuccess {
                // Read back rather than assumed. The AIDL call returns nothing, and the daemon
                // applies it by running `settings put global`, which can fail without saying so —
                // so a transaction that arrived is not yet a setting that changed.
                _hiddenIcon.value = daemon.forcedLauncherIcons().getOrDefault(force)
            }
        }
    }

    fun refreshFeed(freshness: GitHubRepository.Freshness) {
        // Claimed before the coroutine starts rather than inside it. A pull-to-refresh that lands
        // while init's own load is still running would otherwise fetch the same window twice, and
        // the second answer would overwrite the first for no gain.
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                // Loaded first, assigned second, rather than through `MutableStateFlow.update`.
                // That is a compare-and-set spin loop — it re-invokes its lambda whenever another
                // writer wins the race — and the lambda here would be a network fetch, an archive
                // append, a snapshot rewrite and a several-thousand-commit re-parse. Three writers
                // touch this flow: the window collector, pull-to-refresh and the backfill.
                val loaded = github.load(freshness)
                _feed.value = loaded
                // Any load that asked the network for something settles the debt below, whether or
                // not the answer came from there in the end.
                if (freshness != GitHubRepository.Freshness.Cached) _windowChanged.value = false
            } finally {
                // Given back even when the load threw. A flag left set spins the indicator for the
                // life of the process and turns every later pull into a no-op.
                _refreshing.value = false
            }
        }
    }

    /**
     * True when the window was changed and nothing has been fetched since.
     *
     * Changing "the last six months" to "since the beginning" redraws immediately from what is
     * already on disk, which for a *narrower* window is the whole answer and for a wider one is as
     * much of it as has been walked so far. This flag carries the difference: the page says a fetch
     * would help and leaves the choice to the reader, rather than spending their rate limit the
     * moment they touch a setting.
     */
    private val _windowChanged = MutableStateFlow(false)
    val windowChanged: StateFlow<Boolean> = _windowChanged.asStateFlow()

    private val _loadingHistory = MutableStateFlow(false)
    val loadingHistory: StateFlow<Boolean> = _loadingHistory.asStateFlow()

    /**
     * Reaches further back, when the reader has scrolled far enough to mean it.
     *
     * Deliberately driven by scrolling rather than by opening Home. Most people never reach the
     * bottom of the feed, and walking the whole history for them would spend their share of an
     * anonymous rate limit on commits they will not look at — and spend it before the part they
     * will look at can be refreshed. Someone at the end of the list has asked, as plainly as
     * scrolling can ask.
     *
     * Each call fetches a few pages and returns, so the rail grows in steps while the reader keeps
     * scrolling rather than freezing until the whole history has landed. The count and the
     * contributor scoreboard are recomputed from the same reload, which is what makes the stats
     * settle as chunks arrive instead of all at the end.
     */
    fun loadMoreHistory() {
        if (_loadingHistory.value || !_feed.value.hasMoreHistory) return
        _loadingHistory.value = true
        viewModelScope.launch {
            try {
                val added = runCatching { github.backfill() }.getOrDefault(0)
                // Reads from disk: the pages just walked are already in the archive, and going
                // back to GitHub here would spend a request to be told what we have just been
                // told. The reload happens even when nothing was added, so that a walk which ended
                // by finding no new commits can clear the invitation to keep scrolling.
                _feed.value = github.load(GitHubRepository.Freshness.Cached)
                // Assigned rather than latched. A walk that came back with commits is proof the
                // network and the rate limit are fine, so it has to be able to clear this as well
                // as to set it; otherwise one refused walk would leave the rail insisting history
                // had run out for the rest of the process.
                _exhausted.value = added == 0
            } finally {
                // Given back even when the reload threw, since the guard above reads this flag —
                // leaving it set would end the rail's history for the life of the process.
                _loadingHistory.value = false
            }
        }
    }

    /**
     * True when the last walk came back empty-handed for a reason we cannot distinguish from
     * failure.
     *
     * A refused request and a finished history look the same from here, and retrying a refused one
     * on every scroll would hammer a rate limit that is already exhausted. This stops the automatic
     * retries until a walk brings something back; the foot of the feed stays tappable, so a reader
     * who knows they are back online can ask again — and the walk they ask for is what clears it.
     */
    private val _exhausted = MutableStateFlow(false)
    val historyStalled: StateFlow<Boolean> = _exhausted.asStateFlow()

    companion object {
        /** How often opening Home actually goes and checks GitHub. */
        private const val REVALIDATE_PROBABILITY = 0.2f

        val Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(
                        ServiceLocator.daemon,
                        ServiceLocator.github,
                        ServiceLocator.githubAuth,
                    )
                        as T
            }
    }
}

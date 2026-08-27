package org.matrix.vector.manager.ui.screens.home
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.random.Random
import kotlinx.coroutines.launch
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.manager.data.github.CommunityFeed
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.model.ManagerCopy
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW
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
    val dex2oatWrapperState: Int = IManagerService.DEX2OAT_OK,
    val sepolicyLoaded: Boolean = false,
    val systemServerInjected: Boolean = false,
    /**
     * Which build the running framework is: what commit it came from and where it was built.
     *
     * The version code is a commit count, so it cannot tell a branch build from the official build
     * of the same depth — and the framework and the manager are flashed separately, so they are
     * not always the same build. Naming both is the difference between a bug report that can be
     * placed and one that cannot. The commit leads: a CI build reads `93d66473-JingMatrix-Vector`,
     * a clean local one the bare hash, and a local build from a modified tree marks the machine
     * that made it with a `+`. Taken apart by `buildStamp`; nothing compares it as a string.
     */
    val commit: String? = null,
) {
    val versionLabel: String?
        get() = versionName?.let { if (versionCode > 0) "$it ($versionCode)" else it }

    /**
     * Whether there is a daemon this manager can actually ask anything.
     *
     * Not `state != Inactive`, which is what the two callers used to test and which
     * [FrameworkState.Mismatched] would answer wrongly: there a framework is plainly running and
     * simply speaking a generation of the interface this build does not, so every transaction
     * fails. Anything gated on being able to *use* the daemon belongs here rather than on a
     * comparison a later state can slip past.
     */
    val daemonUsable: Boolean
        get() = state != FrameworkState.Inactive && state != FrameworkState.Mismatched
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
    /**
     * A shortcut on the home screen the reader is looking at *now*.
     *
     * Deliberately narrower than "a shortcut exists": a pin does not follow the reader to a launcher
     * they install later, so a device that has switched launchers has a route the platform still
     * reports and a home screen with nothing on it — #883. See LaunchShortcut.isPinnedHere.
     */
    val shortcutPinned: Boolean = false,
    /**
     * Which copy of the manager is installed beside this one, if any.
     *
     * Three answers rather than a boolean because a copy of another build is both at once: it is a
     * route back in, so nothing here should press for another, and it is not this build, so the row
     * offering the install has something left to offer. See [ManagerCopy] for why the version code
     * alone cannot separate the last two.
     */
    val manager: ManagerCopy = ManagerCopy.Absent,
    /**
     * The status notification is a way in, not only a status.
     *
     * Its content intent opens the manager — see the daemon's NotificationManager — so a device
     * showing it is a device with a tap-sized route back, and it is on by default. Leaving it out
     * of this made the first-launch prompt claim there was no way back in to a reader who was
     * looking at one.
     *
     * Seeded to what the daemon itself would answer for a device where nobody has touched the
     * preference — PreferenceStore.isStatusNotificationEnabled reads the stored value `?: true` —
     * because this seed is the answer until the daemon's own has come back, which on a launch that
     * has no binder yet is nine round trips away rather than one. Seeded false it said the opposite
     * of what was sitting in the shade.
     */
    val notificationEnabled: Boolean = true,
    /**
     * False until the daemon has actually answered [notificationEnabled].
     *
     * [unreachable] is a claim about a device, and it must not be made before the read that would
     * settle it has come back. How long that takes depends on which of the two arrived first, this
     * ViewModel or the binder. With a binder already in hand, `init` issues the toggle read through
     * `refreshPresence` before `refreshStatus` asks for anything, and the wait is one cheap round
     * trip; with a binder that lands later, that call found nothing to ask and returned, and the
     * read that answers is the one the `service` collect makes once `refreshStatus`'s eight
     * sequential round trips are done. The second window is long enough to be seen. The seed above
     * is what makes both harmless today; this is what keeps them harmless if the seed is ever wrong
     * again, because not knowing and knowing there is no way in are not the same thing and only the
     * second is worth a modal.
     */
    val notificationKnown: Boolean = false,
    /** One of the IManagerService.ROOT_* constants, for naming the action button's owner. */
    val rootImplementation: Int = 0,
) {
    /**
     * True when opening the manager currently depends on remembering how.
     *
     * False while [notificationKnown] is false as well. The only thing that reads this puts a
     * scrimmed dialog in front of the reader, and an unanswered question is not a missing route.
     *
     * That term withholds nothing an answer has justified: both writers of [notificationEnabled]
     * set [notificationKnown] in the same update, so a false [notificationEnabled] already implies
     * the daemon answered. What keeps the dialog away from a device whose daemon never answers at
     * all is the optimistic seed, and deliberately — HomeScreen already declines to draw it while
     * the framework reads inactive, and the offer to install a manager APK is one only a live
     * daemon can hand over.
     */
    val unreachable: Boolean
        get() =
            notificationKnown &&
                parasitic &&
                !shortcutPinned &&
                !manager.installed &&
                !notificationEnabled
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
) : ViewModel() {

    val openLinksExternally: StateFlow<Boolean> = ServiceLocator.settings.openLinksExternally

    val headerAmbience: StateFlow<String> = ServiceLocator.settings.headerAmbience

    private val _status = MutableStateFlow(FrameworkStatus())
    val status: StateFlow<FrameworkStatus> = _status.asStateFlow()

    private val _feed = MutableStateFlow(CommunityFeed())
    val feed: StateFlow<CommunityFeed> = _feed.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val device = DeviceInfo()

    // --- how the manager can be reached -------------------------------------------------------
    // Above `init` on purpose: a Kotlin class body initialises top to bottom, so the refresh in
    // `init` would run against a `_presence` that does not exist yet — it writes there
    // synchronously, before it launches anything. The two framework toggles are here for the same
    // concern rather than the same certainty: `init` reaches `refreshToggles` through
    // `refreshPresence`, and `viewModelScope` dispatches on `Main.immediate`, so that body starts
    // running on the thread that is still constructing this object instead of being posted for
    // later. What saves it is what it does first rather than where these flows sit — it writes
    // nothing until a `runIpc` has answered, and `runIpc` is a `withContext(Dispatchers.IO)` that
    // always dispatches — so declared below `init` it would be one changed first statement away
    // from writing to a null.

    private val _presence = MutableStateFlow(ManagerPresence())

    // True is the daemon's own default for `enable_status_notification` — PreferenceStore reads the
    // stored value `?: true` — so the switch shows what the framework is doing on a device where
    // nobody has touched it rather than reading "off" until the daemon answers. A switch that reads
    // off while the notification is sitting in the shade is worse than one that briefly reads
    // optimistically: the first contradicts something the reader can see.
    private val _statusNotification = MutableStateFlow(true)
    val statusNotification: StateFlow<Boolean> = _statusNotification.asStateFlow()

    // True is the platform's own default for `show_hidden_icon_apps_enabled`, so the switch shows
    // what the system is doing on a device where nobody has touched it rather than reading "off"
    // until the daemon answers.
    private val _hiddenIcon = MutableStateFlow(true)
    val hiddenIcon: StateFlow<Boolean> = _hiddenIcon.asStateFlow()

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

    /**
     * Whether the status badge should still be pointing out that it opens something.
     *
     * The settings for how to open Vector are on that page and the badge is the only way to it, so a
     * reader who has never tapped it has no reason to think a tick is a button — #856. The header
     * makes the case by having the tick turn into a gear for a moment now and then, and this is what
     * calls it off once it has been made.
     */
    val statusBadgeHint: StateFlow<Boolean> =
        ServiceLocator.settings.statusBadgeOpens
            .map { it < STATUS_BADGE_HINT_OPENS }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ServiceLocator.settings.statusBadgeOpens.value < STATUS_BADGE_HINT_OPENS,
            )

    /**
     * Counts a badge tap that opened System status.
     *
     * Only the badge, not every arrival at that page: the hint is teaching where *this control*
     * leads, so a visit that came from a notification or a deep link has not learnt it.
     */
    fun noteStatusBadgeOpened() = ServiceLocator.settings.noteStatusBadgeOpened()

    /** Re-cuts today's badge count, so a session that crossed midnight offers the hint again. */
    fun refreshStatusBadgeHint() = ServiceLocator.settings.refreshStatusBadgeOpens()

    fun refreshPresence() {
        val context = ServiceLocator.context
        _presence.update {
            // Everything here is answered locally, so it stays synchronous and the first frame is
            // already right. The notification and the root implementation come from the daemon and
            // are folded in as they arrive, leaving whatever was last known in the meantime. Which
            // build the installed manager is belongs to that second group and only half of it is
            // asked for here: the package either exists or it does not, and the installer repeats
            // what it last compared rather than comparing again on this thread.
            it.copy(
                parasitic = LaunchShortcut.isParasitic(context),
                shortcutSupported = LaunchShortcut.isSupported(context),
                shortcutPinned = LaunchShortcut.isPinnedHere(context),
                manager = ServiceLocator.managerInstaller.installedManager(),
            )
        }
        viewModelScope.launch {
            // The toggles first, because one of them decides whether the "no way back in" prompt is
            // drawn at all while the root implementation only names a button on the card that
            // explains the ways in. On a launch that already has a binder this is the first daemon
            // read the ViewModel starts, ahead of `refreshStatus`'s: `init` calls `refreshPresence`
            // before it begins collecting, and `Main.immediate` runs this body inline on the
            // constructing thread. It is also the only retry the toggle reads ever get — arriving
            // at Home or at the status page is the moment a stale switch would be looked at, and it
            // is a moment somebody chose, unlike a timer.
            refreshToggles()
            val root = daemon.getRootImplementation().getOrNull()
            if (root != null) _presence.update { it.copy(rootImplementation = root) }
            // Last, because nothing above waits on it and it is the one read here that can run to
            // seconds and to tens of megabytes: an installed copy wearing this build's own version
            // code is only settled by hashing both APKs. Until it lands the row shows that copy's
            // last known verdict, which for a copy nothing has touched since is still right.
            val manager = ServiceLocator.managerInstaller.refreshInstalledManager()
            _presence.update { it.copy(manager = manager) }
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
        
        val cached = github.load(GitHubRepository.Freshness.Cached)
        
        if (cached.commits.isNotEmpty() || cached.loaded) {
            // ==================== 有缓存：秒开 ====================
            _feed.value = cached
            
            // 300ms 后后台刷新
            viewModelScope.launch {
                delay(300)
                val fresh = github.load(GitHubRepository.Freshness.Revalidate)
                _feed.value = fresh
                _refreshing.value = false
                _windowChanged.value = false
            }
            // ====================================================
        } else {
            // ==================== 无缓存：立即联网 ====================
            // 显示"加载中..."（和原始代码一样）
            _feed.value = CommunityFeed()
            
            viewModelScope.launch {
                val fresh = github.load(GitHubRepository.Freshness.Revalidate)
                // 联网失败时显示"离线"，而不是"没有动态"
                _feed.value = if (fresh.commits.isEmpty() && !fresh.loaded) {
                    CommunityFeed().copy(loaded = true, offline = true)
                } else {
                    fresh
                }
                _refreshing.value = false
                _windowChanged.value = false
            }
            // ====================================================
        }
    
    viewModelScope.launch {
        ServiceLocator.settings.activityWindowMonths.drop(1).collect {
            _windowChanged.value = true
            _feed.value = github.load(GitHubRepository.Freshness.Cached)
        }
    }
    
    viewModelScope.launch {
        combine(ServiceLocator.service, ServiceLocator.peerMismatch) { service, _ -> service }
            .collect { service ->
                refreshStatus(service)
                if (service != null) refreshToggles()
            }
    }
}
    
    private suspend fun refreshStatus(service: IManagerService?) {
        if (service == null || !daemon.isAlive) {
            // A binder did arrive and was refused for speaking a different generation of the
            // interface, which is not the same thing as there being no framework — and saying "not
            // activated" for it sends the reader to reinstall something that is already running.
            val mismatch = ServiceLocator.peerMismatch.value
            _status.value =
                FrameworkStatus(
                    state =
                        if (mismatch != null) FrameworkState.Mismatched else FrameworkState.Inactive
                )
            return
        }

        val versionName = daemon.getFrameworkVersionName().getOrNull()
        val commit = daemon.getBuildStamp().getOrNull()
        val versionCode =
            daemon
                .getFrameworkVersionCode()
                .onFailure { e ->
                    logW("status: framework version code unavailable, update check skipped", e)
                }
                .getOrDefault(0L)
        val api = daemon.getLibxposedApiVersion().getOrNull()

        // One line for both, because they fail together on a wedged binder and only these two
        // defaults synthesise a red HealthIssue card.
        val sepolicyResult = daemon.isSepolicyLoaded()
        val systemServerResult = daemon.isSystemServerAttached()
        val healthFailure = sepolicyResult.exceptionOrNull() ?: systemServerResult.exceptionOrNull()
        if (healthFailure != null && healthFailure !is CancellationException) {
            logW(
                "status: framework health read failed, defaulting to sepolicy/system_server " +
                    "not loaded",
                healthFailure,
            )
        }
        val sepolicy = sepolicyResult.getOrDefault(false)
        val systemServer = systemServerResult.getOrDefault(false)
        val dex2oat = daemon.getDex2OatWrapperState().getOrDefault(IManagerService.DEX2OAT_OK)
        val inliningDisabled = daemon.isDex2OatInliningDisabled().getOrDefault(true)

        val issues = buildList {
            if (!sepolicy) add(HealthIssue.SepolicyNotLoaded)
            if (!systemServer) add(HealthIssue.SystemServerNotInjected)
            // The wrapper and the property are two routes to one end, not a pair: the daemon
            // deletes `dalvik.vm.dex2oat-flags` when it mounts the wrapper over dex2oat and sets
            // it when it unmounts, so either route suppresses the inlining. A wrapper that is not
            // OK therefore only costs anything when the property is not carrying the flag either.
            if (dex2oat != IManagerService.DEX2OAT_OK && !inliningDisabled) {
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
                dex2oatWrapperState = dex2oat,
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

    /**
     * Whether a newer framework build exists, on the channel this device is actually on.
     *
     * Refreshed off the back of the status read rather than on its own timer: the version code it
     * compares against comes from the same daemon call, and asking GitHub before we know what we
     * are running would compare against zero.
     */
    val frameworkUpdate: StateFlow<FrameworkUpdateState> = ServiceLocator.frameworkUpdates.state

    /**
     * Re-reads both framework switches from the daemon, and is meant to be called again.
     *
     * `ServiceLocator.service` does not emit a second time while one binder stays alive, so the
     * collect in `init` is a single shot: with no other caller, one dropped transaction pinned both
     * switches to their seeds — and the launcher prompt to "not asked yet" — for the life of this
     * ViewModel. [refreshPresence] is the other caller, and on a launch that already has a binder
     * the earlier one, since `init` calls it before it starts collecting; it puts the retry on
     * someone opening a screen that shows these rather than on a timer. A timer is the wrong shape
     * here anyway: the launcher-icon read costs the daemon a `settings get global` fork every time.
     *
     * A read that failed writes nothing at all. It is not an answer, and letting it fall back to a
     * default is how a switch read correctly on arrival ends up reporting the opposite after a
     * single refused transaction.
     */
    private suspend fun refreshToggles() {
        // Nothing to ask and nothing gained by asking: with no binder both reads fail without
        // leaving the process, and the log fills with unreadable-toggle warnings about a framework
        // that is simply not running.
        if (!daemon.isAlive) return
        daemon
            .isStatusNotificationEnabled()
            .onSuccess { enabled ->
                _statusNotification.value = enabled
                // The notification is one of the ways into the manager, so what the card offers
                // has to follow the same value the switch above it shows — and it is published
                // here, not at the end of this function, because the read below forks
                // `settings get global` in the daemon and waits for it. Those hundreds of
                // milliseconds used to be spent with the launcher prompt believing there was no
                // way back into an app the reader may well have opened from this very
                // notification. Moving it up is not the whole fix, which is why the seeds and
                // [ManagerPresence.notificationKnown] exist: when the binder arrives after this
                // ViewModel was built, the call that reaches here is the one in the `service`
                // collect, and it waits out refreshStatus's eight sequential round trips first.
                _presence.update {
                    it.copy(notificationEnabled = enabled, notificationKnown = true)
                }
            }
            .onFailure { e -> logW("status: notification toggle unread", e) }
        // Read rather than assumed: this one is a global system setting, so anything on the device
        // can have moved it since the manager last wrote it.
        daemon
            .isForcedLauncherIcons()
            .onSuccess { _hiddenIcon.value = it }
            .onFailure { e -> logW("status: launcher-icon toggle unread", e) }
    }

    fun setStatusNotification(enabled: Boolean) {
        viewModelScope.launch {
            daemon
                .setStatusNotificationEnabled(enabled)
                .onSuccess {
                    _statusNotification.value = enabled
                    // Known either way now, which matters when `enabled` is false: someone who
                    // turns the notification off on a parasitic install with nothing else pinned
                    // has just closed the last easy route back in, and that is precisely the case
                    // the prompt exists to catch.
                    _presence.update {
                        it.copy(notificationEnabled = enabled, notificationKnown = true)
                    }
                }
                .onFailure { e ->
                    logE("framework: setting the status notification to $enabled failed", e)
                }
        }
    }

    fun setForcedLauncherIcons(force: Boolean) {
        viewModelScope.launch {
            daemon.setForcedLauncherIcons(force).onSuccess {
                // Read back rather than assumed. The AIDL call returns nothing, and the daemon
                // applies it by running `settings put global`, which can fail without saying so —
                // so a transaction that arrived is not yet a setting that changed.
                _hiddenIcon.value = daemon.isForcedLauncherIcons().getOrDefault(force)
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

        /**
         * How many times a day the badge has to be used before it stops explaining itself.
         *
         * Five is comfortably more than an accident and less than a sitting spent on that page. The
         * count resets daily — see SettingsRepository.statusBadgeOpens — so this is not a budget
         * that runs out for good.
         */
        private const val STATUS_BADGE_HINT_OPENS = 5

        val Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(
                        ServiceLocator.daemon,
                        ServiceLocator.github,
                    )
                        as T
            }
    }
}

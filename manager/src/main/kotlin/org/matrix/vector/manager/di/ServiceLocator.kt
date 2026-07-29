package org.matrix.vector.manager.di

import org.matrix.vector.manager.data.model.ModuleDetectionCache
import java.io.File
import org.matrix.vector.manager.ui.screens.repo.latestOn
import org.matrix.vector.manager.ui.screens.repo.StoreChannel
import org.matrix.vector.manager.data.model.StoreEntry
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.data.github.GitHubAuth
import org.matrix.vector.manager.data.log.CrashRecorder
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.repository.AppRepository
import org.matrix.vector.manager.data.repository.BackupRepository
import org.matrix.vector.manager.data.repository.FrameworkInstaller
import org.matrix.vector.manager.data.repository.FrameworkUpdateRepository
import org.matrix.vector.manager.data.repository.ModuleInstaller
import org.matrix.vector.manager.data.repository.ModuleUpdateQueue
import org.matrix.vector.manager.data.repository.ModuleRepository
import org.matrix.vector.manager.data.repository.RepoRepository
import org.matrix.vector.manager.data.repository.SettingsRepository
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.ipc.packageEventsFlow
import org.matrix.vector.manager.net.HttpClientFactory

/**
 * Hand-rolled service location, deliberately not a DI framework.
 *
 * The manager normally runs *parasitically*: `ParasiticManagerHooker` injects `manager.apk` into
 * the `com.android.shell` process, so this app's `AndroidManifest.xml` is never installed. Nothing
 * declared in it exists at runtime — no `ContentProvider`, therefore no `androidx.startup`, and no
 * guaranteed custom `Application`. Anything that self-initialises through `InitializationProvider`
 * silently never runs. Everything here is therefore initialised explicitly and lazily.
 *
 * Initialisation order is not fixed either. The daemon may call `Constants.setBinder()` before the
 * activity exists, or the activity may start before any binder arrives. [attach] and [bind] are
 * both idempotent and safe in either order; nothing here throws because the other half has not
 * happened yet.
 */
@SuppressLint("StaticFieldLeak") // Application context; it outlives everything here by design.
object ServiceLocator {

    /** Survives configuration changes, unlike anything scoped to the activity. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var appContext: Context? = null

    private val _service = MutableStateFlow<ILSPManagerService?>(null)

    /**
     * The daemon binder, as observable state.
     *
     * Repositories collect this rather than being poked by a setter, so a binder that arrives after
     * they were constructed — or arrives again after a reconnect — makes them re-read instead of
     * leaving them with whatever they managed to fetch before there was a daemon at all.
     */
    val service: StateFlow<ILSPManagerService?> = _service.asStateFlow()

    val context: Context
        get() =
            appContext
                ?: error("ServiceLocator.attach() must run before the UI touches the context")

    val daemon: DaemonClient by lazy { DaemonClient(service) }

    val http: OkHttpClient by lazy { HttpClientFactory.create(context, settings) }

    val settings: SettingsRepository by lazy { SettingsRepository(context) }

    val modules: ModuleRepository by lazy { ModuleRepository(daemon, appScope) }

    val apps: AppRepository by lazy {
        AppRepository(daemon, context.packageManager, moduleDetection)
    }

    /**
     * Which packages are modules, remembered across launches.
     *
     * Shared rather than per view model: the answer is a property of the installed APKs, and a
     * second copy would mean a second pass of opening every APK and split on the device as a zip.
     */
    val moduleDetection: ModuleDetectionCache by lazy {
        ModuleDetectionCache(File(context.cacheDir, "module-detection.tsv"))
    }

    val store: RepoRepository by lazy { RepoRepository(http, daemon, appScope) }

    val installer: ModuleInstaller by lazy { ModuleInstaller(context, http) }

    val frameworkUpdates: FrameworkUpdateRepository by lazy { FrameworkUpdateRepository(github) }

    /**
     * Every installed module the catalogue knows about, joined to what this device has.
     *
     * Here rather than in either view model because three screens need it and they must agree: the
     * Modules list marks a version as out of date, the module's own sheet offers to update it, and
     * the Store counts the same modules in its header. Three independent answers to "is this out of
     * date" is precisely how those numbers end up contradicting each other on one device.
     *
     * Keyed by package and limited to what is installed, because every reader of this asks about a
     * module in front of them. The Store's own list joins the other direction, catalogue first.
     */
    val storeEntries: StateFlow<Map<String, StoreEntry>> by lazy {
        combine(
                store.catalog,
                store.installedVersions,
                settings.updateChannel,
                settings.mutedUpdates,
            ) { catalog, installed, channelPreference, muted ->
                val channel = StoreChannel.of(channelPreference)
                catalog.modules
                    .filter { it.name in installed }
                    .associate { module ->
                        module.name to
                            StoreEntry(
                                module = module,
                                latest = module.latestOn(channel),
                                installed = installed[module.name],
                                updatesMuted = module.name in muted,
                            )
                    }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }

    /**
     * Installed modules the catalogue has something newer for, muting already applied.
     *
     * Expressed through `StoreEntry.upgradable`, the same property the Store's own list and count
     * use, so there is one definition of the word and not a second one that merely agrees today.
     */
    val upgradablePackages: StateFlow<Set<String>> by lazy {
        storeEntries
            .map { entries -> entries.values.filter { it.upgradable }.map { it.module.name }.toSet() }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    }

    /**
     * Installed modules that *are* out of date but were asked to keep quiet.
     *
     * Counted separately rather than folded into [upgradablePackages], because the two answer
     * different questions: one is "what should this device tell you about", the other is "what did
     * you tell it to stop mentioning". Only the update sheet asks the second, and it asks so that
     * an ignored module is visible when someone goes looking, rather than being unreachable from
     * the panel that hid it.
     */
    val mutedUpgradablePackages: StateFlow<Set<String>> by lazy {
        storeEntries
            .map { entries ->
                entries.values
                    .filter { it.updatesMuted && it.copy(updatesMuted = false).upgradable }
                    .map { it.module.name }
                    .toSet()
            }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    }

    /** Sequential module updates, outliving the sheet that started them. */
    val moduleUpdates: ModuleUpdateQueue by lazy { ModuleUpdateQueue(installer, store, modules, appScope) }

    val frameworkInstaller: FrameworkInstaller by lazy { FrameworkInstaller(context, http, daemon) }

    val backup: BackupRepository by lazy { BackupRepository(context, daemon) }

    val githubAuth: GitHubAuth by lazy { GitHubAuth(context, http) }

    val github: GitHubRepository by lazy {
        GitHubRepository(
            client = http,
            cacheDir = context.cacheDir,
            tokenProvider = { githubAuth.token },
            windowMonthsProvider = { settings.activityWindowMonths.value },
        )
    }

    /** Called from the activity. Safe to call repeatedly; later calls are ignored. */
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext ?: context
        // Before anything else that could fail. Nothing below is load-bearing for it, and a crash
        // during startup is exactly the one that is hardest to catch on a cable.
        CrashRecorder.install(appContext!!)
        observePackageChanges()
    }

    /**
     * Invalidates the caches when a package is installed, updated or removed.
     *
     * The only collector of `packageEventsFlow`, and on [appScope] so it lasts as long as the
     * process: without it a module installed while the manager is open would never appear.
     */
    private fun observePackageChanges() {
        appScope.launch {
            context.packageEventsFlow().collect {
                apps.invalidate()
                modules.refresh()
                modules.notePackagesChanged()
            }
        }
    }

    /**
     * Starts the expensive reads while the splash is still on screen.
     *
     * The three panels a user actually opens first each begin with work that has nothing to do
     * with drawing: enumerating every installed package, reading the module catalogue, fetching
     * the activity feed. Doing that on first visit means the panel appears and then fills in;
     * doing it here means it is usually already there.
     *
     * Every one of these is idempotent and cached, so the view models that ask again on arrival get
     * the finished answer rather than starting a second copy. Failures are ignored on purpose: this
     * is a head start, not a load-bearing step, and a screen that could not be reached because its
     * prefetch failed would be worse than one that is merely slow.
     */
    fun prefetch() {
        appScope.launch { runCatching { apps.getInstalledApps() } }
        appScope.launch { runCatching { store.refresh() } }
        // From disk only. Opening the manager is not by itself a reason to talk to GitHub, and
        // asking for a revalidation here would override Home's own gate — the one that decides how
        // rarely a launch is allowed to go and check.
        appScope.launch { runCatching { github.load(GitHubRepository.Freshness.Cached) } }
    }

    /** Called from `Constants.setBinder`, possibly before [attach]. */
    fun bind(service: ILSPManagerService?) {
        _service.value = service
    }
}

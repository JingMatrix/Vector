package org.matrix.vector.manager.ui.screens.modules
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.vector.ipc.ScopeEntry
import org.matrix.vector.manager.data.model.AppInfo
import org.matrix.vector.manager.data.model.ModuleDetection
import org.matrix.vector.manager.data.model.RecommendedScope
import org.matrix.vector.manager.data.repository.AppRepository
import org.matrix.vector.manager.data.repository.ModuleRepository
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.data.repository.SettingsRepository
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW

/**
 * A package/user pair, as a value type so set arithmetic is correct.
 *
 * Not [ScopeEntry], which carries the same two fields over the wire: it is a generated AIDL bean
 * with identity equality, so a set of them would count two readings of the same target as two
 * targets and every difference taken here would come out as "everything added and everything
 * removed". It is built from these only at the point of writing, in [ScopeViewModel.apply].
 */
data class ScopeTarget(val packageName: String, val userId: Int)

/**
 * How the list is ordered.
 *
 * Five orderings and a reverse toggle, which is more than a picker usually earns: sorting by
 * package name is how you find something whose display name you cannot recall, and by install time
 * is how you find the app you added five minutes ago.
 */
enum class ScopeSort {
    /** Selected first, then recommended, then alphabetical — the working order. */
    Relevance,
    Name,
    PackageName,
    InstallTime,
    UpdateTime,
}

data class ScopeUiState(
    val moduleName: String = "",
    val isEnabled: Boolean = false,
    val includeNewApps: Boolean = false,
    val recommended: RecommendedScope = RecommendedScope.NONE,
    val loading: Boolean = true,
    /**
     * Whether this device has more than one user at all.
     *
     * The framework row explains that it is shared across users, which is only ever news on a
     * device that has more than one. On a single-user phone — most of them — it is a sentence
     * about a distinction that does not exist, so it is not shown.
     */
    val multipleUsers: Boolean = false,
    /**
     * Whether the module is loaded into its own process whatever the scope table says.
     *
     * A legacy module reports being active by hooking a method in its own app, so it has to be in
     * its own scope before it can say anything at all, and the daemon derives that one target
     * rather than storing it. Nothing comes back from `getModuleScope` to say so — so without
     * this, the one row the module certainly hooks is the one row shown unticked, and with the
     * module filter at its default it is not shown at all.
     */
    val selfHooked: Boolean = false,
)

class ScopeViewModel(
    private val modulePackageName: String,
    /**
     * The user whose copy of the module was opened, and so whose apps this screen offers.
     *
     * Not a second scope. A module is one package and one APK for the whole device and has one
     * scope set; what varies per user is which apps exist to point at, and the daemon will not
     * expand a row for a user that does not hold the module. So this selects the half of the
     * device being edited — [apply] merges into the whole stored set rather than replacing it,
     * which is what leaves another user's rows alone.
     */
    private val userId: Int,
    private val daemonClient: DaemonClient,
    private val appRepository: AppRepository,
    private val moduleRepository: ModuleRepository,
    private val packageManager: android.content.pm.PackageManager,
    private val settings: SettingsRepository = ServiceLocator.settings,
) : ViewModel() {

    private val allApps = MutableStateFlow<List<AppInfo>>(emptyList())

    /**
     * What the daemon held when this screen last looked.
     *
     * The baseline the draft is measured against, and not an oracle: the scope table has writers
     * other than this screen, so it goes stale the moment one of them runs. That is why
     * [refreshSavedScope] exists, and why [apply] re-reads instead of trusting it.
     */
    private val savedScope = MutableStateFlow<Set<ScopeTarget>>(emptySet())

    /**
     * What the user has built up but not yet applied.
     *
     * Writing a scope is not incremental — the daemon deletes every scope row of the module and
     * writes the new set in one transaction, then asks for a configuration rebuild. Sending that
     * on every checkbox tap means ten rewrites and ten rebuilds to tick ten apps, so edits
     * accumulate here and go out as one write.
     */
    private val draftScope = MutableStateFlow<Set<ScopeTarget>>(emptySet())

    /**
     * Every target whose tick this visit has actually changed and could otherwise be filtered away
     * — one at a time from [toggle], in a batch from [clearAllVisible].
     *
     * [selectAllVisible] changes ticks too and deliberately marks nothing: a row it ticks is in the
     * draft, and the draft already exempts a row from every filter. Only unticking can drop a row
     * out of the list, so only unticking has anything to record here.
     *
     * Only the list's own filters read it, and only to keep a row present. Unticking is an edit
     * like any other and the row has to survive it — an app that vanishes the moment it is
     * unticked cannot be re-ticked, so a slip becomes permanent for as long as the reader does not
     * think to go and turn a filter on. It never shrinks: a row put in play stays in play until
     * the screen is left, which is the point.
     *
     * Which is also why nothing may go in that was not really changed. A row the reader merely
     * *saw* would be exempted from the system, game and module filters for the rest of the visit,
     * and since this never shrinks there would be no way back: turning system apps off again would
     * hide nothing.
     */
    private val touched = MutableStateFlow<Set<ScopeTarget>>(emptySet())

    private val _uiState = MutableStateFlow(ScopeUiState())
    val uiState: StateFlow<ScopeUiState> = _uiState.asStateFlow()

    val searchQuery = MutableStateFlow("")
    /**
     * System apps are hidden by default.
     *
     * A device carries several hundred of them and a handful of apps the user installed; showing
     * both at once buries the second set. Anything already in the scope is exempt from every
     * filter, so turning this off can never hide a choice that has been made.
     */
    val showSystemApps = MutableStateFlow(settings.scopeShowSystemApps.value)

    val showGames = MutableStateFlow(settings.scopeShowGames.value)

    /**
     * Narrow the list to what the module asked for, plus whatever is already in the scope.
     *
     * Close to the view a static scope gets, reachable on purpose. A module that declares a scope
     * but does not fix it leaves the user to find those apps among several hundred, and the list
     * already knows which they are — so this is the "just show me what it wants" the static case
     * gets for free.
     *
     * Not exclusive, despite the name: it exempts what is already in the draft, as every other
     * filter on this screen does. Dropping every app the module had not named would hide a stray
     * selection and leave it un-untickable, and would narrow the list to nothing for a module that
     * declares no scope at all.
     */
    val showRecommendedOnly = MutableStateFlow(false)

    /**
     * Whether other Xposed modules appear in the list.
     *
     * They are installed apps like any other and a module *can* legitimately hook one, but that
     * is rare enough that the default is off: on a device with two dozen modules, listing them
     * all among the hookable apps is two dozen rows of noise for one plausible use.
     */
    val showModules = MutableStateFlow(settings.scopeShowModules.value)

    val sort =
        MutableStateFlow(
            ScopeSort.entries.firstOrNull { it.name.equals(settings.scopeSort.value, true) }
                ?: ScopeSort.Relevance
        )
    val reverseSort = MutableStateFlow(settings.scopeSortReversed.value)

    /**
     * Whether this module has a screen to open at all.
     *
     * Null until asked, so the control does not flicker into existence on arrival. Most modules
     * have no companion and no launcher entry, and offering to open one is offering nothing —
     * which is why this is worth a lookup rather than a snackbar after the fact.
     *
     * Declared above [init] rather than beside the function that fills it, and it has to stay
     * there. `viewModelScope` dispatches on `Main.immediate`, so [findCompanion] starts inline on
     * the constructing thread and reads this field before the first suspension — while every
     * property declared below the `init` block is still null.
     */
    private val _companion = MutableStateFlow<Boolean?>(null)
    val hasCompanion: StateFlow<Boolean?> = _companion.asStateFlow()

    init {
        findCompanion()
        // Written back as they change rather than on the way out: this screen is left by a back
        // gesture, by the process being killed, and by the host application deciding it is done —
        // and only the first of those runs any teardown of ours.
        viewModelScope.launch {
            showSystemApps.collect { settings.setScopeShowSystemApps(it) }
        }
        viewModelScope.launch { showGames.collect { settings.setScopeShowGames(it) } }
        viewModelScope.launch { showModules.collect { settings.setScopeShowModules(it) } }
        viewModelScope.launch { sort.collect { settings.setScopeSort(it.name.lowercase()) } }
        viewModelScope.launch { reverseSort.collect { settings.setScopeSortReversed(it) } }
    }

    /**
     * Packages that are themselves modules.
     *
     * Null until known. Deciding this means inspecting every installed package, so it is computed
     * once per process by [AppRepository] and shared; until it arrives the filter simply does not
     * apply, which shows a few extra rows for a moment rather than blocking the list on disk I/O.
     */
    private val modulePackages = MutableStateFlow<Set<String>?>(null)

    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    private val _message = MutableStateFlow<ScopeMessage?>(null)
    val message: StateFlow<ScopeMessage?> = _message.asStateFlow()

    /** Added and removed relative to what the daemon holds, so the UI can say what Apply will do. */
    val pendingChanges: StateFlow<PendingChanges> =
        combine(savedScope, draftScope) { saved, draft ->
                PendingChanges(
                    added = (draft - saved).size,
                    removed = (saved - draft).size,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PendingChanges())

    val filteredApps: StateFlow<List<AppInfo>> =
        combine(allApps, draftScope, searchQuery, showSystemApps, showGames) {
                apps,
                draft,
                query,
                showSys,
                showGame ->
                Filters(apps, draft, query, showSys, showGame, false)
            }
            .combine(showRecommendedOnly) { filters, only -> filters.copy(recommendedOnly = only) }
            // The saved set as well as the draft: the difference between them is what "newly
            // ticked" means, and the order below leads with it.
            .combine(savedScope) { filters, saved -> filters.copy(saved = saved) }
            .combine(touched) { filters, t -> filters.copy(touched = t) }
            // Two typed halves rather than one list of Any. The inputs outnumber the arities
            // `combine` provides, and carrying them positionally through a `List<Any>` and casting
            // each one back out lets a rename or a reorder compile and then fail at runtime.
            .combine(
                combine(showModules, modulePackages, sort, reverseSort, _uiState) {
                    showMods,
                    modules,
                    order,
                    reverse,
                    state ->
                    View(showMods, modules, order, reverse, state)
                }
            ) { filters, view ->
                val showMods = view.showModules
                val modules = view.modulePackages
                val order = view.sort
                val reverse = view.reverse
                val recommended = view.state.recommended.packages.toSet()
                // A static scope fixes which apps may be *listed*, so the list is exactly that set.
                // The daemon refuses every target beyond it, so a row for one of the other few
                // hundred apps would offer a choice that does not exist. Absolute, unlike the
                // filters below: this is not a view of the list the reader chose, it is the list.
                val locked = view.state.recommended.staticScope
                // The row the daemon hooks whether or not the table names it. Matched on the user
                // as well as the package: the same module in a work profile is another copy with
                // its own row, and only the copy this screen is editing is the one being loaded
                // into the process in front of it.
                fun implicit(app: AppInfo) =
                    view.state.selfHooked &&
                        app.packageName == modulePackageName &&
                        app.userId == userId
                filters.apps
                    .asSequence()
                    .filter { app -> !locked || app.packageName in recommended }
                    .filter { app ->
                        val matchesQuery =
                            filters.query.isBlank() ||
                                app.appName.contains(filters.query, ignoreCase = true) ||
                                app.packageName.contains(filters.query, ignoreCase = true)
                        // A row the reader is working with is never filtered away — and unticking
                        // it is working with it. Keying this on the draft alone meant that
                        // unticking a system app dropped it through the default filter and out of
                        // the list mid-edit, taking with it the only tick that could undo the
                        // mistake. So a row counts as in play if it was in force when this screen
                        // opened, is in the draft now, or has been touched during this visit.
                        // Derived counts throughout, so the module's own row survives every filter
                        // — including the module filter, which is off by default and would
                        // otherwise hide the row this whole exemption exists to show.
                        val target = ScopeTarget(app.packageName, app.userId)
                        val inPlay =
                            implicit(app) ||
                                target in filters.draft ||
                                target in filters.saved ||
                                target in filters.touched
                        val requested = app.packageName in recommended
                        if (locked || filters.recommendedOnly) {
                            // Both answer one question — what does this module want, and what have
                            // I given it — and the other filters have no say in either. Chrome is
                            // a system app, so letting them apply would show nothing at all for a
                            // module asking for Chrome unless the reader had also thought to turn
                            // system apps on; a module that fixes its scope on system packages,
                            // which is most of them, showed an empty list for exactly that reason.
                            // The screen greys the other three out in both cases, and this is the
                            // code that makes that honest rather than decorative.
                            return@filter matchesQuery && (inPlay || requested)
                        }
                        // The framework, when the module has asked for it, and nothing else.
                        //
                        // A request does not generally outrank the reader's filters: a module may
                        // name dozens of system packages, and exempting all of them would leave
                        // the system-apps switch turning nothing off on exactly the modules whose
                        // lists are longest. The reader has "What the module asks for" for that
                        // view, and it already overrides all three.
                        //
                        // The framework is the exception because it is not one of the several
                        // hundred rows the filters exist to thin out. It is not an installed
                        // package at all — it is a synthetic row this view model adds — so it
                        // cannot be found by turning any filter on and hunting for it, and a
                        // reader who has never seen it has no reason to think it exists. Hidden,
                        // a module whose whole declared scope is the framework shows an empty
                        // list, which is the one case where the filters do not thin a list but
                        // erase it.
                        val frameworkRequested =
                            requested && app.packageName == SYSTEM_FRAMEWORK_PACKAGE
                        val matchesSys =
                            inPlay || frameworkRequested || filters.showSystem || !app.isSystemApp
                        // No exemption needed on either of these: the framework row is built with
                        // `isGame = false` and is not an installed package, so it is not in the
                        // module set. Both already pass it.
                        val matchesGame = inPlay || filters.showGames || !app.isGame
                        val matchesModule =
                            inPlay || showMods || modules == null || app.packageName !in modules
                        matchesQuery && matchesSys && matchesGame && matchesModule
                    }
                    .map { app ->
                        app.copy(
                            isSelectedInScope =
                                implicit(app) ||
                                    ScopeTarget(app.packageName, app.userId) in filters.draft,
                            isImplicitInScope = implicit(app),
                            isRecommended = app.packageName in recommended,
                        )
                    }
                    .sortedWith(comparatorFor(order))
                    .toList()
                    .let { if (reverse) it.reversed() else it }
                    // What is in the scope comes first, then the framework, then everything else.
                    //
                    // Grouping the chosen at the top applies to every ordering, not just to
                    // Relevance, because this is a picker: the rows you have already ticked are
                    // the ones you come back to check, and hunting for them alphabetically among
                    // several hundred is the work the sort was supposed to save. Each sort still
                    // orders within the two groups.
                    //
                    // The framework needs a pin of its own because it is not an app and does not
                    // sort like one: by name it lands under S, by install time wherever its
                    // borrowed timestamp puts it, and either way the one target that is not
                    // discoverable any other way is lost in a list of thousands. It sits below the
                    // chosen rather than above them, so a target nobody has picked never leads the
                    // ones they have.
                    //
                    // After the reverse, so reversing cannot bury any of it at the bottom.
                    .let { list ->
                        fun frameworkFirst(group: List<AppInfo>): List<AppInfo> {
                            val (framework, others) =
                                group.partition { it.packageName == SYSTEM_FRAMEWORK_PACKAGE }
                            return framework + others
                        }
                        val (chosen, rest) = list.partition { it.isSelectedInScope }
                        // What is in force, then what is about to be, then everything else. The
                        // framework leads the last two but not the first: it is the one row that
                        // cannot be found by scrolling, so it has to lead the group it is being
                        // picked from — and once it is in the scope it is a member like any other,
                        // with no claim to sit above targets that are already in force.
                        // A derived row is in force by definition: it is not waiting on an apply,
                        // and grouping it with the newly ticked would promise a write that will
                        // never happen.
                        val (inForce, newlyTicked) =
                            chosen.partition {
                                it.isImplicitInScope ||
                                    ScopeTarget(it.packageName, it.userId) in filters.saved
                            }
                        inForce + frameworkFirst(newlyTicked) + frameworkFirst(rest)
                    }
            }
            // Filtering and sorting the full installed-app list is real work — often thousands of
            // entries — and stateIn(viewModelScope) alone would run it on Dispatchers.Main.immediate
            // on every keystroke.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Everything outside the app list that decides what the list shows. */
    private data class View(
        val showModules: Boolean,
        val modulePackages: Set<String>?,
        val sort: ScopeSort,
        val reverse: Boolean,
        val state: ScopeUiState,
    )

    private data class Filters(
        val apps: List<AppInfo>,
        val draft: Set<ScopeTarget>,
        val query: String,
        val showSystem: Boolean,
        val showGames: Boolean,
        val recommendedOnly: Boolean,
        val saved: Set<ScopeTarget> = emptySet(),
        val touched: Set<ScopeTarget> = emptySet(),
    )

    private fun comparatorFor(order: ScopeSort): Comparator<AppInfo> =
        when (order) {
            ScopeSort.Name -> compareBy { it.appName.lowercase() }
            ScopeSort.PackageName -> compareBy { it.packageName }
            ScopeSort.InstallTime ->
                compareByDescending<AppInfo> { it.firstInstallTime }
                    .thenBy { it.appName.lowercase() }
            ScopeSort.UpdateTime ->
                compareByDescending<AppInfo> { it.lastUpdateTime }.thenBy { it.appName.lowercase() }
            // Whatever the user is working on floats up: what they have chosen, then what the
            // module asked for, then everything else.
            ScopeSort.Relevance ->
                compareByDescending<AppInfo> { it.isSelectedInScope }
                    .thenByDescending { it.isRecommended }
                    .thenBy { it.appName.lowercase() }
        }

    init {
        load()
        // Hidden by default, so the set has to be known without anyone asking for it. It is cached
        // per process, so this is free after the first scope screen of the session.
        viewModelScope.launch { modulePackages.value = appRepository.modulePackages() }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)

            // Only the apps belonging to the user this module is installed for. A module in a
            // work profile can only hook that profile's apps, so listing the owner's alongside
            // them offers choices the framework will not honour — and the same package appears
            // once per user, so an unfiltered list shows visible duplicates.
            val apps =
                withContext(Dispatchers.IO) {
                    appRepository.getInstalledApps().filter { it.userId == userId }
                }

            // The system server is a hook target like any other and modules ask for it by name,
            // but it is not an installed package so it never appears in the package list. Without
            // this entry a module whose entire recommended scope is the framework — Core Patch,
            // for one — offers the user nothing to tick.
            //
            // Offered to every user, not only the owner. There is exactly one system_server on the
            // device, so it is not a per-user target that other users happen to lack — it is one
            // process they all share, and a module in a work profile or a private space would
            // otherwise have no way to ask for the only target it may need (issue #136). The
            // daemon agrees: `ModuleDatabase.setModuleScope` stores a `system` row under user 0
            // whoever asked, and `ConfigCache` maps it to system_server without looking at whose
            // module it was.
            val withFramework = listOf(systemFrameworkEntry(apps)) + apps
            allApps.value = withFramework

            // Asked once per load rather than per row, and held here until the state is built at
            // the end of this function — anything written into `_uiState` before then is discarded
            // when that fresh ScopeUiState replaces it. A failure to ask means not explaining,
            // never explaining wrongly.
            val userCount =
                withContext(Dispatchers.IO) { daemonClient.getUsers().getOrNull()?.size ?: 1 }

            // A scope the daemon will not hand over shows as none rather than keeping the screen
            // shut; [readSavedScope] logs why, and keeps that case apart from the empty list a
            // module with nothing ticked legitimately has.
            val saved = readSavedScope() ?: emptySet()
            savedScope.value = saved
            draftScope.value = saved

            val info =
                withContext(Dispatchers.IO) {
                    runCatching {
                            packageManager.getApplicationInfo(
                                modulePackageName,
                                android.content.pm.PackageManager.GET_META_DATA,
                            )
                        }
                        .onFailure { e ->
                            logW(
                                "scope: package info for $modulePackageName (user $userId) " +
                                    "unavailable, no recommended scope",
                                e,
                            )
                        }
                        .getOrNull()
                }
            // One inspection, two answers: what the module asks to hook, and which generation of
            // module it is. Both come out of the same pass over the APK, and opening it is the
            // expensive part.
            val manifest =
                info?.let {
                    withContext(Dispatchers.IO) { ModuleDetection.inspect(it, packageManager) }
                }
            val recommended =
                manifest?.let { RecommendedScope(it.scope, it.staticScope) } ?: RecommendedScope.NONE

            _uiState.value =
                ScopeUiState(
                    moduleName =
                        info?.loadLabel(packageManager)?.toString() ?: modulePackageName,
                    isEnabled = modulePackageName in moduleRepository.enabledModulesState.value,
                    includeNewApps =
                        daemonClient.getIncludeNewApps(modulePackageName).getOrDefault(false),
                    recommended = recommended,
                    loading = false,
                    multipleUsers = userCount > 1,
                    // The manager's own reading of the APK, not the daemon's. The daemon settles
                    // this while it loads the module and never tells anyone — and it only holds an
                    // answer for a module that is enabled, which is precisely not the state a
                    // module is in while its scope is being chosen for the first time.
                    selfHooked = manifest?.isLegacy == true,
                )
        }
    }

    /**
     * What the daemon holds for this module right now, or null when it would not say.
     *
     * Null and empty are different answers and every caller here has to tell them apart: a fresh
     * module with nothing ticked is a success carrying an empty list, and treating a refused read
     * as "no rows" would turn a broken connection into an erased scope.
     */
    private suspend fun readSavedScope(): Set<ScopeTarget>? {
        val result = daemonClient.getModuleScope(modulePackageName)
        result.exceptionOrNull()?.let { e ->
            logE("scope: reading the saved scope of $modulePackageName failed", e)
        }
        val rows = result.getOrNull() ?: return null
        return rows.map { ScopeTarget(it.packageName, it.userId) }.toSet().asStored()
    }

    /**
     * The set spelled the way the daemon stores it, so two readings of it can be subtracted.
     *
     * `ModuleDatabase.setModuleScope` files the framework under user 0 whoever asked for it, so it
     * always comes back as user 0 — while a scope restored from a backup written by an older
     * manager still names it under the module's own user, and this screen's own row for it is
     * user 0. Comparing the two spellings without this makes the framework look added and removed
     * at once, and a merge built on that difference would act on both.
     */
    private fun Set<ScopeTarget>.asStored(): Set<ScopeTarget> =
        mapTo(mutableSetOf()) {
            if (it.packageName == SYSTEM_FRAMEWORK_PACKAGE) it.copy(userId = 0) else it
        }

    /**
     * Re-reads the stored scope and folds whatever arrived from elsewhere into the draft.
     *
     * Called every time the screen comes back to the front, because this editor is not the only
     * writer of that table and the other writer is one tap away: the button in the corner opens
     * the module, a libxposed module asks for a target while it runs, and the user approves it
     * from the notification shade. Nothing here would ever notice — the load runs once, from
     * `init` — so the list would go on drawing an empty box beside a target the module is already
     * being loaded into, and the apply bar would count a removal nobody asked for.
     *
     * Additive on purpose. What the user has ticked here is theirs and survives untouched; a row
     * that appeared outside joins both the baseline and the draft, so it reads as in force rather
     * than as a pending change. A row that *vanished* outside is left in the draft, where it shows
     * honestly as something applying would put back — unticking it here would undo a choice on the
     * user's behalf and say nothing.
     */
    fun refreshSavedScope() {
        // The screen's first resume lands while the load started in `init` is still in flight, and
        // that load is this same read: running both races two answers into the same field for no
        // gain, and the stale one can win. An apply is likewise mid-write and publishes its own
        // result, which this would only be able to contradict.
        if (_uiState.value.loading || _applying.value) return
        viewModelScope.launch {
            val current = readSavedScope() ?: return@launch
            val baseline = savedScope.value.asStored()
            if (current == baseline) return@launch
            savedScope.value = current
            draftScope.value = draftScope.value + (current - baseline)
        }
    }

    /**
     * A stand-in for the system server.
     *
     * Borrows an existing entry's [android.content.pm.ApplicationInfo] purely so the row has
     * something to draw an icon from; only the package name and label are meaningful.
     */
    private fun systemFrameworkEntry(apps: List<AppInfo>): AppInfo {
        val donor = apps.firstOrNull()
        return AppInfo(
            packageName = SYSTEM_FRAMEWORK_PACKAGE,
            userId = 0,
            appName = FRAMEWORK_LABEL,
            isSystemApp = true,
            isGame = false,
            isSelectedInScope = false,
            isRecommended = false,
            lastUpdateTime = Long.MAX_VALUE,
            firstInstallTime = Long.MAX_VALUE,
            applicationInfo = donor?.applicationInfo ?: android.content.pm.ApplicationInfo(),
        )
    }

    /** Local only. Nothing reaches the daemon until [apply]. */
    fun toggle(app: AppInfo, selected: Boolean) {
        // A derived row is not the scope table's to give or to take away. Writing a row of our own
        // for it would neither add the target — it is already there — nor let it be removed, and
        // unticking it would draw an empty box beside a process the module is still loaded into.
        if (app.isImplicitInScope) return
        val target = ScopeTarget(app.packageName, app.userId)
        // Before the draft changes, so the row cannot be filtered out by the very edit being made.
        touched.value = touched.value + target
        draftScope.value =
            if (selected) draftScope.value + target else draftScope.value - target
    }

    // Both skip the derived row for the reason [toggle] gives: it is shown among the visible rows
    // but it is not one of the ones being written, and either of these sweeping it up would report
    // a change to a row whose tick nothing here decides.
    fun selectAllVisible() {
        draftScope.value =
            draftScope.value +
                filteredApps.value
                    .filterNot { it.isImplicitInScope }
                    .map { ScopeTarget(it.packageName, it.userId) }
    }

    fun clearAllVisible() {
        val draft = draftScope.value
        // The rows this call really unticks, which is only the visible part of the draft — the rest
        // of the list is already clear and nothing is being done to it.
        val cleared =
            filteredApps.value
                .filterNot { it.isImplicitInScope }
                .map { ScopeTarget(it.packageName, it.userId) }
                .filterTo(mutableSetOf()) { it in draft }
        // Unticking everything visible must not empty the list as well. Without this, clearing a
        // list of system apps removes the rows along with the ticks and leaves the reader looking
        // at nothing, with no way to put any of it back. Marking the whole visible list instead of
        // the part that changed would buy that at the price of the filters: [touched] never
        // shrinks, so every row that happened to be on screen would stay on screen however the
        // filters were set for the rest of the visit.
        touched.value = touched.value + cleared
        draftScope.value = draft - cleared
    }

    /** Replace the draft with exactly what the module asked for. */
    fun useRecommended() {
        val recommended = _uiState.value.recommended.packages.toSet()
        if (recommended.isEmpty()) return
        draftScope.value =
            allApps.value
                .filter { it.packageName in recommended }
                .map { ScopeTarget(it.packageName, it.userId) }
                .toSet()
    }

    fun discard() {
        draftScope.value = savedScope.value
    }

    fun setSort(order: ScopeSort) {
        sort.value = order
    }

    fun toggleReverse() {
        reverseSort.value = !reverseSort.value
    }

    /** Turns the "show modules" filter on or off. */
    fun setShowModules(show: Boolean) {
        showModules.value = show
    }

    fun setRecommendedOnly(only: Boolean) {
        showRecommendedOnly.value = only
    }

    fun setIncludeNewApps(enabled: Boolean) {
        viewModelScope.launch {
            daemonClient
                .setIncludeNewApps(modulePackageName, enabled)
                .onSuccess { stored ->
                    // The daemon's answer, not merely the fact that it answered: it refuses a
                    // package it holds no row for, and moving the switch on a refusal would show a
                    // setting that was never saved.
                    if (stored) {
                        _uiState.value = _uiState.value.copy(includeNewApps = enabled)
                    } else {
                        logE(
                            "scope: daemon refused include-new-apps=$enabled for " +
                                modulePackageName,
                        )
                        _message.value = ScopeMessage.IncludeNewAppsFailed
                    }
                }
                .onFailure { e ->
                    logE(
                        "scope: setting include-new-apps=$enabled for $modulePackageName failed",
                        e,
                    )
                    _message.value = ScopeMessage.IncludeNewAppsFailed
                }
        }
    }

    /**
     * Set when an apply changed whether the framework is in this scope.
     *
     * A dialog rather than the usual snackbar: this one asks for a decision, and a message that
     * scrolls away on its own would leave the reader believing a scope is in force when it is
     * stored and inert.
     */
    private val _frameworkRestartNeeded = MutableStateFlow(false)
    val frameworkRestartNeeded: StateFlow<Boolean> = _frameworkRestartNeeded.asStateFlow()

    fun dismissFrameworkRestart() {
        _frameworkRestartNeeded.value = false
    }

    /**
     * Restarts the primary zygote, and with it system_server.
     *
     * Sufficient on its own: the daemon survives — it holds a death recipient on the bridge and
     * re-injects into the replacement — and the new system_server asks for its module list on the
     * way up, by which time the write that prompted this has already rebuilt the cache.
     */
    fun softRebootForFramework() {
        _frameworkRestartNeeded.value = false
        viewModelScope.launch {
            daemonClient.softReboot().onFailure { e ->
                logE("scope: soft reboot after a framework scope change failed", e)
            }
        }
    }

    /** Fills [hasCompanion], which is declared next to [init] for the reason given there. */
    private fun findCompanion() {
        viewModelScope.launch {
            _companion.value =
                daemonClient
                    .findAppUi(modulePackageName, userId, companionFirst = true)
                    .onFailure { e ->
                        logW(
                            "scope: companion lookup for $modulePackageName user $userId failed",
                            e,
                        )
                    }
                    .getOrNull() != null
        }
    }

    /**
     * Opens the module's own screen — its companion activity, or its launcher entry.
     *
     * Here as well as in the long-press sheet because this is the screen you are on when you are
     * thinking about that module: a scope is half of its configuration and the other half lives
     * inside the module, so reaching it from the list would be a detour through a place you had
     * just come from.
     *
     * Passes `companionFirst` exactly as [findCompanion] does, so the control cannot appear for a
     * module whose only screen this call would then decline to open.
     */
    fun openModule() {
        viewModelScope.launch {
            val opened =
                daemonClient
                    .openAppUi(modulePackageName, userId, companionFirst = true)
                    .onFailure { e ->
                        logE(
                            "scope: companion open of $modulePackageName for user $userId failed",
                            e,
                        )
                    }
                    .getOrDefault(false)
            if (!opened) _message.value = ScopeMessage.NothingToOpen
        }
    }

    fun setModuleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (moduleRepository.toggleModule(modulePackageName, enabled)) {
                _uiState.value = _uiState.value.copy(isEnabled = enabled)
            } else {
                _message.value = ScopeMessage.ToggleFailed
            }
        }
    }

    /**
     * Writes what the user did, once.
     *
     * Not the draft as it stands, deliberately. One `setModuleScope` replaces *every* scope row of
     * the module, so sending a draft built when the screen opened sends a set that has never heard
     * of anything written since — and a row can arrive while this screen sits in the background,
     * because the user approving a module's own `requestScope` from the notification shade adds
     * one. This screen's own button for opening the module is exactly how a module gets to run and
     * ask. The approval would then be deleted by the next apply, minutes after the module had
     * already been told it was granted, and nothing anywhere would say so.
     *
     * So the edit is what travels: the targets ticked and unticked here, applied to whatever the
     * daemon holds at the moment of writing rather than to what the user was shown. When the fresh
     * read fails there is nothing better than the picture on screen — falling back to the baseline
     * reduces the expression below to exactly the draft, which is the behaviour this replaces, and
     * refusing to apply at all would leave an edit that can never be committed.
     *
     * One write means one configuration rebuild. The new scope reaches an app when its process
     * next starts; nothing running is restarted here.
     *
     * The daemon enables the module as a side effect of storing a scope.
     */
    fun apply() {
        if (_applying.value) return
        viewModelScope.launch {
            _applying.value = true
            // The snapshot the draft was built from, so the difference between the two is exactly
            // what was done on this screen and nothing else.
            val baseline = savedScope.value.asStored()
            val draft = draftScope.value.asStored()
            val current = readSavedScope()
            if (current == null) {
                logW(
                    "scope: could not re-read the scope of $modulePackageName before writing; " +
                        "applying the draft as it stands"
                )
            }
            val before = current ?: baseline
            val merged = before + (draft - baseline) - (baseline - draft)
            val aidl =
                merged.map { target ->
                    ScopeEntry().apply {
                        packageName = target.packageName
                        userId = target.userId
                    }
                }
            daemonClient
                .setModuleScope(modulePackageName, aidl)
                .onSuccess { stored ->
                    // The daemon's answer, not merely the fact that it answered: it refuses a set
                    // that reaches beyond a scope the module fixes for itself, and moving the saved
                    // set on a refusal would show a scope the framework never took.
                    if (!stored) {
                        logE("scope: daemon refused ${merged.size} targets for $modulePackageName")
                        _message.value = ScopeMessage.ApplyFailed
                    } else {
                        // Whether the framework itself just joined or left this scope. Compared
                        // against what the daemon actually held a moment ago rather than against
                        // the picture the screen was showing, so it is the change this write made
                        // that is reported — not the mere presence of the row, and not a change
                        // somebody else had already made.
                        val framework = ScopeTarget(SYSTEM_FRAMEWORK_PACKAGE, 0)
                        val wasThere = framework in before
                        val isThere = framework in merged
                        savedScope.value = merged
                        // The draft moves with it. What went out is now what is stored, and a row
                        // that arrived from elsewhere and has just been written would otherwise sit
                        // in the saved set but not the draft — the apply bar would come straight
                        // back up offering to remove it.
                        draftScope.value = merged
                        // Storing a scope enables the module, so applying one to a disabled
                        // module would leave the switch here and the row in the module list
                        // both saying it is off. Followed through the switch's own path, so
                        // the enabled set keeps a single keeper.
                        if (!_uiState.value.isEnabled) setModuleEnabled(true)
                        _message.value = ScopeMessage.Applied
                        // system_server reads its module list once, when it starts:
                        // SystemServerService hands the zygisk module whatever
                        // ConfigCache.getModulesForSystemServer() holds at that moment. Every
                        // other target picks a scope up when its own process next starts, which
                        // happens on its own; this one does not until the framework is restarted,
                        // so the change is stored and inert and nothing on screen would say so.
                        // True for leaving as well as joining — a module already loaded into
                        // system_server stays loaded until that process goes.
                        if (wasThere != isThere) _frameworkRestartNeeded.value = true
                        // The module list depicts this scope as a row of app icons. It is a
                        // different screen with a different view model, so it is told rather than
                        // left to discover the change on the next manual refresh.
                        ServiceLocator.modules.noteScopeChanged()
                    }
                }
                .onFailure { e ->
                    logE("scope: apply of ${merged.size} targets to $modulePackageName failed", e)
                    _message.value = ScopeMessage.ApplyFailed
                }
            _applying.value = false
        }
    }

    /**
     * True when leaving now would leave the module enabled with nothing to hook.
     *
     * That combination does nothing at all but looks like it works, so the user is warned and
     * offered the switch rather than left to discover it.
     */
    fun wouldStrandModule(): Boolean =
        _uiState.value.isEnabled && draftScope.value.isEmpty() && savedScope.value.isEmpty()

    /**
     * This one module's scope, as plain JSON.
     *
     * Not gzipped like the whole-list backup: a single scope is small, and a readable file is
     * worth more here — it is the kind of thing someone hand-edits or pastes into an issue.
     */
    fun backupScopeTo(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    runCatching {
                            val payload =
                                draftScope.value.joinToString(",\n  ") {
                                    """{"packageName":"${it.packageName}","userId":${it.userId}}"""
                                }
                            ServiceLocator.context.contentResolver.openOutputStream(uri)?.use {
                                it.write("[\n  $payload\n]".toByteArray())
                            } ?: error("could not open the file")
                        }
                        .onFailure { e ->
                            logE("scope: backup of $modulePackageName failed", e)
                        }
                        .isSuccess
                }
            onDone(ok)
        }
    }

    fun restoreScopeFrom(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val targets =
                withContext(Dispatchers.IO) {
                    runCatching {
                            val text =
                                ServiceLocator.context.contentResolver.openInputStream(uri)?.use {
                                    it.readBytes().decodeToString()
                                } ?: error("could not open the file")
                            Regex("\"packageName\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"userId\"\\s*:\\s*(\\d+)")
                                .findAll(text)
                                .map { ScopeTarget(it.groupValues[1], it.groupValues[2].toInt()) }
                                .toSet()
                        }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            logE("scope: restore for $modulePackageName failed", e)
                        }
                        .getOrNull()
                }
            if (targets == null) {
                onDone(false)
            } else {
                // Into the draft, not straight to the daemon: a restore is an edit like any
                // other, and the user should see what it will do before it is written.
                draftScope.value = targets
                onDone(true)
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // Not private: the scope list has to recognise the framework row to explain what it is, and
    // a second copy of the literal in the screen would be a second thing to keep in step.
    internal companion object {
        /** What the list looks like before anyone touches it; the filter sheet marks a change. */
        const val DEFAULT_SHOW_SYSTEM = false
        const val DEFAULT_SHOW_GAMES = true
        const val DEFAULT_SHOW_MODULES = false

        /** How the daemon names the system server in a scope list. */
        const val SYSTEM_FRAMEWORK_PACKAGE = "system"

        /**
         * What to *show* for it, which is not what it is stored as.
         *
         * The scope table has said `system` since long before this manager, and the daemon, the
         * CLI and every backup file on every device say it too — so the stored name stays. But the
         * process it actually means is `system_server`, and a reader looking at a package name
         * expects the name of the thing. The rename lives here, at the point of display, and
         * nothing written back to the daemon ever passes through it.
         */
        const val SYSTEM_FRAMEWORK_DISPLAY_NAME = "system_server"

        /** The package name as it should appear on screen. */
        fun displayPackageName(packageName: String): String =
            if (packageName == SYSTEM_FRAMEWORK_PACKAGE) SYSTEM_FRAMEWORK_DISPLAY_NAME
            else packageName
        const val FRAMEWORK_LABEL = "System Framework"
    }
}

data class PendingChanges(val added: Int = 0, val removed: Int = 0) {
    val any: Boolean
        get() = added > 0 || removed > 0
}

enum class ScopeMessage {
    Applied,
    ApplyFailed,
    ToggleFailed,
    IncludeNewAppsFailed,
    NothingToOpen,
}

package org.matrix.vector.manager.ui.screens.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import org.matrix.vector.manager.R
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matrix.vector.manager.data.model.OnlineModule
import org.matrix.vector.manager.data.model.Release
import org.matrix.vector.manager.data.model.RepoVersion
import org.matrix.vector.manager.data.model.StoreCatalog
import org.matrix.vector.manager.data.model.StoreEntry
import org.matrix.vector.manager.data.repository.RepoRepository
import org.matrix.vector.manager.data.repository.SettingsRepository

/** A group the list can be asked to bring to the front. Several may apply at once. */
enum class StorePriority(val labelRes: Int) {
    Updates(R.string.store_updates_first),
    Installed(R.string.store_installed_first);

    fun applies(entry: StoreEntry): Boolean =
        when (this) {
            Updates -> entry.upgradable
            Installed -> entry.installed != null
        }
}

enum class StoreSort {
    RecentlyUpdated,
    Name,
    MostStarred,
}

/**
 * Which releases count.
 *
 * Two options, not three. Of the 809 modules in the catalogue 14 publish a beta and **none**
 * publishes a snapshot, so a snapshot channel would have no data behind it at all — and a control
 * that can never change anything is a control that lies.
 */
enum class StoreChannel(val preference: String) {
    Stable("stable"),
    Prerelease("beta");

    companion object {
        fun of(preference: String): StoreChannel =
            entries.firstOrNull { it.preference == preference } ?: Stable
    }
}

/**
 * Every release the chosen channel admits, newest first.
 *
 * **A beta is not a flagged element of `releases`; it is a different array.** In the live catalogue
 * not one entry of any module's `releases` carries `isPrerelease`, and each of the 14 modules that
 * publish a beta keeps it exclusively in `betaReleases`. Selecting the prerelease channel by
 * filtering `releases` on `isPrerelease` therefore matches nothing at all, and the install bar
 * takes the newest release with an APK straight off this list.
 *
 * Merged, the order has to come from the version code rather than from either array's position,
 * because the two are sorted independently and a beta is not automatically newer: of today's 14,
 * `com.luoshui.paycardeditor` publishes beta code 1 against stable code 8.
 */
internal fun OnlineModule.releasesOn(channel: StoreChannel): List<Release> {
    val published = releases.orEmpty().filter { it.isDraft != true }
    if (channel == StoreChannel.Prerelease) {
        val beta = betaReleases.orEmpty().filter { it.isDraft != true }
        if (beta.isEmpty()) return published
        return (published + beta)
            .distinctBy { it.tagName ?: it.id ?: it.name }
            .sortedByDescending { it.version?.versionCode ?: Long.MIN_VALUE }
    }
    // Defensive rather than load-bearing, and it stays because the mirror's shape is not ours to
    // promise: a module that has only ever prereleased still has releases worth listing.
    val stable = published.filter { it.isPrerelease != true }
    return stable.ifEmpty { published }
}

/**
 * The version the channel says is current — which is what every "Update to …" label states.
 *
 * On the prerelease channel that is whichever of the two channels is genuinely newer, not the beta
 * unconditionally. Advertising `latestBetaRelease` on its own offers `paycardeditor`'s readers a
 * downgrade from code 8 to code 1 and calls it an update.
 */
internal fun OnlineModule.latestOn(channel: StoreChannel): RepoVersion? {
    val stable = RepoVersion.parse(latestRelease)
    val best =
        if (channel == StoreChannel.Stable) stable
        else
            listOfNotNull(stable, RepoVersion.parse(latestBetaRelease)).maxByOrNull {
                it.versionCode
            }
    // A detail payload fetched for a module the catalogue has not loaded carries no summary of its
    // own, so the newest release's own tag stands in.
    return best ?: releasesOn(channel).firstOrNull()?.version
}

class RepoViewModel(
    private val repository: RepoRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(StoreSort.RecentlyUpdated)
    val sort: StateFlow<StoreSort> = _sort.asStateFlow()

    /**
     * Which groups are pulled to the front, most recently chosen first.
     *
     * A list rather than a set of switches because these compose: turning on both "updates first"
     * and "installed first" is a coherent request, and the only thing left to decide is which of
     * them wins for a module that is both. Recency answers that — the group you just asked for is
     * the one you are looking at — so the list is ordered by when each was switched on, and it
     * extends to a third rule without changing anything here.
     */
    private val _priorities = MutableStateFlow(listOf(StorePriority.Updates))
    val priorities: StateFlow<List<StorePriority>> = _priorities.asStateFlow()

    val catalog: StateFlow<StoreCatalog> = repository.catalog
    val isRefreshing: StateFlow<Boolean> = repository.isRefreshing

    val channel: StateFlow<StoreChannel> =
        settings.updateChannel
            .map(StoreChannel::of)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                StoreChannel.of(settings.updateChannel.value),
            )

    /**
     * Every catalogue entry paired with what this device has, before any filtering.
     *
     * Shared rather than recomputed per consumer: both the list and the "n updates" count need it,
     * and it walks 809 entries.
     */
    private val allEntries: StateFlow<List<StoreEntry>> =
        combine(repository.catalog, repository.installedVersions, channel, settings.mutedUpdates) {
                catalog,
                installed,
                channel,
                muted ->
                catalog.modules.map { entryFor(it, installed, channel, muted) }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** For the header. Counted over the whole catalogue, not over whatever the search box left. */
    val upgradableCount: StateFlow<Int> =
        allEntries
            .map { entries -> entries.count { it.upgradable } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val entries: StateFlow<List<StoreEntry>> =
        combine(allEntries, _query, view()) { entries, query, view ->
                entries.filter { it.matches(query) }.sortedWith(comparatorFor(view))
            }
            // Off the main thread, for the reason ModulesViewModel records: stateIn on its own
            // collects on Dispatchers.Main.immediate, which would put a filter and a sort over 809
            // entries on the UI thread on every keystroke.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // The catalogue outlives this ViewModel — switching tabs destroys the nav entry — so a
        // return to the Store paints from what is already in memory and only fetches when there is
        // nothing to paint.
        if (!repository.catalog.value.loaded) viewModelScope.launch { repository.refresh() }
        repository.refreshInstalled()
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun togglePriority(priority: StorePriority) {
        _priorities.update { current ->
            if (priority in current) current - priority else listOf(priority) + current
        }
    }

    fun setSort(value: StoreSort) {
        _sort.value = value
    }

    /**
     * DNS over HTTPS, exposed here because the Store is what it exists for.
     *
     * Changing it takes effect on the next lookup: `VectorDns` reads the flag per lookup rather
     * than at client construction, so there is nothing to rebuild and no restart to ask for.
     */
    val doh: StateFlow<Boolean> = settings.dohEnabled

    fun setDoh(enabled: Boolean) = settings.setDohEnabled(enabled)

    /** Persisted: the channel decides what counts as an update everywhere, not just in this list. */
    fun setChannel(value: StoreChannel) {
        settings.setUpdateChannel(value.preference)
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh(force = true) }
    }

    /** The three controls that reorder the list, as one value so `combine` stays readable. */
    private data class View(
        val sort: StoreSort,
        val priorities: List<StorePriority>,
        val channel: StoreChannel,
    )

    private fun view(): Flow<View> =
        combine(_sort, _priorities, channel) { sort, priorities, channel ->
            View(sort, priorities, channel)
        }

    private fun entryFor(
        module: OnlineModule,
        installed: Map<String, RepoVersion>,
        channel: StoreChannel,
        muted: Set<String>,
    ): StoreEntry =
        StoreEntry(
            module = module,
            latest = module.latestOn(channel),
            installed = installed[module.name],
            updatesMuted = module.name in muted,
        )

    private fun StoreEntry.matches(query: String): Boolean {
        if (query.isBlank()) return true
        return module.title.contains(query, ignoreCase = true) ||
            module.name.contains(query, ignoreCase = true) ||
            module.summary?.contains(query, ignoreCase = true) == true
    }

    /**
     * Updates come first by default, mirroring the Modules list's "enabled first": a list's first
     * job is to say what needs attention. It is a separate toggle rather than a fourth sort order
     * because it answers a different question from "in what order do I want to browse".
     */
    private fun comparatorFor(view: View): Comparator<StoreEntry> {
        val order: Comparator<StoreEntry> =
            when (view.sort) {
                // ISO-8601 in UTC sorts correctly as text, so this needs no date parsing per row.
                StoreSort.RecentlyUpdated ->
                    compareByDescending { it.module.latestReleaseTime.orEmpty() }
                StoreSort.Name -> compareBy { it.module.title.lowercase() }
                StoreSort.MostStarred ->
                    compareByDescending<StoreEntry> { it.module.stargazerCount ?: 0 }
                        .thenBy { it.module.title.lowercase() }
            }
        // Applied outermost-last, so the most recently chosen group ends up the primary key.
        return view.priorities.reversed().fold(order) { acc, priority ->
            compareByDescending<StoreEntry> { priority.applies(it) }.then(acc)
        }
    }
}

package org.matrix.vector.ui.store

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient

/**
 * Where the Store's shared code reports, as a `Log` priority; each app routes it to its own log.
 */
fun interface StoreLog {
    fun log(priority: Int, message: String, cause: Throwable?)
}

/**
 * The Store's data: the online catalogue, and what this device already has of it.
 *
 * One implementation for every app that hosts the Store. What differs between them is how the
 * device is asked what is installed, which arrives as a function, and where a log line goes, which
 * is [log]. Everything about the network is the same, and has to be: the hosts, their quirks and
 * the ways they fail are facts about the repository, not about the app reading it.
 *
 * Where the catalogue comes from, and why from two hosts, is [StoreApi]'s business. What is decided
 * here is when to ask and what to do when asking fails.
 *
 * **Caching is declared, not hoped for.** Every request states its own freshness, so the client's
 * disk cache is actually used: the mirror's snapshot revalidates against the server's own
 * ten-minute `max-age` and its ETag, pull-to-refresh forces the network, and when the network
 * yields nothing the same requests are replayed against the cache alone. That last step is why a
 * cold start with no network renders the last known catalogue rather than an error, and it is also
 * what gives the DNS-over-HTTPS setting an effect here, since the shared client is the one carrying
 * the DoH resolver.
 *
 * There is deliberately **no snapshot file** of our own. The OkHttp cache already holds these exact
 * bytes; a 2 MB duplicate in the same cache directory would buy nothing but a second thing to keep
 * in sync.
 */
open class StoreRepository(
    client: OkHttpClient,
    private val scope: CoroutineScope,
    private val log: StoreLog,
    /** What each installed package is at, or null when the device could not be asked. */
    private val readInstalledVersions: suspend () -> Map<String, RepoVersion>?,
) : StoreDataSource {

    private val api = StoreApi(client, log)

    private val _catalog = MutableStateFlow(StoreCatalog())
    override val catalog: StateFlow<StoreCatalog> = _catalog.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    override val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _installed = MutableStateFlow<Map<String, RepoVersion>>(emptyMap())

    /** Detail records fetched for installed modules, by package name. See [completeInstalled]. */
    @Volatile private var records = emptyMap<String, OnlineModule>()

    /** What each package on this device is at, keyed by package name. */
    override val installedVersions: StateFlow<Map<String, RepoVersion>> = _installed.asStateFlow()

    /** Held for the length of a refresh; `tryLock` leaves no window between checking and taking. */
    private val refreshing = Mutex()

    init {
        scope.launch { completeInstalled() }
    }

    /**
     * Reloads the catalogue.
     *
     * [force] is pull-to-refresh: it bypasses the cache rather than revalidating against it, and
     * reads the site to its last page, because a user who pulls is telling us they think what they
     * are looking at is stale.
     */
    override suspend fun refresh(force: Boolean) {
        // A second caller during a refresh is a no-op rather than a queued duplicate of a 2 MB
        // download.
        if (!refreshing.tryLock()) return
        try {
            if (!force && isCurrent(_catalog.value)) return
            _isRefreshing.value = true
            // Nothing in the block suspends, so a caller cancelled half way — a tab switch takes
            // the Store's `viewModelScope` with it — still leaves a whole catalogue behind.
            withContext(Dispatchers.IO) {
                val freshness =
                    if (force) CacheControl.FORCE_NETWORK
                    else
                        CacheControl.Builder()
                            .maxAge(CATALOG_MAX_AGE_MINUTES, TimeUnit.MINUTES)
                            .build()

                // The mirror's list goes up as soon as it is read, before the site has corrected
                // it, but only onto an empty screen: over a list already showing it would take
                // the corrections away, and update badges with them, only to bring them back.
                val blank = _catalog.value.isEmpty
                val paint = { base: StoreCatalog -> if (blank) _catalog.value = complete(base) }

                // Before reporting nothing, ask the cache — the bytes from the last successful
                // visit are usually still on disk, and a stale catalogue is far more use than an
                // empty screen.
                val fetched =
                    api.catalog(freshness, everyPage = force, paint)
                        ?: api.catalog(CacheControl.FORCE_CACHE, everyPage = false, paint)
                            ?.copy(fromCache = true)

                // Nothing on the network and nothing on disk. `loaded` still flips, so the screen
                // can say the repository is unreachable instead of sitting forever on a spinner
                // that means nothing.
                _catalog.value = fetched?.let(::complete) ?: _catalog.value.copy(loaded = true)
            }
        } finally {
            // In a `finally` rather than after the block: a cancelled `viewModelScope` — a
            // rotation mid-refresh — would otherwise strand the flag at true, and with
            // pull-to-refresh reading it that is a spinner that never stops.
            _isRefreshing.value = false
            refreshing.unlock()
        }
    }

    /**
     * Whether an unforced refresh would only fetch again what is already here.
     *
     * The mirror's snapshot answers that for itself through its `max-age`, but the site's pages
     * never answer 304, so without this every unforced refresh would download them again.
     */
    private fun isCurrent(catalog: StoreCatalog): Boolean {
        // Bounded below as well: a clock set back would otherwise hold a refresh off for as long
        // as it was set back by.
        val age = System.currentTimeMillis() - catalog.loadedAtMillis
        val limit = TimeUnit.MINUTES.toMillis(CATALOG_MAX_AGE_MINUTES.toLong())
        return !catalog.isEmpty && !catalog.fromCache && age in 0 until limit
    }

    /**
     * The full record for one module: its README, and every release rather than only the newest.
     *
     * Returns null when no host answers. Callers are expected to fall back to the catalogue entry
     * they already hold, which carries the description and, where the mirror supplied it, the
     * scope, the collaborators and the newest release with its APK — a usable page, and much better
     * than an error screen.
     */
    override suspend fun details(packageName: String): OnlineModule? =
        withContext(Dispatchers.IO) {
            val freshness =
                CacheControl.Builder().maxAge(DETAIL_MAX_AGE_MINUTES, TimeUnit.MINUTES).build()
            api.details(packageName, freshness)
                ?: api.details(packageName, CacheControl.FORCE_CACHE)
        }

    /**
     * Gives every installed module its releases, where the catalogue came without them.
     *
     * An entry the site corrected, or one read from the site alone, names a version and carries no
     * APK. For most of the catalogue that costs nothing, since the detail page fetches its own
     * record. For an installed module it would leave the app offering an update it then cannot
     * install from where it offers it, so those few are fetched here.
     *
     * Driven by the two flows rather than called from [refresh], because the device's package list
     * and the catalogue arrive in either order. A record that fails to arrive leaves the names as
     * they were, and `distinctUntilChanged` then keeps this from asking in a loop.
     */
    private suspend fun completeInstalled() {
        combine(_catalog, _installed) { catalog, installed ->
                catalog.modules
                    .filter { it.releases.isNullOrEmpty() && it.name in installed }
                    .map { it.name }
            }
            .distinctUntilChanged()
            .collectLatest { names ->
                val fetched = names.mapNotNull { details(it) }
                if (fetched.isEmpty()) return@collectLatest
                val message =
                    "store: ${fetched.size} of ${names.size} installed modules given releases"
                log.log(Log.INFO, message, null)
                // Without the README: these stay in memory for as long as the process does.
                records = records + fetched.associate { it.name to it.copy(readmeHTML = null) }
                _catalog.update(::complete)
            }
    }

    /**
     * [catalog] with the [records] laid over the entries that lack releases.
     *
     * Applied to everything published and not only once after a fetch, because every reload brings
     * the same bare entries back.
     *
     * Only a record of the very releases the entry names: the mirror answers when the site does
     * not, and its record may be older.
     */
    private fun complete(catalog: StoreCatalog): StoreCatalog {
        val records = records
        if (records.isEmpty()) return catalog
        return catalog.copy(
            modules =
                catalog.modules.map { module ->
                    records[module.name]?.takeIf {
                        module.releases.isNullOrEmpty() &&
                            it.latestRelease == module.latestRelease &&
                            it.latestBetaReleaseTime == module.latestBetaReleaseTime
                    } ?: module
                }
        )
    }

    /** Re-reads installed versions; called on opening the Store and after an install lands. */
    override fun refreshInstalled() {
        scope.launch { loadInstalled() }
    }

    /**
     * The same read, awaited and handed back, for a caller that has to act on what it finds.
     *
     * Which is how an install records what it produced: the note that suppresses a satisfied offer
     * is compared against [installedVersions], so it has to be written from that same reading and
     * not from one of the caller's own, which need not agree with it.
     *
     * Returns the last known map when the device cannot be asked, which is the safe direction: a
     * note written from a stale version simply fails to match, and the offer stays.
     */
    suspend fun readInstalled(): Map<String, RepoVersion> {
        loadInstalled()
        return _installed.value
    }

    /** Keeps the last map when the device has no answer: what is installed did not stop being. */
    private suspend fun loadInstalled() {
        readInstalledVersions()?.let { _installed.value = it }
    }

    private companion object {
        /** Matches the mirror's own `cache-control: max-age=600`, so revalidation stays free. */
        const val CATALOG_MAX_AGE_MINUTES = 10

        /** Longer: a module's release history changes far less often than the index does. */
        const val DETAIL_MAX_AGE_MINUTES = 60
    }
}

package org.matrix.vector.manager.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.matrix.vector.ui.store.StoreDataSource
import org.matrix.vector.ui.store.OnlineModule
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreCatalog
import org.matrix.vector.manager.data.model.versionCodeCompat
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logI
import org.matrix.vector.manager.logW

/**
 * The Store's data: the online catalogue, and what this device already has of it.
 *
 * **The mirror list is two lists, and that is not an oversight.** The full `modules.json` is served
 * Per-module `module/<package>.json` is served by both hosts, with `modules.lsposed.org` providing the latest data and `backup.modules.lsposed.org` serving as fallback.
 *
 * **Caching is declared, not hoped for.** Every request states its own freshness, so the 16 MB disk
 * cache in `HttpClientFactory` is actually used: the catalogue revalidates against the server's own
 * ten-minute `max-age` and its ETag, pull-to-refresh forces the network, and when every mirror
 * fails the same request is replayed against the cache alone. That last step is why a cold start
 * with no network renders the last known catalogue rather than an error, and it is also what gives
 * the DNS-over-HTTPS setting an effect here, since the shared client is the one carrying the DoH
 * resolver.
 *
 * There is deliberately **no snapshot file** of our own, unlike `GitHubRepository`. The OkHttp
 * cache already holds these exact bytes; a 1.2 MB duplicate in the same cache directory would buy
 * nothing but a second thing to keep in sync.
 */
class RepoRepository(
    private val client: OkHttpClient,
    private val daemon: DaemonClient,
    private val scope: CoroutineScope,
    private val gson: Gson = Gson(),
) : StoreDataSource {

    private val _catalog = MutableStateFlow(StoreCatalog())
    override val catalog: StateFlow<StoreCatalog> = _catalog.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    override val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _installed = MutableStateFlow<Map<String, RepoVersion>>(emptyMap())

    /**
     * What each package on this device is at, keyed by package name.
     *
     * One `getInstalledPackagesFromAllUsers` call and no `ModuleDetection`: the Store already knows
     * that every name it asks about is a module, so it does not need the much more expensive
     * discovery the Modules screen runs, which opens every APK to find out.
     */
    override val installedVersions: StateFlow<Map<String, RepoVersion>> = _installed.asStateFlow()


    /** Held for the length of a refresh; `tryLock` leaves no window between checking and taking. */
    private val refreshing = Mutex()

    init {
        scope.launch {
            // Re-read whenever a binder arrives, including a reconnect. The map is deliberately
            // *not* cleared when the daemon goes away: which packages are installed is a fact
            // about the device, not about the framework, and dropping every "Installed" badge
            // because the daemon died would state something untrue.
            ServiceLocator.service.collect { service -> if (service != null) loadInstalled() }
        }
    }

    /**
     * Reloads the catalogue.
     *
     * [force] is pull-to-refresh: it bypasses the cache rather than revalidating against it,
     * because a user who pulls is telling us they think what they are looking at is stale.
     */
    override suspend fun refresh(force: Boolean) {
        // A second caller during a refresh is a no-op rather than a queued duplicate
        if (!refreshing.tryLock()) return
        try {
            _isRefreshing.value = true
            withContext(Dispatchers.IO) {
                val freshness =
                    if (force) CacheControl.FORCE_NETWORK
                    else
                        CacheControl.Builder()
                            .maxAge(CATALOG_MAX_AGE_MINUTES, TimeUnit.MINUTES)
                            .build()

                // Fetch page 1 immediately to show the initial modules instantly
                val firstPage = fetchPaginatedCatalog(1, freshness)
                    ?: LIST_MIRRORS.firstNotNullOfOrNull { fetchCatalog(it, freshness) }

                if (firstPage != null) {
                    val initialCatalog = firstPage.copy(
                        isSyncing = firstPage.page < firstPage.pageCount,
                        syncProgress = if (firstPage.pageCount > 0) 1f / firstPage.pageCount else 1f,
                    )
                    _catalog.value = initialCatalog

                    // If more pages exist, sync them sequentially in the background
                    if (firstPage.page < firstPage.pageCount) {
                        syncRemainingPages(firstPage, freshness)
                    }
                    return@withContext
                }

                // If network failed, check cache for page 1
                val cached = fetchPaginatedCatalog(1, CacheControl.FORCE_CACHE)
                    ?: LIST_MIRRORS.firstNotNullOfOrNull { fetchCatalog(it, CacheControl.FORCE_CACHE) }

                when {
                    cached != null -> _catalog.value = cached.copy(fromCache = true, isSyncing = false)
                    else -> _catalog.value = _catalog.value.copy(loaded = true, isSyncing = false)
                }
            }
        } finally {
            _isRefreshing.value = false
            refreshing.unlock()
        }
    }

    private suspend fun syncRemainingPages(initial: StoreCatalog, cacheControl: CacheControl) {
        var accumulated = initial.modules.toMutableList()
        val totalPages = initial.pageCount
        for (p in (initial.page + 1)..totalPages) {
            val pageResult = fetchPaginatedCatalog(p, cacheControl)
            if (pageResult != null) {
                accumulated.addAll(pageResult.modules)
                val distinct = usable(accumulated)
                accumulated = distinct.toMutableList()
                val progress = p.toFloat() / totalPages
                _catalog.value = _catalog.value.copy(
                    modules = distinct,
                    page = p,
                    pageCount = totalPages,
                    total = pageResult.total,
                    isSyncing = p < totalPages,
                    syncProgress = progress,
                )
            } else {
                // If a page fails, stop syncing and keep current state
                _catalog.value = _catalog.value.copy(isSyncing = false)
                break
            }
        }
        _catalog.value = _catalog.value.copy(isSyncing = false, syncProgress = 1f)
    }

    /**
     * The full record for one module: its README, and every release rather than only the newest.
     *
     * Returns null when no mirror answers. Callers are expected to fall back to the catalogue entry
     * they already hold, which carries the description, the scope, the collaborators and the newest
     * release with its APK — a usable page, and much better than an error screen.
     */
    override suspend fun details(packageName: String): OnlineModule? =
        withContext(Dispatchers.IO) {
            val freshness =
                CacheControl.Builder().maxAge(DETAIL_MAX_AGE_MINUTES, TimeUnit.MINUTES).build()
            DETAIL_MIRRORS.firstNotNullOfOrNull { fetchDetails(it, packageName, freshness) }
                ?: DETAIL_MIRRORS.firstNotNullOfOrNull {
                    fetchDetails(it, packageName, CacheControl.FORCE_CACHE)
                }
        }

    /** Re-reads installed versions; called on opening the Store and after an install lands. */
    override fun refreshInstalled() {
        scope.launch { loadInstalled() }
    }

    /**
     * The same read, awaited and handed back, for a caller that has to act on what it finds.
     *
     * Which is how an install records what it produced: the note that suppresses a satisfied offer
     * is compared against [installedVersions], so it has to be written from that same reading. A
     * local `getPackageInfo` would answer for user 0 while this map answers with the highest version
     * across every user, and on a device with a work profile the two differ — leaving a note that can
     * never match and a row that nags for ever.
     *
     * Returns the last known map when the daemon cannot be reached, which is the safe direction: a
     * note written from a stale version simply fails to match, and the offer stays.
     */
    suspend fun readInstalled(): Map<String, RepoVersion> {
        loadInstalled()
        return _installed.value
    }

    private suspend fun loadInstalled() {
        val packages =
            daemon
                .getInstalledPackagesFromAllUsers(0, false)
                .onFailure { e ->
                    logW("store: installed versions unavailable", e)
                }
                .getOrNull() ?: return
        val versions = HashMap<String, RepoVersion>(packages.size)
        for (info in packages) {
            val version = RepoVersion(info.versionCodeCompat, info.versionName.orEmpty())
            // The daemon reports every user, so the same package arrives more than once. The
            // highest version wins, because that is the one an update would have to beat.
            val known = versions[info.packageName]
            if (known == null || version.versionCode > known.versionCode) {
                versions[info.packageName] = version
            }
        }
        _installed.value = versions
    }

    private fun fetchCatalog(baseUrl: String, cacheControl: CacheControl): StoreCatalog? {
        val url = baseUrl + "modules.json"
        return try {
            // `use` covers the failure branch as well as the success one, and the failure branch is
            // the one that runs whenever a mirror is down.
            client.newCall(request(url, cacheControl)).execute().use { response ->
                if (!response.isSuccessful) {
                    // The FORCE_CACHE replay synthesises 504 without contacting the mirror, so
                    // only report a status the network actually produced.
                    if (response.networkResponse != null) {
                        logW("store: $url returned HTTP ${response.code}")
                    }
                    return null
                }
                val parsed = parseCatalog(response)
                if (parsed.isEmpty()) return null
                logI("store: ${parsed.size} modules from $url")
                // `fromCache` is deliberately *not* derived from `response.networkResponse`. A hit
                // inside the ten-minute freshness window is served from disk without touching the
                // network, and calling that "the saved catalogue" would put an offline notice on
                // a perfectly current list. Staleness is a property of which branch produced this,
                // so the caller sets the flag on the fallback path and only there.
                StoreCatalog(
                    modules = usable(parsed),
                    loaded = true,
                    loadedAtMillis = response.receivedResponseAtMillis,
                )
            }
        } catch (e: Exception) {
            logW("store: $url unavailable", e)
            null
        }
    }

    private fun fetchDetails(
        baseUrl: String,
        packageName: String,
        cacheControl: CacheControl,
    ): OnlineModule? {
        val url = "${baseUrl}module/$packageName.json"
        return try {
            client.newCall(request(url, cacheControl)).execute().use { response ->
                if (!response.isSuccessful) return null
                gson.fromJson(response.body.string(), OnlineModule::class.java)
            }
        } catch (e: Exception) {
            logW("store: $url unavailable", e)
            null
        }
    }


    private fun fetchPaginatedCatalog(page: Int, cacheControl: CacheControl): StoreCatalog? {
        val url = "https://modules.lsposed.org/module-list/$page.json"
        return try {
            client.newCall(request(url, cacheControl)).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.networkResponse != null) {
                        logW("store: $url returned HTTP ${response.code}")
                    }
                    return null
                }
                val parsed = parsePaginatedCatalog(response) ?: return null
                logI("store: page ${parsed.page}/${parsed.pageCount} (${parsed.modules.size} modules) from $url")
                StoreCatalog(
                    modules = usable(parsed.modules),
                    loaded = true,
                    loadedAtMillis = response.receivedResponseAtMillis,
                    page = parsed.page,
                    pageCount = parsed.pageCount,
                    total = parsed.total,
                )
            }
        } catch (e: Exception) {
            logW("store: $url unavailable", e)
            null
        }
    }

    private data class PaginatedResponse(
        val modules: List<OnlineModule> = emptyList(),
        val page: Int = 1,
        val pageCount: Int = 1,
        val total: Int = 0,
    )

    private fun parsePaginatedCatalog(response: Response): PaginatedResponse? {
        return try {
            val json = JsonParser.parseReader(response.body.charStream()).asJsonObject
            val page = json.get("page")?.asInt ?: 1
            val pageCount = json.get("pageCount")?.asInt ?: 1
            val total = json.get("total")?.asInt ?: 0
            val modulesArray = json.getAsJsonArray("modules") ?: return null
            val modules = ArrayList<OnlineModule>(modulesArray.size())
            for (element in modulesArray) {
                val module = runCatching { gson.fromJson(element, OnlineModule::class.java) }
                if (module.isSuccess) module.getOrNull()?.let(modules::add)
            }
            PaginatedResponse(modules = modules, page = page, pageCount = pageCount, total = total)
        } catch (e: Exception) {
            logW("store: failed to parse paginated catalog", e)
            null
        }
    }

    private fun request(url: String, cacheControl: CacheControl): Request =
        Request.Builder().url(url).cacheControl(cacheControl).build()

    /**
     * Reads the catalogue one entry at a time, and survives a bad one.
     *
     * Binding the whole array in a single `fromJson` call fails the entire Store on one unexpected
     * field — `additionalAuthors` holds objects rather than the strings its name suggests, and
     * `AdditionalAuthor` exists because of it. This is third-party data written by hundreds of
     * authors, so an entry the model does not expect must cost that entry and nothing else.
     *
     * Streamed off the response rather than through a `String`, which also keeps the 1.2 MB body
     * from being materialised twice.
     */
    private fun parseCatalog(response: Response): List<OnlineModule> {
        val modules = ArrayList<OnlineModule>(1024)
        var rejected = 0
        JsonReader(response.body.charStream()).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                // Parsing to a JsonElement first cannot fail on well-formed JSON, so a binding
                // failure below leaves the reader cleanly positioned on the next entry.
                val element = JsonParser.parseReader(reader)
                val module = runCatching { gson.fromJson(element, OnlineModule::class.java) }
                if (module.isSuccess) module.getOrNull()?.let(modules::add) else rejected++
            }
            reader.endArray()
        }
        if (rejected > 0) logW("store: skipped $rejected unreadable entries")
        return modules
    }

    /**
     * What is worth showing of a parsed catalogue.
     *
     * `distinctBy` is not superstition about today's data — it is what stops a mirror serving the
     * same package twice from crashing the Store's `LazyColumn`, which is keyed by package name.
     * Entries with no release or latestRelease are dropped.
     */
    private fun usable(parsed: List<OnlineModule>): List<OnlineModule> =
        parsed
            .asSequence()
            .filter { it.hide != true }
            .filter { !it.releases.isNullOrEmpty() || !it.latestRelease.isNullOrBlank() }
            .distinctBy { it.name }
            .toList()

    private companion object {
        /**
         * The only host serving the full list. Probed rather than assumed: roughly 1.2 MB and 800
         * entries here, against a 403 from `modules.lsposed.org`.
         */
        val LIST_MIRRORS = listOf("https://backup.modules.lsposed.org/")

        /** Detail is served by both hosts; primary site carries the latest data while backup is fallback. */
        val DETAIL_MIRRORS =
            listOf("https://modules.lsposed.org/", "https://backup.modules.lsposed.org/")

        /** Matches 1 hour cache duration so full catalogue sync only happens once an hour or on pull-to-refresh. */
        const val CATALOG_MAX_AGE_MINUTES = 60

        /** Longer: a module's release history changes far less often than the index does. */
        const val DETAIL_MAX_AGE_MINUTES = 60
    }
}

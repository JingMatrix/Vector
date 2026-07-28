package org.matrix.vector.manager.data.repository

import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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
import org.matrix.vector.manager.Constants
import org.matrix.vector.manager.data.model.OnlineModule
import org.matrix.vector.manager.data.model.RepoVersion
import org.matrix.vector.manager.data.model.StoreCatalog
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient

/**
 * The Store's data: the online catalogue, and what this device already has of it.
 *
 * **The mirror list is two lists, and that is not an oversight.** The full `modules.json` is served
 * by exactly one host today: `modules.lsposed.org` answers that path with a 403. Per-module
 * `module/<package>.json` *is* served by both hosts, so the public site is a real fallback there
 * and only there. Merging these two lists back into one would quietly take the Store offline.
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
) {

    private val _catalog = MutableStateFlow(StoreCatalog())
    val catalog: StateFlow<StoreCatalog> = _catalog.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _installed = MutableStateFlow<Map<String, RepoVersion>>(emptyMap())

    /**
     * What each package on this device is at, keyed by package name.
     *
     * One `getInstalledPackagesFromAllUsers` call and no `ModuleDetection`: the Store already knows
     * that every name it asks about is a module, so it does not need the much more expensive
     * discovery the Modules screen runs, which opens every APK to find out.
     */
    val installedVersions: StateFlow<Map<String, RepoVersion>> = _installed.asStateFlow()


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
    suspend fun refresh(force: Boolean = false) {
        // A second caller during a refresh is a no-op rather than a queued duplicate of a 1.2 MB
        // download.
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

                val fetched = LIST_MIRRORS.firstNotNullOfOrNull { fetchCatalog(it, freshness) }
                if (fetched != null) {
                    _catalog.value = fetched
                    return@withContext
                }

                // Every mirror failed. Before reporting nothing, ask the cache — the bytes from
                // the last successful visit are usually still on disk, and a stale catalogue is
                // far more use than an empty screen.
                val cached =
                    LIST_MIRRORS.firstNotNullOfOrNull { fetchCatalog(it, CacheControl.FORCE_CACHE) }
                when {
                    cached != null -> _catalog.value = cached.copy(fromCache = true)
                    // Nothing on the network and nothing on disk. `loaded` still flips, so the
                    // screen can say the repository is unreachable instead of sitting forever on
                    // a spinner that means nothing.
                    else -> _catalog.value = _catalog.value.copy(loaded = true)
                }
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
     * The full record for one module: its README, and every release rather than only the newest.
     *
     * Returns null when no mirror answers. Callers are expected to fall back to the catalogue entry
     * they already hold, which carries the description, the scope, the collaborators and the newest
     * release with its APK — a usable page, and much better than an error screen.
     */
    suspend fun details(packageName: String): OnlineModule? =
        withContext(Dispatchers.IO) {
            val freshness =
                CacheControl.Builder().maxAge(DETAIL_MAX_AGE_MINUTES, TimeUnit.MINUTES).build()
            DETAIL_MIRRORS.firstNotNullOfOrNull { fetchDetails(it, packageName, freshness) }
                ?: DETAIL_MIRRORS.firstNotNullOfOrNull {
                    fetchDetails(it, packageName, CacheControl.FORCE_CACHE)
                }
        }

    /** Re-reads installed versions; called on opening the Store and after an install lands. */
    fun refreshInstalled() {
        scope.launch { loadInstalled() }
    }

    private suspend fun loadInstalled() {
        val packages =
            daemon
                .getInstalledPackagesFromAllUsers(0, false)
                .onFailure { e ->
                    Log.w(Constants.TAG, "store: installed versions unavailable", e)
                }
                .getOrNull() ?: return
        val versions = HashMap<String, RepoVersion>(packages.size)
        for (info in packages) {
            val version = RepoVersion(info.longVersionCodeCompat(), info.versionName.orEmpty())
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
                        Log.w(Constants.TAG, "store: $url returned HTTP ${response.code}")
                    }
                    return null
                }
                val parsed = parseCatalog(response)
                if (parsed.isEmpty()) return null
                Log.i(Constants.TAG, "store: ${parsed.size} modules from $url")
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
            Log.w(Constants.TAG, "store: $url unavailable", e)
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
            Log.w(Constants.TAG, "store: $url unavailable", e)
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
        if (rejected > 0) Log.w(Constants.TAG, "store: skipped $rejected unreadable entries")
        return modules
    }

    /**
     * What is worth showing of a parsed catalogue.
     *
     * `distinctBy` is not superstition about today's data — it is what stops a mirror serving the
     * same package twice from crashing the Store's `LazyColumn`, which is keyed by package name.
     * Entries with no release at all are dropped because there is nothing to install and nothing to
     * say about them.
     */
    private fun usable(parsed: List<OnlineModule>): List<OnlineModule> =
        parsed
            .asSequence()
            .filter { it.hide != true }
            .filter { !it.releases.isNullOrEmpty() }
            .distinctBy { it.name }
            .toList()

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else versionCode.toLong()

    private companion object {
        /**
         * The only host serving the full list. Probed rather than assumed: roughly 1.2 MB and 800
         * entries here, against a 403 from `modules.lsposed.org`.
         */
        val LIST_MIRRORS = listOf("https://backup.modules.lsposed.org/")

        /** Detail is served by both hosts, so here the public site is a genuine fallback. */
        val DETAIL_MIRRORS =
            listOf("https://backup.modules.lsposed.org/", "https://modules.lsposed.org/")

        /** Matches the server's own `cache-control: max-age=600`, so revalidation stays free. */
        const val CATALOG_MAX_AGE_MINUTES = 10

        /** Longer: a module's release history changes far less often than the index does. */
        const val DETAIL_MAX_AGE_MINUTES = 60
    }
}

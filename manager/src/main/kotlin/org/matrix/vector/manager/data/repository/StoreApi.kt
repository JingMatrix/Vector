package org.matrix.vector.manager.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.matrix.vector.manager.logI
import org.matrix.vector.manager.logW
import org.matrix.vector.ui.store.OnlineModule
import org.matrix.vector.ui.store.StoreCatalog

/**
 * The module repository's two hosts, and how one catalogue is made of them.
 *
 * **Neither host serves what the Store needs on its own.** Probed rather than assumed:
 * - the mirror, `backup.modules.lsposed.org`, serves `modules.json`: every module in one cacheable
 *   2 MB response, each entry carrying its newest release with the APK. It is regenerated daily but
 *   ran three days behind when measured, so a release published since is not in it.
 * - the site, `modules.lsposed.org`, answers `modules.json` with a 403 and serves the index only as
 *   `module-list/<n>.json`: thirty entries a page, newest release first, current to the minute.
 *   Those entries state `latestRelease` but carry no release, scope or collaborators, and the pages
 *   answer no conditional request with a 304, so walking all of them costs 45 requests and 1.3 MB
 *   every time.
 *
 * So the mirror is the base and the site corrects it. Because the pages are ordered by release time
 * and the mirror has only been seen *behind*, everything the mirror lacks sits at the front: the
 * walk stops at the first page that agrees with the mirror entirely, typically the fifth. An entry
 * the site knows better loses its now-outdated releases rather than keep an APK that is no longer
 * the one its version names; [RepoRepository] fetches the detail record for the installed ones.
 *
 * Either host can vanish. Without the site the mirror is shown as it is, which is what the Store
 * did before. Without the mirror every page is read, and the catalogue is the site's alone.
 */
internal class StoreApi(
    client: OkHttpClient,
    private val gson: Gson = Gson(),
    private val site: String = "https://modules.lsposed.org/",
    private val mirror: String = "https://backup.modules.lsposed.org/",
) {

    /**
     * The shared client, with its cache made to hold what the replay against it needs.
     *
     * Two things otherwise empty it. OkHttp stores a 404 as readily as a 200, and under the same
     * key, so a host that starts answering 404 — how a retired static site fails — would overwrite
     * the last good catalogue with its own error page. And the site marks its pages
     * `must-revalidate`, which forbids exactly the stale read a cold start with no network is: a
     * `FORCE_CACHE` request for one is refused although the bytes are on disk.
     */
    private val client =
        client
            .newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val stored =
                    response.isSuccessful || response.code == HttpURLConnection.HTTP_NOT_MODIFIED
                when {
                    !stored -> response.cacheControl("no-store")
                    // Still never fresh, so the network is asked whenever it can be.
                    response.cacheControl.mustRevalidate -> response.cacheControl("max-age=0")
                    else -> response
                }
            }
            .build()

    private fun Response.cacheControl(value: String): Response =
        newBuilder().header("Cache-Control", value).build()

    /**
     * The catalogue, or null when [cacheControl] could not produce a whole one.
     *
     * [everyPage] reads the site to its last page even where the mirror already agrees. That finds
     * what does not sit at the front — a release republished under its old date, a beta with no
     * stable release beside it — and costs the whole index, so it is for a reader who asked.
     *
     * [onBase] receives the mirror's list as soon as it is read, before the site has corrected it.
     */
    fun catalog(
        cacheControl: CacheControl,
        everyPage: Boolean,
        onBase: (StoreCatalog) -> Unit = {},
    ): StoreCatalog? {
        val base = fetchSnapshot(cacheControl)?.also(onBase)
        val modules = LinkedHashMap<String, OnlineModule>()
        base?.modules?.associateByTo(modules) { it.name }
        // With nothing to compare against, there is no page at which to stop.
        val toTheEnd = everyPage || base == null

        var loadedAtMillis = base?.loadedAtMillis ?: 0L
        var complete = false
        var number = 1
        var pageCount = 1
        var source = cacheControl
        walk@ while (true) {
            // The first page goes alone, being what says how many there are. After it, every
            // remaining page at once where all are wanted, and otherwise a few: the page that
            // agrees with the base ends the walk, and whatever was read past it is thrown away.
            val reach = if (toTheEnd) pageCount else number + READ_AHEAD - 1
            val asked = source
            for (fetched in fetchPages(number..reach.coerceIn(number, pageCount), asked)) {
                var page = fetched
                if (page == null && base != null && !asked.onlyIfCached) {
                    // The base can be current straight off the disk while the site is out of
                    // reach, and the corrections it had last time are then still on disk too. The
                    // rest of the walk stays there: offline, every further attempt is a timeout
                    // to sit out.
                    source = CacheControl.FORCE_CACHE
                    page = fetchPage(number, source)
                }
                // Only a page read since that base, though, which a saved one need not be: an
                // older page laid over a newer base would take a version back.
                if (page == null || (base != null && page.receivedAtMillis < base.loadedAtMillis)) {
                    break@walk
                }
                if (loadedAtMillis == 0L) loadedAtMillis = page.receivedAtMillis
                // Bounded, because this number decides how many requests go out together.
                pageCount = page.pageCount.coerceIn(number, MAX_PAGES)
                val corrected = page.modules.count { correct(modules, it) }
                complete = number == pageCount || (!toTheEnd && corrected == 0)
                if (complete) break@walk
                number++
            }
        }

        // A walk that broke off is only a loss when it was the sole source, and then only on the
        // network: the replay against the cache is the last resort, and part of a list beats none.
        if (base == null && !complete && (modules.isEmpty() || !cacheControl.onlyIfCached)) {
            return null
        }
        if (number > 1 || complete) logI("store: site read to page $number of $pageCount")
        return StoreCatalog(usable(modules.values), loaded = true, loadedAtMillis = loadedAtMillis)
    }

    /**
     * The full record for one module, from the site first because the mirror's lags.
     *
     * Read as strictly as a catalogue entry, and only accepted as the module that was asked for:
     * the caller files it under that name.
     */
    fun details(packageName: String, cacheControl: CacheControl): OnlineModule? =
        sequenceOf(site, mirror).firstNotNullOfOrNull { host ->
            fetch("${host}module/$packageName.json", cacheControl) { response ->
                val root = JsonParser.parseReader(response.body.charStream()).asJsonObject
                bind(root)?.takeIf { it.name == packageName }
            }
        }

    private fun fetchSnapshot(cacheControl: CacheControl): StoreCatalog? {
        val url = mirror + "modules.json"
        return fetch(url, cacheControl) { response ->
            val parsed = usable(parseSnapshot(response))
            if (parsed.isEmpty()) return@fetch null
            logI("store: ${parsed.size} modules from $url")
            // `fromCache` is deliberately *not* derived from `response.networkResponse`. A hit
            // inside the ten-minute freshness window is served from disk without touching the
            // network, and calling that "the saved catalogue" would put an offline notice on
            // a perfectly current list. Staleness is a property of which branch produced this,
            // so the caller sets the flag on the fallback path and only there.
            StoreCatalog(parsed, loaded = true, loadedAtMillis = response.receivedResponseAtMillis)
        }
    }

    private class Page(
        val modules: List<OnlineModule>,
        val pageCount: Int,
        val receivedAtMillis: Long,
    )

    /**
     * [numbers] in order, each null where its page could not be read.
     *
     * Together rather than in turn, because the site takes a third of a second over each page
     * however small it is: 45 of those in a row is a quarter of a minute under a spinner, and at
     * once, down the one HTTP/2 connection, they are half a second.
     */
    private fun fetchPages(numbers: IntRange, cacheControl: CacheControl): List<Page?> =
        if (numbers.first == numbers.last) {
            listOf(fetchPage(numbers.first, cacheControl))
        } else {
            runBlocking(Dispatchers.IO) {
                numbers.map { async { fetchPage(it, cacheControl) } }.awaitAll()
            }
        }

    private fun fetchPage(number: Int, cacheControl: CacheControl): Page? =
        fetch("${site}module-list/$number.json", cacheControl) { response ->
            val root = JsonParser.parseReader(response.body.charStream()).asJsonObject
            val modules = root.getAsJsonArray("modules").mapNotNull { bind(it.asJsonObject) }
            // The walk counts for itself and only asks the page where the end is, so a page that
            // misstates its own number cannot send it round again.
            Page(modules, root.get("pageCount").asInt, response.receivedResponseAtMillis)
        }

    /**
     * One GET, with every way it can fail folded into null.
     *
     * [read] runs inside the same `try`, because a body that is not the JSON it was expected to be
     * is one more way for a host to be down.
     */
    private fun <T> fetch(url: String, cacheControl: CacheControl, read: (Response) -> T?): T? =
        try {
            val request = Request.Builder().url(url).cacheControl(cacheControl).build()
            // `use` covers the failure branch as well as the success one, and the failure branch is
            // the one that runs whenever a host is down.
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    read(response)
                } else {
                    // The FORCE_CACHE replay synthesises 504 without contacting the host, so only
                    // report a status the network actually produced.
                    if (response.networkResponse != null) {
                        logW("store: $url returned HTTP ${response.code}")
                    }
                    null
                }
            }
        } catch (e: Exception) {
            logW("store: $url unavailable", e)
            null
        }

    /**
     * Reads the snapshot one entry at a time, and survives a bad one.
     *
     * Binding the whole array in a single `fromJson` call fails the entire Store on one unexpected
     * field — `additionalAuthors` holds objects rather than the strings its name suggests, and
     * `AdditionalAuthor` exists because of it. This is third-party data written by hundreds of
     * authors, so an entry the model does not expect must cost that entry and nothing else.
     *
     * Streamed off the response rather than through a `String`, which also keeps the 2 MB body from
     * being materialised twice.
     */
    private fun parseSnapshot(response: Response): List<OnlineModule> {
        val modules = ArrayList<OnlineModule>(1536)
        var rejected = 0
        JsonReader(response.body.charStream()).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                // Parsing to a JsonElement first cannot fail on well-formed JSON, so a binding
                // failure below leaves the reader cleanly positioned on the next entry.
                val module = bind(JsonParser.parseReader(reader).asJsonObject)
                if (module != null) modules.add(module) else rejected++
            }
            reader.endArray()
        }
        if (rejected > 0) logW("store: skipped $rejected unreadable entries")
        return modules
    }

    /** Null for an entry the model cannot hold, including one with no name to key it by. */
    private fun bind(entry: JsonObject): OnlineModule? {
        val module = runCatching { gson.fromJson(entry, OnlineModule::class.java) }.getOrNull()
        // Gson constructs through `Unsafe`, so the non-null type on `name` is not a promise.
        @Suppress("SENSELESS_COMPARISON")
        return module?.takeIf { it.name != null }
    }

    companion object {
        /** Pages read together past the first, where the walk expects to stop early. */
        const val READ_AHEAD = 4

        /** Four times today's index; past it a `pageCount` is a mistake, not a catalogue. */
        const val MAX_PAGES = 200

        /**
         * Folds one page entry into [modules]; true when the page knew something they did not.
         *
         * The stable version and the time of the newest beta are the whole comparison. The two
         * hosts word `summary` and `sourceUrl` differently for hundreds of unchanged modules, so
         * comparing more would report a difference on every page and the walk would never stop
         * early.
         *
         * **The later statement wins, not the site.** That the mirror is the one behind is what was
         * measured, not what is promised, and a site that stopped being rebuilt would otherwise
         * take every version the mirror has since learnt back to its own. The cost is a withdrawn
         * release, which the site states by naming an older one: that waits for the mirror, as it
         * did when the mirror was all there was.
         *
         * The beta is compared by its time because that is all a page says of it: there is no
         * `latestBetaRelease` to read. A newer beta therefore leaves the entry naming none, and the
         * detail record is what names it.
         */
        fun correct(modules: MutableMap<String, OnlineModule>, entry: OnlineModule): Boolean {
            val known = modules[entry.name]
            // ISO-8601 in UTC on both hosts, so the text orders as the times do.
            val newerBeta =
                entry.latestBetaReleaseTime.orEmpty() > known?.latestBetaReleaseTime.orEmpty()
            val newerRelease =
                entry.latestRelease != known?.latestRelease &&
                    entry.latestReleaseTime.orEmpty() >= known?.latestReleaseTime.orEmpty()
            if (known != null && !newerRelease && !newerBeta) return false
            modules[entry.name] =
                known?.copy(
                    description = entry.description ?: known.description,
                    stargazerCount = entry.stargazerCount ?: known.stargazerCount,
                    updatedAt = entry.updatedAt ?: known.updatedAt,
                    latestRelease = entry.latestRelease,
                    latestReleaseTime = entry.latestReleaseTime,
                    latestBetaRelease = known.latestBetaRelease.takeUnless { newerBeta },
                    latestBetaReleaseTime = entry.latestBetaReleaseTime,
                    // Both lists are the base's, and its newest is no longer the newest: left in
                    // place, a one-tap update would install the old APK under the new version.
                    releases = null,
                    betaReleases = null,
                ) ?: entry
            return true
        }

        /**
         * What is worth showing of a parsed catalogue.
         *
         * An entry is kept for having a release to install or, on a page entry, a version to name;
         * one with neither has nothing to say. Keying by package name upstream is what stops a host
         * serving the same package twice from crashing the Store's `LazyColumn`.
         */
        fun usable(parsed: Collection<OnlineModule>): List<OnlineModule> =
            parsed
                .asSequence()
                .filter { it.hide != true }
                .filter { !it.releases.isNullOrEmpty() || !it.latestRelease.isNullOrBlank() }
                .distinctBy { it.name }
                .toList()
    }
}

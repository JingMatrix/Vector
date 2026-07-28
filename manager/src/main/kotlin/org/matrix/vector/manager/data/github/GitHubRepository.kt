package org.matrix.vector.manager.data.github
import android.util.Log
import org.matrix.vector.manager.Constants

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Activity on the project's GitHub repository: the commits, the people behind them, the repository
 * counters, and the published builds.
 *
 * Offline-first: [load] falls back to whatever is on disk whenever the network fails, and the
 * caller renders it with a "showing cached" affordance rather than an error. A framework manager
 * must never be blocked by GitHub being unreachable.
 */
class GitHubRepository(
    private val client: OkHttpClient,
    cacheDir: File,
    /**
     * Supplies the optional sign-in token. Anonymous access is a fully supported mode — this only
     * ever raises the rate limit from 60 to 5000 requests an hour.
     */
    private val tokenProvider: () -> String? = { null },
    /** How far back to reach, in months. User-configurable; see SettingsRepository. */
    private val windowMonthsProvider: () -> Int = { DEFAULT_WINDOW_MONTHS },
) {

    private val snapshotFile = File(cacheDir, "github_feed.json")
    private val peopleFile = File(cacheDir, "github_people.json")

    /**
     * The stars, forks and licence, in a file of their own.
     *
     * Kept out of the feed snapshot, which is rewritten on every successful commit fetch: the repo
     * comes from a *separate* request that fails independently, so one rate-limited hour would
     * otherwise replace a perfectly good answer with nothing and every later launch would write the
     * nothing back. Here they can only be replaced by an actual answer to the question they came
     * from, because nothing else writes this file.
     *
     * They are also the slowest-changing thing on the screen — a star count from yesterday is not
     * wrong in any way a reader cares about — so serving a stale one indefinitely is the correct
     * behaviour, not a fallback.
     */
    private val repoFile = File(cacheDir, "github_repo.json")

    /**
     * The whole history, once it has been walked.
     *
     * Separate from the feed snapshot on purpose: the snapshot is one window's worth and is
     * replaced wholesale, while this only ever grows. See [CommitArchive] for why it is
     * append-only and keyed by SHA.
     */
    private val archive by lazy {
        CommitArchive(
            File(cacheDir, "github_history.ndjson"),
            File(cacheDir, "github_history_state.json"),
            json,
        )
    }

    private fun readRepo(): GhRepo? =
        runCatching { json.decodeFromString<GhRepo>(repoFile.readText()) }.getOrNull()

    private fun writeRepo(repo: GhRepo?) {
        if (repo == null) return
        runCatching { repoFile.writeText(json.encodeToString(repo)) }
    }

    /**
     * Names we have already tried to resolve to a GitHub account, and what came back.
     *
     * A null value is a remembered *404*: it is as worth keeping as a success, because the
     * alternative is asking about the same unresolvable name on every load. Only a 404 lands here;
     * see [resolvePerson] for why every other kind of failure is left out. Persisted so that it
     * survives the process, which parasitically is `com.android.shell` and is killed often.
     */
    private val resolvedPeople: MutableMap<String, ResolvedPerson?> by lazy {
        runCatching { json.decodeFromString<Map<String, ResolvedPerson?>>(peopleFile.readText()) }
            .getOrDefault(emptyMap())
            .toMutableMap()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * How hard to try for fresh data.
     *
     * Opening Home is not a reason to talk to GitHub. The window it shows moves a few times a
     * week at most, so revalidating on every launch spends the user's battery and their share of
     * an anonymous rate limit to redraw the same rows.
     */
    enum class Freshness {
        /** Disk only. Never touches the network. */
        Cached,
        /**
         * Serve from the HTTP cache while the answer is under [REVALIDATE_MINUTES] old, and
         * revalidate after that. A 304 costs nothing against the rate limit.
         */
        Revalidate,
        /** Ignore caches entirely. Only for an explicit pull-to-refresh. */
        Force,
    }

    suspend fun load(freshness: Freshness = Freshness.Revalidate): CommunityFeed =
        withContext(Dispatchers.IO) {
            // Zero means "as far back as there is", which is a different question from "how many
            // months". Either way this call fetches exactly one page — the newest hundred — and the
            // rest of history comes from [archive], which [backfill] fills in a few pages at a time
            // across sessions. The line under the feed says how far back the answer actually
            // reaches, rather than claiming the project began there.
            val months = windowMonthsProvider().coerceIn(0, 60)
            val windowStart =
                if (months == 0) 0L
                else System.currentTimeMillis() / 1000 - months * DAYS_PER_MONTH * 24L * 60 * 60
            val previous =
                runCatching { json.decodeFromString<Snapshot>(snapshotFile.readText()) }.getOrNull()
            val fetched = runCatching { fetch(windowStart, freshness) }
            val fresh = fetched.getOrNull()
            // Not on the Cached path: a cold OkHttp cache answers FORCE_CACHE with an
            // unsatisfiable 504, and every scroll to the foot of the feed would log.
            if (fresh == null && freshness != Freshness.Cached) {
                Log.w(
                    Constants.TAG,
                    "feed: github commit fetch failed ($freshness), falling back to disk",
                    fetched.exceptionOrNull(),
                )
            }
            if (fresh != null) {
                // Merged with what is already on disk rather than replacing it. The stars, forks
                // and licence come from a *second* request, and the two do not fail together — a
                // rate-limited hour can deliver the commits and not the repo — so a field this
                // fetch could not answer keeps the last answer that was.
                writeRepo(fresh.repo)
                // The head window is the mutable part of history — it can be amended or rebased —
                // so it is appended every time and the duplicates resolved on read.
                archive.append(fresh.rawCommits)
                val repo = fresh.repo ?: readRepo() ?: previous?.repo
                val total = if (fresh.totalCommits > 0) fresh.totalCommits else previous?.totalCommits ?: 0L
                runCatching {
                        snapshotFile.writeText(
                            json.encodeToString(Snapshot(total, fresh.rawCommits, repo))
                        )
                    }
                    .onFailure { e -> Log.w(Constants.TAG, "feed: snapshot write failed", e) }
                return@withContext build(
                    timeline(fresh.rawCommits, windowStart),
                    repo,
                    windowStart,
                    fromCache = false,
                    offline = false,
                    totalCommits = total,
                    freshness = freshness,
                )
            }
            val cached =
                runCatching { json.decodeFromString<Snapshot>(snapshotFile.readText()) }
                    // A file written before the total was stored still parses as a bare list, and
                    // is worth reading — it only costs the "you are here" line until the next fetch.
                    .recoverCatching {
                        Snapshot(
                            commits = json.decodeFromString<List<GhCommit>>(snapshotFile.readText())
                        )
                    }
                    // An empty snapshot rather than an early return with an empty *feed*. The
                    // snapshot is one window's worth of commits; the archive is every commit ever
                    // walked, and it lives in a different file — so giving up here because this
                    // file is missing or unreadable would throw away thousands of commits that are
                    // still on disk. Falling through costs nothing when there really is nothing:
                    // the merge below simply has no fallback to merge.
                    .getOrNull() ?: Snapshot()
            build(
                timeline(cached.commits, windowStart),
                // Its own file first; the feed snapshot's copy is the fallback behind it.
                readRepo() ?: cached.repo,
                windowStart,
                fromCache = true,
                offline = freshness != Freshness.Cached,
                // Read back rather than recomputed: it comes from the `Link` header of a request
                // this path did not make, and without it a cached read loses the commit numbering
                // that the "you are here" marker is built on.
                totalCommits = cached.totalCommits,
            )
        }

    /**
     * The commits the feed should render, from the archive where it reaches and [fallback] where it
     * does not.
     *
     * Both sources are used rather than one: the archive is authoritative — it is the only thing
     * that reaches past the newest hundred — but it lives in the cache directory and can be cleared
     * out from under us at any moment, in which case the page must still show what it just fetched
     * rather than nothing. Merging by SHA makes the overlap between them free.
     *
     * The window is applied here, at the end, so it is a view of the archive rather than a limit on
     * what is kept. Narrowing the window is then instant and costs no request, and widening it
     * later finds the history already on disk.
     */
    private fun timeline(fallback: List<GhCommit>, windowStart: Long): List<GhCommit> {
        val merged = LinkedHashMap<String, GhCommit>()
        fallback.forEach { merged[it.sha] = it }
        archive.read().forEach { merged[it.sha] = it }
        val all = merged.values.sortedByDescending { it.commit.author.date }
        // Learned from the whole archive, not from the window, so a co-author trailer in a recent
        // commit can be resolved by an attribution GitHub made three years ago.
        identities = identityIndex(all)
        return if (windowStart <= 0) all
        else all.filter { parseIso8601(it.commit.author.date) >= windowStart }
    }

    /**
     * Addresses and names that GitHub has already told us belong to an account.
     *
     * The problem this solves is co-author trailers. `Co-authored-by: Someone <someone@gmail.com>`
     * carries no account, and there is no API that turns an address into one — the users search
     * endpoint deliberately refuses to index email addresses, and answers `total_count: 0` for a
     * `@users.noreply.github.com` one no matter how it is phrased. So the address either resolves
     * from something already in hand or it does not resolve at all.
     *
     * Something already in hand is exactly what the archive is. Every commit carries both the git
     * identity that wrote it — name and email — and, when GitHub could match that identity to an
     * account, the account itself. Every such commit is therefore a verified email-to-login pair,
     * published by the only party in a position to know. Someone who co-authored one commit has
     * very often authored another; indexing the pairs makes the second commit answer the first.
     *
     * The cost is one pass over a list already in memory, and no request at all. Names are indexed
     * too, one tier weaker: a display name is not unique and is claimed first-wins, which is the
     * right trade for a credit line but would not be for anything that mattered more.
     */
    private fun identityIndex(commits: List<GhCommit>): Map<String, CommitPerson> {
        val index = HashMap<String, CommitPerson>()
        commits.forEach { c ->
            val user = c.author ?: return@forEach
            val person =
                CommitPerson(
                    login = user.login,
                    avatarUrl = user.avatarUrl,
                    profileUrl = user.htmlUrl,
                    isBot = user.type == "Bot" || user.login.endsWith("[bot]"),
                )
            c.commit.author.email.lowercase().takeIf { it.isNotEmpty() }?.let {
                index.putIfAbsent(it, person)
            }
            c.commit.author.name.lowercase().takeIf { it.isNotEmpty() }?.let {
                index.putIfAbsent(it, person)
            }
        }
        return index
    }

    /** Rebuilt on every load from the archive; empty until the first one. */
    @Volatile private var identities: Map<String, CommitPerson> = emptyMap()

    /**
     * Every commit on the default branch, ever, as GitHub last reported it.
     *
     * From the `Link: rel="last"` header of a one-per-page request — a number the walk can be
     * checked against. Holding that many unique commits *is* holding the history, which is a
     * better answer than "a page came back empty": it is known before the request that would have
     * proved it, so a finished archive stops asking rather than asking once more to be told no.
     */
    @Volatile private var knownTotalCommits: Long = 0

    /**
     * Walks the history backwards, a page at a time, and stops.
     *
     * ## The algorithm
     *
     * The cursor is the commit date of the oldest commit held, and each request asks for commits at
     * or before it — `until=`, not `page=`. Page numbers are the obvious choice and the wrong one:
     * they are relative to the tip, so a single new commit landing mid-walk shifts every boundary
     * and silently skips or repeats a page. A date is absolute. The cost of that choice is that
     * the boundary commit comes back again on the next request, which is harmless because the
     * archive is keyed by SHA.
     *
     * The *commit* date, not the author date, because that is what `until` filters on and the two
     * are not the same — about half of the newest hundred commits here differ, by as much as three
     * weeks. A cursor on the author date would ask for commits before a moment that had already
     * passed for some of them, and they would never be seen again.
     *
     * A date cursor has one failure mode, and this repository has it. Squashes and imports stamp
     * many commits with the same second — 100+ of these share `2023-02-26T08:48:49Z` — and a plateau
     * wider than one page is a wall the cursor cannot climb: every request returns the same hundred,
     * and the walk stops a fifth of the way through history believing it is done. So when a page
     * fails to move the cursor, the walk pages by number *within that timestamp* until it does.
     * Numbered paging is safe in exactly this position and nowhere else: the window is anchored by
     * an `until` in the past, so commits landing now fall outside it and cannot shift it.
     *
     * Completion is an *empty* page, and nothing weaker. "Nothing new in this page" is what the
     * plateau produces on every request, and "fewer than a hundred" is what a shared boundary
     * second produces legitimately; neither means the history has run out.
     *
     * ## Why it stops early
     *
     * An anonymous client gets sixty requests an hour and a few thousand commits is thirty of them.
     * So this fetches a handful of pages and returns, leaving the cursor on disk for the next call
     * — from the next launch, or from the reader scrolling towards the end of the list. A history
     * that assembles over a few sessions is fine; one that spends someone's entire rate limit in a
     * single launch, and then leaves the store empty for an hour, is not.
     *
     * Returns the number of commits genuinely new to the archive.
     */
    suspend fun backfill(maxPages: Int = 3): Int =
        withContext(Dispatchers.IO) {
            var state = archive.state()
            if (state.complete) return@withContext 0

            val known = archive.read()
            // The cheapest completion test there is, and the only one that does not cost a
            // request: the project has a known number of commits and this holds that many. It also
            // covers the case the page-walk cannot — an archive assembled across several sessions
            // whose last page happened to end exactly on the first commit, which otherwise stays
            // "incomplete" forever and re-asks on every visit to the foot of the feed.
            if (knownTotalCommits > 0 && known.size >= knownTotalCommits) {
                archive.writeState(state.copy(complete = true))
                return@withContext 0
            }
            val seen = known.mapTo(mutableSetOf()) { it.sha }
            var cursor =
                state.oldestSeenEpochSeconds.takeIf { it > 0 }
                    ?: known.minOfOrNull { cursorDateOf(it) }
                    ?: return@withContext 0
            var page = state.pageWithinCursor.coerceAtLeast(1)

            var added = 0
            repeat(maxPages) {
                val url = "$API/$REPO/commits?until=${iso8601(cursor)}&per_page=100&page=$page"
                val result =
                    runCatching {
                        get(url, Freshness.Revalidate)?.let {
                            json.decodeFromString<List<GhCommit>>(it)
                        }
                    }
                val batch = result.getOrNull()
                // A refused or failed request is not the end of history. Leaving `complete` false
                // means the next call tries again rather than declaring the archive finished
                // because GitHub was rate limiting at the time.
                if (batch == null) {
                    Log.w(
                        Constants.TAG,
                        "feed: history backfill $url unavailable",
                        result.exceptionOrNull(),
                    )
                    return@repeat
                }

                if (batch.isEmpty()) {
                    state = state.copy(complete = true)
                    archive.writeState(state)
                    archive.compactIfWasteful()
                    return@withContext added
                }

                val fresh = batch.filterNot { it.sha in seen }
                if (fresh.isNotEmpty()) {
                    archive.append(fresh)
                    fresh.forEach { seen += it.sha }
                    added += fresh.size
                }

                // Whether the cursor can move is decided by the whole page, not by the new part of
                // it: inside a plateau every commit is already known and the oldest date is
                // unchanged, which is exactly the case the page number exists to get past.
                val oldest = batch.minOf { cursorDateOf(it) }
                if (oldest < cursor) {
                    cursor = oldest
                    page = 1
                } else {
                    page++
                }
                state =
                    state.copy(
                        oldestSeenEpochSeconds = cursor,
                        pageWithinCursor = page,
                        pagesFetched = state.pagesFetched + 1,
                    )
                archive.writeState(state)
            }
            archive.compactIfWasteful()
            added
        }

    /** The date `until` understands: when the commit landed, falling back to when it was written. */
    private fun cursorDateOf(commit: GhCommit): Long =
        parseIso8601(commit.commit.committer?.date ?: commit.commit.author.date)

    /**
     * What the feed file holds.
     *
     * The total is stored beside the commits because it cannot be derived from them: it comes from
     * the `Link: rel="last"` header of the commit request, and a cached read makes no request.
     */
    @Serializable
    private data class Snapshot(
        val totalCommits: Long = 0L,
        val commits: List<GhCommit> = emptyList(),
        /**
         * The stars, forks and licence line.
         *
         * Stored for the same reason as the total: it comes from a second request, and a cached
         * read makes none — so without it the "take part" numbers would be missing on every launch
         * that deliberately does not fetch, which is most of them. `github_repo.json` owns the copy
         * that is preferred on read; this one stays as the fallback behind it.
         */
        val repo: GhRepo? = null,
    )

    private class Fetched(
        val rawCommits: List<GhCommit>,
        val repo: GhRepo?,
        val totalCommits: Long,
    )

    private fun fetch(windowStartEpochSeconds: Long, freshness: Freshness): Fetched {
        val since = iso8601(windowStartEpochSeconds)
        val commits =
            get("$API/$REPO/commits?since=$since&per_page=100", freshness)?.let {
                json.decodeFromString<List<GhCommit>>(it)
            } ?: throw IllegalStateException("commits unavailable")

        // The repo stats are a nice-to-have; a failure here must not lose the commits.
        val repo =
            runCatching { get("$API/$REPO", freshness)?.let { json.decodeFromString<GhRepo>(it) } }
                .getOrNull()

        val total = runCatching { fetchTotalCommits() }.getOrDefault(0L)
        return Fetched(commits, repo, total)
    }

    /**
     * How many commits the default branch has, ever.
     *
     * There is no field for this, but asking for one commit per page makes GitHub report the last
     * page number in its `Link` header, and that number is the count. One cheap request, and it
     * is what makes "you are N commits behind" exact rather than a guess.
     */
    private fun fetchTotalCommits(): Long {
        val request =
            Request.Builder()
                .url("$API/$REPO/commits?per_page=1")
                .header("Accept", "application/vnd.github+json")
                .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
                .build()
        client.newCall(request).execute().use { response ->
            val link = response.header("Link") ?: return 0L
            return LAST_PAGE.find(link)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        }
    }

    /**
     * A body, and the status that came with it.
     *
     * Almost every caller wants the payload and nothing else, which is what [get] is for.
     * [resolvePerson] is the exception: it has to tell "GitHub says there is no such account" apart
     * from "GitHub would not answer just now", and those two differ only in the code.
     */
    private class Answer(val code: Int, val body: String?)

    private fun get(url: String, freshness: Freshness): String? = getWithStatus(url, freshness).body

    private fun getWithStatus(url: String, freshness: Freshness): Answer {
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
                .apply {
                    // OkHttp replays the stored ETag as If-None-Match on its own. A 304 costs
                    // nothing against GitHub's 60/hour budget, so revalidation stays cheap.
                    cacheControl(
                        when (freshness) {
                            Freshness.Force -> CacheControl.FORCE_NETWORK
                            Freshness.Cached -> CacheControl.FORCE_CACHE
                            Freshness.Revalidate ->
                                CacheControl.Builder()
                                    .maxAge(REVALIDATE_MINUTES.toInt(), TimeUnit.MINUTES)
                                    .build()
                        }
                    )
                }
                .build()

        client.newCall(request).execute().use { response ->
            return Answer(
                response.code,
                if (response.isSuccessful) response.body.string() else null,
            )
        }
    }

    private fun build(
        raw: List<GhCommit>,
        repo: GhRepo?,
        windowStart: Long,
        fromCache: Boolean,
        offline: Boolean,
        totalCommits: Long,
        freshness: Freshness = Freshness.Cached,
    ): CommunityFeed {
        if (totalCommits > 0) knownTotalCommits = totalCommits
        // Read once: three of the answers below are about what is *held*, not what is shown.
        val archived = archive.read()
        val archiveOldest =
            archived.minOfOrNull { parseIso8601(it.commit.author.date) } ?: Long.MAX_VALUE
        val commits =
            raw.map { c ->
                    val subject = c.commit.message.lineSequence().first().trim()
                    val primary =
                        c.author?.let {
                            CommitPerson(
                                login = it.login,
                                avatarUrl = it.avatarUrl,
                                profileUrl = it.htmlUrl,
                                isBot = it.type == "Bot" || it.login.endsWith("[bot]"),
                            )
                        }
                        // GitHub links a commit to an account by email, and does not always
                        // manage it — a commit written under an address the account never
                        // verified arrives with no author at all. The trailer path already knows
                        // how to make something of a name and an address, so it is reused here
                        // rather than dropping the person to a bare string.
                        ?: person(c.commit.author.name, c.commit.author.email, freshness)

                    // Everyone credited, author first, deduplicated case-insensitively — the
                    // maintainer often appears in a trailer on their own commits.
                    val authors =
                        (listOf(primary) + coAuthors(c.commit.message, freshness)).distinctBy {
                            it.login.lowercase()
                        }

                    TimelineCommit(
                        sha = c.sha,
                        shortSha = c.sha.take(7),
                        subject = subject.removeSuffix(" (#${prNumber(subject) ?: ""})").trim(),
                        kind = CommitKind.of(subject),
                        pullRequest = prNumber(subject),
                        authors = authors,
                        epochSeconds = parseIso8601(c.commit.author.date),
                        htmlUrl = c.htmlUrl,
                        globalIndex = 0, // assigned after sorting, below

                        // Collaboration counts: a commit the maintainer landed with an outside
                        // co-author is a community contribution and is highlighted as one.
                        isCommunity =
                            authors.any {
                                !it.isBot && !it.login.equals(OWNER, ignoreCase = true)
                            },
                        isBot = primary.isBot,
                    )
                }
                .sortedByDescending { it.epochSeconds }
                // Newest-first, so the head of the list is the newest commit and its distance
                // from the repository root is the total count.
                .mapIndexed { index, commit -> commit.copy(globalIndex = totalCommits - index) }

        // Credit follows people, not commits: a co-author is a contributor. Bots are excluded —
        // automation is not a contributor.
        val contributors =
            commits
                .flatMap { commit -> commit.authors.map { person -> person to commit.epochSeconds } }
                .filterNot { (person, _) -> person.isBot }
                .groupBy { (person, _) -> person.login.lowercase() }
                .map { (_, entries) ->
                    val people = entries.map { it.first }
                    Contributor(
                        login = people.first().login,
                        avatarUrl = people.firstNotNullOfOrNull { it.avatarUrl },
                        profileUrl = people.firstNotNullOfOrNull { it.profileUrl },
                        commits = entries.size,
                        lastEpochSeconds = entries.maxOf { it.second },
                    )
                }
                // Ties break on recency, so among equals the person who contributed most
                // recently is shown first — the row is meant to move.
                .sortedWith(
                    compareByDescending<Contributor> { it.commits }
                        .thenByDescending { it.lastEpochSeconds }
                        .thenBy { it.login }
                )

        return CommunityFeed(
            commits = commits,
            contributors = contributors,
            // The oldest commit actually in hand, not the window that was asked for. They differ
            // whenever the window reaches further back than the history does — always, for an
            // unbounded window — and "since 1 January 1970" would be a strange thing to tell
            // someone. Saying how far the data really goes is both honest and more useful.
            windowStartEpochSeconds =
                commits.minOfOrNull { it.epochSeconds }?.coerceAtLeast(windowStart) ?: windowStart,
            repo = repo,
            totalCommits = totalCommits,
            fromCache = fromCache,
            offline = offline,
            loaded = true,
            // Whether *fetching* could add anything, which is not the same question as whether
            // the window is full.
            //
            // Judged against the oldest commit in the **archive**, never against the oldest one on
            // screen. The rendered list is already cut to the window, so its oldest entry is at or
            // after the window's start by construction; a test against that can never fail, and a
            // bounded window would offer "load earlier commits" forever, on a fetch that could only
            // return commits the window would throw away again.
            hasMoreHistory =
                !archive.state().complete &&
                    !(totalCommits > 0 && archived.size >= totalCommits) &&
                    (windowStart <= 0 || archiveOldest > windowStart),
            // The archive reaches past the start of a bounded window: everything the window can
            // ever show is already held, so the foot says so instead of inviting a fetch.
            windowCovered = windowStart > 0 && archiveOldest <= windowStart,
        )
    }

    /**
     * Parses `Co-authored-by: Name <email>` trailers.
     *
     * When the address is a GitHub noreply one the login is exactly the local part, and the
     * numeric prefix — `44231502+byemaxx@users.noreply.github.com` — is the user id, which yields
     * the real avatar. Otherwise [identityIndex] is asked whether this project has seen the address
     * attributed before, which resolves most of the rest for free. Only when all of that fails is
     * the person shown under the name they signed with, with a monogram rather than being dropped.
     */
    private fun coAuthors(message: String, freshness: Freshness): List<CommitPerson> =
        CO_AUTHOR.findAll(message)
            .map { match -> person(match.groupValues[1].trim(), match.groupValues[2].trim(), freshness) }
            .toList()

    /**
     * The best account we can make of a name and an email address.
     *
     * Four tiers, cheapest and most certain first: an address GitHub has already attributed
     * somewhere in the archive is simply that account; a `@users.noreply.github.com` address *is*
     * the account and needs nothing but parsing; a name seen attributed before costs nothing
     * either; a handle-shaped name is worth one lookup. Failing all four, the person is shown under
     * the name they signed with, uncredited but not dropped.
     */
    private fun person(name: String, email: String, freshness: Freshness): CommitPerson {
        // Before anything else, and before any request: an address this project has seen attributed
        // is settled, and no amount of guessing improves on GitHub's own answer.
        identities[email.lowercase()]?.let {
            return it
        }
        val noreply = GITHUB_NOREPLY.find(email)
        if (noreply != null) {
            val id = noreply.groupValues[1].takeIf { it.isNotEmpty() }
            val login = noreply.groupValues[2]
            return CommitPerson(
                login = login,
                avatarUrl =
                    if (id != null) "https://avatars.githubusercontent.com/u/$id?v=4"
                    else "https://github.com/$login.png",
                profileUrl = "https://github.com/$login",
                isBot = login.endsWith("[bot]"),
            )
        }
        identities[name.lowercase()]?.let {
            return it
        }
        return resolvePerson(name, freshness)?.let {
            CommitPerson(
                login = it.login,
                avatarUrl = it.avatarUrl,
                profileUrl = it.profileUrl,
                isBot = it.isBot,
            )
        } ?: CommitPerson(login = name, avatarUrl = null, profileUrl = null)
    }

    /**
     * The canary builds, newest first.
     *
     * These are **prereleases**, not Actions artifacts, and that is the whole point. GitHub gates an
     * artifact download behind an account even for a public repository — `actions/artifacts/<id>/zip`
     * answers 401 to an anonymous caller, while a release asset answers 206 — so sourcing canaries
     * from artifacts would mean asking every would-be tester for an OAuth grant to work around a
     * storage decision. CI attaches the same zips to a rolling `canary-<versionCode>` prerelease,
     * and this reads that, so nobody signs in to anything.
     *
     * Filtered to the canary tag rather than taking every prerelease: a hand-cut release candidate
     * is also a prerelease, and it is not a nightly.
     */
    suspend fun canaryBuilds(freshness: Freshness = Freshness.Revalidate): List<CanaryBuild> =
        withContext(Dispatchers.IO) {
            val body =
                get("$API/$REPO/releases?per_page=$CANARY_FETCH", freshness)
                    ?: return@withContext emptyList()

            runCatching { json.decodeFromString<List<GhRelease>>(body) }
                .onFailure { e -> Log.e(Constants.TAG, "update: canary release list unreadable", e) }
                .getOrDefault(emptyList())
                .filter { it.prerelease && it.tagName.startsWith(CANARY_TAG_PREFIX) }
                .take(CANARY_KEEP)
                .map { release ->
                    CanaryBuild(
                        id = release.id,
                        versionCode = release.versionCode() ?: 0,
                        title = release.name ?: release.tagName,
                        branch = release.tagName,
                        shortSha = release.targetCommitish.take(7),
                        epochSeconds = parseIso8601(release.publishedAt.orEmpty()),
                        htmlUrl = release.htmlUrl,
                        artifacts =
                            release.assets.map {
                                CanaryArtifact(
                                    id = it.id,
                                    name = it.name,
                                    sizeInBytes = it.size,
                                    expired = false,
                                    downloadUrl = it.downloadUrl,
                                )
                            },
                    )
                }
        }

    /**
     * Every published build, both channels, newest first.
     *
     * One fetch for both because they come from the same endpoint, and because deciding which
     * channel a reader is on needs to see both: a canary that has aged out of the rolling five is
     * still recognisable as a canary by being *newer than the newest stable release*, and that
     * comparison is impossible with only one of the two lists in hand.
     */
    suspend fun frameworkReleases(freshness: Freshness = Freshness.Revalidate):
        List<FrameworkRelease> =
        withContext(Dispatchers.IO) {
            val body =
                get("$API/$REPO/releases?per_page=$CANARY_FETCH", freshness)
                    ?: return@withContext emptyList()

            runCatching { json.decodeFromString<List<GhRelease>>(body) }
                .onFailure { e -> Log.e(Constants.TAG, "update: release list unreadable", e) }
                .getOrDefault(emptyList())
                .mapNotNull { release ->
                    val canary = release.prerelease && release.tagName.startsWith(CANARY_TAG_PREFIX)
                    FrameworkRelease(
                        tag = release.tagName,
                        title = release.name ?: release.tagName,
                        versionCode = release.versionCode() ?: return@mapNotNull null,
                        isCanary = canary,
                        notesMarkdown = release.body,
                        htmlUrl = release.htmlUrl,
                        epochSeconds = parseIso8601(release.publishedAt.orEmpty()),
                        // A branch name is not a build. Only a SHA identifies one.
                        commit =
                            release.targetCommitish.takeIf { c ->
                                c.length >= 7 && c.all { it.isDigit() || it in 'a'..'f' }
                            },
                        zips =
                            release.assets
                                .filter { it.name.endsWith(".zip", ignoreCase = true) }
                                .map {
                                    CanaryArtifact(
                                        id = it.id,
                                        name = it.name,
                                        sizeInBytes = it.size,
                                        expired = false,
                                        downloadUrl = it.downloadUrl,
                                    )
                                },
                    )
                }
                .sortedByDescending { it.versionCode }
        }

    /**
     * The build number a release represents, or null when it is not comparable with ours.
     *
     * `canary-3049` states it outright. A stable tag does not, so it is read out of the zip's file
     * name — the CI names them with the same version code — and a release whose number cannot be
     * established at all is dropped rather than compared as zero, which would have made every
     * stable release look older than every canary.
     *
     * **Only releases of this product count, and that is not pedantry.** The version code restarted
     * when LSPosed became Vector: this repository's own release list holds `LSPosed-v1.11.0-7209`
     * beside `Vector-v2.0-3021`, so a plain numeric comparison makes the *older* project look four
     * thousand builds newer, and the manager would offer LSPosed 1.11.0 to a Vector device as an
     * update — a cross-product downgrade, flashed with root. Matching the [ZIP_PREFIX] asset prefix
     * is what keeps the comparison inside one numbering scheme.
     */
    private fun GhRelease.versionCode(): Long? {
        val ours = assets.filter { it.name.startsWith(ZIP_PREFIX, ignoreCase = true) }
        if (ours.isEmpty()) return null
        if (tagName.startsWith(CANARY_TAG_PREFIX)) {
            return tagName.removePrefix(CANARY_TAG_PREFIX).toLongOrNull()
        }
        return ours.firstNotNullOfOrNull { asset ->
            Regex("(\\d{3,})").findAll(asset.name).map { it.value }.lastOrNull()?.toLongOrNull()
        }
    }

    @Serializable
    private data class ResolvedPerson(
        val login: String,
        val avatarUrl: String?,
        val profileUrl: String?,
        val isBot: Boolean,
    )

    /**
     * Turns a name signed in a trailer into a GitHub account, when it can be done safely.
     *
     * GitHub's own web UI resolves these by matching the commit *email* to an account, which it can
     * do because it holds every address a user has ever verified. We cannot: the address is usually
     * private, and `search/users?q=…+in:email` finds nothing for it — checked against
     * `krc440002@gmail.com`, which the web UI resolves and the search API returns zero results for.
     *
     * What is left is asking whether an account exists under that name, and that is only safe for
     * names that are plainly *handles*. `GET /users/Qing` answers 200 with a real account — id
     * 158244, an unrelated person — so probing every display name would eventually attach a
     * stranger's face and profile to someone else's contribution. [HANDLE_SHAPED] keeps the shape
     * of a handle (`frknkrc44`) and rejects the shape of a name (`Qing`, `Furkan Karcıoğlu`).
     *
     * The cost of the guard is that a handle made only of letters stays unresolved. That is the
     * right way to be wrong: an unlinked contributor is merely uncredited, a mislinked one is
     * credited to the wrong person.
     */
    private fun resolvePerson(name: String, freshness: Freshness): ResolvedPerson? {
        val key = name.lowercase()
        if (resolvedPeople.containsKey(key)) return resolvedPeople[key]
        if (!HANDLE_SHAPED.matches(name)) return null
        // A cache-only load must not decide that a name is unresolvable: the request would be
        // served FORCE_CACHE, miss, and the miss would be written down permanently — so the first
        // launch after install, which reads the feed from disk, would poison every name before the
        // network was ever asked.
        if (freshness == Freshness.Cached) return null

        val answer = runCatching { getWithStatus("$API_ROOT/users/$name", freshness) }.getOrNull()
        val found =
            runCatching {
                    answer?.body?.let {
                        val user = json.decodeFromString<GhUser>(it)
                        ResolvedPerson(
                            login = user.login,
                            avatarUrl = user.avatarUrl,
                            profileUrl = user.htmlUrl,
                            isBot = user.type == "Bot" || user.login.endsWith("[bot]"),
                        )
                    }
                }
                .getOrNull()

        // Only a 404 says anything about the person. A rate-limited 403, a 5xx or a dropped
        // connection says something about the moment, and this map is persisted — writing one of
        // those down as "no such account" would leave a contributor uncredited on every later
        // launch. Anything that is not a plain "not found" leaves the key absent, and the next load
        // asks again.
        if (found == null && answer?.code != HTTP_NOT_FOUND) return null

        resolvedPeople[key] = found
        runCatching { peopleFile.writeText(json.encodeToString(resolvedPeople.toMap())) }
        return found
    }

    private fun prNumber(subject: String): Int? =
        PR_SUFFIX.find(subject)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun iso8601(epochSeconds: Long): String {
        val cal =
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = epochSeconds * 1000
            }
        return String.format(
            java.util.Locale.ROOT,
            "%04d-%02d-%02dT%02d:%02d:%02dZ",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    private fun parseIso8601(value: String): Long =
        runCatching {
                val f =
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                (f.parse(value)?.time ?: 0L) / 1000
            }
            .getOrDefault(0L)

    companion object {
        const val OWNER = "JingMatrix"
        const val REPO = "$OWNER/Vector"
        const val REPO_URL = "https://github.com/$REPO"
        const val ISSUES_URL = "$REPO_URL/issues"
        const val PULLS_URL = "$REPO_URL/pulls"
        const val DISCUSSIONS_URL = "$REPO_URL/discussions"
        const val GOOD_FIRST_ISSUE_URL = "$REPO_URL/issues?q=is%3Aopen+label%3A%22good+first+issue%22"

        /**
         * The workflow's own run list, filtered to master.
         *
         * The way out of the app for anyone who wants a build log, or a commit older than the five
         * canaries CI keeps published. [canaryBuilds] itself reads prereleases, not this page.
         */
        /**
         * The Actions page, filtered the way the project README's build badge filters it.
         *
         * `event:push` and `is:completed` as well as the branch: a run started by hand, or one
         * still going, is not a build anyone should be told to fetch, and the badge is the link
         * that already draws that line.
         */
        const val CANARY_URL =
            "$REPO_URL/actions/workflows/core.yml" +
                "?query=event%3Apush+branch%3Amaster+is%3Acompleted"
        private const val CANARY_TAG_PREFIX = "canary-"

        /**
         * What this product's own release zips are called.
         *
         * The release list still carries the pre-rename LSPosed builds, whose version codes are
         * from a different and higher numbering; see `versionCode()`.
         */
        private const val ZIP_PREFIX = "Vector-"

        /** CI keeps five; a few extra are fetched so a stable release among them costs nothing. */
        private const val CANARY_FETCH = 12
        private const val CANARY_KEEP = 5

        private const val API = "https://api.github.com/repos"
        private const val API_ROOT = "https://api.github.com"

        /** The only status that is an answer about a person rather than about the hour. */
        private const val HTTP_NOT_FOUND = 404

        /**
         * A name shaped like a handle rather than a display name.
         *
         * A digit, a hyphen or an underscore somewhere in it, no spaces, no accented letters, and
         * within GitHub's 39-character limit. See [resolvePerson] for why the bar is deliberately
         * this high.
         */
        private val HANDLE_SHAPED =
            Regex("^(?=.*[0-9_-])[A-Za-z0-9](?:[A-Za-z0-9_]|-(?=[A-Za-z0-9_])){0,38}$")

        /**
         * Six months: long enough that a quiet stretch does not read as a dead project, short
         * enough that the people row is still a scoreboard rather than a monument. At this
         * project's rate that is ~44 commits, which is one unpaginated request. Overridable in
         * settings.
         */
        const val DEFAULT_WINDOW_MONTHS = 6

        /** The window is a rough reach backwards, not a calendar, so a flat month will do. */
        private const val DAYS_PER_MONTH = 30L

        /** How long a fetched answer is served without asking GitHub anything; see [Freshness]. */
        private const val REVALIDATE_MINUTES = 30L

        private val PR_SUFFIX = Regex("""\(#(\d+)\)\s*$""")

        private val LAST_PAGE = Regex("""[?&]page=(\d+)>;\s*rel="last"""")

        private val CO_AUTHOR =
            Regex("""(?im)^\s*Co-authored-by:\s*(.+?)\s*<([^>]+)>\s*$""")

        private val GITHUB_NOREPLY =
            Regex("""^(?:(\d+)\+)?([^@]+)@users\.noreply\.github\.com$""", RegexOption.IGNORE_CASE)
    }
}

package org.matrix.vector.manager.data.github

import java.util.Calendar
import java.util.Locale
import kotlin.math.sqrt

/**
 * What the rail draws, in order.
 *
 * The rail is deliberately **not** a branch graph. This project squash-merges, so its history is
 * linear by construction — there were zero merge commits in the last 44 — and drawing lanes would
 * be inventing structure that does not exist. What the data *does* carry, and a uniform list
 * throws away, is time and the reader's own position in it. That is what these items encode.
 */
sealed interface FeedItem {

    /** A commit. The rail spans its full height; elapsed time is carried by [Gap] below it. */
    data class Commit(
        val commit: TimelineCommit,
        val isFirst: Boolean,
        val isLast: Boolean,
    ) : FeedItem

    /**
     * The elapsed time between two commits, as rail.
     *
     * A separate row rather than a trailing segment inside the commit row: a commit row's height is
     * set by its text, so a segment drawn inside it stops short of the next node and the rail
     * breaks visibly between same-day commits.
     */
    data class Gap(val days: Int, val afterSha: String) : FeedItem

    /**
     * The line between what the reader is running and what they are not.
     *
     * `versionCode` is `git rev-list --count`, so a commit's distance from HEAD is exactly its
     * version number — no guessing and no extra endpoint. Everything above this line is what an
     * update would actually bring, named commit by commit, which is the question a framework
     * user actually has.
     */
    data class InstalledMarker(
        val versionCode: Long,
        val commitsAhead: Int,
        /**
         * True when the installed build is *past* the head of master.
         *
         * That cannot happen to anyone running a published build, so it means the framework was
         * built locally or from another branch. Worth saying: the feed below is then not the
         * history of what is installed, and neither an issue report nor a bisect against it means
         * what the reader would assume.
         */
        val aheadOfMaster: Boolean = false,
    ) : FeedItem

    /**
     * Where the rail crosses into an earlier month, with what that month amounted to.
     *
     * A bare month name is just a scroll landmark. With the month's own totals it becomes the
     * summary layer of the timeline: you can read the project's shape by skimming the separators
     * without reading a single commit.
     */
    data class MonthMarker(
        /** Stable across languages: a grouping key and a list key, never shown. */
        val key: String,
        /** `Calendar.MONTH`, named at draw time in whatever language the reader chose. */
        val month: Int,
        /** Null in the current year, where the year would be noise. */
        val year: Int?,
        val commits: Int,
        val people: Int,
    ) : FeedItem

    /**
     * Every bot commit in the window, folded into one row at the foot of the rail.
     *
     * Gathered out of the timeline rather than left in place: dependency bumps arrive in bursts and
     * would otherwise be most of what the rail shows, pushing the human commits — which are what
     * the reader came for — off the screen.
     */
    data class Bots(val count: Int, val commits: List<TimelineCommit>) : FeedItem
}

object FeedLayout {

    /** Below this a gap is just normal cadence and gets no label. */
    const val QUIET_THRESHOLD_DAYS = 14

    fun build(feed: CommunityFeed, installedVersionCode: Long): List<FeedItem> {
        val visible = feed.commits.filterNot { it.isBot }
        if (visible.isEmpty()) return emptyList()

        val bots = feed.commits.filter { it.isBot }
        val items = ArrayList<FeedItem>(visible.size * 2 + 8)

        // Each commit's month, worked out once, and its totals accumulated in a second sweep.
        //
        // The alternative — re-scanning every commit at each month boundary to total it — is
        // O(commits × months) with a fresh Calendar per comparison. That is cheap on a six-month
        // window and thousands of allocations on the full archive, which is the case that has to
        // stay fast.
        val months = ArrayList<String>(visible.size)
        val calendar = Calendar.getInstance()
        visible.forEach { commit ->
            calendar.timeInMillis = commit.epochSeconds * 1000
            months += monthKey(calendar)
        }
        val commitsPerMonth = HashMap<String, Int>()
        val peoplePerMonth = HashMap<String, MutableSet<String>>()
        visible.forEachIndexed { index, commit ->
            val key = months[index]
            commitsPerMonth[key] = (commitsPerMonth[key] ?: 0) + 1
            val people = peoplePerMonth.getOrPut(key) { HashSet() }
            commit.authors.forEach { if (!it.isBot) people += it.login.lowercase() }
        }
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)

        // Only meaningful once both numbers are known, and only when the reader is actually
        // behind — telling someone who is up to date that they are up to date is noise.
        val commitsAhead =
            if (feed.totalCommits > 0 && installedVersionCode > 0) {
                (feed.totalCommits - installedVersionCode).toInt().coerceAtLeast(0)
            } else 0
        // Past the head of master: place it at the top rather than looking for a commit it could
        // sit above, because there is not one.
        val aheadOfMaster = feed.totalCommits > 0 && installedVersionCode > feed.totalCommits
        if (aheadOfMaster) {
            items +=
                FeedItem.InstalledMarker(
                    versionCode = installedVersionCode,
                    commitsAhead = (installedVersionCode - feed.totalCommits).toInt(),
                    aheadOfMaster = true,
                )
        }
        var markerPlaced = aheadOfMaster || commitsAhead <= 0

        var lastMonth: String? = null

        visible.forEachIndexed { index, commit ->
            if (!markerPlaced && commit.globalIndex <= installedVersionCode) {
                items += FeedItem.InstalledMarker(installedVersionCode, commitsAhead)
                markerPlaced = true
            }

            val month = months[index]
            if (month != lastMonth) {
                calendar.timeInMillis = commit.epochSeconds * 1000
                val year = calendar.get(Calendar.YEAR)
                items +=
                    FeedItem.MonthMarker(
                        key = month,
                        month = calendar.get(Calendar.MONTH),
                        year = year.takeIf { it != thisYear },
                        commits = commitsPerMonth[month] ?: 0,
                        people = peoplePerMonth[month]?.size ?: 0,
                    )
                lastMonth = month
            }

            val older = visible.getOrNull(index + 1)
            items +=
                FeedItem.Commit(
                    commit = commit,
                    isFirst = index == 0,
                    isLast = older == null && bots.isEmpty(),
                )

            if (older != null) {
                val gapDays =
                    ((commit.epochSeconds - older.epochSeconds) / 86_400L).toInt().coerceAtLeast(0)
                items += FeedItem.Gap(gapDays, commit.sha)
            }
        }

        if (bots.isNotEmpty()) items += FeedItem.Bots(bots.size, bots)
        return items
    }

    /**
     * Gap in days to rail height.
     *
     * Square root rather than linear. Linear is the honest chart, but this project's gaps span
     * 0 to 76 days, so a literal scale spends a full screen of empty rail on one silence and
     * flattens every ordinary one-to-three-day gap into the same nothing. The root keeps short
     * gaps distinguishable, still shows a long one as visibly long, and the clamp stops any
     * single quiet stretch from dominating the scroll.
     */
    fun railHeightDp(gapDays: Int): Float =
        (MIN_GAP_DP + sqrt(gapDays.toFloat()) * SCALE).coerceAtMost(MAX_GAP_DP)

    const val MIN_GAP_DP = 8f
    const val MAX_GAP_DP = 120f
    private const val SCALE = 13f

    /**
     * The grouping key, deliberately language-independent.
     *
     * A displayed month name cannot be built here. `Locale.getDefault()` is the process default,
     * which parasitically belongs to the host app rather than to the manager, and the app's own
     * in-composition language override is not visible from the model at all. So the model groups by
     * an invariant key and the screen names the month at draw time.
     */
    private fun monthKey(calendar: Calendar): String =
        "%d-%02d".format(Locale.ROOT, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH))
}

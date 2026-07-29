package org.matrix.vector.manager.data.github

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.matrix.vector.manager.logW

/**
 * Every commit the app has ever seen, kept on disk.
 *
 * `/commits` returns at most a hundred commits per request and no date window widens that, so
 * "from the start of the project" can only be answered by walking the history backwards a page at
 * a time and remembering what came back. This is where it is remembered.
 *
 * ## Why an append-only file
 *
 * A commit that is not at the tip never changes. Its message, its author and its date are fixed the
 * moment it is buried under another commit, so the overwhelming majority of this archive is
 * immutable and rewriting it would be pure waste. New pages are *appended*, one JSON object per
 * line, which costs the length of the chunk rather than the length of the history — the difference
 * between writing 20 KB and rewriting 600 KB, every time, on a phone.
 *
 * The head is the exception. The newest commits can still be amended, rebased or force-pushed away,
 * so the refresh rewrites that window on every run. Appending a second copy of a commit is
 * therefore normal rather than a bug, and [read] resolves it: later lines win, so the freshest
 * record of any SHA is the one that survives. The file is compacted when the duplicates outgrow the
 * real content, which for a normal refresh rhythm is rarely.
 *
 * ## Why SHA is the key
 *
 * It is the only identifier git guarantees. Dates collide — several commits share a second, and the
 * page boundary is *chosen* by date, so the same commit legitimately arrives twice — and positions
 * shift as new work lands. Keying on the SHA makes an overlapping page harmless, which in turn lets
 * the backfill cursor be a date rather than a page number. That matters: page numbers are
 * invalidated by every new commit, dates are not.
 */
class CommitArchive(private val file: File, private val stateFile: File, private val json: Json) {

    /**
     * Where the backwards walk has reached.
     *
     * [oldestSeenEpochSeconds] is the cursor — the next request asks for commits at or before it —
     * and [complete] records that a request came back empty, which is the only signal that the
     * history has genuinely run out. All of it is persisted because the walk is spread across
     * sessions: an anonymous client gets sixty requests an hour, and a few thousand commits is
     * thirty of them, so finishing in one sitting is neither possible nor polite.
     *
     * [pageWithinCursor] is what makes the walk correct on a repository that has been squashed or
     * imported. This one has 100+ commits stamped with the same second — `2023-02-26T08:48:49Z` —
     * and a cursor alone cannot get past them: asking for commits at or before that second returns
     * the same hundred every time, and the walk stalls a fifth of the way through history while
     * looking exactly like success. Inside such a plateau the walk pages by number instead, which
     * is safe here in a way it is not in general: the page window is anchored by `until` at a
     * moment in the past, so new commits land outside it and cannot shift it.
     */
    @Serializable
    data class State(
        val oldestSeenEpochSeconds: Long = 0,
        val complete: Boolean = false,
        /** Purely for the log line that explains a slow or stalled backfill. */
        val pagesFetched: Int = 0,
        /** Which page of the current cursor's timestamp to ask for next; 1 unless in a plateau. */
        val pageWithinCursor: Int = 1,
        /**
         * The walk that wrote this file.
         *
         * A cursor left behind by a superseded walk is worse than no cursor at all: a walk that
         * stalls records `complete: true`, and honouring that would mean never looking again.
         * Anything not written by the current walk is re-walked from the top.
         */
        val algorithm: Int = 0,
    )

    companion object {
        /** Bump when the walk changes in a way that invalidates a saved cursor. */
        const val ALGORITHM = 1
    }

    fun state(): State =
        runCatching { json.decodeFromString<State>(stateFile.readText()) }
            .getOrDefault(State())
            .let { if (it.algorithm == ALGORITHM) it else State(algorithm = ALGORITHM) }

    fun writeState(state: State) {
        runCatching { stateFile.writeText(json.encodeToString(state.copy(algorithm = ALGORITHM))) }
    }

    /**
     * Everything held, newest first, one record per SHA.
     *
     * Later lines win, which is what makes appending a rewritten head window correct rather than
     * corrupting: the newest copy of a commit is the one that ends up furthest down the file.
     */
    fun read(): List<GhCommit> {
        parsed?.let {
            return it
        }
        return parse().also { parsed = it }
    }

    /**
     * The parsed file, held for as long as it is unchanged.
     *
     * One load reads this twice — once to lay out the feed, once to know what the backfill already
     * has — and a backfill reads it again after appending. At three thousand commits that is three
     * passes over megabytes of JSON per visit to the foot of the list, all of it to reproduce a
     * list that has not changed. Every writer here invalidates it, so there is no way to hold a
     * stale copy.
     */
    @Volatile private var parsed: List<GhCommit>? = null

    private fun parse(): List<GhCommit> {
        if (!file.isFile) return emptyList()
        val byShaLatestWins = LinkedHashMap<String, GhCommit>()
        var total = 0
        var skipped = 0
        var firstFailure: Throwable? = null
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                total++
                runCatching { json.decodeFromString<GhCommit>(line) }
                    .onFailure { e ->
                        skipped++
                        if (firstFailure == null) firstFailure = e
                    }
                    .getOrNull()
                    // A truncated final line — a process killed mid-append — costs that one commit
                    // and nothing else. It is why this is a line format and not one JSON document.
                    ?.let { byShaLatestWins[it.sha] = it }
            }
        }
        lineCount = total
        // Ordered by the author date, which is the date the feed prints beside every row. It is
        // deliberately not the committer date the backfill walks on: that cursor is a minimum over
        // a whole page, never the end of this list, so the two orders never have to agree.
        val unique = byShaLatestWins.values.sortedByDescending { it.commit.author.date }
        // Exactly one bad line is the truncated tail above and is not worth saying anything about;
        // more than one is systematic — a renamed field would make the whole archive read as empty.
        if (skipped > 1) {
            logW("feed: skipped $skipped of $total archive lines", firstFailure)
            // Repaired here, where it is found, rather than left to [compactIfWasteful]: that runs
            // only during a backfill, which happens only if someone scrolls to the foot of
            // history, so damage would otherwise be re-skipped on every launch instead of being
            // cleared once. Rewriting what parsed is the whole repair — the damage is unreadable
            // text between two records and there is nothing in it to recover — and it happens
            // once, because the next parse finds nothing to skip.
            rewrite(unique)
        }
        return unique
    }

    /**
     * Appends a chunk. Duplicates are expected and are resolved on read, not here.
     *
     * Serialised against every other writer, and it has to be. `appendText` opens its own stream
     * per call and a hundred commits is far more than one buffer, so two overlapping appends
     * interleave at a buffer boundary rather than one following the other, splicing a commit
     * message into the middle of the next record. Each such tear costs two commits and is
     * permanent. Overlap is reachable: `ServiceLocator.prefetch` launches `load()`, which appends
     * the head window, while the home screen can be running `backfill()`.
     */
    fun append(commits: List<GhCommit>) {
        if (commits.isEmpty()) return
        synchronized(writeLock) {
            parsed = null
            runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(
                        commits.joinToString("\n", postfix = "\n") { json.encodeToString(it) }
                    )
                }
                .onFailure { e ->
                    logW("feed: appending ${commits.size} commits to the archive failed", e)
                }
        }
    }

    /**
     * Rewrites the file with one line per commit, dropping the superseded copies.
     *
     * Only worth doing when the duplicates have grown past the content itself, which takes a great
     * many refreshes — the head window is a hundred commits and a full history is thousands.
     */
    fun compactIfWasteful() {
        if (!file.isFile) return
        synchronized(writeLock) {
            // read() first: it is memoised, and parsing is what sets [lineCount]. Counting the
            // lines any other way means a second pass over megabytes of JSON to answer a question
            // the parse has already answered.
            val unique = read()
            if (lineCount <= unique.size * 2) return
            rewrite(unique)
        }
    }

    /**
     * Replaces the file with exactly [unique], one record per line.
     *
     * Through a temporary and a rename, so a reader either sees the whole old file or the whole
     * new one and never a half-written replacement. Shared by compaction and by repair because
     * they are the same operation: both write back only what could be read.
     */
    private fun rewrite(unique: List<GhCommit>) {
        synchronized(writeLock) {
            runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(
                        unique.joinToString("\n", postfix = "\n") { json.encodeToString(it) }
                    )
                    tmp.renameTo(file)
                    parsed = unique
                    lineCount = unique.size
                }
                .onFailure { e ->
                    logW("feed: rewriting the archive failed", e)
                }
        }
    }

    /** Lines the last parse walked, so compaction need not read the file again to count them. */
    @Volatile private var lineCount = 0

    /**
     * Taken by everything that writes the file.
     *
     * Readers do not take it. A reader that catches a half-written final line loses that one
     * record and says so, which is the behaviour a line format is chosen for; a *writer* that
     * catches another writer loses records in the middle of the file, permanently.
     */
    private val writeLock = Any()
}

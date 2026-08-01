package org.matrix.vector.manager.data.log

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.yield

/**
 * A random-access window onto one of the daemon's log files.
 *
 * A single log file is capped today: `logcat.cpp` rotates at 4 MB per part. That cap belongs to
 * the daemon build that happens to be running, though, and the manager cannot inspect the
 * provenance of the descriptor it was handed before it reads from it. **A reader whose peak memory
 * scales with file size is wrong regardless of today's number** — reading a part into `String`s
 * would be megabytes of churn per refresh, inside a process whose heap belongs to
 * `com.android.shell`.
 *
 * So nothing here scales with the file:
 * - [index] never allocates a `String`. It scans bytes for `'\n'` and records line offsets into a
 *   `LongArray` — 8 bytes per line, ~240 KB for a full part, and capped at [MAX_INDEXED_LINES].
 * - [readRows] materialises at most a window's worth of lines, reading them in one seek per
 *   256 KB block. Peak heap is a function of the window size alone.
 * - [scan] streams the whole file to build a filter, but only ever holds one block plus the
 *   matching *offsets*.
 *
 * The descriptor is real and seekable: `ManagerService.getVerboseLog()` opens
 * `/proc/self/fd/N`, which resolves the procfs symlink back to the log inode, so positional reads
 * work and this class exploits them rather than streaming forward.
 *
 * Ownership is exactly one object. [ParcelFileDescriptor.AutoCloseInputStream] adopts the
 * descriptor and closes it once, in [close]. Wrapping the raw `pfd.fileDescriptor` in a
 * `FileInputStream` *and* closing the `ParcelFileDescriptor` separately closes the same fd number
 * twice, and between the two closes the runtime is free to hand that number to an OkHttp socket or
 * a Coil bitmap, which the second close then silently detaches.
 */
class LogFile(pfd: ParcelFileDescriptor) : Closeable {

    private val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
    private val channel: FileChannel = stream.channel
    private val block = ByteArray(READ_BLOCK)
    private val oneByte = ByteBuffer.allocate(1)

    /**
     * Pass one: where every line starts.
     *
     * One sequential read of the page cache with a tight byte loop and no decoding at all. The
     * size is captured once and everything past it is ignored, so a line the daemon is appending
     * while this runs is never half-decoded — it simply appears on the next refresh.
     */
    suspend fun index(): LogIndex {
        val size = channel.size()
        val starts = LongVec()
        starts.add(0L)
        var dropped = 0
        var pos = 0L

        while (pos < size) {
            val want = min(READ_BLOCK.toLong(), size - pos).toInt()
            val read = readAt(pos, want)
            if (read <= 0) break
            for (i in 0 until read) {
                if (block[i] == NEWLINE) starts.add(pos + i + 1)
            }
            pos += read

            // A file long enough to blow the offset table is a file nobody is going to read from
            // the top anyway, so the leading offsets are dropped and the header says so. Silently
            // showing a truncated file as if it were whole is the one thing not allowed.
            if (starts.size > MAX_INDEXED_LINES + DROP_BLOCK) {
                starts.dropFirst(DROP_BLOCK)
                dropped += DROP_BLOCK
            }
            yield()
        }

        // The array doubles as its own end sentinel: line k spans [bounds[k], bounds[k + 1]).
        if (starts.last() != size) starts.add(size)
        return LogIndex(starts.toArray(), dropped)
    }

    /**
     * Pass two: turn a selection of lines into rows.
     *
     * [lines] is ascending and absolute. The unfiltered case passes a contiguous run, so the read
     * covers exactly the bytes needed; a filtered case passes a sparse selection, and the block
     * loop below reads through the gaps and discards them rather than issuing one seek per line.
     * Either way the bytes held at once are bounded by [READ_BLOCK].
     */
    suspend fun readRows(index: LogIndex, lines: IntArray): List<LogRow> {
        val rows = ArrayList<LogRow>(lines.size + 8)
        var lastDate: String? = null
        var traceOwner = -1
        var continuation: ArrayList<String>? = null
        // Set when a line folded into the entry above had been cut. The flag belongs on the row a
        // reader can see, and after a join the cut line may no longer be one of them.
        var continuationCut = false

        fun flushContinuation() {
            val lines = continuation
            if (traceOwner >= 0 && lines != null) {
                val owner = rows[traceOwner] as LogRow.Entry
                rows[traceOwner] =
                    owner.copy(continuation = lines, truncated = owner.truncated || continuationCut)
            }
            continuation = null
            continuationCut = false
            traceOwner = -1
        }

        fun addContinuation(text: String, cut: Boolean) {
            (continuation ?: ArrayList<String>(8).also { continuation = it }).add(text)
            if (cut) continuationCut = true
        }

        forEachLine(index, lines, null) { lineIndex, text, truncated ->
            // Parsed first, always. A multi-line message reaches the file as one writev, so its
            // continuation lines carry no prefix — but *carrying* one is what makes a line an entry
            // of its own, and asking only whether a line could be a continuation swallows the whole
            // log into whichever entry happens to be first.
            val row = parseLogLine(lineIndex, text, truncated)
            val owner = if (traceOwner >= 0) rows[traceOwner] as LogRow.Entry else null
            if (owner != null && row !is LogRow.Entry && isContinuationLine(text)) {
                addContinuation(text, truncated)
            } else if (owner != null && row is LogRow.Entry && isSplitChunk(owner, row)) {
                // A tail the writer was forced to cut off. Its prefix is dropped and its message
                // rejoins the entry above, which is where it was written; the lines after it need
                // no special case, because [traceOwner] never moved.
                addContinuation(row.message, truncated)
            } else {
                flushContinuation()
                when (row) {
                    is LogRow.Entry -> {
                        if (row.date != lastDate) {
                            lastDate = row.date
                            rows.add(LogRow.DayBreak(lineIndex, row.date))
                        }
                        rows.add(row)
                        traceOwner = rows.size - 1
                    }
                    else -> rows.add(row)
                }
            }
        }
        flushContinuation()
        return rows
    }

    /**
     * Walks back from [line] to the entry that owns it.
     *
     * Without this a window boundary landing inside a multi-line message opens the page on its tail
     * with nothing to attach it to. It stops on the first line that is an entry — the owner — or on
     * one of the daemon's raw banners, which own nothing.
     *
     * Indented lines, which are most of them, still cost one byte: nothing else in the format
     * begins with a space or a tab, so they are continuations without being read. Anything else
     * costs a capped read of the line's head, which is what the general rule needs — a continuation
     * is no longer recognisable from its first character, and pretending otherwise is what left the
     * `Caused by:` and the `--- Parsed Mount Argument ---` lines stranded.
     *
     * A line that is *neither* is also where the walk has to stop: it is a marker the scanner could
     * not read, and stepping over it would hand the window a start belonging to some earlier
     * entry.
     *
     * An entry that [looksLikeSplitChunk] is stepped over too, since [readRows] is about to fold it
     * into the entry above and stopping on it would open the page on half a trace again. Only the
     * message is examined, not [isSplitChunk]'s full test: the entry this one would be compared
     * against is further back than the line above, and walking one line too far only widens the
     * window, which is free — whereas stopping one line too early is the bug.
     */
    fun entryStart(index: LogIndex, line: Int): Int {
        if (line >= index.lineCount) return line
        var at = line
        var steps = 0
        while (at > 0 && steps < TRACE_LOOKBACK) {
            val first = firstByte(index, at)
            if (first != SPACE && first != TAB) {
                val text = lineText(index, at)
                val row = parseLogLine(at, text)
                if (row is LogRow.Entry) {
                    if (!looksLikeSplitChunk(row.message)) break
                } else if (!isContinuationLine(text)) break
            }
            at--
            steps++
        }
        return at
    }

    /**
     * Builds the filter, and the facets, in one streaming pass.
     *
     * Only a *matching* line is ever kept, and only as its offset, so filtering a 40 MB file costs
     * one sequential read and an `IntArray` of hits. Progress is a real fraction of bytes scanned
     * rather than a spinner, because on a large file this is long enough to be worth reporting
     * honestly.
     */
    suspend fun scan(
        index: LogIndex,
        query: LogQuery,
        onProgress: (Float) -> Unit,
    ): LogScanResult {
        val matches = if (query.isActive) IntVec() else null
        val tags = HashMap<String, Int>()
        val levels = HashMap<LogLevel, Int>()
        var previousMatched = false
        // The last row that was an entry, which is what lets a line be read as part of its message
        // here exactly as [readRows] reads it — both the unprefixed kind and the split-off tail.
        // Without it the two passes disagree, and a filtered view drops the half of a trace the
        // unfiltered one keeps. It is the row rather than a flag because [isSplitChunk] compares
        // against the entry itself.
        var lastEntry: LogRow.Entry? = null

        forEachLine(index, null, onProgress) { lineIndex, text, truncated ->
            // Parsed before the continuation test, for the reason given in [readRows]: a line that
            // carries a prefix is an entry whatever precedes it.
            val row = parseLogLine(lineIndex, text, truncated)
            val owner = lastEntry
            val joins =
                owner != null &&
                    if (row is LogRow.Entry) isSplitChunk(owner, row) else isContinuationLine(text)
            if (joins) {
                // Frames follow their entry into the filtered view; a stack trace whose header
                // matched and whose body vanished is a filter actively hiding the answer. A joined
                // tail counts for neither facet, because the row it belongs to was counted once.
                if (previousMatched) matches?.add(lineIndex)
                return@forEachLine
            }
            if (row is LogRow.Entry) {
                tags[row.tag] = (tags[row.tag] ?: 0) + 1
                levels[row.level] = (levels[row.level] ?: 0) + 1
            }
            lastEntry = row as? LogRow.Entry
            previousMatched = query.matches(row)
            if (previousMatched) matches?.add(lineIndex)
        }

        return LogScanResult(
            matches = matches?.toArray(),
            facets =
                LogFacets(
                    tags = tags.entries.sortedByDescending { it.value }.map { it.key to it.value },
                    levels = levels,
                ),
        )
    }

    override fun close() {
        runCatching { stream.close() }
    }

    // --- Block iteration ---------------------------------------------------------------------

    /**
     * Feeds lines to [action] a block at a time.
     *
     * Blocks end on a line boundary, so no line ever straddles two reads and the caller never has
     * to stitch. A single line longer than [READ_BLOCK] is the one exception and is cut short —
     * [MAX_LINE_BYTES] cuts it far sooner in any case.
     */
    private suspend fun forEachLine(
        index: LogIndex,
        selection: IntArray?,
        onProgress: ((Float) -> Unit)?,
        action: (lineIndex: Int, text: String, truncated: Boolean) -> Unit,
    ) {
        val bounds = index.bounds
        val count = selection?.size ?: index.lineCount
        if (count == 0) return
        val span = (bounds[index.lineCount] - bounds[0]).coerceAtLeast(1L)

        var k = 0
        while (k < count) {
            val startLine = selection?.get(k) ?: k
            val startOffset = bounds[startLine]

            // Take as many whole lines as fit in one block, always at least one.
            var endLine = startLine + 1
            while (endLine < index.lineCount && bounds[endLine + 1] - startOffset <= READ_BLOCK) {
                endLine++
            }
            val want = min(bounds[endLine] - startOffset, READ_BLOCK.toLong()).toInt()
            val read = readAt(startOffset, want)

            while (k < count) {
                val line = selection?.get(k) ?: k
                if (line >= endLine) break
                val from = (bounds[line] - startOffset).toInt()
                val to = min((bounds[line + 1] - startOffset).toInt(), read)
                var length = max(0, to - from)
                // The stored bound includes the newline that ended the line.
                if (length > 0 && block[from + length - 1] == NEWLINE) length--
                if (length > 0 && block[from + length - 1] == RETURN) length--
                val cut = length > MAX_LINE_BYTES
                if (cut) {
                    // The limit is a byte count, so it can land in the middle of a UTF-8 sequence.
                    // Backing up over the continuation bytes cuts between characters instead of
                    // handing the decoder half of one, which it would show as a replacement mark.
                    length = MAX_LINE_BYTES
                    while (length > 0 && (block[from + length].toInt() and 0xC0) == 0x80) length--
                }
                action(line, String(block, from, length, Charsets.UTF_8), cut)
                k++
            }

            onProgress?.invoke(((bounds[endLine] - bounds[0]).toFloat() / span).coerceIn(0f, 1f))
            yield()
        }
    }

    /** Fills [block] from [offset]; returns how many bytes actually landed. */
    private fun readAt(offset: Long, length: Int): Int {
        val buffer = ByteBuffer.wrap(block, 0, length)
        var total = 0
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, offset + total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    /**
     * The head of a line, decoded — enough of it to tell an entry and a banner from a continuation.
     *
     * Only the head is needed: [parseLogLine] decides on the prefix, which is fixed-width and far
     * shorter than this, and [isContinuationLine] on a banner, which is shorter still. So the read
     * is capped rather than following a line of unbounded length. It borrows [block], which is safe
     * only because the one caller, [entryStart], runs between block iterations and never during
     * one.
     */
    private fun lineText(index: LogIndex, line: Int): String {
        val from = index.bounds[line]
        val length = min(index.bounds[line + 1] - from, HEADER_PROBE.toLong()).toInt()
        if (length <= 0) return ""
        val read = readAt(from, length)
        return if (read <= 0) "" else String(block, 0, read, Charsets.UTF_8).trimEnd('\n', '\r')
    }

    private fun firstByte(index: LogIndex, line: Int): Int {
        if (index.bounds[line + 1] <= index.bounds[line]) return -1
        oneByte.clear()
        if (channel.read(oneByte, index.bounds[line]) <= 0) return -1
        return oneByte.get(0).toInt()
    }

    companion object {
        /** One page-cache-friendly read. Also the largest amount of raw log held at any moment. */
        private const val READ_BLOCK = 256 * 1024

        /**
         * 400,000 lines is ~3.2 MB of offsets, and more than ten times the lines in a full 4 MB
         * part. Past it the *oldest* lines are dropped, because a log is read from the end.
         */
        private const val MAX_INDEXED_LINES = 400_000

        private const val DROP_BLOCK = 50_000

        /** How far back a window start may walk to find the entry that owns a stack frame. */
        private const val TRACE_LOOKBACK = 64

        /** Comfortably past the longest throwable type name anyone has written. */
        private const val HEADER_PROBE = 512

        private const val NEWLINE = '\n'.code.toByte()
        private const val RETURN = '\r'.code.toByte()
        private const val SPACE = ' '.code
        private const val TAB = '\t'.code
    }
}

/**
 * Where every line of the file starts, plus the end sentinel.
 *
 * `bounds` has `lineCount + 1` entries; line `k` is the bytes in `[bounds[k], bounds[k + 1])`.
 * [droppedLeading] is how many lines fell off the front of an over-long file, and exists so the
 * header can say so rather than quietly misreport the file's length.
 */
class LogIndex(val bounds: LongArray, val droppedLeading: Int) {
    val lineCount: Int
        get() = bounds.size - 1
}

/** What [LogFile.scan] found: the filtered line numbers, and what the file contains. */
class LogScanResult(val matches: IntArray?, val facets: LogFacets)

/** The tags and levels actually present, with counts, so the filter sheet cannot go stale. */
data class LogFacets(
    val tags: List<Pair<String, Int>> = emptyList(),
    val levels: Map<LogLevel, Int> = emptyMap(),
)

/** Everything that narrows the view. All of it is applied in one pass over the file. */
data class LogQuery(
    val levels: Set<LogLevel> = emptySet(),
    val tag: String? = null,
    val text: String = "",
) {
    val isActive: Boolean
        get() = levels.isNotEmpty() || tag != null || text.isNotBlank()

    fun matches(row: LogRow): Boolean =
        when (row) {
            is LogRow.Entry ->
                (levels.isEmpty() || row.level in levels) &&
                    (tag == null || row.tag == tag) &&
                    (text.isBlank() ||
                        row.message.contains(text, ignoreCase = true) ||
                        row.tag.contains(text, ignoreCase = true))
            // A rotation banner has neither level nor tag, so it survives only a plain text
            // search. It marks where the daemon restarted, which is worth keeping when it can be.
            is LogRow.Marker ->
                levels.isEmpty() &&
                    tag == null &&
                    (text.isBlank() || row.text.contains(text, ignoreCase = true))
            is LogRow.DayBreak -> false
        }
}

/** Growable `long` storage. `ArrayList<Long>` would box every offset. */
private class LongVec(initial: Int = 1 shl 12) {
    private var data = LongArray(initial)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun last(): Long = if (size == 0) -1L else data[size - 1]

    fun dropFirst(n: Int) {
        System.arraycopy(data, n, data, 0, size - n)
        size -= n
    }

    fun toArray(): LongArray = data.copyOf(size)
}

/** The same, for line numbers, which are half the width. */
private class IntVec(initial: Int = 1 shl 10) {
    private var data = IntArray(initial)
    private var size = 0

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}

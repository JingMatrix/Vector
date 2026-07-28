package org.matrix.vector.manager.data.log

/**
 * The shape of a line in the daemon's log, and the scanner that recovers it.
 *
 * The authority for this format is not a sample of the output — it is the writer,
 * `daemon/src/main/jni/logcat.cpp`, which emits every entry as a single `writev` of
 *
 * ```
 * "[ " %Y-%m-%dT%H:%M:%S ".%03ld %8d:%6d:%6d %c/%-15.*s ] " <message> "\n"
 * ```
 *
 * Three properties of that format decide how this parser is written:
 *
 * 1. **The widths are `printf` minimums, not columns.** A uid of `1010324` is seven digits and a
 *    future pid can exceed six, so the prefix has to be *scanned*. Slicing at constant offsets
 *    works right up until the day it silently does not.
 * 2. **A message containing newlines is still one `writev`.** Its continuation lines therefore
 *    carry no prefix at all — a Java stack trace arrives as one entry followed by N raw lines each
 *    beginning with a tab. Those frames belong to the entry above them, not to nothing.
 * 3. **Not every line is an entry.** `----part N start----` / `-----part N end----` mark the
 *    daemon rotating to a fresh file, and the watchdog writes its own banners. Those are real
 *    information about the log rather than noise, so they survive as [LogRow.Marker] instead of
 *    being dropped.
 *
 * Anything the scanner cannot make sense of degrades to a marker carrying the raw text. A log
 * viewer that hides a line it failed to understand is worse than useless during a diagnosis.
 */
enum class LogLevel(val char: Char) {
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
    FATAL('F'),
    SILENT('S'),
    UNKNOWN('?');

    companion object {
        /** The characters `kLogChar` in logcat.cpp emits, indexed by Android's log priority. */
        fun of(c: Char): LogLevel =
            when (c) {
                'V' -> VERBOSE
                'D' -> DEBUG
                'I' -> INFO
                'W' -> WARN
                'E' -> ERROR
                'F' -> FATAL
                'S' -> SILENT
                else -> UNKNOWN
            }

        /**
         * The levels worth offering as a filter. Nothing writes at `SILENT`, and `UNKNOWN` is what
         * an unrecognised level character degrades to.
         */
        val selectable = listOf(VERBOSE, DEBUG, INFO, WARN, ERROR, FATAL)
    }
}

/** One row of the rendered log. [index] is the line's absolute position in the file. */
sealed interface LogRow {
    val index: Int

    /**
     * Stable identity for the lazy list.
     *
     * This is what lets the window be extended upwards without the viewport lurching: the list
     * re-resolves its first visible item by key after rows are inserted above it, so a prepend
     * re-anchors instead of shifting. A day break shares its line's index with the entry it
     * introduces, so its key is negated to keep the two distinct.
     */
    val key: Long
        get() = index.toLong()

    data class Entry(
        override val index: Int,
        /** `yyyy-MM-dd`, kept as written; only the day separator ever needs it. */
        val date: String,
        /** `HH:mm:ss.SSS`. The date is redundant on every row and moves to the separator. */
        val time: String,
        val uid: Int,
        val pid: Int,
        val tid: Int,
        val level: LogLevel,
        val tag: String,
        val message: String,
        /** Continuation lines of a multi-line message — in practice, stack frames. */
        val trace: List<String> = emptyList(),
        /** Set when the line exceeded [MAX_LINE_CHARS] and was cut. */
        val truncated: Boolean = false,
    ) : LogRow

    /** A rotation banner, a watchdog line, or anything the scanner could not read. */
    data class Marker(override val index: Int, val text: String) : LogRow

    /** Synthetic: introduces the first entry of a calendar day. */
    data class DayBreak(override val index: Int, val date: String) : LogRow {
        override val key: Long
            get() = -(index.toLong() + 1)
    }
}

/**
 * Cut point for a single line, applied to the raw bytes as the line is read.
 *
 * The longest line observed in either log on a real device is 816 characters (an attestation
 * dump), so this only ever bites on pathological output — but without it one runaway line sets
 * the horizontal extent for the entire list and makes panning useless.
 */
const val MAX_LINE_CHARS = 4096

/** The three-character delimiter that ends the prefix. See [parseLogLine]. */
private const val DELIMITER = " ] "

/** `"[ "` plus the 23-character timestamp; below this the fixed-position checks run off the end. */
private const val MIN_PREFIX = 26

/** A line is a continuation of the entry above it when it starts with whitespace. */
fun isContinuationLine(text: String): Boolean =
    text.isNotEmpty() && (text[0] == ' ' || text[0] == '\t')

/** Parses one raw line, degrading to [LogRow.Marker] rather than failing. */
fun parseLogLine(index: Int, text: String, truncated: Boolean = false): LogRow =
    parseEntry(index, text, truncated) ?: LogRow.Marker(index, text)

private fun parseEntry(index: Int, line: String, truncated: Boolean): LogRow.Entry? {
    val n = line.length
    if (n < MIN_PREFIX || line[0] != '[' || line[1] != ' ') return null

    // The timestamp is fixed-width, so it is the one part worth checking by position: cheap
    // separators to reject in six comparisons before any digit scanning happens.
    if (
        line[6] != '-' ||
            line[9] != '-' ||
            line[12] != 'T' ||
            line[15] != ':' ||
            line[18] != ':' ||
            line[21] != '.'
    )
        return null

    var i = 25 // "[ " + 23 characters of timestamp

    val uidField = readInt(line, skipSpaces(line, i))
    if (uidField == NO_INT) return null
    i = endOf(uidField)
    if (i >= n || line[i] != ':') return null

    val pidField = readInt(line, skipSpaces(line, i + 1))
    if (pidField == NO_INT) return null
    i = endOf(pidField)
    if (i >= n || line[i] != ':') return null

    val tidField = readInt(line, skipSpaces(line, i + 1))
    if (tidField == NO_INT) return null
    i = endOf(tidField)

    if (i + 2 >= n || line[i] != ' ' || line[i + 2] != '/') return null
    val level = LogLevel.of(line[i + 1])
    val tagStart = i + 3

    // The delimiter is the three-character sequence, not a bare ']'. A message that contains a
    // bracket — "[TX_ID: 773] Intercept…" — has no space before its ']', and the tag is padded
    // with spaces to fifteen columns, so the first " ] " is always the real end of the prefix.
    val delimiter = line.indexOf(DELIMITER, tagStart)
    if (delimiter < 0) return null

    return LogRow.Entry(
        index = index,
        date = line.substring(2, 12),
        time = line.substring(13, 25),
        uid = valueOf(uidField),
        pid = valueOf(pidField),
        tid = valueOf(tidField),
        level = level,
        tag = line.substring(tagStart, delimiter).trimEnd(),
        message = line.substring(delimiter + DELIMITER.length),
        truncated = truncated,
    )
}

private fun skipSpaces(s: String, from: Int): Int {
    var i = from
    while (i < s.length && s[i] == ' ') i++
    return i
}

/**
 * [readInt] has to return both the value and where it stopped.
 *
 * The two are packed into one `Long` rather than returned as a `Pair`, because a `Pair` would
 * allocate three times per parsed line — and a window of a full 4 MB log part is thirty thousand
 * lines, re-parsed every time the window moves. A top-level scratch variable would be shorter
 * still, but both log panes parse concurrently on the IO pool and would corrupt each other.
 */
private const val NO_INT = -1L

private fun valueOf(field: Long): Int = (field ushr 32).toInt()

private fun endOf(field: Long): Int = (field and 0xFFFFFFFFL).toInt()

/** Reads an unsigned decimal, refusing anything long enough to overflow. */
private fun readInt(s: String, from: Int): Long {
    var i = from
    var value = 0L
    while (i < s.length && s[i] in '0'..'9') {
        value = value * 10 + (s[i] - '0')
        if (value > Int.MAX_VALUE) return NO_INT
        i++
    }
    if (i == from) return NO_INT
    return (value shl 32) or i.toLong()
}

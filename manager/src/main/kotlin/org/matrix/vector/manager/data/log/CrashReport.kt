package org.matrix.vector.manager.data.log

/**
 * A recorded crash, in the shape the screens ask questions of.
 *
 * The file [CrashRecorder] writes is the record; this is that record read back. Parsing it here
 * rather than rendering the text means the UI can answer "what threw", "where", and "is this frame
 * ours" without a reader having to find those things in a wall of monospace — and it means the one
 * frame that names our own code can be pulled to the front of a summary, which is the single fact a
 * bug report is usually missing.
 *
 * Parsing never decides what is kept. A line the parser does not recognise contributes no frame and
 * nothing more; the file on disk is untouched, [CrashRecorder.read] still returns every byte of it,
 * and the copy action on the trace screen reads from there rather than from anything here. A trace
 * is evidence, and failing to understand it is not a reason to be unable to hand it over.
 */
data class CrashReport(
    /** The recorded timestamp, in the fixed format the file was written with. */
    val at: String,
    /**
     * The thread that threw, or empty for a record written before the header carried one — the
     * cache outlives an update, so the first run after one reads the old shape.
     */
    val thread: String,
    /** Build, host and platform, as one line. Restated from "What is running" on that screen. */
    val build: String,
    /** The throwable, then what caused it, in the order `printStackTrace` prints them. */
    val sections: List<CrashSection>,
) {
    /**
     * The innermost cause, which is the thing that actually went wrong.
     *
     * `RuntimeException: Unable to start activity` is the platform restating where it noticed; the
     * end of the chain is the sentence worth putting in a summary.
     */
    val root: CrashSection?
        get() = sections.lastOrNull()

    /**
     * The first frame in code we ship, anywhere in the chain.
     *
     * A crash inside `ActivityThread` is not a report anyone can act on until it says which of our
     * frames led there, and that frame is rarely near the top — the platform's own frames sit above
     * it. Null when nothing in the trace is ours, which happens and is itself worth seeing.
     */
    val ours: CrashFrame?
        get() = sections.firstNotNullOfOrNull { section -> section.frames.firstOrNull { it.ours } }
}

/** One throwable in the chain: what it was, what it said, and where it had been. */
data class CrashSection(
    /** The fully qualified type, e.g. `java.net.UnknownHostException`. */
    val type: String,
    val message: String?,
    val frames: List<CrashFrame>,
    /** The `... N more` count, which stands for frames identical to the ones already printed. */
    val elided: Int,
    /** False for the throwable that reached the handler, true for everything under `Caused by:`. */
    val isCause: Boolean,
) {
    /** The type without its package, which is what a heading has room for. */
    val simpleType: String
        get() = type.substringAfterLast('.')
}

/** One `at ...` line, split at the point where it stops being a name and starts being a place. */
data class CrashFrame(
    /** `org.matrix.vector.manager.ui.MainActivity.onCreate` */
    val method: String,
    /** `MainActivity.kt:39`, or null for a native frame, which prints no source. */
    val location: String?,
    /** Whether the class belongs to something in this repository rather than to the platform. */
    val ours: Boolean,
) {
    /** `MainActivity.onCreate` — the part a reader recognises, without the package. */
    val shortMethod: String
        get() {
            val method = this.method.substringAfterLast('.', "")
            val type = this.method.substringBeforeLast('.').substringAfterLast('.')
            return if (method.isEmpty() || type.isEmpty()) this.method else "$type.$method"
        }

    /** The line as it was written, for copying a single frame. */
    val line: String
        get() = if (location == null) "at $method" else "at $method($location)"
}

/**
 * The packages this project ships, by prefix.
 *
 * Used only to decide emphasis, so being wrong costs a frame its highlight and nothing else. The
 * legacy Xposed prefixes are here because a module's crash goes through them and a reader chasing
 * one wants those frames to stand out for the same reason they want ours to.
 */
private val OUR_PACKAGES =
    listOf(
        "org.matrix.vector",
        // Where this project's own code used to live. A trace is read long after it was captured —
        // out of a saved report, or out of the crash cache an update did not clear — so the frames
        // an older build wrote still arrive under the old name and are still ours.
        "org.lsposed.lspd",
        "de.robv.android.xposed",
        "io.github.libxposed",
    )

private val FRAME = Regex("""^\s*at (.+?)(?:\(([^)]*)\))?$""")
private val ELIDED = Regex("""^\s*\.\.\. (\d+) more$""")
private const val CAUSED_BY = "Caused by: "
private const val SUPPRESSED = "Suppressed: "

/**
 * Reads back a record written by [CrashRecorder].
 *
 * The two header lines are ours; everything after them is a stack trace, handed to
 * [parseStackTrace].
 *
 * Returns null only when there is no header to read, never on a trace it cannot make sense of.
 */
fun parseCrashReport(record: String): CrashReport? {
    val lines = record.trimEnd().lines()
    if (lines.size < 2) return null
    val (at, thread) =
        lines[0].split(" · thread ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    return CrashReport(
        at = at,
        thread = thread,
        build = lines[1],
        sections = parseStackTrace(lines.drop(2)),
    )
}

/**
 * `Throwable.printStackTrace` output, as the chain of throwables it describes.
 *
 * The shape is fixed by the JDK: a header line naming the throwable, tab-indented `at` lines, an
 * optional `... N more`, and the same again after `Caused by:`. Suppressed exceptions print under
 * `Suppressed:` and are treated as another link, since for reading purposes they are one.
 *
 * Total by construction. A line it does not recognise contributes nothing and ends nothing; text
 * that is not a trace at all yields an empty list, which is how a caller asks "is there a trace
 * here" without a second parser to decide it first. Written against the *printed* form rather than
 * against our own writer, because the traces it is given come from the daemon, from modules, and
 * from the platform's crash handler as readily as from us.
 */
fun parseStackTrace(trace: String): List<CrashSection> = parseStackTrace(trace.trimEnd().lines())

/**
 * The same, for a caller that already holds the lines.
 *
 * The log panel does: an entry's continuation lines *are* the trace, so joining them into a string
 * for this to split again would be work done twice on every visible row.
 */
fun parseStackTrace(lines: List<String>): List<CrashSection> {
    val sections = mutableListOf<CrashSection>()

    var type: String? = null
    var message: String? = null
    var isCause = false
    var open = false
    var frames = mutableListOf<CrashFrame>()
    var elided = 0

    fun flush() {
        if (!open) return
        sections += CrashSection(type.orEmpty(), message, frames.toList(), elided, isCause)
        frames = mutableListOf()
        elided = 0
        open = false
        type = null
        message = null
        isCause = false
    }

    for (line in lines) {
        val frame = FRAME.matchEntire(line)
        val skipped = ELIDED.matchEntire(line)
        when {
            frame != null -> {
                // A frame may arrive before any header, and does whenever the text handed here is
                // only the *continuation* of a log entry: `XposedBridge.log(Throwable)` writes the
                // whole trace as one message, so the header lands on the entry's own line and the
                // frames land under it. Such a trace opens an untyped section. Reading the frame as
                // a header instead — which a stricter rule did — spent it on a heading that said
                // "java:248)", the tail of the frame it had just eaten.
                open = true
                val method = frame.groupValues[1]
                val location = frame.groupValues[2].takeIf { it.isNotEmpty() }
                frames += CrashFrame(method, location, OUR_PACKAGES.any(method::startsWith))
            }
            skipped != null -> {
                open = true
                elided = skipped.groupValues[1].toIntOrNull() ?: 0
            }
            line.isBlank() -> Unit
            else -> {
                // A header: the throwable itself, or one introduced by Caused by:/Suppressed:.
                flush()
                open = true
                isCause = line.startsWith(CAUSED_BY) || line.startsWith(SUPPRESSED)
                val header = line.removePrefix(CAUSED_BY).removePrefix(SUPPRESSED).trim()
                // "type: message", where the type never contains a space and the message may.
                val split = header.indexOf(": ")
                type = if (split < 0) header else header.substring(0, split)
                message = if (split < 0) null else header.substring(split + 2)
            }
        }
    }
    flush()
    return sections
}

/**
 * A line that introduces a throwable, written flush left by `printStackTrace`.
 *
 * Either a labelled link in the chain, or a bare header: a dotted type name with no spaces in it,
 * ending in something that reads as a throwable, optionally followed by `: ` and a message.
 * Deliberately narrow, because callers use it to decide whether a line belongs to a trace at all —
 * "store: refreshing failed" is rejected on the first test, having no dot in its type.
 */
fun isThrowableHeader(text: String): Boolean {
    if (text.startsWith(CAUSED_BY) || text.startsWith(SUPPRESSED)) return true
    val type = text.substringBefore(": ")
    return type.contains('.') &&
        type.none { it.isWhitespace() } &&
        (type.endsWith("Exception") || type.endsWith("Error") || type.endsWith("Throwable"))
}

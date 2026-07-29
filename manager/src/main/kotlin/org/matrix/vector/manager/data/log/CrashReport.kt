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
    val thread: String,
    /** Build, host and platform, as one line. Restated from "What is running" on the same screen. */
    val build: String,
    /** The throwable, then whatever it was caused by, in the order `printStackTrace` prints them. */
    val sections: List<CrashSection>,
) {
    /** The throwable that reached the handler. Null only for a record we could not parse at all. */
    val thrown: CrashSection?
        get() = sections.firstOrNull()

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
 * The two header lines are ours; everything after them is `Throwable.printStackTrace` output, whose
 * shape is fixed by the JDK: a header line naming the throwable, tab-indented `at` lines, an
 * optional `... N more`, and the same again after `Caused by:`. Suppressed exceptions print under
 * `Suppressed:` and are treated as another section, since for reading purposes they are one.
 *
 * Returns null only when there is no header to read, never on a trace it cannot make sense of — an
 * unparsed tail simply contributes no frames, and [CrashReport.raw] still has every byte of it.
 */
fun parseCrashReport(record: String): CrashReport? {
    val lines = record.trimEnd().lines()
    if (lines.size < 2) return null

    val (at, thread) = lines[0].split(" · thread ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val sections = mutableListOf<CrashSection>()

    var type: String? = null
    var message: String? = null
    var isCause = false
    var frames = mutableListOf<CrashFrame>()
    var elided = 0

    fun flush() {
        val started = type ?: return
        sections += CrashSection(started, message, frames.toList(), elided, isCause)
        frames = mutableListOf()
        elided = 0
    }

    for (line in lines.drop(2)) {
        val frame = FRAME.matchEntire(line)
        val skipped = ELIDED.matchEntire(line)
        when {
            frame != null && type != null -> {
                val method = frame.groupValues[1]
                val location = frame.groupValues[2].takeIf { it.isNotEmpty() }
                frames += CrashFrame(method, location, OUR_PACKAGES.any(method::startsWith))
            }
            skipped != null -> elided = skipped.groupValues[1].toIntOrNull() ?: 0
            line.isBlank() -> Unit
            else -> {
                // A header: the throwable itself, or one introduced by Caused by:/Suppressed:.
                flush()
                val cause = line.startsWith(CAUSED_BY) || line.startsWith(SUPPRESSED)
                val header = line.removePrefix(CAUSED_BY).removePrefix(SUPPRESSED).trim()
                // "type: message", where the type never contains a space and the message may.
                val split = header.indexOf(": ")
                type = if (split < 0) header else header.substring(0, split)
                message = if (split < 0) null else header.substring(split + 2)
                isCause = cause
            }
        }
    }
    flush()

    return CrashReport(at = at, thread = thread, build = lines[1], sections = sections)
}

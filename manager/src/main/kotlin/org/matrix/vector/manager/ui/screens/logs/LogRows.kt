package org.matrix.vector.manager.ui.screens.logs

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.log.isThrowableHeader
import org.matrix.vector.manager.data.log.parseStackTrace
import org.matrix.vector.manager.ui.components.StackTrace
import org.matrix.vector.manager.data.log.LogLevel
import org.matrix.vector.manager.data.log.LogRow
import org.matrix.vector.manager.ui.theme.VectorLogLine

/**
 * Horizontal panning shared by every row of a log.
 *
 * Neither `Modifier.horizontalScroll` on the `LazyColumn` nor a shared `ScrollState` on the rows
 * will do, and for the same reason: both derive the pan extent from whatever is currently measured.
 * A lazy list only measures its visible window, and a `ScrollState` holds one `maxValue` that the
 * last row to measure wins — so scrolling vertically brings a longer line into the window, the
 * extent is recomputed, and the clamp on the current offset moves under the reader's finger.
 *
 * So the offset lives here, and the extent is the **running maximum** of every row width measured
 * so far. It only ever grows while a window is on screen, which is what makes it impossible for a
 * newly composed row to yank the content sideways. It restarts when the reading changes — see
 * [reportRow] for why that restart has to be lazy.
 */
@Stable
class LogPan {
    /** Read during placement only, so a pan re-places rows without recomposing them. */
    var offset by mutableFloatStateOf(0f)
        private set

    // Deliberately not snapshot state: these are written during measurement, and making them
    // observable would invalidate the very layout pass that produced them.
    private var contentWidth = 0
    private var viewportWidth = 0
    private var epoch = 0
    private var measuredEpoch = -1

    /**
     * The running maximum is restarted by [reset] *lazily*, on the next row measured, rather than
     * eagerly.
     *
     * [reset] must not zero the width itself. It is called from a `LaunchedEffect`, which can land
     * after the rows have measured for the frame, and nothing re-measures them afterwards — a
     * zeroed extent would then stay zero and the log could not be panned at all. Bumping an epoch
     * and restarting on the next measurement is correct whichever order the two land in.
     */
    fun reportRow(width: Int) {
        if (measuredEpoch != epoch) {
            measuredEpoch = epoch
            contentWidth = width
        } else if (width > contentWidth) {
            contentWidth = width
        }
    }

    fun reportViewport(width: Int) {
        viewportWidth = width
    }

    fun reset() {
        offset = 0f
        epoch++
    }

    /** Consumes a horizontal drag, returning how much of it was used. */
    fun consume(delta: Float): Float {
        val limit = (contentWidth - viewportWidth).coerceAtLeast(0).toFloat()
        val before = offset
        offset = (before - delta).coerceIn(0f, limit)
        return before - offset
    }
}

@Composable
fun rememberLogPan(): LogPan = remember { LogPan() }

/** The gesture side of [LogPan]; goes on whatever contains the list. */
@Composable
fun panGesture(pan: LogPan): Modifier {
    val state = rememberScrollableState { delta -> pan.consume(delta) }
    return Modifier.scrollable(state, Orientation.Horizontal)
}

/** The layout side: measure at intrinsic width, place at the shared offset, clip to the viewport. */
private fun Modifier.panContent(pan: LogPan): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable =
            measurable.measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = Constraints.Infinity,
                    minHeight = constraints.minHeight,
                    maxHeight = constraints.maxHeight,
                )
            )
        pan.reportRow(placeable.width)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        pan.reportViewport(width)
        layout(width, placeable.height) { placeable.place(-pan.offset.roundToInt(), 0) }
    }

/**
 * One row of the log.
 *
 * The anatomy is the payoff of parsing: a rail in the level's colour *and* the level letter, since
 * under Material You the hue belongs to the wallpaper and no state may be distinguishable by colour
 * alone; the time of day only, because the date lives on the day separator; the tag, tappable to
 * filter to itself; then the message. `uid:pid:tid` are twenty-two columns wide — `%8d:%6d:%6d` in
 * `logcat.cpp` — and are what forces sideways panning, so they hide behind a tap.
 *
 * All of it is **one** styled `Text` rather than a `Row` of cells. Cells confine the message to
 * whatever the metadata leaves over, which on a phone is a narrow column beside a mostly empty
 * gutter; as one string the message wraps under the metadata and uses the full width. The cost is
 * that the tag is not a `Chip` with its own click target, so the tap is resolved against the text
 * layout instead — see [tagRangeOf].
 */
@Composable
fun LogRowItem(
    row: LogRow,
    wordWrap: Boolean,
    showTag: Boolean,
    pan: LogPan,
    query: String,
    inlineTraces: Boolean,
    onTagClick: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenTrace: (String) -> Unit,
) {
    when (row) {
        is LogRow.DayBreak -> DayBreakRow(row)
        is LogRow.Marker -> MarkerRow(row, query)
        is LogRow.Entry ->
            EntryRow(
                row,
                wordWrap,
                showTag,
                pan,
                query,
                inlineTraces,
                onTagClick,
                onCopy,
                onOpenTrace,
            )
    }
}

@Composable
private fun EntryRow(
    entry: LogRow.Entry,
    wordWrap: Boolean,
    showTag: Boolean,
    pan: LogPan,
    query: String,
    /** Whether a trace opens under the row or on a screen. See `SettingsRepository`. */
    inlineTraces: Boolean,
    onTagClick: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenTrace: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var framesOpen by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val accent = levelColor(entry.level)
    // Wide enough to read as a colour rather than as a hairline, narrow enough to stay a
    // margin rather than a column.
    val railWidth = with(LocalDensity.current) { 4.5.dp.toPx() }

    val muted = MaterialTheme.colorScheme.outline
    // The badge is washed with the level's own colour rather than one fixed container pair, so the
    // level reads from the widest thing on the row and not only from the rail at its edge and the
    // single letter at its start. A scan down a page now separates on a band of colour.
    //
    // A wash, not a fill, and the tag itself stays `onSurface`. The level palette runs from `error`
    // to `outlineVariant` — deliberately, since debug and verbose are noise and are meant to recede
    // — and tag text in those colours on a tint of themselves would be a badge nobody can read at
    // the two levels the log is mostly made of. The colour identifies; the text stays legible.
    val tagBackground = accent.copy(alpha = TAG_TINT)
    val tagForeground = MaterialTheme.colorScheme.onSurface
    val hit = MaterialTheme.colorScheme.primaryContainer
    val onHit = MaterialTheme.colorScheme.onPrimaryContainer
    val line =
        remember(entry, query, showTag, accent, tagBackground, hit) {
            buildLine(entry, query, showTag, accent, muted, tagBackground, tagForeground, hit, onHit)
        }
    // Filtered to one tag, every line carries the same tag — so it is stated once above the list
    // and dropped from the lines, which is a quarter of the width back on a narrow screen.
    val tagRange = remember(entry, showTag) { if (showTag) tagRangeOf(entry) else IntRange.EMPTY }

    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(accent, size = Size(railWidth, size.height)) }
            .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Text(
            line,
            style = VectorLogLine,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = wordWrap,
            maxLines = if (wordWrap) Int.MAX_VALUE else 1,
            onTextLayout = { layout = it },
            modifier =
                (if (wordWrap) Modifier.fillMaxWidth() else Modifier.panContent(pan)).pointerInput(
                    entry.index
                ) {
                    detectTapGestures(
                        // No onLongPress: the long press belongs to the enclosing
                        // SelectionContainer, so copying the whole line — metadata included — is
                        // the double tap.
                        onDoubleTap = { onCopy(rawText(entry)) },
                        onTap = { position ->
                            val offset = layout?.getOffsetForPosition(position)
                            if (offset != null && offset in tagRange) onTagClick(entry.tag)
                            else expanded = !expanded
                        },
                    )
                },
        )

        if (entry.truncated) {
            Text(
                stringResource(R.string.logs_line_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 18.dp),
            )
        }

        if (expanded) {
            Text(
                stringResource(R.string.logs_row_detail, entry.uid, entry.pid, entry.tid),
                style = VectorLogLine,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }

        if (entry.continuation.isNotEmpty()) {
            // Parsed once per row, not per recomposition: the expander is tapped rarely and the
            // count on it has to be right whether or not anyone ever taps.
            //
            // The message is offered to the parser because it is often the trace's first line.
            // `XposedBridge.log(Throwable)` writes the whole trace as one message, so the header —
            // `java.lang.ClassNotFoundException: Didn't find class …` — is the entry's own text and
            // only the frames are continuations. Passing the frames alone left the trace headless
            // and the reader without the one line naming what was thrown. It is offered rather
            // than prepended: when the entry says something of its own, as `logE(msg, tr)` does,
            // the header is the first continuation line and the message is not part of the trace.
            //
            // Only the *type* is taken from it, not the message after the colon: the entry's line
            // is right above, already saying it in full. Passing the whole header printed the same
            // sentence twice, once in the log's face and once in the trace's.
            val sections =
                remember(entry.message, entry.continuation) {
                    val type = throwableTypeOf(entry.message)
                    val lines =
                        if (type == null) entry.continuation else listOf(type) + entry.continuation
                    parseStackTrace(lines)
                }
            val frameCount = remember(sections) { sections.sumOf { it.frames.size } }
            // Only a trace gets the trace treatment. Now that any unprefixed line is attached to
            // its entry, most of these blocks are not traces at all — zygisk's mount-argument
            // report is a page of plain text — and calling it "20 frames" would be a lie told by
            // an expander that then rendered nothing under it.
            val isTrace = frameCount > 0
            Text(
                if (isTrace) pluralStringResource(R.plurals.logs_frames, frameCount, frameCount)
                else
                    pluralStringResource(
                        R.plurals.logs_more_lines,
                        entry.continuation.size,
                        entry.continuation.size,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.padding(start = 18.dp, top = 2.dp)
                        .combinedClickable(
                            onClick = {
                                // The screen is a *stack trace* screen; sending a mount-argument
                                // dump to it would be sending it somewhere that cannot read it.
                                if (inlineTraces || !isTrace) framesOpen = !framesOpen
                                else onOpenTrace(traceText(entry))
                            }
                        ),
            )
            if (framesOpen && isTrace && inlineTraces) {
                // The full renderer, not a run of monospace lines. A trace in the log is the same
                // text as a trace on the crash screen and is read for the same reason, so the one
                // that reads better wins in both places. Indented to sit under the expander that
                // opened it, and given the row's own width rather than the panned one — a trace is
                // read as rows, and rows that slide sideways with the log lines above them would
                // be read a column at a time.
                StackTrace(
                    sections = sections,
                    onCopyFrame = { onCopy(it.line) },
                    modifier = Modifier.padding(start = 26.dp, top = 4.dp, bottom = 4.dp),
                )
            } else if (framesOpen) {
                // Plain text, kept as the writer set it out — the alignment in a mount-argument
                // report or a table of properties is the whole of its legibility. So it pans
                // sideways with the log lines above it rather than wrapping, under the same
                // switch, and joins the shared pan extent so the columns stay lined up with them.
                Text(
                    entry.continuation.joinToString("\n"),
                    style = VectorLogLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = wordWrap,
                    modifier =
                        Modifier.padding(start = 26.dp, top = 2.dp, bottom = 2.dp)
                            .then(
                                if (wordWrap) Modifier.fillMaxWidth()
                                else Modifier.panContent(pan)
                            ),
                )
            }
        }
    }
}

/**
 * A rotation banner, a watchdog line, or a line the scanner could not read.
 *
 * Worth rendering as its own thing rather than as text: `----part 7 start----` is the daemon
 * telling you exactly where it restarted, which is often the answer to "why does the log stop".
 */
@Composable
private fun MarkerRow(marker: LogRow.Marker, query: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.width(12.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            highlighted(marker.text.trim(), query),
            style = VectorLogLine,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DayBreakRow(day: LogRow.DayBreak) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            day.date,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * The whole line as one styled string: level, time, tag, message.
 *
 * The tag gets a background span rather than a real chip, with a space either side standing in for
 * padding. That is the compromise that buys the message the full width of the screen.
 */
private fun buildLine(
    entry: LogRow.Entry,
    query: String,
    showTag: Boolean,
    accent: Color,
    muted: Color,
    tagBackground: Color,
    tagForeground: Color,
    hitBackground: Color,
    hitForeground: Color,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
        append(entry.level.char)
    }
    append(' ')
    withStyle(SpanStyle(color = muted)) { append(entry.time) }
    append(' ')
    if (showTag) {
        withStyle(SpanStyle(color = tagForeground, background = tagBackground)) {
            append(' ')
            append(entry.tag)
            append(' ')
        }
        append("  ")
    }

    if (query.isBlank()) {
        append(entry.message)
        return@buildAnnotatedString
    }
    var from = 0
    while (true) {
        val at = entry.message.indexOf(query, from, ignoreCase = true)
        if (at < 0) {
            append(entry.message.substring(from))
            return@buildAnnotatedString
        }
        append(entry.message.substring(from, at))
        withStyle(SpanStyle(background = hitBackground, color = hitForeground)) {
            append(entry.message.substring(at, at + query.length))
        }
        from = at + query.length
    }
}

/**
 * Where the tag sits in the string [buildLine] produced.
 *
 * Derived from the layout above rather than searched for, because a tag can legitimately appear in
 * the message too and tapping the message must not filter.
 */
private fun tagRangeOf(entry: LogRow.Entry): IntRange {
    val start = 2 + entry.time.length + 1
    return start until start + entry.tag.length + 2
}

/**
 * How much of the level's colour the tag badge carries.
 *
 * Enough to name the level at a glance across a scrolling page, little enough that the tag on top
 * of it is read as text rather than as decoration.
 */
private const val TAG_TINT = 0.22f

/**
 * Colour is reinforcement here, never the signal: the level letter carries the meaning, because
 * under Material You the hues come from the wallpaper.
 */
@Composable
fun levelColor(level: LogLevel): Color =
    when (level) {
        LogLevel.ERROR,
        LogLevel.FATAL -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
fun levelLabel(level: LogLevel): String =
    stringResource(
        when (level) {
            LogLevel.VERBOSE -> R.string.logs_level_verbose
            LogLevel.DEBUG -> R.string.logs_level_debug
            LogLevel.INFO -> R.string.logs_level_info
            LogLevel.WARN -> R.string.logs_level_warn
            LogLevel.ERROR -> R.string.logs_level_error
            LogLevel.FATAL -> R.string.logs_level_fatal
            else -> R.string.logs_level_other
        }
    )

/**
 * The throwable type an entry's message names, or null if it does not name one.
 *
 * `XposedBridge.log(Throwable)` writes a whole trace as one message, so the header is the entry's
 * own line and only the frames arrive as continuations. This recovers the type from it so the trace
 * below can be headed by the thing that was thrown. A `Caused by:` line is refused: it is never the
 * first line of a trace, so a message shaped like one is not the header this is looking for.
 */
private fun throwableTypeOf(message: String): String? =
    message
        .takeIf { isThrowableHeader(it) && !it.startsWith("Caused by: ") }
        ?.substringBefore(": ")

/**
 * The entry's trace as `printStackTrace` would have written it.
 *
 * The whole header, message and all, unlike the inline expander's — a screen shows the trace with
 * no log line above it, so the sentence naming what failed has nowhere else to come from.
 */
private fun traceText(entry: LogRow.Entry): String =
    if (isThrowableHeader(entry.message))
        (listOf(entry.message) + entry.continuation).joinToString("\n")
    else entry.continuation.joinToString("\n")

/**
 * Rebuilds the entry as the daemon wrote it, for the clipboard.
 *
 * One prefix, then the message and everything under it. Where the writer had to cut a long message
 * into several entries the second prefix does not come back, because what is being copied is the
 * message that was written rather than the transport that carried it — and a stack trace with a
 * timestamp wedged into the middle of it is one nobody can paste anywhere useful.
 */
private fun rawText(entry: LogRow.Entry): String = buildString {
    append("[ ")
    append(entry.date)
    append('T')
    append(entry.time)
    append(' ')
    append(entry.uid)
    append(':')
    append(entry.pid)
    append(':')
    append(entry.tid)
    append(' ')
    append(entry.level.char)
    append('/')
    append(entry.tag)
    append(" ] ")
    append(entry.message)
    entry.continuation.forEach {
        append('\n')
        append(it)
    }
}

/** Marks every occurrence of the active search text, so a hit is findable inside a long line. */
@Composable
private fun highlighted(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val background = MaterialTheme.colorScheme.primaryContainer
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    return remember(text, query, background) {
        buildAnnotatedString {
            var from = 0
            while (true) {
                val hit = text.indexOf(query, from, ignoreCase = true)
                if (hit < 0) {
                    append(text.substring(from))
                    return@buildAnnotatedString
                }
                append(text.substring(from, hit))
                withStyle(SpanStyle(background = background, color = foreground)) {
                    append(text.substring(hit, hit + query.length))
                }
                from = hit + query.length
            }
        }
    }
}

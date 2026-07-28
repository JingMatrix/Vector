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
    onTagClick: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    when (row) {
        is LogRow.DayBreak -> DayBreakRow(row)
        is LogRow.Marker -> MarkerRow(row, query)
        is LogRow.Entry -> EntryRow(row, wordWrap, showTag, pan, query, onTagClick, onCopy)
    }
}

@Composable
private fun EntryRow(
    entry: LogRow.Entry,
    wordWrap: Boolean,
    showTag: Boolean,
    pan: LogPan,
    query: String,
    onTagClick: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var framesOpen by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val accent = levelColor(entry.level)
    // Wide enough to read as a colour rather than as a hairline, narrow enough to stay a
    // margin rather than a column.
    val railWidth = with(LocalDensity.current) { 4.5.dp.toPx() }

    val muted = MaterialTheme.colorScheme.outline
    val tagBackground = MaterialTheme.colorScheme.secondaryContainer
    val tagForeground = MaterialTheme.colorScheme.onSecondaryContainer
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

        if (entry.trace.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.logs_frames, entry.trace.size, entry.trace.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.padding(start = 18.dp, top = 2.dp)
                        .combinedClickable(onClick = { framesOpen = !framesOpen }),
            )
            if (framesOpen) {
                entry.trace.forEach { frame ->
                    Text(
                        frame.trim(),
                        style = VectorLogLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 26.dp),
                    )
                }
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

/** Rebuilds the line exactly as the daemon wrote it, for the clipboard. */
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
    entry.trace.forEach {
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

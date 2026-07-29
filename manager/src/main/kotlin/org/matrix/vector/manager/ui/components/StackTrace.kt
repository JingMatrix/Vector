package org.matrix.vector.manager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.log.CrashFrame
import org.matrix.vector.manager.data.log.CrashSection
import org.matrix.vector.manager.ui.theme.VectorMono

/**
 * A stack trace, read as a list rather than as a wall of text.
 *
 * A trace is already a structured thing — a chain of throwables, each with a list of frames — and
 * printing it as one string is a format for a terminal, not for a screen someone is scrolling on a
 * phone. Rendered as rows it can do what the text cannot: mark the frames that belong to this
 * project, separate the name of a method from the file it lives in, and let one frame be lifted to
 * the clipboard without a text selection.
 *
 * Two things are deliberate about the emphasis. The frames in **our** code are the ones a reader is
 * looking for and the platform's are context, so ours carry the weight and a filled marker while
 * the platform's are dimmed — the opposite of the printed order, where the platform usually comes
 * first. And the chain reads downwards to the *root* cause: `printStackTrace` puts the outermost
 * throwable at the top, but "Caused by" is where the answer is, so each cause is introduced by a
 * divider rather than buried in the run of frames.
 *
 * Shared by the crash card's trace screen and the log panel, which had been folding raw frames
 * behind an expander. The same text arrives at both from the same parser, so it may as well be read
 * the same way in both.
 *
 * Emits a plain column of rows and takes no scrolling of its own, so a caller can drop it into a
 * `LazyColumn` item, a card, or an expanded log row without fighting a nested scroll. Long traces
 * belong in [stackTraceItems] instead, which spends the caller's lazy list on them.
 */
@Composable
fun StackTrace(
    sections: List<CrashSection>,
    onCopyFrame: (CrashFrame) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        sections.forEach { section ->
            StackTraceSectionHeader(section)
            section.frames.forEach { frame -> StackTraceFrame(frame) { onCopyFrame(frame) } }
            if (section.elided > 0) StackTraceElided(section.elided)
        }
    }
}

/**
 * The same rows, contributed to a caller's `LazyColumn` instead of composed all at once.
 *
 * A trace can run to a hundred frames and a screen showing nothing else should not compose them
 * all to show eight.
 *
 * Keys are positions, not frame text. A `StackOverflowError` prints the same frame hundreds of
 * times over and a cause chain repeats the frames it shares, so keying on the line would hand
 * `LazyColumn` a duplicate key — which it does not tolerate: it throws, on the screen whose whole
 * job is showing someone what threw.
 */
fun LazyListScope.stackTraceItems(sections: List<CrashSection>, onCopyFrame: (CrashFrame) -> Unit) {
    sections.forEachIndexed { index, section ->
        item(key = "s:$index") { StackTraceSectionHeader(section) }
        items(section.frames.size, key = { "f:$index:$it" }) { at ->
            val frame = section.frames[at]
            StackTraceFrame(frame) { onCopyFrame(frame) }
        }
        if (section.elided > 0) {
            item(key = "e:$index") { StackTraceElided(section.elided) }
        }
    }
}

/**
 * The throwable a run of frames belongs to.
 *
 * The type is the heading and the message is the sentence under it, which is the way round a reader
 * needs them: the type says what kind of failure this is and is short enough to scan, the message
 * says what was being attempted and is often a whole line long. A cause is introduced by a labelled
 * divider so that the change of subject is visible while scrolling past at speed.
 */
@Composable
private fun StackTraceSectionHeader(section: CrashSection) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        if (section.isCause) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.crash_caused_by),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.error,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                HorizontalDivider(Modifier.weight(1f), color = colors.error.copy(alpha = 0.3f))
            }
            Spacer(Modifier.height(6.dp))
        }
        // Untyped when the text began at its first frame, which is what a trace looks like when its
        // header was the log entry's own line. Nothing is drawn then rather than an empty heading:
        // the entry above is already showing the sentence this would have repeated.
        if (section.simpleType.isNotEmpty()) {
            Text(
                section.simpleType,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.error,
            )
            section.message?.let { message ->
                Spacer(Modifier.height(2.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * One frame, as two lines: what ran, and where that is written.
 *
 * Only the file and line are monospaced. `MainActivity.kt:39` is an identifier a reader compares
 * character by character against their editor; `MainActivity.onCreate` is a name they read, and
 * reads worse in a typewriter face. The frame stays on one line and scrolls sideways rather than
 * wrapping — a wrapped frame reads as two frames.
 *
 * Tapping copies this frame alone, which is the unit people quote to each other.
 */
@Composable
private fun StackTraceFrame(frame: CrashFrame, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onCopy).padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Filled for our code, hollow for the platform's: the shape carries the distinction where
        // colour alone would not, and the column of markers can be scanned without reading a word.
        Surface(
            modifier = Modifier.padding(top = 6.dp).size(7.dp),
            shape = CircleShape,
            color = if (frame.ours) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.25f),
            content = {},
        )
        Spacer(Modifier.padding(horizontal = 6.dp))
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                frame.shortMethod,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (frame.ours) FontWeight.Medium else FontWeight.Normal,
                color = if (frame.ours) colors.onSurface else colors.onSurfaceVariant,
                softWrap = false,
                maxLines = 1,
            )
            Text(
                frame.location ?: frame.method,
                style = VectorMono.copy(fontSize = 11.sp),
                color = colors.onSurfaceVariant.copy(alpha = if (frame.ours) 1f else 0.7f),
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

/** The frames `printStackTrace` replaced with `... N more`, having printed them already. */
@Composable
private fun StackTraceElided(count: Int) {
    Text(
        pluralStringResource(R.plurals.crash_frames_elided, count, count),
        modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

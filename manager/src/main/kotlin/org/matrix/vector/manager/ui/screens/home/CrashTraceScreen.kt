package org.matrix.vector.manager.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.log.CrashFrame
import org.matrix.vector.manager.data.log.CrashRecorder
import org.matrix.vector.manager.data.log.CrashSection
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.copyToClipboard
import org.matrix.vector.manager.ui.components.show
import org.matrix.vector.manager.ui.theme.VectorMono

/**
 * The newest crash, read as a list rather than as a wall of text.
 *
 * A stack trace is already a structured thing — a chain of throwables, each with a list of frames —
 * and printing it as one string is a format for a terminal, not for a screen someone is scrolling
 * on a phone. Rendered as rows it can do what the text cannot: mark the frames that belong to this
 * project, separate the name of a method from the file it lives in, and let one frame be lifted to
 * the clipboard without a text selection.
 *
 * Two things are deliberate about what is emphasised. The frames in **our** code are the ones a
 * reader is looking for and the platform's are context, so ours carry the weight and a filled
 * marker while the platform's are dimmed — the opposite of the printed order, where the platform
 * usually comes first. And the chain reads downwards to the *root* cause: `printStackTrace` puts
 * the outermost throwable at the top, but "Caused by" is where the answer is, so each cause is
 * introduced by a divider rather than buried in the run of frames.
 *
 * The record is read here rather than passed through the route, because the process is quite likely
 * to have died since the card was drawn — that is, after all, the subject.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashTraceScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val report = remember { CrashRecorder.newest(context) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    val frameCopied = stringResource(R.string.crash_frame_copied)

    Scaffold(
        snackbarHost = { VectorSnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crash_trace)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    // Every record, not the one on screen. The screen shows the newest because
                    // that is the one being asked about, but a crash loop writes several and a
                    // maintainer wants all of them; and this stays enabled when the newest could
                    // not be parsed, since a record we failed to read is exactly the one worth
                    // getting off the device by hand.
                    IconButton(
                        onClick = {
                            copyToClipboard(context, CrashRecorder.read(context).orEmpty())
                            scope.launch { snackbars.show(copied, SnackbarTone.Success) }
                        }
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy_all),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (report == null || report.sections.isEmpty()) {
            Text(
                stringResource(R.string.crash_unreadable),
                modifier = Modifier.padding(padding).padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "when") {
                Text(
                    stringResource(R.string.crash_when_value, report.at, report.thread),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    report.build,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
            report.sections.forEachIndexed { index, section ->
                item(key = "s:$index") { SectionHeader(section) }
                items(section.frames, key = { "f:$index:${it.line}" }) { frame ->
                    FrameRow(
                        frame = frame,
                        onCopy = {
                            copyToClipboard(context, frame.line)
                            scope.launch { snackbars.show(frameCopied, SnackbarTone.Success) }
                        },
                    )
                }
                if (section.elided > 0) {
                    item(key = "e:$index") {
                        Text(
                            pluralStringResource(
                                R.plurals.crash_frames_elided,
                                section.elided,
                                section.elided,
                            ),
                            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
private fun SectionHeader(section: CrashSection) {
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
private fun FrameRow(frame: CrashFrame, onCopy: () -> Unit) {
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

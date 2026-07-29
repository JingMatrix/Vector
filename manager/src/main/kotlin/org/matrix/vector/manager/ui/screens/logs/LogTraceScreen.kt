package org.matrix.vector.manager.ui.screens.logs

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.log.parseStackTrace
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.copyToClipboard
import org.matrix.vector.manager.ui.components.show
import org.matrix.vector.manager.ui.components.stackTraceItems

/**
 * A trace from the log, given the room the log itself does not have.
 *
 * The same rows as the crash screen, from the same parser — a trace written by the daemon, by a
 * module, or by the manager is the same `printStackTrace` output whichever of them wrote it, so
 * there is one way to read it. Reached only when the reader has said they prefer a screen to the
 * inline expander; see `SettingsRepository.logTracesInline`.
 *
 * Copy takes the raw text, exactly as the log holds it, because that is what goes into an issue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTraceScreen(text: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val sections = remember(text) { parseStackTrace(text) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    val frameCopied = stringResource(R.string.crash_frame_copied)

    Scaffold(
        snackbarHost = { VectorSnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_trace_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            copyToClipboard(context, text)
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
        if (sections.isEmpty()) {
            Text(
                text,
                modifier = Modifier.padding(padding).padding(20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        ) {
            stackTraceItems(sections) { frame ->
                copyToClipboard(context, frame.line)
                scope.launch { snackbars.show(frameCopied, SnackbarTone.Success) }
            }
        }
    }
}

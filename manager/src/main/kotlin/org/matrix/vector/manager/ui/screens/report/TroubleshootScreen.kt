package org.matrix.vector.manager.ui.screens.report
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.log.logArchiveName
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.show

/**
 * What to try, and what to bring, before opening an issue.
 *
 * The tracker sits at the foot of the screen rather than being the whole of it, because the first
 * reply to a bug report is a checklist — try the latest canary, update your Zygisk implementation,
 * attach logs — and a screen can *do* most of that instead of describing it.
 *
 * The part worth the screen is the logs. A report without them is a conversation that has to start
 * over, and the two ways of getting them are not equivalent:
 * - the **zip** comes from the daemon, which assembles the complete export — when the daemon is
 *   alive;
 * - the **log folder** is the fallback for when it is not, which is exactly the case for the bugs
 *   worth reporting. The manager cannot read `/data/adb/lspd/log` itself — parasitically it runs as
 *   `com.android.shell`, with no root of its own — so that path goes out through `su`, where a
 *   refusal is an answer the reader can act on rather than a fault.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroubleshootScreen(
    onNavigateBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenCanary: () -> Unit,
) {
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val savedLabel = stringResource(R.string.logs_saved)
    val failedLabel = stringResource(R.string.logs_save_failed)

    val saveLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok =
                    withContext(Dispatchers.IO) {
                        runCatching {
                                context.contentResolver.openFileDescriptor(uri, "wt").use { fd ->
                                    // Thrown rather than returned, so one catch covers all three
                                    // ways this fails: a refused open, no descriptor, and a
                                    // failed transaction.
                                    checkNotNull(fd) { "no descriptor for the chosen file" }
                                    ServiceLocator.daemon.writeBugReportTo(fd).getOrThrow()
                                }
                            }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                logE("report: saving the log archive failed", e)
                            }
                            .isSuccess
                    }
                if (ok) snackbars.show(savedLabel, SnackbarTone.Success)
                else snackbars.show(failedLabel, SnackbarTone.Failure)
            }
        }

    Scaffold(
        snackbarHost = { VectorSnackbarHost(snackbars) },
        // Docked rather than last in the list. It is where the screen is heading, and a reader who
        // has decided to file anyway should not have to scroll past the advice to do it.
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    // Scaffold places its bottom slot against the bottom of the window and leaves
                    // the system bars to it, so the button says where it stands itself.
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Button(onClick = { onOpenUrl(GitHubRepository.ISSUES_URL) }) {
                        Icon(
                            Icons.Rounded.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.report_open_tracker))
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Step(
                    icon = Icons.Rounded.Science,
                    title = stringResource(R.string.report_step_canary),
                    body = stringResource(R.string.report_step_canary_body),
                ) {
                    FilledTonalButton(onClick = onOpenCanary) {
                        Text(stringResource(R.string.home_test_canary))
                    }
                }
            }

            item {
                Step(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    title = stringResource(R.string.report_step_zygisk),
                    body = stringResource(R.string.report_step_zygisk_body),
                    titleAction = {
                        OutlinedButton(onClick = { onOpenUrl(NEO_ZYGISK) }) { Text("NeoZygisk") }
                    },
                )
            }

            item {
                Step(
                    icon = Icons.Rounded.Save,
                    title = stringResource(R.string.report_step_logs),
                    body = stringResource(R.string.report_step_logs_body),
                ) {
                    Button(onClick = { saveLauncher.launch(logArchiveName("zip")) }) {
                        Icon(
                            Icons.Rounded.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.logs_save))
                    }
                }
            }

            item {
                Step(
                    icon = Icons.Rounded.Folder,
                    title = stringResource(R.string.report_step_crashed),
                    body = stringResource(R.string.report_step_crashed_body),
                ) {
                    Column {
                        // Root can read the folder the manager cannot, so this works with no
                        // computer in the room — which is the situation the whole step exists for.
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { exportWithRoot() }
                                    result
                                        .onSuccess {
                                            snackbars.show(
                                                context.getString(R.string.report_saved_to, it),
                                                SnackbarTone.Success,
                                            )
                                        }
                                        .onFailure { failure ->
                                            // Names the fix rather than the symptom: "permission
                                            // denied" tells someone nothing they can act on, and
                                            // being refused is the normal first outcome here.
                                            val detail =
                                                failure.message
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?.let { ": $it" }
                                                    .orEmpty()
                                            snackbars.show(
                                                context.getString(
                                                    R.string.report_root_refused,
                                                    detail,
                                                ),
                                                SnackbarTone.Failure,
                                            )
                                        }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.report_save_with_root))
                        }
                        Spacer(Modifier.height(10.dp))
                        // The adb route is a link rather than a printed one-liner: it only helps
                        // someone at a computer, and that reader is better served by the thread,
                        // where it sits in context and stays current.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.report_see_guide),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            OutlinedButton(onClick = { onOpenUrl(GUIDE_ISSUE_URL) }) {
                                Text("#$GUIDE_ISSUE")
                            }
                        }
                    }
                }
            }

        }
    }
}

/** One thing to try, with the control that does it. */
@Composable
private fun Step(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    titleAction: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            // A step whose whole action is one link does not need a line of its own for it.
            titleAction?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.let {
            Spacer(Modifier.height(10.dp))
            it()
        }
    }
}

/**
 * Copies the daemon's log folder somewhere the user can attach it, using root.
 *
 * The daemon chmods `/data/adb/lspd` to 0700 and owns it as root, so this is the only way to get
 * those files off a phone with no computer attached. Invoking `su` is also what raises the grant
 * prompt, so the first press is the request.
 *
 * Whether it works depends on how the manager is running. Parasitically it is inside
 * `com.android.shell`, which root managers usually trust already; installed as an ordinary app it
 * has its own uid and has to be allowed like anything else. A refusal is therefore an expected
 * outcome rather than an error, and [RootRefused] carries it back to be reported as an instruction.
 *
 * `tar` rather than a copy: the folder accumulates rotated parts of both logs, and the result is
 * going to be attached to an issue.
 */
private fun exportWithRoot(): Result<String> = runCatching {
    val target = "/sdcard/Download/${logArchiveName("tar.gz")}"
    val process =
        ProcessBuilder("su", "-c", "tar -czf $target -C /data/adb/lspd log && chmod 644 $target")
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() != 0) throw RootRefused(output)
    target
}

/** Root said no, or there is none. Carries whatever `su` had to say, which is often nothing. */
private class RootRefused(output: String) : Exception(output)

private const val NEO_ZYGISK = "https://github.com/JingMatrix/NeoZygisk"
private const val GUIDE_ISSUE = 123
private const val GUIDE_ISSUE_URL = "https://github.com/JingMatrix/Vector/issues/$GUIDE_ISSUE"

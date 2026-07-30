package org.matrix.vector.manager.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.BuildConfig
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import android.content.res.Configuration
import java.util.Locale
import org.matrix.vector.manager.R
import org.matrix.vector.manager.ui.components.FrameworkState
import org.matrix.vector.manager.data.log.CrashRecorder
import org.matrix.vector.manager.data.model.XposedApi
import org.matrix.vector.manager.data.repository.ManagerInstallStep
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.show
import kotlinx.coroutines.launch
import org.matrix.vector.manager.ui.theme.VectorMono

/**
 * Everything a bug report needs about this device, on one page.
 *
 * A row that reports a *problem* carries its explanation with it rather than just a red word — the
 * user of a root framework needs to know what broke and what it costs them, not merely that
 * something did. The whole page goes to the clipboard from the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemStatusScreen(
    onNavigateBack: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val device = viewModel.device
    val context = LocalContext.current
    val statusNotification by viewModel.statusNotification.collectAsStateWithLifecycle()
    val hiddenIcon by viewModel.hiddenIcon.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val managerInstall by viewModel.managerInstall.collectAsStateWithLifecycle()

    // Both the shortcut and the install can be undone from outside the app while it is open —
    // dragged off the home screen, uninstalled from Settings — so what the rows offer is re-read on
    // arrival rather than trusted from whenever the ViewModel was built.
    LaunchedEffect(Unit) { viewModel.refreshPresence() }

    val sections = buildSections(status, device, context)
    // The same page again, in English, for the clipboard.
    //
    // This text exists to be pasted into an issue, and the person reading it there is a maintainer
    // who may not read the language the reporter's phone is set to. Copying what is on screen is
    // the obvious behaviour and the wrong one: a status report in Vietnamese helps nobody triage
    // it, and the reporter cannot be expected to switch languages first. The screen stays in the
    // reader's language; the clipboard is for someone else.
    val englishSections =
        remember(status, device) {
            val english =
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                        setLocale(Locale.ENGLISH)
                    }
                )
            buildSections(status, device, english)
        }
    // Read once per visit rather than watched: a crash cannot be recorded while this screen is on
    // screen, because the process that would record it is the one drawing it.
    var crashes by remember { mutableStateOf(CrashRecorder.read(context)) }
    // The two switches below belong to the framework, so they are only live while it is.
    val daemonAlive = status.state != FrameworkState.Inactive
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    val shortcutRefused = stringResource(R.string.launcher_shortcut_refused)
    val installDone = stringResource(R.string.launcher_install_done)
    val installFailed = stringResource(R.string.launcher_install_failed)

    Scaffold(
        snackbarHost = { VectorSnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_status)) },
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
                            // Copied as it reads, headings and all — this text ends up pasted
                            // into an issue, where the grouping is as useful as it is on screen.
                            copy(
                                context,
                                englishSections.joinToString("\n\n") { (heading, items) ->
                                    heading +
                                        items.joinToString("") { "\n  ${it.label}: ${it.value}" }
                                },
                            )
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
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (status.issues.isNotEmpty()) {
                items(status.issues, key = { it.name }) { issue -> IssueCard(issue) }
                item { Spacer(Modifier.height(4.dp)) }
            }
            if (crashes != null) {
                item(key = "crashes") {
                    CrashCard(
                        report = crashes!!,
                        onCopy = {
                            copy(context, crashes!!)
                            scope.launch { snackbars.show(copied, SnackbarTone.Success) }
                        },
                        onClear = {
                            CrashRecorder.clear(context)
                            crashes = null
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            sections.forEach { (heading, items) ->
                item(key = "h:$heading") { SectionHeading(heading) }
                items(items, key = { it.label }) { row -> InfoRow(row) }
            }

            // Framework behaviour, set from the screen that reports on the framework.
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
            }
            item {
                FrameworkToggle(
                    title = stringResource(R.string.status_notification),
                    subtitle = stringResource(R.string.status_notification_summary),
                    checked = statusNotification,
                    enabled = daemonAlive,
                    onCheckedChange = viewModel::setStatusNotification,
                )
            }
            item {
                FrameworkToggle(
                    title = stringResource(R.string.force_launcher_icons),
                    subtitle = stringResource(R.string.force_launcher_icons_summary),
                    checked = hiddenIcon,
                    enabled = daemonAlive,
                    onCheckedChange = viewModel::setForcedLauncherIcons,
                )
            }

            // How to get back in. Only parasitically: installed, the manager has a launcher icon
            // like any other app and neither of these means anything.
            if (presence.parasitic) {
                item { SectionHeading(stringResource(R.string.launcher_section)) }
                item {
                    ActionRow(
                        title = stringResource(R.string.launcher_shortcut),
                        subtitle = stringResource(R.string.launcher_shortcut_summary),
                        action = stringResource(R.string.launcher_shortcut_create),
                        done = presence.shortcutPinned,
                        doneLabel = stringResource(R.string.launcher_shortcut_pinned),
                        // A launcher that refuses pin requests would take the tap and do nothing
                        // visible, so the row says so instead of offering a button that cannot work.
                        enabled = presence.shortcutSupported,
                        note =
                            if (presence.shortcutSupported) null
                            else stringResource(R.string.launcher_shortcut_unsupported),
                        onClick = {
                            if (!viewModel.requestShortcut()) {
                                scope.launch {
                                    snackbars.show(shortcutRefused, SnackbarTone.Failure)
                                }
                            }
                        },
                    )
                }
                item {
                    ActionRow(
                        title = stringResource(R.string.launcher_install),
                        subtitle = stringResource(R.string.launcher_install_summary),
                        action = stringResource(R.string.launcher_install_action),
                        done = presence.installed,
                        doneLabel = stringResource(R.string.launcher_installed),
                        // The APK comes from the daemon, so there is nothing to install without one.
                        enabled = daemonAlive && managerInstall !is ManagerInstallStep.Installing,
                        busy = managerInstall is ManagerInstallStep.Installing,
                        onClick = viewModel::installManagerApp,
                    )
                }
            }
        }
    }

    // Reported once per outcome, then acknowledged so a later visit does not replay it. The
    // platform's own status message is not shown: it is untranslated and phrased for a developer,
    // and it is already in the log for whoever needs it.
    LaunchedEffect(managerInstall) {
        val message =
            when (managerInstall) {
                is ManagerInstallStep.Done -> installDone to SnackbarTone.Success
                is ManagerInstallStep.Failed -> installFailed to SnackbarTone.Failure
                else -> return@LaunchedEffect
            }
        // Acknowledged first, and shown on the screen's own scope rather than this effect's:
        // acknowledging changes the state this effect is keyed on and so cancels it, and `show`
        // suspends for as long as the snackbar is up. The other order loses the message.
        viewModel.acknowledgeManagerInstall()
        scope.launch { snackbars.show(message.first, message.second) }
    }
}

/**
 * A row that does something, shaped like the ones above it that report something.
 *
 * The finished state replaces the button rather than disabling it: "Already on your home screen" is
 * the answer to the question the row poses, and a greyed-out "Create" leaves the reader working out
 * why it will not press.
 */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    action: String,
    done: Boolean,
    doneLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    busy: Boolean = false,
    /** Shown under the subtitle when the action cannot be offered, saying why. */
    note: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (note != null && !done) {
                Spacer(Modifier.height(2.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            done ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        doneLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.primary,
                    )
                }
            busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> TextButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}

@Composable
private fun IssueCard(issue: HealthIssue) {
    val (title, summary) =
        when (issue) {
            HealthIssue.SepolicyNotLoaded ->
                R.string.issue_sepolicy_title to R.string.issue_sepolicy_summary
            HealthIssue.SystemServerNotInjected ->
                R.string.issue_system_server_title to R.string.issue_system_server_summary
            HealthIssue.Dex2oatWrapperBroken ->
                R.string.issue_dex2oat_title to R.string.issue_dex2oat_summary
        }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The manager's own crashes, which nothing else on the device keeps.
 *
 * On this page rather than under Logs, because every log there is the daemon's and because this is
 * the page someone opens when they are about to report something. It previews the newest trace only
 * — the older ones are on file and travel with the copy — since the question being asked is "what
 * just happened", not "what has ever happened".
 *
 * The card is absent when there have been no crashes, which is the normal state and deserves no
 * row of its own.
 */
@Composable
private fun CrashCard(report: String, onCopy: () -> Unit, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val newest = remember(report) { report.trim().lines().take(CRASH_PREVIEW_LINES) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = colors.error)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(
                    stringResource(R.string.crash_recorded_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.crash_recorded_summary),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                newest.joinToString("\n"),
                style = VectorMono.copy(fontSize = 12.sp),
                color = colors.onSurfaceVariant,
                maxLines = CRASH_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.action_copy_all)) }
                TextButton(onClick = onClear) { Text(stringResource(R.string.crash_recorded_clear)) }
            }
        }
    }
}

/**
 * One fact, at a size meant to be read.
 *
 * The value is the size of body text rather than of a caption, because the value is what the page
 * is *for*. Monospace is kept for identifiers — versions, hashes, package names, ABIs, where
 * character-by-character comparison is the point — and dropped for words like "Loaded", which are
 * prose and read worse in it.
 *
 * A fact that can be good or bad says which by its colour, so the page answers "is anything wrong"
 * before it is read at all.
 */
@Composable
private fun InfoRow(row: InfoItem) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.value,
                style =
                    if (row.monospace) VectorMono.copy(fontSize = 15.sp)
                    else MaterialTheme.typography.bodyLarge,
                color =
                    when (row.health) {
                        Health.Good -> colors.primary
                        Health.Bad -> colors.error
                        Health.Neutral -> colors.onSurface
                    },
            )
        }
        if (row.health != Health.Neutral) {
            Icon(
                imageVector =
                    if (row.health == Health.Good) Icons.Rounded.CheckCircle
                    else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (row.health == Health.Good) colors.primary else colors.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Whether a fact is one that can be wrong, and whether it currently is. */
private enum class Health {
    Good,
    Bad,
    Neutral,
}

/** A row of the status page. */
private data class InfoItem(
    val label: String,
    val value: String,
    val health: Health = Health.Neutral,
    /** True where the value is an identifier to be compared character by character. */
    val monospace: Boolean = true,
)

/** A heading, so the page reads as three short lists rather than one long one. */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
    )
}

/**
 * The page's contents, in three groups.
 *
 * Grouped because they answer three different questions — what is running, is it working, and on
 * what — so a reader after one of them does not have to scan all ten rows to find it.
 */
private fun buildSections(
    status: FrameworkStatus,
    device: DeviceInfo,
    context: Context,
): List<Pair<String, List<InfoItem>>> {
    val unknown = "—"
    fun str(id: Int) = context.getString(id)
    return listOf(
        str(R.string.info_section_build) to
            listOf(
                InfoItem(
                    str(R.string.info_framework_version),
                    buildString {
                        append(status.versionLabel ?: unknown)
                        // The exact build, not just its number. Two builds share a version code
                        // whenever they sit at the same depth on different branches, so this names
                        // where the build came from as well as the commit: the repository for a CI
                        // build, the machine for a local one from a modified tree. That is what a
                        // bug report needs and what the number alone cannot give.
                        status.commit?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
                    },
                ),
                // Named separately from the framework, because they are flashed separately and are
                // not always the same build. When these two disagree, that is the answer to a whole
                // class of "it behaves oddly" reports.
                InfoItem(
                    str(R.string.info_manager_version),
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})  ·  ${BuildConfig.VERSION_HASH}",
                ),
                // Named by which scale the number is on. The two share a field and nothing else: 93
                // is a legacy Xposed API, 101 is a libxposed one, and calling both "Xposed API" is
                // how a reader ends up comparing versions that were never comparable.
                InfoItem(
                    str(
                        if (status.apiVersion?.let { XposedApi.isLibxposed(it) } == true)
                            R.string.info_api_version_libxposed
                        else R.string.info_api_version
                    ),
                    status.apiVersion?.toString() ?: unknown,
                ),
                InfoItem(str(R.string.info_manager_package), context.packageName),
            ),
        str(R.string.info_section_health) to
            listOf(
                InfoItem(
                    str(R.string.info_selinux),
                    str(
                        if (status.sepolicyLoaded) R.string.info_loaded
                        else R.string.info_not_loaded
                    ),
                    health = if (status.sepolicyLoaded) Health.Good else Health.Bad,
                    monospace = false,
                ),
                InfoItem(
                    str(R.string.info_system_server),
                    str(
                        if (status.systemServerInjected) R.string.info_injected
                        else R.string.info_not_injected
                    ),
                    health = if (status.systemServerInjected) Health.Good else Health.Bad,
                    monospace = false,
                ),
                InfoItem(
                    str(R.string.info_dex2oat),
                    dex2oatLabel(context, status.dex2oatCompatibility),
                    health =
                        if (status.dex2oatCompatibility == ILSPManagerService.DEX2OAT_OK)
                            Health.Good
                        else Health.Bad,
                    monospace = false,
                ),
            ),
        str(R.string.info_section_device) to
            listOf(
                InfoItem(
                    str(R.string.info_android),
                    "${device.androidRelease} (API ${device.sdkInt})",
                ),
                InfoItem(str(R.string.info_device), device.device, monospace = false),
                InfoItem(str(R.string.info_abi), device.abi),
            ),
    )
}

private fun dex2oatLabel(context: Context, compatibility: Int): String =
    context.getString(
        when (compatibility) {
            ILSPManagerService.DEX2OAT_OK -> R.string.info_supported
            ILSPManagerService.DEX2OAT_CRASHED -> R.string.info_dex2oat_crashed
            ILSPManagerService.DEX2OAT_MOUNT_FAILED -> R.string.info_dex2oat_mount_failed
            ILSPManagerService.DEX2OAT_SELINUX_PERMISSIVE ->
                R.string.info_dex2oat_selinux_permissive
            ILSPManagerService.DEX2OAT_SEPOLICY_INCORRECT ->
                R.string.info_dex2oat_sepolicy_incorrect
            else -> R.string.info_unsupported
        }
    )

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(BuildConfig.MANAGER_PACKAGE_NAME, text))
}

@Composable
private fun FrameworkToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    /**
     * False when there is no daemon to write to.
     *
     * These two are the framework's settings rather than the app's, and only the daemon can reach
     * either — one lives in its own preference store, the other in `Settings.Global`, which it
     * reads and writes as root. With no daemon there is nothing to read the state from and nothing
     * to write it to, so a live switch would show a value it invented and accept a change that went
     * nowhere. Dimmed and inert says the truth: the setting exists, and the thing that owns it is
     * not running.
     */
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(vertical = 10.dp)
                .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Enough of the newest trace to recognise it; the rest travels in the copy. */
private const val CRASH_PREVIEW_LINES = 6

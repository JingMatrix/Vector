package org.matrix.vector.manager.ui.screens.home

import android.os.Build
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
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Notifications
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.matrix.vector.ipc.IManagerService
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
import org.matrix.vector.manager.data.log.CrashRecorder
import org.matrix.vector.manager.data.model.ManagerCopy
import org.matrix.vector.manager.data.model.XposedApi
import org.matrix.vector.manager.data.log.CrashReport
import org.matrix.vector.manager.data.model.buildStamp
import org.matrix.vector.manager.data.repository.ManagerInstallStep
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.copyToClipboard
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
    onOpenCrash: () -> Unit,
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
    var crash by remember { mutableStateOf(CrashRecorder.newest(context)) }
    // The two switches below belong to the framework, so they are only live while it is.
    val daemonAlive = status.daemonUsable
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    val shortcutRefused = stringResource(R.string.launcher_shortcut_refused)
    val installDone = stringResource(R.string.launcher_install_done)

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
                            copyToClipboard(
                                context,
                                englishSections.joinToString("\n\n") { (heading, items) ->
                                    heading +
                                        items.joinToString("") {
                                            // With the detail, which the screen only sets apart
                                            // rather than shortens. Where a build came from is
                                            // half of what makes the stamp worth pasting.
                                            "\n  ${it.label}: ${it.value}${it.detail.orEmpty()}"
                                        }
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
            crash?.let { report ->
                item(key = "crashes") {
                    CrashCard(
                        report = report,
                        onOpenTrace = onOpenCrash,
                        onClear = {
                            CrashRecorder.clear(context)
                            crash = null
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
            // like any other app and none of this means anything.
            if (presence.parasitic) {
                item {
                    Spacer(Modifier.height(20.dp))
                    OpeningVectorCard(
                        presence = presence,
                        install = managerInstall,
                        daemonAlive = daemonAlive,
                        onCreateShortcut = {
                            if (!viewModel.requestShortcut()) {
                                scope.launch {
                                    snackbars.show(shortcutRefused, SnackbarTone.Failure)
                                }
                            }
                        },
                        onEnableNotification = { viewModel.setStatusNotification(true) },
                        onInstall = viewModel::installManagerApp,
                        onRemoveConflicting = viewModel::removeConflictingManager,
                    )
                }
            }
        }
    }

    // Success only. A failure stays on the card, where it can still be read by someone who was not
    // looking at this screen when it happened — which is the common case, since the install runs
    // while they are free to go elsewhere. Acknowledged first, and shown on the screen's own scope
    // rather than this effect's: acknowledging changes the state this effect is keyed on and so
    // cancels it, and `show` suspends for as long as the snackbar is up.
    LaunchedEffect(managerInstall) {
        if (managerInstall !is ManagerInstallStep.Done) return@LaunchedEffect
        viewModel.acknowledgeManagerInstall()
        scope.launch { snackbars.show(installDone, SnackbarTone.Success) }
    }
}

/**
 * The one card that answers "how do I open this again".
 *
 * A card rather than more rows, because these are not the settings the rows above are and did not
 * read as them: a switch is always the same width, so a column of switches lines up, while these
 * trailing controls were a long label, a spinner and a button — three different widths that left the
 * right-hand edge ragged and squeezed each description into a narrow column with nothing beside it.
 * The page already has this shape for "here is a situation, here is what to do about it": IssueCard
 * and CrashCard.
 *
 * One card rather than one per remedy, because the reader's question is not "should I pin a
 * shortcut" but "which of these do I have" — and each separate card would have to re-explain the
 * same situation before getting to its own answer.
 *
 * The two routes that need no setup are a closing note rather than rows: nothing can be done to
 * them, so a row with no control would be a row that only ever reports.
 */
@Composable
private fun OpeningVectorCard(
    presence: ManagerPresence,
    install: ManagerInstallStep,
    daemonAlive: Boolean,
    onCreateShortcut: () -> Unit,
    onEnableNotification: () -> Unit,
    onInstall: () -> Unit,
    onRemoveConflicting: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.launcher_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.launcher_body),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            RouteRow(
                icon = Icons.AutoMirrored.Rounded.AddToHomeScreen,
                label = stringResource(R.string.launcher_shortcut),
                done = presence.shortcutPinned,
                action = stringResource(R.string.launcher_shortcut_create),
                // A launcher that refuses pin requests would take the tap and do nothing visible,
                // so the row says so rather than offering a button that cannot work.
                enabled = presence.shortcutSupported,
                onClick = onCreateShortcut,
            )
            RouteRow(
                icon = Icons.Rounded.Notifications,
                // The same setting as the switch above, named the same, because it is the same
                // thing seen from the other question: there it is framework behaviour, here it is
                // a way in. Both read and write the daemon, so they cannot disagree.
                label = stringResource(R.string.status_notification),
                done = presence.notificationEnabled,
                action = stringResource(R.string.launcher_turn_on),
                enabled = daemonAlive,
                onClick = onEnableNotification,
            )
            RouteRow(
                icon = Icons.Rounded.InstallMobile,
                label = stringResource(R.string.launcher_install),
                // A copy of a different build counts as not done, and has to: a done row is a
                // check and nothing else, so marking it done would leave no way to replace it and
                // no spinner while it was being replaced. The row's own button then reads as a
                // reinstall rather than an install, which is what tells the two apart.
                done = presence.manager == ManagerCopy.Present,
                action =
                    stringResource(
                        if (presence.manager == ManagerCopy.Diverged)
                            R.string.launcher_install_reinstall
                        else R.string.launcher_install_action
                    ),
                // The APK comes from the daemon, so there is nothing to install without one.
                enabled = daemonAlive,
                busy = install is ManagerInstallStep.Installing,
                onClick = onInstall,
            )

            // Anything a row cannot say in its one line goes below all three, so that saying it
            // does not make one row taller than its neighbours.
            if (!presence.shortcutSupported && !presence.shortcutPinned) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.launcher_shortcut_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            // Said in the reader's own terms rather than left to a button reading "Reinstall" over
            // an already installed app, which on its own only looks redundant. Toned like the
            // framework's own "same number, different build" note and not like a failure: the
            // install worked, and what is installed opens — it is simply not this build.
            if (presence.manager == ManagerCopy.Diverged) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.launcher_install_diverged),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.tertiary,
                )
            }
            if (install is ManagerInstallStep.Failed) {
                Spacer(Modifier.height(8.dp))
                InstallFailure(install, onRemoveConflicting)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.launcher_note, SECRET_CODE, rootManagerName(presence)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * One way in: what it is, and whether this device has it.
 *
 * The state is an icon rather than a word. "Pinned", "on" and "installed" are three different words
 * for one fact — that this route is already available — and reading them as a column made three
 * identical answers look like three different ones.
 *
 * The height is fixed, and that is the whole point of the row existing as its own composable. What
 * sits on the right changes as the reader acts — a button becomes a check, or a spinner — and a
 * `TextButton` is 40dp tall against an icon's 20dp, so an unpinned row was visibly taller than a
 * pinned one and the card jumped every time a state flipped. Nothing here may wrap or stack for the
 * same reason: an explanation that needs a second line goes underneath all three rows instead.
 */
@Composable
private fun RouteRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    done: Boolean,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().height(ROUTE_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (done) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // Every trailing slot is either a 20dp icon or a compact button, so the edge stays straight
        // however the rows are filled in.
        when {
            done ->
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.launcher_route_available),
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
            busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> TextButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}

/**
 * Why the install did not happen, on the card rather than in a snackbar.
 *
 * A snackbar is shown once, to whoever is looking at that screen at that moment. The install can
 * finish while the reader is somewhere else — and parasitically the host process is killed often
 * enough that they may not even be in the app — so the outcome has to survive on the row that
 * offered it.
 */
@Composable
private fun InstallFailure(failure: ManagerInstallStep.Failed, onRemoveConflicting: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(
            stringResource(
                if (failure.signatureConflict) R.string.launcher_install_conflict
                else R.string.launcher_install_failed
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.error,
        )
        // Offered only for the one failure that has an answer. The old copy has to go before the
        // platform will accept this one, and it is removed for every user because a copy left in
        // another profile refuses the install just as loudly as one in this profile.
        if (failure.signatureConflict) {
            TextButton(onClick = onRemoveConflicting) {
                Text(stringResource(R.string.launcher_install_remove))
            }
        }
    }
}

/**
 * What to call the root manager in the closing note.
 *
 * Product names, so they are not translated. The generic fallback covers a daemon that does not
 * report one and the two answers that name no single implementation — nothing installed, or two of
 * them — where naming one would be a guess.
 */
@Composable
private fun rootManagerName(presence: ManagerPresence): String =
    when (presence.rootImplementation) {
        IManagerService.ROOT_MAGISK -> "Magisk"
        IManagerService.ROOT_KERNELSU -> "KernelSU"
        IManagerService.ROOT_APATCH -> "APatch"
        else -> stringResource(R.string.launcher_root_generic)
    }

/** Must match `SECRET_CODE` in the daemon's VectorService, which is what actually answers it. */
private const val SECRET_CODE = "*#*#832867#*#*"

/**
 * Every route row, whatever it currently shows.
 *
 * 48dp because that is the minimum touch target Material enforces on the button one of these rows
 * carries — so it is the tallest state any of them can take, and pinning the rest to it is what
 * stops the card resizing under the reader's finger.
 */
private val ROUTE_ROW_HEIGHT = 48.dp

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
 * the page someone opens when they are about to report something. It summarises the newest crash
 * only — the older ones are on file and travel with the log export — since the question being asked
 * is "what just happened", not "what has ever happened".
 *
 * Four facts, not a trace. This card sits among rows that each state one thing, and a block of
 * monospace here would be the only thing on the page a reader has to decode rather than read; the
 * trace has its own screen, one tap away, where it can be a list instead of a paragraph. The four
 * are chosen as the answers to what a maintainer asks first: what threw, what it said, the nearest
 * frame that is ours, and when. "Where" is the one worth having on the card at all — it is the
 * fact that decides who picks the report up, and it is buried in the middle of the printed trace.
 *
 * The card is absent when there have been no crashes, which is the normal state and deserves no
 * row of its own.
 */
@Composable
private fun CrashCard(report: CrashReport, onOpenTrace: () -> Unit, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
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
            Spacer(Modifier.height(12.dp))
            // The root cause rather than what reached the handler: "RuntimeException: Unable to
            // start activity" is the platform saying where it noticed, and the end of the chain is
            // the sentence that names what actually failed.
            report.root?.let { cause ->
                CrashFact(stringResource(R.string.crash_what), cause.simpleType, error = true)
                cause.message?.let { CrashFact(stringResource(R.string.crash_message), it) }
            }
            CrashFact(
                stringResource(R.string.crash_where),
                report.ours?.shortMethod ?: stringResource(R.string.crash_where_unknown),
                monospace = report.ours != null,
            )
            CrashFact(stringResource(R.string.crash_when), crashWhen(report))
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onOpenTrace) {
                    Text(stringResource(R.string.crash_open_trace))
                }
                TextButton(onClick = onClear) { Text(stringResource(R.string.crash_recorded_clear)) }
            }
        }
    }
}

/**
 * One line of the summary, laid out as the rows below it are: label above, fact underneath.
 *
 * Tighter than [InfoRow] because four of these sit inside a card rather than on the page, and
 * because none of them is a status anyone needs to spot from across the room.
 */
@Composable
private fun CrashFact(
    label: String,
    value: String,
    monospace: Boolean = false,
    error: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(
            value,
            style =
                if (monospace) VectorMono.copy(fontSize = 14.sp)
                else MaterialTheme.typography.bodyMedium,
            color = if (error) colors.error else colors.onSurface,
        )
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
 *
 * A row may end in a [InfoItem.detail], set smaller and in the muted colour. It is part of the same
 * value and stays in the same line — a reader copying a version out by hand still gets all of it —
 * but it is not what the row is looked up for. On the build rows that is where the stamp came from;
 * the commit is what someone comparing two devices reads, and the repository or machine after it is
 * two thirds of the characters and almost never the answer.
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
                text =
                    buildAnnotatedString {
                        append(row.value)
                        row.detail?.let { detail ->
                            val muted =
                                SpanStyle(fontSize = 12.sp, color = colors.onSurfaceVariant)
                            withStyle(muted) { append(detail) }
                        }
                    },
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
    /** The tail of the value that is context rather than identity; set apart, never dropped. */
    val detail: String? = null,
    val health: Health = Health.Neutral,
    /** True where the value is an identifier to be compared character by character. */
    val monospace: Boolean = true,
)

/**
 * A build row: the version number, the commit, and — set apart — where that build was made.
 *
 * The stamp leads with the commit and says where after it, so the split is the commit's own length
 * and needs no second opinion about which half is which. What is left includes the separator, which
 * is worth keeping visible: `-` is a repository that holds this exact commit and `+` is a machine
 * holding changes that no repository does.
 *
 * A stamp that names no commit — "unknown", from a build made where git could not be asked — is not
 * cut at all. It goes to the muted half whole, because none of it is an identifier.
 */
private fun buildRow(label: String, number: String, reported: String?): InfoItem {
    val stamp = reported?.takeIf { it.isNotBlank() } ?: return InfoItem(label, number)
    val commit = buildStamp(stamp).commit.orEmpty()
    return InfoItem(label, "$number  ·  $commit", detail = stamp.removePrefix(commit))
}

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
                // The exact build, not just its number. Two builds share a version code whenever
                // they sit at the same depth on different branches, so the stamp names where the
                // build came from as well as the commit: the repository for a CI build, the machine
                // for a local one from a modified tree. That is what a bug report needs and what
                // the number alone cannot give.
                buildRow(
                    str(R.string.info_framework_version),
                    status.versionLabel ?: unknown,
                    status.commit,
                ),
                // Named separately from the framework, because they are flashed separately and are
                // not always the same build. When these two disagree, that is the answer to a whole
                // class of "it behaves oddly" reports.
                buildRow(
                    str(R.string.info_manager_version),
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    BuildConfig.VERSION_HASH,
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
            listOfNotNull(
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
                // Omitted below Android 10, where there is no wrapper to report on: the daemon only
                // starts that machinery from Q and answers DEX2OAT_OK before then, so the row read
                // "Supported", in green, for a feature the device does not have. A reader chasing a
                // module that will not hook was being told this part was fine.
                if (device.sdkInt < Build.VERSION_CODES.Q) null
                else
                    InfoItem(
                        str(R.string.info_dex2oat),
                        dex2oatLabel(context, status.dex2oatWrapperState),
                        health =
                            if (status.dex2oatWrapperState == IManagerService.DEX2OAT_OK)
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

private fun dex2oatLabel(context: Context, state: Int): String =
    context.getString(
        when (state) {
            IManagerService.DEX2OAT_OK -> R.string.info_supported
            IManagerService.DEX2OAT_CRASHED -> R.string.info_dex2oat_crashed
            IManagerService.DEX2OAT_MOUNT_FAILED -> R.string.info_dex2oat_mount_failed
            IManagerService.DEX2OAT_SELINUX_PERMISSIVE -> R.string.info_dex2oat_selinux_permissive
            IManagerService.DEX2OAT_SEPOLICY_INCORRECT -> R.string.info_dex2oat_sepolicy_incorrect
            else -> R.string.info_unsupported
        }
    )

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


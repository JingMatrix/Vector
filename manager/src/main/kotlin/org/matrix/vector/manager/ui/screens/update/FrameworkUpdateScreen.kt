package org.matrix.vector.manager.ui.screens.update

import org.matrix.vector.manager.data.repository.FrameworkUpdateState
import org.matrix.vector.manager.data.repository.divergesFrom
import androidx.compose.foundation.clickable
import org.matrix.vector.manager.ui.theme.currentLocale
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.ui.components.SheetHeading
import org.matrix.vector.manager.ui.components.sheetRowColors
import org.matrix.vector.manager.data.repository.ReleaseDirection
import org.matrix.vector.manager.data.github.FrameworkRelease
import java.util.Date
import java.text.DateFormat
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.History
import org.matrix.vector.manager.data.github.ZipVariant
import org.matrix.vector.manager.data.github.CanaryArtifact
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.repository.FlashStep
import org.matrix.vector.manager.ui.screens.repo.StoreHtmlPane
import org.matrix.vector.manager.ui.theme.VectorLogLine

/**
 * What is in the update, and what happened when it was installed.
 *
 * One screen for both because they are one act with a pause in it: the reader is deciding whether
 * to flash, and then watching the flash. Splitting them would mean navigating away from the notes
 * at the moment they become most relevant — when the installer complains about something.
 *
 * The output pane is the same monospace treatment the Logs panel uses, because it is the same kind
 * of thing and a reader who has seen one should recognise the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameworkUpdateScreen(
    /** The build to open on, when the caller had one in mind. Null means the screen's own choice. */
    openOnVersionCode: Long? = null,
    onNavigateBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    viewModel: FrameworkUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    // Before the list has loaded, which is the point: the pin is a number, and it is resolved
    // against the history whenever that arrives.
    LaunchedEffect(openOnVersionCode) { openOnVersionCode?.let(viewModel::select) }
    val update by viewModel.update.collectAsStateWithLifecycle()
    val flash by viewModel.flash.collectAsStateWithLifecycle()
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val chosenZip by viewModel.chosenZip.collectAsStateWithLifecycle()
    val root by viewModel.root.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val direction by viewModel.direction.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var versionsOpen by remember { mutableStateOf(false) }
    // The screen is about whichever release is selected, which defaults to the update when there
    // is one and to the newest known build otherwise — so the page has something to show and
    // something to do even when everything is up to date.
    val release = selected

    if (versionsOpen) {
        VersionsSheet(
            history = update.history,
            update = update,
            selected = selected,
            onSelect = viewModel::select,
            onDismiss = { versionsOpen = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = release?.title ?: stringResource(R.string.update_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        if (release != null) {
                            Text(
                                text =
                                    stringResource(
                                        if (release.isCanary) R.string.update_channel_canary
                                        else R.string.update_channel_release
                                    ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (update.history.size > 1) {
                        IconButton(onClick = { versionsOpen = true }) {
                            Icon(
                                Icons.Rounded.History,
                                contentDescription = stringResource(R.string.update_versions),
                            )
                        }
                    }
                    release?.htmlUrl?.let { url ->
                        IconButton(onClick = { onOpenUrl(url) }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = stringResource(R.string.store_open_release),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            UpdateBar(
                zips = release?.zips.orEmpty(),
                chosen = chosenZip,
                onChoose = viewModel::chooseVariant,
                direction = direction,
                diverged = update.divergesFrom(selected),
                canFlash = chosenZip?.downloadUrl != null && root.canFlash,
                // Null unless root itself is the obstacle; "nothing to install" is not a root
                // problem and must not borrow its sentence.
                rootLabel = root.label(),
                flash = flash,
                onFlash = { viewModel.flash() },
                onCancelDownload = viewModel::cancelDownload,
                onReboot = { scope.launch { viewModel.reboot() } },
                onDismiss = viewModel::acknowledge,
            )
        },
    ) { padding ->
        // Decided per step rather than "log as soon as anything starts": a download produces no
        // installer output, so switching at the start of one would leave a full-height empty box
        // for the whole transfer. The notes are the most relevant thing there is to read while the
        // release they describe is being fetched.
        val showLog = flash !is FlashStep.Idle && (flash !is FlashStep.Downloading || lines.isNotEmpty())

        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!showLog) {
                val html =
                    remember(release?.notesMarkdown) {
                        release?.notesMarkdown?.takeIf { it.isNotBlank() }?.let {
                            releaseMarkdownToHtml(it)
                        }
                    }
                when {
                    html != null ->
                        StoreHtmlPane(
                            html = html,
                            modifier = Modifier.fillMaxSize(),
                            onOpenUrl = onOpenUrl,
                        )
                    release == null -> Empty(stringResource(R.string.update_none))
                    else -> Empty(stringResource(R.string.update_no_notes))
                }
            } else {
                InstallLog(lines, terminal = flash is FlashStep.Done || flash is FlashStep.Failed)
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The installer's output, live.
 *
 * Auto-follows the tail, because the interesting line during a flash is always the newest one, and
 * a reader who has scrolled up is a reader reading something — so it only follows while the list is
 * already at the bottom.
 */
@Composable
private fun InstallLog(lines: List<String>, terminal: Boolean) {
    val state = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        // Only while the tail is already in view: following unconditionally would yank a reader
        // who has scrolled up to study an earlier line back to the bottom on every line the
        // installer prints.
        val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= lines.size - 2) state.animateScrollToItem(lines.lastIndex)
    }

    // The daemon narrates its own failures, so this is rare — but a flash can still end with
    // nothing in hand if the binder dies mid-install. An empty box would read as "still working"
    // at exactly the moment the reader needs to know it stopped.
    if (lines.isEmpty() && terminal) {
        Empty(stringResource(R.string.update_no_output))
        return
    }

    LazyColumn(
        state = state,
        modifier =
            Modifier.fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(lines) { line ->
            Text(
                text = line,
                style = VectorLogLine,
                color = MaterialTheme.colorScheme.onSurface,
                // Installers print progress bars and paths that are wider than any phone; wrapping
                // them turns one line of output into four and makes the log unreadable.
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun UpdateBar(
    zips: List<CanaryArtifact>,
    chosen: CanaryArtifact?,
    onChoose: (ZipVariant) -> Unit,
    direction: ReleaseDirection,
    diverged: Boolean,
    canFlash: Boolean,
    rootLabel: String?,
    flash: FlashStep,
    onFlash: () -> Unit,
    onCancelDownload: () -> Unit,
    onReboot: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        when (flash) {
            is FlashStep.Downloading -> {
                // The one step of a flash that comes with a way out. A release zip is tens of
                // megabytes and the reader may be paying for them; more to the point, a download
                // left to finish goes straight on to flash the build they have just decided
                // against, and until this row had a button on it the only way to stop that was to
                // force stop the app. The button names the download rather than saying "Cancel",
                // because the installer that follows genuinely cannot be called off and a bare
                // "Cancel" sitting on this bar would read as an offer to do that too.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text =
                            stringResource(
                                R.string.update_downloading,
                                formatSize(flash.bytes),
                                formatSize(flash.total),
                            ),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.update_cancel_download))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (flash.total > 0) {
                    LinearProgressIndicator(
                        progress = { flash.bytes.toFloat() / flash.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            FlashStep.Flashing ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.update_flashing),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            // Both finished rows are put away by tapping them, the same press the modules list
            // takes on its own settled update line. The flash now outlives the screen, so a result
            // stays up until somebody reads it — and a build that was installed and not restarted
            // straight away would otherwise sit on the bar for the life of the process, with the
            // variant picker and the Install button behind it.
            FlashStep.Done ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.update_done),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.update_reboot_why),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = onReboot) {
                        Icon(
                            Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.update_reboot))
                    }
                }
            is FlashStep.Failed ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.error)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = failureText(flash.code),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onFlash) { Text(stringResource(R.string.retry)) }
                }
            FlashStep.Idle ->
                Column {
                    // Above the button and only while idle: it is the question the button answers,
                    // and once a flash is running the choice has already been made.
                    VariantPicker(zips = zips, chosen = chosen, onChoose = onChoose)
                    // Only when root is genuinely the problem. Tying this to the button's disabled
                    // state instead would tell a perfectly rooted device with nothing to install
                    // that it had no root.
                    if (rootLabel != null) {
                        Text(
                            text = rootLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    // Same number, different build. Worth saying plainly, because the number is
                    // the only thing the rest of the screen compares by and it is not enough: the
                    // version code is a commit count on master, so a branch build wears the same
                    // one as the release it was never built from.
                    if (diverged) {
                        Text(
                            text = stringResource(R.string.update_diverged),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.tertiary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (direction == ReleaseDirection.Older) {
                        // Specific, not a vague caution: the daemon's onDowngrade wipes the module
                        // database whenever the schema on disk is newer than the build being
                        // installed, and the manager cannot tell from a release list whether it
                        // moved. Naming the consequence — and the backup that prevents it — is the
                        // only useful thing to say.
                        Text(
                            text = stringResource(R.string.update_rollback_warning),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.tertiary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = onFlash,
                        enabled = canFlash,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Rounded.SystemUpdateAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        // The button says what it will actually do. "Install" on a build older
                        // than the running one is not wrong so much as unhelpful — the word is
                        // the last chance to notice you picked the wrong row.
                        Text(
                            stringResource(
                                when (direction) {
                                    ReleaseDirection.Newer -> R.string.update_install
                                    ReleaseDirection.Installed -> R.string.update_reinstall
                                    ReleaseDirection.Older -> R.string.update_rollback
                                }
                            )
                        )
                    }
                }
        }
    }
}

@Composable
private fun failureText(code: Int): String =
    when (code) {
        ILSPManagerService.INSTALL_NO_ROOT -> stringResource(R.string.update_no_root)
        ILSPManagerService.INSTALL_NOT_EXECUTED -> stringResource(R.string.update_failed_start)
        ILSPManagerService.INSTALL_NO_SUCH_FILE -> stringResource(R.string.update_failed_download)
        else -> stringResource(R.string.update_failed_exit, code)
    }

private fun formatSize(bytes: Long): String =
    when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f kB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

/**
 * Which of the release's builds to install.
 *
 * CI publishes two zips per release, Release and Debug, and the choice has to be the reader's: the
 * troubleshooting screen tells them a debug build is what maintainers need to help, so the app has
 * to be able to install one.
 *
 * A segmented row rather than checkboxes, because this is one-of-two rather than on-and-off: two
 * checkboxes permit neither and both, and a single "install the debug build" makes the ordinary
 * choice an unlabelled absence. It is the same control the theme selector uses, for the same
 * reason — the shared outline says "it is one of these".
 *
 * The size sits on each segment because it is the part of the decision that is otherwise a
 * surprise, and it is the size the release itself reports rather than an assumption about which
 * build is bigger. The line beneath says what the choice means and changes with it, so the answer
 * arrives at the moment the question is asked.
 *
 * One zip means no control at all — nobody should be asked to choose between one thing — and an
 * unrecognised name keeps its own file name rather than being labelled as something it may not be.
 */
@Composable
private fun VariantPicker(
    zips: List<CanaryArtifact>,
    chosen: CanaryArtifact?,
    onChoose: (ZipVariant) -> Unit,
) {
    if (zips.size < 2) return
    val colors = MaterialTheme.colorScheme
    // Release first, whatever order the release listed its assets in, so the default sits under the
    // reader's thumb rather than wherever GitHub happened to put it.
    val ordered = zips.sortedBy { it.variant.ordinal }

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ordered.forEachIndexed { index, zip ->
            SegmentedButton(
                selected = zip.name == chosen?.name,
                onClick = { onChoose(zip.variant) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ordered.size),
                icon = {},
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text =
                            when (zip.variant) {
                                ZipVariant.Release -> stringResource(R.string.update_variant_release)
                                ZipVariant.Debug -> stringResource(R.string.update_variant_debug)
                                ZipVariant.Other -> zip.name
                            },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatSize(zip.sizeInBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val why =
        when (chosen?.variant) {
            ZipVariant.Debug -> stringResource(R.string.update_variant_debug_why)
            ZipVariant.Release -> stringResource(R.string.update_variant_release_why)
            else -> null
        }
    if (why != null) {
        Spacer(Modifier.height(6.dp))
        // Crossfaded so the sentence reads as the answer to the tap that just happened rather
        // than as text that was always there.
        AnimatedContent(targetState = why, label = "variant") { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * How much of a version row the status on its right may take.
 *
 * Wide enough for "Installed" and "Older" on one line in every language shipped, and narrow enough
 * that what is left still holds a build's name and its date without wrapping on a phone. The
 * divergence clause is longer than that and wraps inside this width, which is the point of fixing
 * it: one row's wordier status must not move where the next row's name begins.
 */
private val STATUS_WIDTH = 96.dp

/**
 * Every build on this channel, so "no update available" is not a dead end.
 *
 * The same list that answers "is there anything newer" also answers "what could I go back to",
 * and the second question is the one people ask after a build breaks something for them.
 *
 * The installed build is marked rather than hidden: it is the reference point every other row is
 * read against, and removing it would leave the reader counting positions to work out where they
 * are.
 *
 * "Installed" is decided by the same rule the bar above uses, not by the version number alone. That
 * number is `git rev-list --count` over master, so a build from a branch and a build from master at
 * the same depth wear it identically, and a locally built one may not come from any published
 * commit at all. Marking on the number alone would put a confident "Installed" against a row the
 * bar two lines up is calling divergent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionsSheet(
    history: List<FrameworkRelease>,
    update: FrameworkUpdateState,
    selected: FrameworkRelease?,
    onSelect: (FrameworkRelease) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val colors = MaterialTheme.colorScheme
    val locale = currentLocale()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LocalizedOverlay {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SheetHeading(stringResource(R.string.update_versions), Icons.Rounded.History)
                history.forEach { release ->
                    val sameNumber = release.versionCode == update.installedVersionCode
                    // A build that carries this number but was not made from this release: another
                    // branch, or a working tree with changes in it.
                    val diverged = update.divergesFrom(release)
                    val installed = sameNumber && !diverged
                    val older = release.versionCode < update.installedVersionCode
                    ListItem(
                        modifier =
                            Modifier.clickable {
                                onSelect(release)
                                onDismiss()
                            },
                        // One line each, always. What names the build is the same shape in every
                        // row — "Vector v2.0 canary 3060", then its date and channel — and a row
                        // that wraps because of what is beside it reads as a different kind of
                        // entry when it is not.
                        headlineContent = {
                            Text(release.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                        DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                                            .format(Date(release.epochSeconds * 1000)),
                                        if (release.isCanary) {
                                            stringResource(R.string.update_channel_canary)
                                        } else {
                                            stringResource(R.string.update_channel_release)
                                        },
                                    )
                                    .joinToString("  ·  "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            // The one that is running, marked the way the activity feed marks the
                            // commit you are on — a filled dot against hollow ones.
                            Icon(
                                if (installed) Icons.Rounded.RadioButtonChecked
                                else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint =
                                    when {
                                        installed -> colors.primary
                                        // Not filled and not the accent: this row is where the
                                        // reader *appears* to be and is not.
                                        diverged -> colors.tertiary
                                        release.versionCode == selected?.versionCode ->
                                            colors.onSurface
                                        else -> colors.outline
                                    },
                            )
                        },
                        // A column of its own width, kept even when this row has nothing to say.
                        // The status is one word on most rows and a whole clause on the divergent
                        // one; sized to its content it would take the room the build's name needs
                        // and wrap that row alone, and a slot that disappears when empty would
                        // start each row's name in a different place. The label wraps inside its
                        // column instead, where it costs nothing: three lines of it are still
                        // shorter than the two the name and its date already occupy.
                        trailingContent = {
                            val label =
                                when {
                                    installed -> stringResource(R.string.update_installed)
                                    diverged -> stringResource(R.string.update_same_number)
                                    older -> stringResource(R.string.update_older)
                                    else -> null
                                }
                            Box(
                                modifier = Modifier.width(STATUS_WIDTH),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                if (label != null) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.End,
                                        color =
                                            when {
                                                installed -> colors.primary
                                                diverged -> colors.tertiary
                                                else -> colors.onSurfaceVariant
                                            },
                                    )
                                }
                            }
                        },
                        colors = sheetRowColors,
                    )
                }
            }
        }
    }
}

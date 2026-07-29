package org.matrix.vector.manager.ui.screens.modules

import org.matrix.vector.manager.ui.screens.modules.ScopeViewModel.Companion.SYSTEM_FRAMEWORK_PACKAGE
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.vector.ImageVector
import org.matrix.vector.manager.ui.components.ChoiceRow
import org.matrix.vector.manager.ui.components.SheetAction
import org.matrix.vector.manager.ui.components.SheetHeading
import org.matrix.vector.manager.ui.components.ToggleRow
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.matrix.vector.manager.ui.components.VectorAlertDialog
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.model.AppInfo
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.components.AppIcon
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.show
import org.matrix.vector.manager.ui.components.PackageActionResult
import org.matrix.vector.manager.ui.components.PackageActionSheet
import org.matrix.vector.manager.ui.components.SearchField
import org.matrix.vector.manager.ui.theme.VectorMono

class ScopeViewModelFactory(private val packageName: String, private val userId: Int) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ScopeViewModel(
            modulePackageName = packageName,
            userId = userId,
            daemonClient = ServiceLocator.daemon,
            appRepository = ServiceLocator.apps,
            moduleRepository = ServiceLocator.modules,
            packageManager = ServiceLocator.context.packageManager,
        )
            as T
}

/**
 * Which apps a module may hook.
 *
 * The screen's shape follows from one fact: **a scope is written whole, never incrementally.** The
 * daemon deletes every scope row of the module, writes the new set and rebuilds its configuration,
 * so sending that on each tap would mean ten rewrites to tick ten apps. Edits are therefore a
 * draft the user builds up, and applying is a deliberate act with its size stated — *3 to add, 1
 * to remove*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeScreen(
    packageName: String,
    userId: Int,
    onNavigateBack: () -> Unit,
    viewModel: ScopeViewModel = viewModel(factory = ScopeViewModelFactory(packageName, userId)),
) {
    val apps by viewModel.filteredApps.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pending by viewModel.pendingChanges.collectAsStateWithLifecycle()
    val applying by viewModel.applying.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showSystem by viewModel.showSystemApps.collectAsStateWithLifecycle()
    val showGames by viewModel.showGames.collectAsStateWithLifecycle()
    val recommendedOnly by viewModel.showRecommendedOnly.collectAsStateWithLifecycle()
    val showModules by viewModel.showModules.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sort.collectAsStateWithLifecycle()
    val reversed by viewModel.reverseSort.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val hasCompanion by viewModel.hasCompanion.collectAsStateWithLifecycle()

    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scopeSaved = stringResource(R.string.scope_backup_done)
    val scopeFailed = stringResource(R.string.scope_backup_failed)

    fun report(result: PackageActionResult) {
        val text =
            result.argument?.let { context.getString(result.messageRes, it) }
                ?: context.getString(result.messageRes)
        scope.launch { snackbars.show(text, result.tone) }
    }

    val scopeBackupLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) {
                viewModel.backupScopeTo(uri) { ok ->
                    scope.launch {
                        if (ok) snackbars.show(scopeSaved, SnackbarTone.Success)
                        else snackbars.show(scopeFailed, SnackbarTone.Failure)
                    }
                }
            }
        }

    val scopeRestoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.restoreScopeFrom(uri) { ok ->
                    if (!ok) scope.launch { snackbars.show(scopeFailed, SnackbarTone.Failure) }
                }
            }
        }
    val haptics = LocalHapticFeedback.current
    var confirmStranded by remember { mutableStateOf(false) }
    val frameworkRestartNeeded by viewModel.frameworkRestartNeeded.collectAsStateWithLifecycle()

    val staticScopeNotice = stringResource(R.string.scope_static)
    val applied = stringResource(R.string.scope_applied)
    val applyFailed = stringResource(R.string.scope_apply_failed)
    val toggleFailed = stringResource(R.string.scope_toggle_failed)
    val nothingToOpen = stringResource(R.string.action_no_launcher)

    LaunchedEffect(message) {
        val text =
            when (message) {
                ScopeMessage.Applied -> applied
                ScopeMessage.ApplyFailed -> applyFailed
                ScopeMessage.ToggleFailed,
                ScopeMessage.IncludeNewAppsFailed -> toggleFailed
                ScopeMessage.NothingToOpen -> nothingToOpen
                null -> null
            }
        if (text != null) {
            haptics.performHapticFeedback(
                if (message == ScopeMessage.Applied) HapticFeedbackType.Confirm
                else HapticFeedbackType.Reject
            )
            snackbars.show(
                text,
                if (message == ScopeMessage.Applied) SnackbarTone.Success else SnackbarTone.Failure,
            )
            viewModel.consumeMessage()
        }
    }

    // Leaving a module enabled with nothing to hook does nothing at all but looks like it works.
    fun attemptBack() {
        if (viewModel.wouldStrandModule()) confirmStranded = true else onNavigateBack()
    }

    // The gesture leaves this screen exactly as the arrow does, so it asks the same question
    // first. Declared here it wins over the navigator's own back handling.
    BackHandler { attemptBack() }

    Scaffold(
        topBar = {
            // One line: back, who this is about, and the switch. A large two-line bar would spend
            // a fifth of the screen restating a name the user has just tapped, on a screen whose
            // whole job is a long list.
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.moduleName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            // The column is a fixed slice of one row, and module names are not.
                            // Rather than truncate the end of a name — often exactly the part that
                            // distinguishes two builds of the same module — it scrolls itself.
                            //
                            // Finite, not endless: this is a screen someone sits on while working
                            // through a long list, and a title that never stops moving is a
                            // distraction. It says its piece and settles.
                            modifier = Modifier.basicMarquee(iterations = 3),
                        )
                        Text(
                            text = packageName,
                            style = VectorMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            // A package name is read from both ends: the head says who publishes
                            // it, the tail says which one it is. Ellipsising the middle keeps both
                            // — "org.matrix…chromext" — where cutting the end would throw away the
                            // only part that distinguishes it. Static, so the line above is the
                            // only thing on this bar that ever moves.
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::attemptBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    // The master switch, in the bar rather than in a card competing with the app
                    // list: it is the single most consequential control on the screen. What an
                    // overflow menu would hold here lives in the search field instead, next to the
                    // list it acts on.
                    Switch(
                        checked = state.isEnabled,
                        onCheckedChange = { enable ->
                            haptics.performHapticFeedback(
                                if (enable) HapticFeedbackType.ToggleOn
                                else HapticFeedbackType.ToggleOff
                            )
                            viewModel.setModuleEnabled(enable)
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        snackbarHost = { VectorSnackbarHost(snackbars) },
        // The module's own screen, in the corner rather than in the bar. The bar holds what the
        // screen *is* — whose scope, and whether it runs — and this is a departure from it: it
        // leaves for somewhere else.
        floatingActionButton = {
            // Only when there is something behind it. A module with no companion and no launcher
            // entry — which is most of them — would otherwise carry a button whose whole function
            // is to report that it has nothing to do.
            if (hasCompanion == true) {
                FloatingActionButton(onClick = viewModel::openModule) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Launch,
                        contentDescription = stringResource(R.string.action_open_companion),
                    )
                }
            }
        },
        bottomBar = {
            // Appears only when the draft differs from what the daemon holds, so the count is
            // stated exactly when there is one.
            AnimatedVisibility(
                visible = pending.any,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                ApplyBar(
                    added = pending.added,
                    removed = pending.removed,
                    applying = applying,
                    onDiscard = viewModel::discard,
                    onApply = viewModel::apply,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChange = { viewModel.searchQuery.value = it },
                placeholder = stringResource(R.string.scope_search_hint),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                // Everything that changes *what the list shows or contains* lives here, beside
                // the list it acts on, rather than behind an overflow menu in the title bar.
                ScopeSelectMenu(
                    hasRecommended = !state.recommended.isEmpty,
                    includeNewApps = state.includeNewApps,
                    onUseRecommended = viewModel::useRecommended,
                    onSelectAll = viewModel::selectAllVisible,
                    onSelectNone = viewModel::clearAllVisible,
                    onIncludeNewApps = viewModel::setIncludeNewApps,
                    onBackup = { scopeBackupLauncher.launch("$packageName-scope.json") },
                    onRestore = { scopeRestoreLauncher.launch(arrayOf("*/*")) },
                )
                ScopeFilterMenu(
                    showSystem = showSystem,
                    showGames = showGames,
                    showModules = showModules,
                    hasRecommended = !state.recommended.isEmpty,
                    recommendedOnly = recommendedOnly,
                    onToggleRecommendedOnly = {
                        viewModel.setRecommendedOnly(!recommendedOnly)
                    },
                    locked = state.recommended.staticScope,
                    onLockedClick = { scope.launch { snackbars.show(staticScopeNotice) } },
                    onToggleSystem = { viewModel.showSystemApps.value = !showSystem },
                    onToggleGames = { viewModel.showGames.value = !showGames },
                    onToggleModules = { viewModel.setShowModules(!showModules) },
                )
                ScopeSortMenu(
                    sort = sortOrder,
                    reversed = reversed,
                    onSort = viewModel::setSort,
                    onReverse = viewModel::toggleReverse,
                )
            }

            Spacer(Modifier.height(10.dp))

            // Every installed package, with its label and its icon, read through the package
            // manager: on a phone with a few hundred of them that is a visible wait, and without
            // this there is no way to tell a slow load from a filter that has matched nothing.
            //
            // Held until the load *finishes*, not merely until there is something to draw. The app
            // list is published early and the saved scope arrives after it, so a list drawn in
            // between is in the wrong order, and the re-sort that follows inserts the scope's own
            // rows above whatever LazyColumn had anchored — the screen opens part-way down, with
            // the module's targets scrolled off the top, which reads as though they are not there.
            if (state.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (apps.isEmpty()) {
                ScopeEmptyState()
                return@Column
            }

            // Pinned to the top until the reader scrolls, rather than scrolled to the top once.
            //
            // The list is computed on a background dispatcher and lags the loading flag, so the
            // first thing drawn is the previous emission — the one built before the saved scope
            // arrived, without the scope's own rows at its head. When the real list lands it
            // prepends them, and `items` being keyed means LazyColumn holds the row it had
            // anchored and lets the new ones appear above it: the screen opens exactly one
            // scope's-worth of rows down. Scrolling once on arrival cannot fix that, because on
            // arrival there is nothing yet to scroll past.
            //
            // So every change to the head of the list re-pins, until a drag says the reader has
            // taken over. A drag rather than any scroll, because the pin itself is a scroll.
            var readerHasScrolled by remember(packageName, userId) { mutableStateOf(false) }
            LaunchedEffect(listState) {
                listState.interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Start) readerHasScrolled = true
                }
            }
            val headKey = apps.firstOrNull()?.let { "${it.packageName}:${it.userId}" }
            LaunchedEffect(headKey) { if (!readerHasScrolled) listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(apps, key = { "${it.packageName}:${it.userId}" }) { app ->
                    AppRow(
                        app = app,
                        enabled = !state.recommended.staticScope,
                        origin =
                            when {
                                state.recommended.staticScope && app.isRecommended ->
                                    ScopeOrigin.Locked
                                app.isRecommended -> ScopeOrigin.Requested
                                else -> ScopeOrigin.Chosen
                            },
                        sharedNote =
                            app.packageName == SYSTEM_FRAMEWORK_PACKAGE && state.multipleUsers,
                        onToggle = { checked ->
                            haptics.performHapticFeedback(
                                if (checked) HapticFeedbackType.ToggleOn
                                else HapticFeedbackType.ToggleOff
                            )
                            viewModel.toggle(app, checked)
                        },
                        onAction = ::report,
                    )
                }
            }
        }
    }

    // Asked after the apply has already succeeded, so it is not a confirmation — the scope is
    // stored either way. It exists because system_server is the one target that cannot pick a
    // scope up by itself.
    if (frameworkRestartNeeded) {
        VectorAlertDialog(
            onDismissRequest = { viewModel.dismissFrameworkRestart() },
            icon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) },
            title = { Text(stringResource(R.string.scope_framework_restart_title)) },
            text = { Text(stringResource(R.string.scope_framework_restart_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.softRebootForFramework() }) {
                    Text(
                        stringResource(R.string.action_soft_reboot),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissFrameworkRestart() }) {
                    Text(stringResource(R.string.scope_framework_restart_later))
                }
            },
        )
    }

    if (confirmStranded) {
        VectorAlertDialog(
            onDismissRequest = { confirmStranded = false },
            title = { Text(stringResource(R.string.scope_empty_title)) },
            text = { Text(stringResource(R.string.scope_empty_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setModuleEnabled(false)
                        confirmStranded = false
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.scope_empty_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStranded = false }) {
                    Text(stringResource(R.string.scope_empty_keep))
                }
            },
        )
    }
}

/**
 * Everything that changes the *selection*, in the search field's trailing slot.
 *
 * A sheet rather than a dropdown, as elsewhere in the app: these entries are sentences, not words.
 * "Sélectionner tout ce qui est affiché" does not fit the width a menu gives itself, so in French
 * every second row wraps and the menu reads as a paragraph. A sheet has the full width, and it can
 * carry the leading icons that tell an action from a setting.
 */
@Composable
private fun ScopeSelectMenu(
    hasRecommended: Boolean,
    includeNewApps: Boolean,
    onUseRecommended: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onIncludeNewApps: (Boolean) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.Rounded.Checklist,
            contentDescription = stringResource(R.string.scope_select),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        ScopeSheet(
            stringResource(R.string.scope_select),
            Icons.Rounded.Checklist,
            { open = false },
        ) {
            if (hasRecommended) {
                SheetAction(
                    title = stringResource(R.string.scope_use_recommended),
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = {
                        onUseRecommended()
                        open = false
                    },
                )
            }
            SheetAction(
                title = stringResource(R.string.scope_select_visible),
                icon = Icons.Rounded.DoneAll,
                onClick = {
                    onSelectAll()
                    open = false
                },
            )
            SheetAction(
                title = stringResource(R.string.scope_clear_visible),
                icon = Icons.Rounded.RemoveDone,
                onClick = {
                    onSelectNone()
                    open = false
                },
            )
            // The one entry in this sheet that changes the *future* of the scope rather than its
            // present, so its label says exactly that and not something narrower.
            ToggleRow(
                title = stringResource(R.string.scope_include_new_apps),
                subtitle = stringResource(R.string.scope_include_new_apps_summary),
                icon = Icons.Rounded.PlaylistAdd,
                checked = includeNewApps,
                onCheckedChange = onIncludeNewApps,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // This module's scope alone, separate from the whole-list backup on the module
            // screen — useful when moving one module's configuration between devices.
            SheetAction(
                title = stringResource(R.string.scope_backup),
                icon = Icons.Rounded.SaveAlt,
                onClick = {
                    onBackup()
                    open = false
                },
            )
            SheetAction(
                title = stringResource(R.string.scope_restore),
                icon = Icons.Rounded.SettingsBackupRestore,
                onClick = {
                    onRestore()
                    open = false
                },
            )
        }
    }
}

/**
 * What the list *contains*.
 *
 * All three were in the legacy manager and all three earn their place: system apps are usually
 * noise but occasionally the target, games are bulk, and other modules are installed apps that are
 * rarely what you are hooking.
 *
 * Chips rather than rows: these are short, all of one kind, and several are on at once — which a
 * column of ticks states less clearly than a row of filled chips.
 */
@Composable
private fun ScopeFilterMenu(
    showSystem: Boolean,
    showGames: Boolean,
    showModules: Boolean,
    hasRecommended: Boolean,
    recommendedOnly: Boolean,
    onToggleRecommendedOnly: () -> Unit,
    locked: Boolean,
    onLockedClick: () -> Unit,
    onToggleSystem: () -> Unit,
    onToggleGames: () -> Unit,
    onToggleModules: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Anything other than the defaults must not be silent — and "other than the defaults" is the
    // point, because the defaults themselves hide system apps and other modules. Asking whether
    // anything is hidden would light the mark on a device nobody had touched, which says nothing.
    // It matters because these choices survive the visit: returning to a list filtered the way you
    // left it a week ago is exactly when you need telling.
    val filtering =
        !locked &&
            (showSystem != ScopeViewModel.DEFAULT_SHOW_SYSTEM ||
                showGames != ScopeViewModel.DEFAULT_SHOW_GAMES ||
                showModules != ScopeViewModel.DEFAULT_SHOW_MODULES ||
                recommendedOnly)

    // Under a static scope the list is already exactly the module's own fixed set, so there is
    // nothing to filter. The control stays present but visibly dead, and says why when pressed —
    // removing it entirely would just raise the same question silently.
    IconButton(onClick = { if (locked) onLockedClick() else open = true }) {
        BadgedBox(badge = { if (filtering) Badge(modifier = Modifier.size(6.dp)) }) {
            Icon(
                Icons.Rounded.FilterList,
                contentDescription = stringResource(R.string.modules_filter),
                tint =
                    when {
                        locked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        filtering -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
    if (open) {
        ScopeSheet(
            stringResource(R.string.modules_filter),
            Icons.Rounded.FilterList,
            { open = false },
        ) {
            if (hasRecommended) {
                // The static-scope view, on request. Offered only when the module has actually
                // asked for something — otherwise it would narrow the list to nothing.
                ChoiceRow {
                    FilterChip(
                        selected = recommendedOnly,
                        onClick = { onToggleRecommendedOnly() },
                        label = { Text(stringResource(R.string.scope_recommended_only)) },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            // Off while the module's own request is what the list is answering. That question has
            // one answer — what it asked for, and what it has been given — and these three can
            // only subtract from it: Chrome is a system app, so a module asking for Chrome would
            // show an empty list to anyone who had not also turned system apps on. Greyed rather
            // than hidden, so the reader can see the settings are still there and why they are not
            // in play.
            ChoiceRow {
                FilterChip(
                    selected = showSystem,
                    enabled = !recommendedOnly,
                    onClick = { onToggleSystem() },
                    label = { Text(stringResource(R.string.scope_system_apps)) },
                )
                FilterChip(
                    selected = showGames,
                    enabled = !recommendedOnly,
                    onClick = { onToggleGames() },
                    label = { Text(stringResource(R.string.scope_games)) },
                )
                FilterChip(
                    selected = showModules,
                    enabled = !recommendedOnly,
                    onClick = { onToggleModules() },
                    label = { Text(stringResource(R.string.scope_modules)) },
                )
            }
        }
    }
}

/** What order it is in: every [ScopeSort], and a reverse toggle over whichever is chosen. */
@Composable
private fun ScopeSortMenu(
    sort: ScopeSort,
    reversed: Boolean,
    onSort: (ScopeSort) -> Unit,
    onReverse: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.AutoMirrored.Rounded.Sort,
            contentDescription = stringResource(R.string.scope_sort),
            tint =
                if (sort != ScopeSort.Relevance || reversed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        ScopeSheet(
            stringResource(R.string.scope_sort),
            Icons.AutoMirrored.Rounded.Sort,
            { open = false },
        ) {
            ChoiceRow {
                ScopeSort.entries.forEach { option ->
                    FilterChip(
                        selected = option == sort,
                        onClick = { onSort(option) },
                        label = { Text(stringResource(option.labelRes())) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ToggleRow(
                title = stringResource(R.string.scope_sort_reverse),
                icon = Icons.Rounded.SwapVert,
                checked = reversed,
                onCheckedChange = { onReverse() },
            )
        }
    }
}

/** The shell all three of this screen's sheets share, so they cannot drift apart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeSheet(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LocalizedOverlay {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SheetHeading(title, icon)
                content()
            }
        }
    }
}


/**
 * Why the list is empty, which the list itself can never say.
 *
 * Search and the two filter sheets sit directly above, and any of them can narrow this to nothing —
 * so an empty area under them reads as a screen that failed rather than as a question that was
 * asked and answered.
 */
@Composable
private fun ScopeEmptyState() {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.scope_no_match),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * How a row came to be in the scope, which decides the ring around its icon.
 *
 * Different mechanisms can put an app in a module's scope and they behave differently when the
 * world changes — one is fixed forever, one is the module's suggestion, one is only ever what you
 * ticked. Rendered as an identical checkbox, a scope the module controls looks exactly like a
 * scope the user controls.
 *
 * There is deliberately no "auto-included" origin. The include-new-apps setting reacts to packages
 * installed *from now on*, and nothing records how an app already in the scope got there, so any
 * such label would be a guess. It is explained in the sheet, where it is a property of the module
 * rather than a claim about a row.
 */
private enum class ScopeOrigin {
    /** The module fixed this scope; the user cannot change it. */
    Locked,
    /** The module asked for it, but it is the user's choice. */
    Requested,
    /** Nothing asked for it; it is in the scope because someone ticked it. */
    Chosen,
}

@Composable
private fun ScopeOrigin.color(): Color =
    when (this) {
        // Locked reads as "not yours to change", so it borrows the disabled-ish outline rather
        // than a colour that invites a tap.
        ScopeOrigin.Locked -> MaterialTheme.colorScheme.outline
        ScopeOrigin.Requested -> MaterialTheme.colorScheme.primary
        ScopeOrigin.Chosen -> Color.Transparent
    }

private fun ScopeOrigin.labelRes(): Int =
    when (this) {
        ScopeOrigin.Locked -> R.string.scope_origin_locked
        ScopeOrigin.Requested -> R.string.scope_recommended
        ScopeOrigin.Chosen -> R.string.scope_origin_chosen
    }

@Composable
private fun AppRow(
    app: AppInfo,
    enabled: Boolean,
    origin: ScopeOrigin,
    sharedNote: Boolean,
    onToggle: (Boolean) -> Unit,
    onAction: (PackageActionResult) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val ring = origin.color()

    ListItem(
        modifier =
            Modifier.combinedClickable(
                    onClick = { if (enabled) onToggle(!app.isSelectedInScope) },
                    onLongClick = {
                        // The long press is where re-optimize lives, and re-optimize is the fix
                        // for a hook that silently never fires because ART inlined its target.
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        menuOpen = true
                    },
                )
                .semantics { role = Role.Checkbox },
        leadingContent = {
            // The ring is drawn outside the icon rather than tinting it: an app icon is the user's
            // own landmark for finding a row and recolouring it would destroy that.
            AppIcon(
                applicationInfo = app.applicationInfo,
                contentDescription = null,
                size = 36.dp,
                modifier =
                    Modifier.border(width = 2.dp, color = ring, shape = CircleShape).padding(4.dp),
            )
        },
        headlineContent = { Text(app.appName) },
        supportingContent = {
            Column {
                Text(
                    ScopeViewModel.displayPackageName(app.packageName),
                    style = VectorMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (origin != ScopeOrigin.Chosen) {
                    Text(
                        text = stringResource(origin.labelRes()),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ring,
                    )
                }
                // The one row on this screen that is not an app, and the one row offered
                // identically to every user. Someone editing a work profile module's scope has no
                // other way to know that this target is not scoped to their profile — but on a
                // single-user device that is a sentence about a distinction that does not exist.
                if (sharedNote) {
                    Text(
                        text = stringResource(R.string.scope_framework_shared),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = { Checkbox(checked = app.isSelectedInScope, onCheckedChange = null) },
        colors =
            ListItemDefaults.colors(
                containerColor = Color.Transparent
            ),
    )
    if (menuOpen) {
        PackageActionSheet(
            packageName = app.packageName,
            userId = app.userId,
            appName = app.appName,
            applicationInfo = app.applicationInfo,
            isModule = false,
            onDismiss = { menuOpen = false },
            onResult = onAction,
        )
    }
}

/** States how much applying will change — so many to add, so many to remove — before it does it. */
@Composable
private fun ApplyBar(
    added: Int,
    removed: Int,
    applying: Boolean,
    onDiscard: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.scope_pending, added, removed),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.scope_apply_effect),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDiscard, enabled = !applying) {
                Text(stringResource(R.string.scope_discard))
            }
            Button(onClick = onApply, enabled = !applying) {
                Text(stringResource(R.string.scope_apply))
            }
        }
    }
}

private fun ScopeSort.labelRes(): Int =
    when (this) {
        ScopeSort.Relevance -> R.string.scope_sort_relevance
        ScopeSort.Name -> R.string.scope_sort_name
        ScopeSort.PackageName -> R.string.scope_sort_package
        ScopeSort.InstallTime -> R.string.scope_sort_installed
        ScopeSort.UpdateTime -> R.string.scope_sort_updated
    }

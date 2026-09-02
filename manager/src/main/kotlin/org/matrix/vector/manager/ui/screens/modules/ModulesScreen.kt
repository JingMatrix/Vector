package org.matrix.vector.manager.ui.screens.modules

import org.matrix.vector.ui.REACH_ICON_SIZE
import org.matrix.vector.ui.UpdatableVersion
import org.matrix.vector.ui.ModuleRow as SharedModuleRow
import org.matrix.vector.ui.ApiBadge as SharedApiBadge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.matrix.vector.ui.SharedAlertDialog
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import android.text.format.Formatter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import org.matrix.vector.ui.store.ReleaseAsset
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreEntry
import org.matrix.vector.manager.data.repository.ModuleUpdateQueue
import org.matrix.vector.ui.SheetHeading
import org.matrix.vector.ui.sheetRowColors
import org.matrix.vector.ui.store.StoreChannel
import org.matrix.vector.ui.store.releasesOn
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.manager.R
import org.matrix.vector.ui.R as UiR
import org.matrix.vector.manager.data.model.InstalledModule
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.ui.AppIcon
import org.matrix.vector.manager.ui.components.PackageActionSheet
import org.matrix.vector.ui.SnackbarTone
import org.matrix.vector.ui.SharedSnackbarHost
import org.matrix.vector.ui.show
import org.matrix.vector.manager.ui.components.PackageActionResult
import org.matrix.vector.ui.PanelHeader
import org.matrix.vector.ui.SearchField
import org.matrix.vector.ui.theme.Mono
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

class ModulesViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ModulesViewModel(
            ServiceLocator.daemon,
            ServiceLocator.modules,
            ServiceLocator.context.packageManager,
        )
            as T
}

/**
 * The module list.
 *
 * Its first job is to answer *what is running*, so enabled modules sort to the top, a disabled row
 * is dimmed and the module's own name carries the state in its colour — legible from the shape of
 * the list itself, not only from the position of a switch. The header says the same thing
 * numerically, and the filter turns it into a question that can be asked directly.
 *
 * Each row also carries the module's **reach**: which apps it is scoped to, as icons. That is the
 * fact behind most trips into the scope editor, so showing it here saves the trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    onModuleClick: (packageName: String, userId: Int) -> Unit,
    onOpenStore: (packageName: String) -> Unit,
    viewModel: ModulesViewModel = viewModel(factory = ModulesViewModelFactory()),
) {
    val tabs by viewModel.userModulesTabs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val daemonAvailable by viewModel.daemonAvailable.collectAsStateWithLifecycle()

    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val upgradable by viewModel.upgradable.collectAsStateWithLifecycle()
    val mutedUpgradable by viewModel.mutedUpgradable.collectAsStateWithLifecycle()
    val updateQueue by viewModel.updateQueue.collectAsStateWithLifecycle()
    val storeEntries by viewModel.storeEntries.collectAsStateWithLifecycle()
    val updateChannel by viewModel.updateChannel.collectAsStateWithLifecycle()
    var confirmUninstall by remember { mutableStateOf(false) }
    var showUpdates by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val actionScope = rememberCoroutineScope()

    /**
     * One sentence for a batch: whatever actually happened.
     *
     * The three outcomes stay separate — what changed, what was already so, and what refused — so
     * that a run where nothing needed doing says so rather than claiming work it did not do.
     */
    fun batchResult(
        doneRes: Int,
        alreadyRes: Int,
        allAlreadyRes: Int,
        outcome: ModulesViewModel.BatchOutcome,
    ): Pair<String, SnackbarTone> {
        val (changed, already, failed) = outcome
        if (failed > 0) {
            return context.getString(
                R.string.modules_batch_partial,
                "$changed/${changed + failed}",
            ) to SnackbarTone.Failure
        }
        if (changed == 0 && already > 0) {
            return context.resources.getQuantityString(allAlreadyRes, already, already) to
                SnackbarTone.Neutral
        }
        val done = context.resources.getQuantityString(doneRes, changed, changed)
        if (already == 0) return done to SnackbarTone.Success
        val alreadySaid = context.resources.getQuantityString(alreadyRes, already, already)
        return "$done  ·  $alreadySaid" to SnackbarTone.Success
    }

    fun reportBatch(result: Pair<String, SnackbarTone>) {
        actionScope.launch { snackbars.show(result.first, result.second) }
    }

    // Long-press actions all speak through this one snackbar, and a two-stage action calls it more
    // than once — that it started, and how it ended.
    fun report(result: PackageActionResult) {
        val text =
            result.argument?.let { context.getString(result.messageRes, it) }
                ?: context.getString(result.messageRes)
        actionScope.launch { snackbars.show(text, result.tone) }
    }
    val scope = rememberCoroutineScope()
    val backedUp = stringResource(R.string.modules_backup_done)
    val backupFailed = stringResource(R.string.modules_backup_failed)
    val restored = stringResource(R.string.modules_restore_done)
    val restoreFailed = stringResource(R.string.modules_restore_failed)

    val backupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) {
            uri ->
            if (uri != null) {
                viewModel.backupTo(uri) { count ->
                    scope.launch {
                        if (count != null) snackbars.show(String.format(backedUp, count), SnackbarTone.Success)
                        else snackbars.show(backupFailed, SnackbarTone.Failure)
                    }
                }
            }
        }

    val selectionBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) {
            uri ->
            if (uri != null) {
                viewModel.backupSelectedTo(uri) { count ->
                    scope.launch {
                        if (count != null)
                            snackbars.show(String.format(backedUp, count), SnackbarTone.Success)
                        else snackbars.show(backupFailed, SnackbarTone.Failure)
                    }
                }
            }
        }

    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.restoreFrom(uri) { outcome ->
                    scope.launch {
                        if (outcome != null)
                            snackbars.show(
                                String.format(restored, outcome.restored, outcome.skipped),
                                SnackbarTone.Success,
                            )
                        else snackbars.show(restoreFailed, SnackbarTone.Failure)
                    }
                }
            }
        }

    Scaffold(snackbarHost = { SharedSnackbarHost(snackbars) }) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // Hoisted above the header: the count in it is the *visible* profile's, so the header
            // has to know which page is showing. Aggregating across profiles made "4 of 6 active"
            // describe a set the user was not looking at.
            val pagerState = rememberPagerState(pageCount = { tabs.size })
            val visible = tabs.getOrNull(pagerState.currentPage)
            // The sheet lives outside the pager, so it needs the current page's answer handed to
            // it rather than reading the whole device's.
            val present = visible?.modules?.map { it.packageName }?.toSet().orEmpty()
            val visibleUpgradable = upgradable intersect present
            val visibleMutedUpgradable = mutedUpgradable intersect present

            // Inside the Column so the per-profile sets are in scope; a modal sheet draws in its
            // own window, so where it sits in the tree costs nothing.
            if (showUpdates) {
                ModuleUpdatesSheet(
                    entries = storeEntries,
                    upgradable = visibleUpgradable,
                    mutedUpgradable = visibleMutedUpgradable,
                    channel = StoreChannel.of(updateChannel),
                    onStart = viewModel::startUpdates,
                    onDismiss = { showUpdates = false },
                )
            }

            // The selection bar takes the title and description rows and nothing else, so the
            // search field below stays exactly where the thumb left it and the list does not jump
            // the moment a module is picked up. Filling the whole header would leave one row of
            // controls floating in a band of colour half the height of the header.
            ModulesHeader(
                active = visible?.modules?.count { it.isEnabled } ?: counts.first,
                total = visible?.modules?.size ?: counts.second,
                onBackup = { backupLauncher.launch("vector-modules.bak") },
                onRestore = { restoreLauncher.launch(arrayOf("*/*")) },
                titleOverlay =
                    if (selection.isEmpty()) null
                    else {
                        {
                            SelectionBar(
                                count = selection.size,
                                onClose = viewModel::clearSelection,
                                onEnable = {
                                    viewModel.setSelectedEnabled(true) { outcome ->
                                        reportBatch(
                                            batchResult(
                                                R.plurals.modules_batch_enabled,
                                                R.plurals.modules_batch_already_on,
                                                R.plurals.modules_batch_all_already_on,
                                                outcome,
                                            )
                                        )
                                    }
                                },
                                onDisable = {
                                    viewModel.setSelectedEnabled(false) { outcome ->
                                        reportBatch(
                                            batchResult(
                                                R.plurals.modules_batch_disabled,
                                                R.plurals.modules_batch_already_off,
                                                R.plurals.modules_batch_all_already_off,
                                                outcome,
                                            )
                                        )
                                    }
                                },
                                onBackup = { selectionBackupLauncher.launch("vector-modules.bak") },
                                onUninstall = { confirmUninstall = true },
                            )
                        }
                    },
                search = { ModulesSearch(query, viewModel, filter, sort) },
            )

            // No blocking spinner: the pull-to-refresh indicator already reports the reload, and
            // a full-screen spinner on every route in made the list flash.
            if (isLoading && tabs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (tabs.isEmpty() || tabs.all { it.modules.isEmpty() }) {
                // A filter empties the list exactly as a search does, so both count as narrowing.
                // Otherwise picking "Inactive" on a device where everything is on would say "you
                // have no modules installed" over a list the filter had just hidden.
                EmptyState(
                    daemonAvailable = daemonAvailable,
                    filtered = query.isNotBlank() || filter != ModuleFilter.All,
                )
                return@Column
            }

            if (tabs.size > 1) {
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(tab.user.name, fontWeight = FontWeight.Medium) },
                        )
                    }
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
              // Pull to re-read the installed packages and the daemon's enabled set. A module
              // installed or removed outside the manager is the common case, and the broadcast
              // that catches it does not fire for every route in.
              PullToRefreshBox(
                isRefreshing = isLoading,
                onRefresh = { viewModel.loadModules() },
              ) {
                val modules = tabs[page].modules
                // Sections only make sense when the order is by state. Under any other sort the
                // groups would interleave, and a header that lies about what follows it is worse
                // than no header.
                val sectioned = sort == ModuleSort.EnabledFirst && query.isBlank()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
                ) {
                    item(key = "updates") {
                        UpdateLine(
                            // Counted against the modules on *this* page. A profile has its own
                            // set of installed modules, and a count carried over from another one
                            // offers updates for packages that are not there — the sheet would
                            // then be empty, or worse, install into the wrong profile.
                            updates = modules.count { it.packageName in upgradable },
                            queue = updateQueue,
                            onOpen = { showUpdates = true },
                            onAcknowledge = viewModel::acknowledgeUpdates,
                        )
                    }
                    if (sectioned) {
                        val active = modules.filter { it.isEnabled }
                        val inactive = modules.filterNot { it.isEnabled }

                        if (active.isNotEmpty()) {
                            stickyHeader(key = "h:active") {
                                SectionHeader(stringResource(R.string.modules_section_active), active.size)
                            }
                            moduleRows(active, facts, selection, upgradable, onModuleClick, onOpenStore, viewModel::toggleSelected, ::report)
                        }
                        if (inactive.isNotEmpty()) {
                            stickyHeader(key = "h:inactive") {
                                SectionHeader(
                                    stringResource(R.string.modules_section_inactive),
                                    inactive.size,
                                )
                            }
                            moduleRows(inactive, facts, selection, upgradable, onModuleClick, onOpenStore, viewModel::toggleSelected, ::report)
                        }
                    } else {
                        moduleRows(modules, facts, selection, upgradable, onModuleClick, onOpenStore, viewModel::toggleSelected, ::report)
                    }
                }
              }
            }
        }
    }

    if (confirmUninstall) {
        SharedAlertDialog(
            onDismissRequest = { confirmUninstall = false },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.modules_uninstall_title)) },
            // Names the consequence rather than asking "are you sure". The backup on this screen
            // holds the enabled flag and the scope; the module's own stored settings go with it
            // and nothing here can bring them back.
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.modules_uninstall_body,
                        selection.size,
                        selection.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUninstall = false
                        viewModel.uninstallSelected { outcome ->
                            reportBatch(
                                batchResult(
                                    R.plurals.modules_batch_uninstalled,
                                    // Nothing is ever "already uninstalled" here: the list only
                                    // holds what is installed, so these two are unreachable and
                                    // are the same string rather than an invented sentence.
                                    R.plurals.modules_batch_uninstalled,
                                    R.plurals.modules_batch_uninstalled,
                                    outcome,
                                )
                            )
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.action_uninstall),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) {
                    Text(stringResource(UiR.string.logs_cancel))
                }
            },
        )
    }
}

/**
 * What the selection can be done to.
 *
 * Laid over the header rather than replacing it, and inset so it reads as a panel that has come
 * forward over the screen rather than a coloured slab bolted to the top of it. The count takes the
 * place the title held, the actions take the place the backup and restore icons held, so the eye
 * does not have to find anything twice.
 *
 * Uninstall is last and in the error colour, and asks before it does anything — it is the only
 * irreversible thing on this screen.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onBackup: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.modules_selection_clear),
                )
            }
            Text(
                text = pluralStringResource(R.plurals.modules_selected, count, count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 2.dp),
            )
            SelectionAction(Icons.Rounded.CheckCircle, R.string.modules_batch_enable, onEnable)
            SelectionAction(Icons.Rounded.Block, R.string.modules_batch_disable, onDisable)
            SelectionAction(Icons.Rounded.SaveAlt, R.string.modules_backup, onBackup)
            SelectionAction(
                Icons.Rounded.Delete,
                R.string.action_uninstall,
                onUninstall,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    descriptionRes: Int,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(descriptionRes),
            tint = tint ?: LocalContentColor.current,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** The module search field, as the header's third row. */
@Composable
private fun ModulesSearch(
    query: String,
    viewModel: ModulesViewModel,
    filter: ModuleFilter,
    sort: ModuleSort,
) {
    SearchField(
        query = query,
        onQueryChange = viewModel::setQuery,
        placeholder = stringResource(R.string.modules_search_hint),
    ) {
        ModuleFilterButton(
            filter = filter,
            onFilterChange = viewModel::setFilter,
            sort = sort,
            onSortChange = viewModel::setSort,
        )
    }
}

/** The filter menu that lives in the search field's trailing slot. */
@Composable
private fun ModuleFilterButton(
    filter: ModuleFilter,
    onFilterChange: (ModuleFilter) -> Unit,
    sort: ModuleSort,
    onSortChange: (ModuleSort) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val filtering = filter != ModuleFilter.All || sort != ModuleSort.EnabledFirst

    Box {
        IconButton(onClick = { menuOpen = true }) {
            BadgedBox(
                badge = {
                    // A filter that narrows the list must never be silent — an empty list with
                    // no visible cause reads as "nothing installed".
                    if (filtering) Badge(modifier = Modifier.size(6.dp))
                }
            ) {
                Icon(
                    Icons.Rounded.FilterList,
                    contentDescription = stringResource(R.string.modules_filter),
                    tint =
                        if (filtering) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
LocalizedOverlay {

            ModuleFilter.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    trailingIcon = {
                        if (option == filter) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = {
                        onFilterChange(option)
                        menuOpen = false
                    },
                )
            }
            HorizontalDivider()
            ModuleSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    trailingIcon = {
                        if (option == sort) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = {
                        onSortChange(option)
                        menuOpen = false
                    },
                )
            }
        }
}
    }
}

@Composable
private fun ModulesHeader(
    active: Int,
    total: Int,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    titleOverlay: (@Composable () -> Unit)? = null,
    search: @Composable () -> Unit,
) {
    PanelHeader(
        title = stringResource(R.string.nav_modules),
        modifier = modifier,
        titleOverlay = titleOverlay,
        actions = {
            // Both shown rather than hidden behind an overflow. There are exactly two, they are
            // opposites, and a menu holding two items costs a tap to say what a glance could.
            //
            // Deliberately *not* a mirrored pair: at 24dp two mirror images of the same shape read
            // as one shape, and telling them apart means stopping to work out which way the arrow
            // points. Two different pictures instead — a tray to save into, and the platform's own
            // restore glyph — each naming the outcome rather than the mechanism. Nothing here
            // uploads anywhere either; the file goes wherever the document picker is pointed.
            IconButton(onClick = onRestore) {
                Icon(
                    Icons.Rounded.SettingsBackupRestore,
                    contentDescription = stringResource(R.string.modules_restore),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onBackup) {
                Icon(
                    Icons.Rounded.SaveAlt,
                    contentDescription = stringResource(R.string.modules_backup),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        description = {
            if (total > 0) {
                Text(
                    text = stringResource(R.string.modules_active_of, active, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        search = search,
    )
}

/**
 * A module, as a row.
 *
 * No card and no tinted background. Three states have to be distinguishable at a glance — running,
 * off, and asking for an API the framework does not provide — and painting the whole row for each
 * would turn the list into stacked blocks of colour fighting the icons and the text. **The
 * module's own name carries the state instead**: the accent colour when it is running, muted when
 * it is off, the error colour when the framework is too old for it.
 *
 * The icon is left exactly as the module ships it. Wrapping it in a coloured well would make every
 * module look like it belonged to Vector rather than to its author.
 *
 * Two columns for the three questions the row answers: what it is (icon, and the API it needs),
 * and what it does (name and description). How it is configured — the version and the reach — is
 * laid over the second column rather than given a third, as the Box below explains.
 */
@Composable
private fun ModuleRow(
    module: InstalledModule,
    facts: ModuleFacts?,
    hasUpdate: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onIconClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenStore: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val incompatible = facts?.incompatible == true

    val nameColor by
        animateColorAsState(
            when {
                incompatible -> colors.error
                module.isEnabled -> colors.primary
                else -> colors.onSurfaceVariant
            },
            label = "moduleNameColor",
        )

    val brokenSince = facts?.apiBrokenSince
    val loadFailure = facts?.loadFailure
    // A state the module is in stands in for the description: a load failure first (a module that
    // cannot load is doing nothing, and unsaid that looks like a switch that turned itself off),
    // then an incompatibility, then the quieter caution that the API moved underneath it.
    val note: (@Composable () -> Unit)? =
        if (loadFailure != null) {
            {
                Text(
                    text =
                        stringResource(
                            when (loadFailure) {
                                IManagerService.MODULE_LOAD_UNSUPPORTED_API ->
                                    R.string.modules_load_unsupported_api
                                IManagerService.MODULE_LOAD_NO_APK -> R.string.modules_load_no_apk
                                IManagerService.MODULE_LOAD_UNUSABLE -> R.string.modules_load_unusable
                                else -> R.string.modules_load_unusable
                            }
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
        } else if (incompatible) {
            {
                Text(
                    text =
                        stringResource(
                            if (module.isLegacy) R.string.modules_incompatible_legacy
                            else R.string.modules_incompatible,
                            module.minVersion,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
        } else if (brokenSince != null) {
            {
                Text(
                    text = stringResource(R.string.modules_api_behind, module.apiVersion, brokenSince),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.tertiary,
                )
            }
        } else null

    // Who this module touches, handed to the row as data — the row itself draws it bottom-right. The
    // framework is a scope target with no launcher icon, so it rides in as the leading mark rather
    // than becoming part of a number; an empty, framework-less scope passes nothing and the row
    // reserves no band.
    val scopePreview = facts?.scopePreview.orEmpty()
    SharedModuleRow(
        icon = {
            AppIcon(
                applicationInfo = module.applicationInfo,
                contentDescription = null,
                size = ICON_SIZE,
            )
        },
        name = module.appName,
        versionName = module.versionName,
        description = module.description,
        apiBadge = { ApiBadge(module = module, incompatible = incompatible) },
        nameColor = nameColor,
        hasUpdate = hasUpdate,
        onVersionClick = if (hasUpdate) onOpenStore else null,
        dimmed = !module.isEnabled && !incompatible,
        selected = selected,
        onIconClick = onIconClick,
        onIconLongClick = onLongClick,
        onClick = onClick,
        onLongClick = onLongClick,
        note = note,
        reachLeading =
            if (facts?.scopeFramework == true) {
                {
                    Icon(
                        Icons.Rounded.Android,
                        contentDescription = stringResource(R.string.modules_scope_framework),
                        tint = colors.primary,
                        modifier = Modifier.size(REACH_ICON_SIZE),
                    )
                }
            } else null,
        reachIcons =
            scopePreview.map { info ->
                { AppIcon(applicationInfo = info, contentDescription = null, size = REACH_ICON_SIZE) }
            },
        reachCount = (facts?.scopeCount ?: 0).coerceAtLeast(0),
    )
}

/**
 * The size the module's icon is drawn at, matching the shared row's own icon slot.
 *
 * Comfortably a touch target — it is the selection handle — while leaving the width a larger icon
 * would take to the column that holds the name and the description, where the reading happens.
 */
private val ICON_SIZE = 48.dp

/**
 * `API 101` / `Xposed 93`, with the scale small and quiet and the number carrying the colour.
 *
 * The scale name is context that rarely changes; the number is the fact being checked. A module
 * that declares no API at all shows `API ?` rather than a sentence — it is the same shape as every
 * other badge, so the missing value reads as missing rather than as a different kind of thing.
 */
@Composable
private fun ApiBadge(module: InstalledModule, incompatible: Boolean) {
    val undeclared = !module.declaresApiVersion
    // Map the module's declared API onto the shared badge: an undeclared value shows "?" and takes
    // the error treatment, the same as an incompatible one.
    SharedApiBadge(
        label =
            stringResource(
                if (module.isLegacy) R.string.modules_api_scale_legacy
                else R.string.modules_api_scale_modern
            ),
        value = if (undeclared) "?" else module.apiVersion.toString(),
        incompatible = incompatible || undeclared,
    )
}

@Composable
private fun EmptyState(daemonAvailable: Boolean, filtered: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Extension,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text =
                    stringResource(
                        when {
                            !daemonAvailable -> R.string.modules_no_daemon
                            filtered -> R.string.modules_no_match
                            else -> R.string.modules_empty
                        }
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun ModuleFilter.labelRes(): Int =
    when (this) {
        ModuleFilter.All -> R.string.modules_filter_all
        ModuleFilter.Active -> R.string.modules_filter_active
        ModuleFilter.Inactive -> R.string.modules_filter_inactive
    }

private fun ModuleSort.labelRes(): Int =
    when (this) {
        ModuleSort.EnabledFirst -> R.string.modules_sort_enabled
        ModuleSort.Name -> R.string.modules_sort_name
        ModuleSort.RecentlyUpdated -> R.string.modules_sort_recent
        ModuleSort.WidestScope -> R.string.modules_sort_scope
    }

/** Emits one row per module, plus its divider. */
private fun androidx.compose.foundation.lazy.LazyListScope.moduleRows(
    modules: List<InstalledModule>,
    facts: Map<ModuleKey, ModuleFacts>,
    selection: Set<ModuleKey>,
    upgradable: Set<String>,
    onModuleClick: (String, Int) -> Unit,
    onOpenStore: (String) -> Unit,
    onSelect: (InstalledModule) -> Unit,
    onAction: (PackageActionResult) -> Unit,
) {
    items(modules, key = { "${it.packageName}:${it.userId}" }) { module ->
        ModuleListItem(
            module = module,
            facts = facts[ModuleKey(module.packageName, module.userId)],
            hasUpdate = module.packageName in upgradable,
            selected = ModuleKey(module.packageName, module.userId) in selection,
            selectionActive = selection.isNotEmpty(),
            onClick = { onModuleClick(module.packageName, module.userId) },
            onOpenStore = { onOpenStore(module.packageName) },
            onSelect = { onSelect(module) },
            onAction = onAction,
        )
        // Inset from both ends. A full-bleed rule cuts the list into slabs; a short one reads as
        // a breath between rows, which is all it is for.
        HorizontalDivider(
            modifier = Modifier.padding(start = 108.dp, end = 32.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}

/**
 * A module row, with the sheet its long press opens.
 *
 * There is deliberately no swipe-to-toggle: a horizontal drag on a row inside a vertically
 * scrolling list competes with the scroll for every gesture that is not perfectly straight.
 *
 * **The icon is the selection handle.** Tapping it picks the module up; from there the same tap on
 * any other icon adds to the set and the bar at the top acts on all of them at once, which is what
 * makes enabling, removing or backing up eight modules one act rather than eight.
 */
@Composable
private fun ModuleListItem(
    module: InstalledModule,
    facts: ModuleFacts?,
    hasUpdate: Boolean,
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onOpenStore: () -> Unit,
    onSelect: () -> Unit,
    onAction: (PackageActionResult) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    ModuleRow(
        module = module,
        facts = facts,
        hasUpdate = hasUpdate,
        selected = selected,
        onOpenStore = onOpenStore,
        // Once anything is selected the whole row joins the selection, because that is what every
        // other list on the platform does and aiming at a 48dp icon to add the ninth module would
        // be its own small ordeal.
        onClick = if (selectionActive) onSelect else onClick,
        onIconClick = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelect()
        },
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            menuOpen = true
        },
    )

    if (menuOpen) {
        PackageActionSheet(
            packageName = module.packageName,
            userId = module.userId,
            appName = module.appName,
            applicationInfo = module.applicationInfo,
            isModule = true,
            onDismiss = { menuOpen = false },
            onResult = onAction,
            onOpenStore = { onOpenStore() },
        )
    }
}

/** A pinned label saying which half of the list you are in, and how big it is. */
@Composable
private fun SectionHeader(title: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The one line in the panel that says how many modules are behind, and how the run is going.
 *
 * A line under the header rather than a badge on a tab or a banner over the list. The panel's own
 * first sentence is already "3 of 11 active"; "4 can be updated" is the same kind of fact about the
 * same set, and it reads as the second half of that sentence rather than as an interruption.
 *
 * It is absent when there is nothing to update. A row that says "everything is current" is a row
 * that has to be read to learn nothing, on every visit, forever.
 *
 * During a run it stops being a button and becomes the report: which module, how far through. That
 * is why it is here and not inside the sheet — updating four modules takes longer than anyone will
 * hold a sheet open, so the progress has to live somewhere they will actually be.
 */
@Composable
private fun UpdateLine(
    updates: Int,
    queue: ModuleUpdateQueue.State,
    onOpen: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val running = queue.running
    val settled = !running && queue.total > 0
    if (!running && !settled && updates == 0) return

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primary.copy(alpha = 0.09f))
                .clickable(onClick = if (settled) onAcknowledge else onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                when {
                    settled && queue.failed.isNotEmpty() -> Icons.Rounded.ErrorOutline
                    settled -> Icons.Rounded.CheckCircle
                    else -> Icons.Rounded.ArrowCircleUp
                },
            contentDescription = null,
            tint = if (settled && queue.failed.isNotEmpty()) colors.error else colors.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text =
                when {
                    running ->
                        stringResource(
                            R.string.modules_updating,
                            queue.current?.title ?: "",
                            queue.finished + 1,
                            queue.total,
                        )
                    settled && queue.failed.isNotEmpty() ->
                        pluralStringResource(
                            R.plurals.modules_update_failed,
                            queue.failed.size,
                            queue.failed.size,
                        )
                    settled ->
                        pluralStringResource(
                            R.plurals.modules_updated,
                            queue.done.size,
                            queue.done.size,
                        )
                    else -> pluralStringResource(R.plurals.modules_updates, updates, updates)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = if (settled && queue.failed.isNotEmpty()) colors.error else colors.primary,
        )
    }
}

/**
 * Which of the modules that are behind to bring forward.
 *
 * Checkboxes rather than a single "update everything" button, because these are other people's
 * APKs going onto someone's phone: the reader gets to see the list and say which. Everything that
 * can be installed in one step is ticked to begin with, since that is what someone opening this
 * usually means.
 *
 * Modules whose updates were silenced are listed too, below the rest and unticked. They are
 * genuinely out of date, and this is the one screen where saying so is useful rather than nagging
 * — it is also the only way to find what you muted six months ago without going through the store
 * one module at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleUpdatesSheet(
    entries: Map<String, StoreEntry>,
    upgradable: Set<String>,
    mutedUpgradable: Set<String>,
    channel: StoreChannel,
    onStart: (List<ModuleUpdateQueue.Item>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current

    // One APK is installable from here; several is a choice this sheet has no room to make, so
    // those keep their row, uncheckable, pointing at the store page that does.
    data class Row(
        val entry: StoreEntry,
        val release: RepoVersion?,
        val asset: ReleaseAsset?,
        val muted: Boolean,
    )

    val rows =
        remember(entries, upgradable, mutedUpgradable, channel) {
            (upgradable + mutedUpgradable).mapNotNull { name ->
                val entry = entries[name] ?: return@mapNotNull null
                val release = entry.module.releasesOn(channel).firstOrNull()
                val apks = release?.releaseAssets.orEmpty().filter { it.isApk }
                Row(entry, release?.version, apks.singleOrNull(), name in mutedUpgradable)
            }
                .sortedWith(compareBy({ it.muted }, { it.entry.module.title.lowercase() }))
        }

    var chosen by
        remember(rows) {
            mutableStateOf(
                rows.filter { !it.muted && it.asset != null }.map { it.entry.module.name }.toSet()
            )
        }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LocalizedOverlay {
            Column(Modifier.padding(bottom = 24.dp)) {
                SheetHeading(
                    stringResource(R.string.modules_updates_title),
                    Icons.Rounded.ArrowCircleUp,
                )
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    rows.forEach { row ->
                        val name = row.entry.module.name
                        val selectable = row.asset != null
                        ListItem(
                            // Toggleable rather than clickable, for the same reason the checkbox
                            // takes no callback: the row *is* the tick. A plain clickable is
                            // announced as a button carrying the module's name, saying nothing
                            // about whether it is going to be updated.
                            modifier =
                                Modifier.toggleable(
                                    value = name in chosen,
                                    enabled = selectable,
                                    role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        chosen = if (checked) chosen + name else chosen - name
                                    },
                                ),
                            supportingContent = {
                                Text(
                                    text =
                                        when {
                                            row.asset == null ->
                                                stringResource(R.string.action_update_choose)
                                            // "1.1.1 → 1.1.1" is not a thing to say to anyone.
                                            row.entry.sameVersion ->
                                                stringResource(
                                                    R.string.modules_update_reinstall,
                                                    row.entry.latest?.versionName.orEmpty(),
                                                    Formatter.formatShortFileSize(
                                                        context,
                                                        row.asset.size,
                                                    ),
                                                )
                                            else ->
                                                stringResource(
                                                    R.string.modules_update_versions,
                                                    row.entry.installed?.versionName.orEmpty(),
                                                    row.entry.latest?.versionName.orEmpty(),
                                                    Formatter.formatShortFileSize(
                                                        context,
                                                        row.asset.size,
                                                    ),
                                                )
                                        }
                                )
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = name in chosen,
                                    onCheckedChange = null,
                                    enabled = selectable,
                                )
                            },
                            trailingContent =
                                if (!row.muted) null
                                else {
                                    {
                                        Text(
                                            text = stringResource(R.string.modules_update_ignored),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                },
                            colors = sheetRowColors,
                        ) { Text(row.entry.module.title) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    enabled = chosen.isNotEmpty(),
                    onClick = {
                        onStart(
                            rows
                                .filter { it.entry.module.name in chosen && it.asset != null }
                                .map {
                                    ModuleUpdateQueue.Item(
                                        packageName = it.entry.module.name,
                                        title = it.entry.module.title,
                                        asset = it.asset!!,
                                        release = it.release,
                                    )
                                }
                        )
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.modules_update_selected, chosen.size))
                }
            }
        }
    }
}

package org.matrix.vector.manager.ui.screens.modules

import org.matrix.vector.manager.ui.components.UpdatableVersion
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.TabRow
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
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import org.matrix.vector.manager.ui.components.VectorAlertDialog
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
import androidx.compose.material3.rememberModalBottomSheetState
import org.matrix.vector.manager.data.model.ReleaseAsset
import org.matrix.vector.manager.data.model.StoreEntry
import org.matrix.vector.manager.data.repository.ModuleUpdateQueue
import org.matrix.vector.manager.ui.components.SheetHeading
import org.matrix.vector.manager.ui.screens.repo.StoreChannel
import org.matrix.vector.manager.ui.screens.repo.releasesOn
import androidx.compose.foundation.layout.widthIn
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.model.InstalledModule
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.components.AppIcon
import org.matrix.vector.manager.ui.components.PackageActionSheet
import org.matrix.vector.manager.ui.components.SnackbarTone
import org.matrix.vector.manager.ui.components.VectorSnackbarHost
import org.matrix.vector.manager.ui.components.show
import org.matrix.vector.manager.ui.components.PackageActionResult
import org.matrix.vector.manager.ui.components.PanelHeader
import org.matrix.vector.manager.ui.components.SearchField
import org.matrix.vector.manager.ui.theme.VectorMono
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

    Scaffold(snackbarHost = { VectorSnackbarHost(snackbars) }) { innerPadding ->
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
                TabRow(selectedTabIndex = pagerState.currentPage) {
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
        VectorAlertDialog(
            onDismissRequest = { confirmUninstall = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
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
                    Text(stringResource(R.string.logs_cancel))
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
                Icons.Rounded.DeleteOutline,
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

    var expanded by rememberSaveable(module.packageName) { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                // Intrinsic height so the right column can push its lower item to the bottom of
                // whatever the description made this row.
                .height(IntrinsicSize.Min)
                // A module that is off recedes rather than merely changing colour: the list is
                // read first as "what is running", and everything else should sit behind that.
                .alpha(if (module.isEnabled || incompatible) 1f else 0.45f)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The icon is the switch. Double-tapping it turns the module on or off without leaving the
        // list, which is what someone flipping several modules actually wants; a single tap only
        // says what state it is in, because a one-tap toggle here would fire every time a thumb
        // brushed the list.
        Column(
            modifier = Modifier.combinedClickable(onClick = onIconClick, onLongClick = onLongClick),
            // Against the text, not centred over the badge. The badge below is wider than the icon
            // — "Xposed 54" is — so centring left the icon a few pixels short of the edge the
            // names and descriptions all start from, and every row in the list showed that gap.
            horizontalAlignment = Alignment.End,
        ) {
            // Fixed at the icon's own size whatever is drawn inside it, so that picking a module
            // up cannot resize its row. A tick larger than the icon would grow this box, and with
            // it the icon column, the row's intrinsic height and every row below — selecting one
            // module would reflow the list under the thumb that selected it.
            Box(modifier = Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
                AppIcon(
                    applicationInfo = module.applicationInfo,
                    contentDescription = null,
                    size = ICON_SIZE,
                )
                // The tick covers the icon rather than sitting beside it. A selected row has to be
                // unmistakable at a glance across a screen of them, and the icon is the one part
                // of the row the eye is already using to tell the rows apart.
                if (selected) {
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            ApiBadge(module = module, incompatible = incompatible)
        }

        Spacer(Modifier.width(16.dp))

        // Only this area opens the scope, so that a tap on the icon beside it — which is the
        // selection handle — cannot navigate away instead.
        //
        // A Box, not a third column. Reserving a column for the version and the reach would take
        // its width from *every line* of the description, the one piece of prose on this screen,
        // and take it whether or not anything was there to put in it. They overlap the text column
        // instead and are kept clear of the text by *vertical* placement: the version sits in the
        // title's band, the reach in the band below the last line. Nothing is reserved
        // horizontally, so the description runs the full width.
        Box(
            Modifier.weight(1f)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
        Column(Modifier.padding(bottom = REACH_BAND)) {
            // The title's band. Both halves are fixed and both scroll, so neither can ever reach
            // the other however long the module's name or its version string becomes — which is
            // not a hypothetical: names run to "Enable Screenshot (formerly known as Disable
            // FLAG_SECURE)" and versions to a tag with a commit hash on the end.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = module.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier =
                        Modifier.weight(1f)
                            .basicMarquee(iterations = 1, repeatDelayMillis = 3_000),
                )
                Spacer(Modifier.width(10.dp))
                UpdatableVersion(
                    text = module.versionName.ifBlank { "" },
                    hasUpdate = hasUpdate,
                    marquee = true,
                    color = colors.onSurfaceVariant,
                    // With an update in hand the version is the shortest route to the release that
                    // would replace it, so it becomes the link. Without one it is inert: a tap
                    // that sometimes navigates and sometimes does nothing teaches nothing.
                    modifier =
                        Modifier.width(VERSION_WIDTH)
                            .then(
                                if (!hasUpdate) Modifier
                                else
                                    Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                        onOpenStore()
                                    }
                            ),
                )
            }
            val brokenSince = facts?.apiBrokenSince
            val loadFailure = facts?.loadFailure
            if (loadFailure != null) {
                // First, above every other note. A module that cannot be loaded is doing nothing
                // at all, and unsaid that is indistinguishable from a switch that turned itself
                // off.
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        stringResource(
                            when (loadFailure) {
                                // Named separately from "could not load it" because it is the one
                                // refusal that is not brokenness: the module is old, and its
                                // author is the only one who can move it forward.
                                ILSPManagerService.MODULE_LOAD_UNSUPPORTED_API ->
                                    R.string.modules_load_unsupported_api
                                ILSPManagerService.MODULE_LOAD_UNUSABLE ->
                                    R.string.modules_load_unusable
                                else -> R.string.modules_load_no_apk
                            }
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            } else if (incompatible) {
                Spacer(Modifier.height(4.dp))
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
            } else if (brokenSince != null) {
                // Not an error: the framework will load this and it may work perfectly. It is a
                // caution, in the caution colour, naming the version that changed underneath it so
                // the reader can go and ask its author about that specific thing.
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        stringResource(
                            R.string.modules_api_behind,
                            // "Built for" is the target, and it is what decided this caution was
                            // due; showing the floor beside a verdict reached from the target
                            // would be two numbers disagreeing in one sentence.
                            module.apiVersion,
                            brokenSince,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.tertiary,
                )
            } else if (module.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        // The only prose on this screen, and the thing that says what the module
                        // actually does — so it gets room.
                        text = module.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        // Whether there is more to read is a property of this description at this
                        // width, which only the layout knows — so the control appears only when
                        // it would do something.
                        onTextLayout = { truncated = it.hasVisualOverflow || expanded },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (truncated) {
                        Icon(
                            imageVector =
                                if (expanded) Icons.Rounded.ExpandLess
                                else Icons.Rounded.ExpandMore,
                            contentDescription =
                                stringResource(
                                    if (expanded) R.string.modules_collapse
                                    else R.string.modules_expand
                                ),
                            tint = colors.primary,
                            modifier =
                                Modifier.padding(start = 4.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable { expanded = !expanded },
                        )
                    }
                }
            }
        }

            // The reach, in the band the row already left empty under the last line of text. It
            // is allowed to run left past where a column would have ended — nothing is there — so
            // it costs the description no width at all.
            ScopePreview(
                module = module,
                facts = facts,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * The strip along the bottom of a row that the reach sits in.
 *
 * The row already ended in a gap of about this size, so the icons landed in space that was being
 * left empty anyway: full-width prose and a right-aligned reach, for a few density-independent
 * pixels rather than a whole column.
 */
private val REACH_BAND = 22.dp

/** Room for a version and its mark. Anything longer scrolls past instead of pushing. */
private val VERSION_WIDTH = 104.dp

/**
 * The module's icon, and the slot it is drawn in whether or not it is selected.
 *
 * Comfortably a touch target — it is the selection handle — while leaving the width a larger icon
 * would take to the column that holds the name and the description, where the reading happens.
 */
private val ICON_SIZE = 48.dp

/**
 * Who the module actually touches.
 *
 * A count alone answers a question nobody asked; three recognisable icons answer "does this touch
 * anything I care about" without opening anything, and the remainder collapses to a number after
 * them. Nothing is drawn at all when the scope is empty or not yet known.
 */
@Composable
private fun ScopePreview(
    module: InstalledModule,
    facts: ModuleFacts?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val reach = facts?.scopeCount ?: -1
    val framework = facts?.scopeFramework == true
    // Nothing to depict, so nothing is drawn. A row saying "no apps" would spend a line on an
    // absence, and would say it of every module that hooks only the framework.
    if (reach <= 0 && !framework) return

    val preview = facts?.scopePreview.orEmpty()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (framework) {
            // The framework is a scope target with no icon, so it gets a mark of its own rather
            // than silently becoming part of a number.
            Icon(
                Icons.Rounded.Android,
                contentDescription = stringResource(R.string.modules_scope_framework),
                tint = colors.primary,
                modifier = Modifier.padding(start = 3.dp).size(20.dp),
            )
        }
        preview.forEach { info ->
            AppIcon(
                applicationInfo = info,
                contentDescription = null,
                size = 20.dp,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
        val remainder = reach - preview.size
        if (remainder > 0) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.modules_scope_more, remainder),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * `API 101` / `Xposed 93`, with the scale small and quiet and the number carrying the colour.
 *
 * The scale name is context that rarely changes; the number is the fact being checked. A module
 * that declares no API at all shows `API ?` rather than a sentence — it is the same shape as every
 * other badge, so the missing value reads as missing rather than as a different kind of thing.
 */
@Composable
private fun ApiBadge(module: InstalledModule, incompatible: Boolean) {
    val colors = MaterialTheme.colorScheme
    val undeclared = !module.declaresApiVersion

    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text =
                stringResource(
                    if (module.isLegacy) R.string.modules_api_scale_legacy
                    else R.string.modules_api_scale_modern
                ),
            // Barely there: the scale is context, and it repeats down every row. The number is
            // the only part anyone reads twice.
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Text(
            text = if (undeclared) "?" else module.apiVersion.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (incompatible || undeclared) colors.error else colors.primary,
        )
    }
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
                style = VectorMono,
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
    val sheetState = rememberModalBottomSheetState()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current

    // One APK is installable from here; several is a choice this sheet has no room to make, so
    // those keep their row, uncheckable, pointing at the store page that does.
    data class Row(val entry: StoreEntry, val asset: ReleaseAsset?, val muted: Boolean)

    val rows =
        remember(entries, upgradable, mutedUpgradable, channel) {
            (upgradable + mutedUpgradable).mapNotNull { name ->
                val entry = entries[name] ?: return@mapNotNull null
                val apks =
                    entry.module
                        .releasesOn(channel)
                        .firstOrNull()
                        ?.releaseAssets
                        .orEmpty()
                        .filter { it.isApk }
                Row(entry, apks.singleOrNull(), name in mutedUpgradable)
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
                            headlineContent = { Text(row.entry.module.title) },
                            supportingContent = {
                                Text(
                                    text =
                                        when {
                                            row.asset == null ->
                                                stringResource(R.string.action_update_choose)
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
                        )
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

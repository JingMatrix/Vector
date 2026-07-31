package org.matrix.vector.manager.ui.screens.repo

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.LowPriority
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import org.matrix.vector.manager.ui.components.ChoiceRow
import org.matrix.vector.manager.ui.components.SheetHeading
import org.matrix.vector.manager.ui.components.ToggleRow
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.model.StoreCatalog
import org.matrix.vector.manager.data.model.StoreEntry
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.components.PanelHeader
import org.matrix.vector.manager.ui.theme.currentLocale
import org.matrix.vector.manager.ui.components.SearchField
import org.matrix.vector.manager.ui.theme.VectorMono

class RepoViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RepoViewModel(ServiceLocator.store, ServiceLocator.settings) as T
}

/**
 * The Store: what else there is to install.
 *
 * Its first job is the same as the Modules list's — say what needs attention — so a module with an
 * update waiting sorts above one that is merely interesting, and the header states the number
 * before anyone scrolls. Everything after that is browsing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(
    onModuleClick: (packageName: String) -> Unit,
    viewModel: RepoViewModel = viewModel(factory = RepoViewModelFactory()),
) {
    val entries by viewModel.entries.collectAsState()
    val catalog by viewModel.catalog.collectAsState()
    val query by viewModel.query.collectAsState()
    val refreshing by viewModel.isRefreshing.collectAsState()
    val updates by viewModel.upgradableCount.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val priorities by viewModel.priorities.collectAsState()
    val channel by viewModel.channel.collectAsState()

    Scaffold { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            StoreHeader(
                catalog = catalog,
                updates = updates,
                search = { StoreSearch(query, viewModel, sort, priorities, channel) },
            )

            Spacer(Modifier.height(4.dp))

            // Nothing has ever loaded and a fetch is running: the one moment a spinner says
            // something the list could not say better itself.
            if (!catalog.loaded && entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    if (entries.isEmpty()) {
                        // Inside the list rather than beside it: an empty Box has nothing to
                        // scroll, and pull-to-refresh is exactly what the reader wants when the
                        // reason the list is empty is that the network was down.
                        item {
                            EmptyState(
                                modifier = Modifier.fillParentMaxSize(),
                                catalog = catalog,
                                filtered = query.isNotBlank(),
                            )
                        }
                    } else {
                        items(entries, key = { it.module.name }) { entry ->
                            StoreRow(entry = entry, onClick = { onModuleClick(entry.module.name) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreHeader(catalog: StoreCatalog, updates: Int, search: @Composable () -> Unit) {
    val context = LocalContext.current
    PanelHeader(
        title = stringResource(R.string.nav_store),
        description = {
            if (catalog.modules.isNotEmpty()) {
                val total =
                    context.resources.getQuantityString(
                        R.plurals.store_module_count,
                        catalog.modules.size,
                        catalog.modules.size,
                    )
                // "Up to date" is stated in words rather than as a zero, because a zero in a row
                // of counts reads as a failure to load rather than as good news.
                val state =
                    if (updates > 0)
                        context.resources.getQuantityString(
                            R.plurals.store_update_count,
                            updates,
                            updates,
                        )
                    else stringResource(R.string.store_all_current)
                Text(
                    text = "$total  ·  $state",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        search = search,
    )
}

/** The store search field, as the header's third row. */
@Composable
private fun StoreSearch(
    query: String,
    viewModel: RepoViewModel,
    sort: StoreSort,
    priorities: List<StorePriority>,
    channel: StoreChannel,
) {
    SearchField(
        query = query,
        onQueryChange = viewModel::setQuery,
        placeholder = stringResource(R.string.store_search_hint),
    ) {
        StoreFilterButton(
            sort = sort,
            onSortChange = viewModel::setSort,
            priorities = priorities,
            onTogglePriority = viewModel::togglePriority,
            channel = channel,
            onChannelChange = viewModel::setChannel,
        )
    }
}

/**
 * Sort, priority, channel and DNS — as a sheet, not a menu.
 *
 * A menu is for a short list of like things. This holds two exclusive groups, one multi-select
 * group that ranks its choices, and a network switch, and a menu can only separate them with
 * dividers that say nothing about which group is which. It also sizes itself to its widest child,
 * so the switch's sentence would wrap, and a leading icon on that one row alone would push its
 * label 24dp right of every other. A sheet has room for a heading per group and for the anatomy a
 * boolean setting wants: title, the sentence explaining the cost, and a `Switch` at full width, so
 * nothing wraps in any language. Marquee was considered for the label and rejected — scrolling text
 * hides a choice behind a delay in a list whose purpose is comparing choices, and it fights the
 * reduce-motion setting.
 *
 * DNS-over-HTTPS lives here rather than in a settings screen because this is the panel it exists
 * for: it is the workaround for a network that will not resolve the module mirrors, and there is no
 * settings screen to put it on.
 */
@Composable
private fun StoreFilterButton(
    sort: StoreSort,
    onSortChange: (StoreSort) -> Unit,
    priorities: List<StorePriority>,
    onTogglePriority: (StorePriority) -> Unit,
    channel: StoreChannel,
    onChannelChange: (StoreChannel) -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val narrowed =
        sort != StoreSort.RecentlyUpdated ||
            channel != StoreChannel.Stable ||
            priorities != listOf(StorePriority.Updates)

    IconButton(onClick = { sheetOpen = true }) {
        BadgedBox(badge = { if (narrowed) Badge(modifier = Modifier.size(6.dp)) }) {
            Icon(
                Icons.Rounded.FilterList,
                contentDescription = stringResource(R.string.store_filter),
                tint =
                    if (narrowed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (sheetOpen) {
        StoreFilterSheet(
            sort = sort,
            onSortChange = onSortChange,
            priorities = priorities,
            onTogglePriority = onTogglePriority,
            channel = channel,
            onChannelChange = onChannelChange,
            onDismiss = { sheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreFilterSheet(
    sort: StoreSort,
    onSortChange: (StoreSort) -> Unit,
    priorities: List<StorePriority>,
    onTogglePriority: (StorePriority) -> Unit,
    channel: StoreChannel,
    onChannelChange: (StoreChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    // Left at the default rather than passing skipPartiallyExpanded, which removes the half-height
    // stop — the only thing a drag on a sheet can do other than dismiss it. Material adds that stop
    // only when the content is taller than half the screen, so short sheets still open at their own
    // height and nothing gains a useless drag.
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LocalizedOverlay {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SheetHeading(stringResource(R.string.store_group_sort), Icons.AutoMirrored.Rounded.Sort)
                ChoiceRow {
                    StoreSort.entries.forEach { option ->
                        FilterChip(
                            selected = option == sort,
                            onClick = { onSortChange(option) },
                            label = { Text(stringResource(option.labelRes())) },
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                SheetHeading(
                    stringResource(R.string.store_group_priority),
                    Icons.Rounded.LowPriority,
                )
                ChoiceRow {
                    StorePriority.entries.forEach { priority ->
                        val rank = priorities.indexOf(priority)
                        FilterChip(
                            selected = rank >= 0,
                            onClick = { onTogglePriority(priority) },
                            label = { Text(stringResource(priority.labelRes)) },
                            // Several of these can be on at once, so a tick is not enough — the
                            // one that wins for a module in both groups is the one chosen last,
                            // and the chip says so rather than leaving it to be inferred.
                            leadingIcon =
                                if (rank >= 0 && priorities.size > 1) {
                                    {
                                        Text(
                                            text = "${rank + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                } else null,
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                SheetHeading(
                    stringResource(R.string.store_group_channel),
                    Icons.Rounded.NewReleases,
                )
                ChoiceRow {
                    StoreChannel.entries.forEach { option ->
                        FilterChip(
                            selected = option == channel,
                            onClick = { onChannelChange(option) },
                            label = { Text(stringResource(option.labelRes())) },
                        )
                    }
                }

            }
        }
    }
}

/**
 * A module, as a row.
 *
 * No card, matching the Modules list: what distinguishes rows here is state, and painting each one
 * as a block of colour makes state harder to read rather than easier. The two facts the list exists
 * to answer — *do I already have this* and *is mine out of date* — are on the last line, so they
 * line up down the page and can be skimmed without reading a single description.
 */
@Composable
private fun StoreRow(entry: StoreEntry, onClick: () -> Unit) {
    val module = entry.module
    val colors = MaterialTheme.colorScheme

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = module.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // An identifier, so monospaced — the type rules exist for exactly this.
            text = module.name,
            style = VectorMono,
            color = colors.onSurfaceVariant,
        )
        if (!module.summary.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = module.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // An icon as well as a colour on both badges: under Material You the wallpaper owns
            // the hues, so no state may be distinguishable by colour alone.
            when {
                entry.upgradable ->
                    RowBadge(
                        icon = Icons.Rounded.Upgrade,
                        text =
                            stringResource(
                                if (entry.sameVersion) R.string.store_badge_reinstall
                                else R.string.store_badge_update,
                                entry.latest?.versionName.orEmpty(),
                            ),
                        tint = colors.primary,
                    )
                entry.installed != null ->
                    RowBadge(
                        icon = Icons.Rounded.Check,
                        text = stringResource(R.string.store_badge_installed),
                        tint = colors.onSurfaceVariant,
                    )
            }
            module.latestReleaseTime.asRepositoryDate(currentLocale())?.let { date ->
                if (entry.installed != null) Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.store_updated_on, date),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowBadge(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * The three reasons this list can be empty, which must never render identically.
 *
 * "Nothing matched your search" and "we could not reach the repository" are completely different
 * situations, and only the second one is answered by pulling down to try again.
 *
 * The reason is decided once and both the icon and the sentence are read off it, so they cannot
 * disagree in the case that matters: with a query typed *and* nothing downloaded, an unreachable
 * repository is why the list is empty whatever is in the search box, so it wins. Blaming the
 * reader's query for a network failure would hide the one thing pull-to-refresh fixes.
 */
private enum class StoreEmptiness {
    Unreachable,
    NoMatch,
    NothingPublished,
}

@Composable
private fun EmptyState(modifier: Modifier, catalog: StoreCatalog, filtered: Boolean) {
    val reason =
        when {
            catalog.isEmpty -> StoreEmptiness.Unreachable
            filtered -> StoreEmptiness.NoMatch
            else -> StoreEmptiness.NothingPublished
        }
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                // The icon carries the distinction as much as the sentence does: a struck-out
                // cloud for "we could not reach the repository", a struck-out search for "your
                // query matched none of the modules we do have".
                if (reason == StoreEmptiness.NoMatch) Icons.Rounded.SearchOff
                else Icons.Rounded.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text =
                    stringResource(
                        when (reason) {
                            StoreEmptiness.Unreachable -> R.string.store_unreachable
                            StoreEmptiness.NoMatch -> R.string.store_no_match
                            StoreEmptiness.NothingPublished -> R.string.store_empty
                        }
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun StoreSort.labelRes(): Int =
    when (this) {
        StoreSort.RecentlyUpdated -> R.string.store_sort_recent
        StoreSort.Name -> R.string.store_sort_name
        StoreSort.MostStarred -> R.string.store_sort_stars
    }

private fun StoreChannel.labelRes(): Int =
    when (this) {
        StoreChannel.Stable -> R.string.store_channel_stable
        StoreChannel.Prerelease -> R.string.store_channel_prerelease
    }

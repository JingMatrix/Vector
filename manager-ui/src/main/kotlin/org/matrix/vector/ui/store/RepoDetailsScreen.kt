package org.matrix.vector.ui.store

import org.matrix.vector.ui.R as UiR
import org.matrix.vector.ui.ToggleRow
import org.matrix.vector.ui.ScrollingLabel
import org.matrix.vector.ui.SheetHeading
import org.matrix.vector.ui.sheetRowColors
import org.matrix.vector.ui.theme.Mono
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.MoreVert
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One module, in full — shared between apps.
 *
 * The page is seeded from the catalogue entry the list already holds, so it paints immediately and
 * — because that entry carries the newest release and its APK — can be installed from before the
 * detail request has finished, or at all. A failed fetch costs the README and the older releases,
 * never the page.
 *
 * The install capability is injected as [host] and may be null. With no host the page collapses to
 * browse-and-open: no install bar, no asset picker, no confirm dialog, and the releases offer only a
 * link out. [onOpenUrl] is the caller's — how a URL is opened (in-app browser, system, …) is the
 * app's decision, not this screen's. [fetchSubresource] and [contextForWebView] are handed to the
 * README's [StoreHtmlPane]; both may be null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailsScreen(
    packageName: String,
    onNavigateBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    dataSource: StoreDataSource,
    settings: StoreSettings,
    host: StoreInstallHost? = null,
    fetchSubresource: ((android.webkit.WebResourceRequest) -> android.webkit.WebResourceResponse?)? = null,
    contextForWebView: ((android.content.Context, Boolean) -> android.content.Context)? = null,
) {
    // Installs survive back-navigation, so the scope that carries them cannot be viewModelScope. The
    // host runs the transfer on its own scope anyway; this is the same guarantee should it ever hand
    // the work back to the view model.
    val backgroundScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val viewModel: RepoDetailsViewModel =
        viewModel(
            key = packageName,
            factory =
                viewModelFactory {
                    initializer {
                        RepoDetailsViewModel(packageName, dataSource, settings, host, backgroundScope)
                    }
                },
        )
    val state by viewModel.state.collectAsState()
    val installedScope by viewModel.installedScope.collectAsState()
    val installedIsLegacy by viewModel.installedIsLegacy.collectAsState()
    val install by viewModel.installState.collectAsState()

    var choosing by remember { mutableStateOf<Release?>(null) }
    var optionsOpen by remember { mutableStateOf(false) }
    // The release travels with the asset: what is installed is recorded against the release it came
    // from, and picking an older release from the list must not silence the newest one.
    var confirming by remember { mutableStateOf<Pair<Release, ReleaseAsset>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.module?.title ?: packageName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = packageName,
                            style = Mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(UiR.string.back),
                        )
                    }
                },
                actions = {
                    state.module?.let { module ->
                        IconButton(onClick = { onOpenUrl(module.repoUrl) }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = stringResource(UiR.string.store_open_module),
                            )
                        }
                    }
                    IconButton(onClick = { optionsOpen = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = stringResource(UiR.string.store_options),
                        )
                    }
                },
            )
        },
        bottomBar = {
            // The install bar is the one piece of install UI on a resting page, so it is the gate:
            // no host, no bar.
            if (host != null) {
                InstallBar(
                    state = state,
                    install = install,
                    onInstall = { release ->
                        val assets = release.apks
                        // One file is the overwhelmingly common case, and asking which of one is
                        // noise. More than one and the choice is the user's — some modules ship a
                        // variant per architecture.
                        if (assets.size == 1) confirming = release to assets.first()
                        else choosing = release
                    },
                    onAcknowledge = viewModel::acknowledgeInstall,
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            val module = state.module
            if (module == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.fetch == DetailFetch.Loading) CircularProgressIndicator()
                    else
                        RetryMessage(
                            message = stringResource(UiR.string.store_unreachable),
                            onRetry = viewModel::fetchDetails,
                        )
                }
                return@Column
            }

            val tabs =
                listOf(
                    UiR.string.store_tab_readme,
                    UiR.string.store_tab_releases,
                    UiR.string.store_tab_information,
                )
            val pagerState = rememberPagerState(pageCount = { tabs.size })
            val scope = rememberCoroutineScope()
            // Hoisted so the pager can ask whether the reader is in the middle of a scroll. Kept
            // here rather than inside each tab also means a tab returned to is where it was left.
            val releasesScroll = rememberLazyListState()
            val informationScroll = rememberLazyListState()

            /**
             * Whether the page in front of the reader is moving under their finger.
             *
             * A vertical scroll and a horizontal page turn are siblings in Compose's gesture
             * arbitration, not parent and child, so a drag that is mostly-but-not-entirely vertical
             * — which is every real drag on a phone held in one hand — is split between them: the
             * list scrolls *and* the pager slides partway to the next tab. On a screen whose three
             * tabs are all long documents, that happens constantly while simply reading.
             *
             * So while a list is scrolling the pager stops accepting drags at all, and the gesture
             * cannot be taken away mid-read. It becomes available again the moment the list
             * settles, which is also the moment someone who wants the next tab would ask for it.
             *
             * The README tab is absent on purpose: it is a WebView, which claims its own vertical
             * drags — see `claimVerticalDrags` — so there is no Compose scroll state to read here.
             */
            val reading by remember {
                derivedStateOf {
                    when (pagerState.currentPage) {
                        1 -> releasesScroll.isScrollInProgress
                        2 -> informationScroll.isScrollInProgress
                        else -> false
                    }
                }
            }

            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(stringResource(label)) },
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !reading,
                // And when a page turn *is* offered, it has to be meant. Asking for most of the
                // width makes an accidental sideways component fall back to where it started, the
                // way a navigation gesture does.
                flingBehavior =
                    PagerDefaults.flingBehavior(
                        state = pagerState,
                        snapPositionalThreshold = COMMIT_FRACTION,
                    ),
            ) { page ->
                when (page) {
                    0 ->
                        ReadmeTab(
                            module = module,
                            fetch = state.fetch,
                            onRetry = viewModel::fetchDetails,
                            onOpenUrl = onOpenUrl,
                            fetchSubresource = fetchSubresource,
                            contextForWebView = contextForWebView,
                        )
                    1 ->
                        ReleasesTab(
                            state = state,
                            listState = releasesScroll,
                            canInstall = host != null,
                            onOpenUrl = onOpenUrl,
                            onInstall = { release ->
                                val assets = release.apks
                                if (assets.size == 1) confirming = release to assets.first()
                                else choosing = release
                            },
                        )
                    else ->
                        InformationTab(
                            module = module,
                            listState = informationScroll,
                            installedScope = installedScope,
                            installedIsLegacy = installedIsLegacy,
                            onOpenUrl = onOpenUrl,
                        )
                }
            }
        }
    }

    if (optionsOpen) {
        val muted by viewModel.updatesMuted.collectAsState()
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
        ModalBottomSheet(onDismissRequest = { optionsOpen = false }, sheetState = sheetState) {
            Column(Modifier.padding(bottom = 24.dp)) {
                SheetHeading(stringResource(UiR.string.store_options), Icons.Rounded.Tune)
                ToggleRow(
                    title = stringResource(UiR.string.store_mute_updates),
                    icon = Icons.Rounded.NotificationsOff,
                    checked = muted,
                    onCheckedChange = viewModel::setUpdatesMuted,
                    subtitle = stringResource(UiR.string.store_mute_updates_summary),
                )
            }
        }
    }

    choosing?.let { release ->
        AssetSheet(
            release = release,
            onDismiss = { choosing = null },
            onPick = { asset ->
                choosing = null
                confirming = release to asset
            },
        )
    }

    // Guarded on the host: the confirm dialog is install UI, and its silent-mode hint is the host's
    // to report. Without one, nothing ever sets `confirming` anyway.
    host?.let { installHost ->
        confirming?.let { (release, asset) ->
            ConfirmInstall(
                module = state.module,
                packageName = packageName,
                asset = asset,
                silent = installHost.silentInstall,
                onDismiss = { confirming = null },
                onConfirm = {
                    confirming = null
                    viewModel.install(asset, release.version)
                },
            )
        }
    }
}

/**
 * The primary action, on every tab.
 *
 * It lives in a bar rather than on the Releases tab because installing is what the page is *for*,
 * and burying it one swipe away behind a tab makes the reader hunt for it after they have decided.
 */
@Composable
private fun InstallBar(
    state: RepoDetailsState,
    install: InstallStep,
    onInstall: (Release) -> Unit,
    onAcknowledge: () -> Unit,
) {
    val context = LocalContext.current
    val newest = state.releases.firstOrNull { it.apks.isNotEmpty() } ?: return

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            when (install) {
                is InstallStep.Downloading -> {
                    val done = Formatter.formatShortFileSize(context, install.bytes)
                    val total = Formatter.formatShortFileSize(context, install.total)
                    Text(
                        text = stringResource(UiR.string.store_downloading, done, total),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (install.total > 0) {
                        LinearProgressIndicator(
                            progress = { install.bytes.toFloat() / install.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is InstallStep.Installing,
                is InstallStep.Confirming -> {
                    Text(
                        text =
                            stringResource(
                                if (install is InstallStep.Confirming) UiR.string.store_confirming
                                else UiR.string.store_installing
                            ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is InstallStep.Failed -> {
                    // Named, not swallowed: an install that fails silently leaves the user with a
                    // button that appears to do nothing.
                    Text(
                        text =
                            install.reason?.let {
                                stringResource(
                                    UiR.string.store_install_failed_reason,
                                    install.packageName,
                                    it,
                                )
                            } ?: stringResource(UiR.string.store_install_failed, install.packageName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    // The same body as the resting button below, because this is the same press:
                    // clearing the failure on its own would only put the Install button back and
                    // leave the reader to press it again, which is a retry that retries nothing.
                    TextButton(
                        onClick = {
                            onAcknowledge()
                            onInstall(newest)
                        }
                    ) {
                        Text(stringResource(UiR.string.retry))
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            onAcknowledge()
                            onInstall(newest)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        // One line, whatever the version name is: a button that grows a second row
                        // to fit a build identifier moves the bar under the reader's thumb.
                        ScrollingLabel(
                            text =
                                when {
                                    state.upgradable ->
                                        stringResource(
                                            if (state.sameVersion) UiR.string.store_badge_reinstall
                                            else UiR.string.store_badge_update,
                                            state.latest?.versionName.orEmpty(),
                                        )
                                    state.installed != null ->
                                        stringResource(UiR.string.store_reinstall)
                                    else -> stringResource(UiR.string.store_install)
                                },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadmeTab(
    module: OnlineModule,
    fetch: DetailFetch,
    onRetry: () -> Unit,
    onOpenUrl: (String) -> Unit,
    fetchSubresource: ((android.webkit.WebResourceRequest) -> android.webkit.WebResourceResponse?)?,
    contextForWebView: ((android.content.Context, Boolean) -> android.content.Context)?,
) {
    val readme = module.readmeHTML
    when {
        !readme.isNullOrBlank() ->
            StoreHtmlPane(
                html = readme,
                modifier = Modifier.fillMaxSize(),
                onOpenUrl = onOpenUrl,
                fetchSubresource = fetchSubresource,
                contextForWebView = contextForWebView,
            )
        // A spinner while the request is in flight, rather than "no readme" — which would be a
        // statement about the module when it is really a statement about the network.
        fetch == DetailFetch.Loading ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        fetch == DetailFetch.Unavailable ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RetryMessage(stringResource(UiR.string.store_detail_partial), onRetry)
            }
        else ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(UiR.string.store_readme_missing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@Composable
private fun ReleasesTab(
    state: RepoDetailsState,
    listState: LazyListState,
    /** Whether releases may be installed from here — false collapses each card to "Open release". */
    canInstall: Boolean,
    onOpenUrl: (String) -> Unit,
    onInstall: (Release) -> Unit,
) {
    if (state.releases.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(UiR.string.store_releases_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    // One expanded set of notes at a time, and the newest starts open: "what changed" is the
    // question this tab is opened to answer, while five releases with their notes open is a wall of
    // text with no structure and the list stops being skimmable.
    //
    // The default is keyed by *which* release is newest, not by the list object. The view model's
    // combine rebuilds that list on every emission — an installed-version refresh, a channel
    // change, the detail fetch landing — and keying on it would re-apply the default and reopen the
    // notes under a reader who had just closed them, for reasons that had nothing to do with them.
    val newest = state.releases.firstOrNull()?.key(0)
    var expanded by remember(newest) { mutableStateOf<String?>(newest) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(state.releases, key = { index, release -> release.key(index) }) { index, release
            ->
            val key = release.key(index)
            ReleaseCard(
                release = release,
                // Compared on the version code, which is what the platform compares. Two
                // releases can carry the same name and be different builds.
                installed =
                    release.version != null && state.installed?.versionCode == release.version?.versionCode,
                canInstall = canInstall,
                notesOpen = expanded == key,
                onToggleNotes = { expanded = if (expanded == key) null else key },
                onOpenUrl = onOpenUrl,
                onInstall = { onInstall(release) },
            )
            if (index < state.releases.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

/**
 * One release.
 *
 * Not a card, despite the name. An outlined box around every entry turns a list of five releases
 * into five framed panels competing with each other and with the notes inside them; a rule between
 * plain rows reads as a list, which is what the rest of the app does.
 *
 * The two facts that decide anything — is this newer than what I have, and is it a prerelease — are
 * badges rather than grey words in a row of other grey words, and installing is a filled button
 * rather than one of two identical text buttons.
 */
@Composable
private fun ReleaseCard(
    release: Release,
    installed: Boolean,
    canInstall: Boolean,
    notesOpen: Boolean,
    onToggleNotes: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onInstall: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val locale = currentLocale()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = release.name ?: release.tagName.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // On the prerelease channel this list is stable and beta merged, ordered by version
            // code, so an entry has to say which of the two it is. Unmarked, the only difference
            // would be a tag string nobody reads as a channel.
            if (release.isPrerelease == true) {
                ReleaseBadge(
                    text = stringResource(UiR.string.store_badge_prerelease),
                    container = colors.tertiaryContainer,
                    content = colors.onTertiaryContainer,
                )
            }
            if (installed) {
                Spacer(Modifier.width(6.dp))
                ReleaseBadge(
                    text = stringResource(UiR.string.store_badge_installed),
                    container = colors.secondaryContainer,
                    content = colors.onSecondaryContainer,
                )
            }
        }

        Spacer(Modifier.height(3.dp))
        // The version line doubles as the notes' disclosure. A release's tag, its date and its
        // notes are one object, so the line that names it is where you press to see more of it,
        // the way every expandable row on the platform behaves — rather than spending a second row
        // on "Show the release notes". The chevron turns rather than swapping glyphs, which says
        // the same thing without a word to read.
        val hasNotes = !release.descriptionHTML.isNullOrBlank()
        val chevron by animateFloatAsState(if (notesOpen) 180f else 0f, label = "notesChevron")
        val disclose =
            stringResource(
                if (notesOpen) UiR.string.store_release_notes_hide else UiR.string.store_release_notes
            )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .then(
                        if (!hasNotes) Modifier
                        else
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onToggleNotes)
                    )
                    .padding(vertical = 4.dp),
        ) {
            // The tag and the date share what is left after the chevron, and the date is served
            // first: it is a fixed handful of characters, while a tag is whatever the publisher
            // wrote and is regularly longer than the screen. Measured the other way round the tag
            // would push the date into a column one character wide.
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // The tag, not the name: it carries the version code, which is what
                // actually decides whether the platform will accept this over what is
                // installed.
                release.tagName?.let {
                    ScrollingLabel(
                        text = it,
                        style = Mono,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                release.publishedAt.asRepositoryDate(locale)?.let {
                    if (release.tagName != null) {
                        Text(
                            text = "  ·  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.outlineVariant,
                        )
                    }
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (hasNotes) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = disclose,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(chevron),
                )
            }
        }

        if (hasNotes) {
            if (notesOpen) {
                Spacer(Modifier.height(8.dp))
                // Plain text at its natural height. The list is the only thing that scrolls on
                // this screen, and it stays that way.
                val notes =
                    remember(release.descriptionHTML, colors.primary) {
                        releaseNotes(
                            html = release.descriptionHTML.orEmpty(),
                            linkColor = colors.primary,
                            codeColor = colors.tertiary,
                        )
                    }
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            release.url?.let { url ->
                TextButton(onClick = { onOpenUrl(url) }) {
                    Text(stringResource(UiR.string.store_open_release))
                }
                Spacer(Modifier.width(8.dp))
            }
            if (canInstall && release.apks.isNotEmpty()) {
                FilledTonalButton(onClick = onInstall) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(UiR.string.store_install))
                }
            }
        }
    }
}

/** A fact worth noticing, as a pill rather than another grey word in a row of grey words. */
@Composable
private fun ReleaseBadge(text: String, container: Color, content: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier =
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(container)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun InformationTab(
    module: OnlineModule,
    listState: LazyListState,
    /** What the copy on this device declares, when the catalogue declares nothing. */
    installedScope: List<String>,
    /** Whether that copy is a legacy module, which decides how to read the catalogue's scope. */
    installedIsLegacy: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    // Hoisted: the rows below are emitted from a LazyListScope, which is not a composable.
    val locale = currentLocale()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (!module.summary.isNullOrBlank()) {
            item {
                InfoRow(
                    icon = Icons.Rounded.Code,
                    label = stringResource(UiR.string.store_info_summary),
                    value = module.summary.orEmpty(),
                )
            }
        }
        item {
            // The single most useful fact before installing anything: which apps this reaches
            // into. The catalogue first, because it describes the published module. Failing that,
            // what the installed copy declares in its own APK — accurate for the build actually on
            // this device, and labelled as such so the two are not confused.
            //
            // The catalogue's list is written in the module's own vocabulary, and a legacy
            // module's is the reverse of everything else here: its "android" is the system server
            // and its "system" is the ordinary android package. The installed list beside it has
            // already been through that swap on its way out of the APK, so without this the same
            // module can name the same target two different ways in two adjacent lines.
            val published =
                module.scope
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        if (installedIsLegacy) swapLegacyFrameworkNames(it) else it
                    }
            InfoRow(
                icon = Icons.Rounded.TrackChanges,
                label =
                    if (published == null && installedScope.isNotEmpty())
                        stringResource(UiR.string.store_info_scope_installed)
                    else stringResource(UiR.string.store_info_scope),
                value =
                    published?.joinToString("\n")
                        ?: installedScope.takeIf { it.isNotEmpty() }?.joinToString("\n")
                        ?: stringResource(UiR.string.store_info_scope_undeclared),
            )
        }
        module.homepageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            item {
                InfoRow(
                    icon = Icons.Rounded.Language,
                    label = stringResource(UiR.string.store_info_homepage),
                    value = url,
                    onClick = { onOpenUrl(url) },
                )
            }
        }
        module.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
            item {
                InfoRow(
                    icon = Icons.Rounded.Code,
                    label = stringResource(UiR.string.store_info_source),
                    value = url,
                    onClick = { onOpenUrl(url) },
                )
            }
        }
        module.collaborators?.takeIf { it.isNotEmpty() }?.let { people ->
            item {
                InfoRow(
                    icon = Icons.Rounded.Group,
                    label = stringResource(UiR.string.store_info_collaborators),
                    value =
                        (people.mapNotNull { it.name ?: it.login } +
                                module.additionalAuthors.orEmpty().mapNotNull { it.name })
                            .joinToString(", "),
                )
            }
        }
        module.stargazerCount?.takeIf { it > 0 }?.let { stars ->
            item {
                InfoRow(
                    icon = Icons.Rounded.Star,
                    label = stringResource(UiR.string.store_info_stars),
                    value = stars.toString(),
                )
            }
        }
        module.latestReleaseTime.asRepositoryDate(locale)?.let { date ->
            item {
                InfoRow(
                    icon = Icons.Rounded.Today,
                    label = stringResource(UiR.string.store_info_updated),
                    value = date,
                )
            }
        }
        module.createdAt.asRepositoryDate(locale)?.let { date ->
            item {
                InfoRow(
                    icon = Icons.Rounded.Today,
                    label = stringResource(UiR.string.store_info_created),
                    value = date,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        supportingContent = { Text(value) },
        leadingContent = { Icon(icon, contentDescription = null) },
    ) { Text(label) }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

/** Shown only when a release ships more than one APK — an architecture split, usually. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetSheet(release: Release, onDismiss: () -> Unit, onPick: (ReleaseAsset) -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(UiR.string.store_choose_asset),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        release.apks.forEach { asset ->
            ListItem(
                modifier = Modifier.clickable { onPick(asset) },
                supportingContent = {
                    val size = Formatter.formatShortFileSize(context, asset.size)
                    val downloads =
                        asset.downloadCount?.let {
                            context.resources.getQuantityString(
                                UiR.plurals.store_asset_downloads,
                                it,
                                it,
                            )
                        }
                    Text(listOfNotNull(size, downloads).joinToString("  ·  "))
                },
                colors = sheetRowColors,
            ) { Text(asset.name.orEmpty()) }
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

/**
 * How much of the width a drag must cover for the tab to change.
 *
 * Above `PagerDefaults`' half. The problem is not that the wrong tab arrives, it is that one
 * arrives at all while someone is reading, so the bar for "yes, they meant this" is set where an
 * accidental sideways component cannot reach it.
 */
private const val COMMIT_FRACTION = 0.65f

/** The active locale, for date formatting. Read from the configuration the composition runs under. */
@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
private fun RetryMessage(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry) { Text(stringResource(UiR.string.retry)) }
    }
}

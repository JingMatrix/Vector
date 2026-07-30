package org.matrix.vector.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.matrix.vector.manager.ui.navigation.FrameworkUpdate
import org.matrix.vector.manager.ui.screens.update.FrameworkUpdateScreen
import org.matrix.vector.manager.ui.navigation.Canary
import org.matrix.vector.manager.ui.screens.canary.CanaryScreen
import org.matrix.vector.manager.ui.navigation.Troubleshoot
import org.matrix.vector.manager.ui.screens.report.TroubleshootScreen
import org.matrix.vector.manager.ui.navigation.DeepLink
import org.matrix.vector.manager.ui.navigation.LocalNavigator
import org.matrix.vector.manager.ui.navigation.Navigator
import org.matrix.vector.manager.ui.navigation.PanelBar
import org.matrix.vector.manager.ui.navigation.PanelEditDone
import org.matrix.vector.manager.ui.navigation.Scope
import org.matrix.vector.manager.ui.navigation.StoreDetail
import org.matrix.vector.manager.ui.navigation.CrashTrace
import org.matrix.vector.manager.ui.navigation.LogTrace
import org.matrix.vector.manager.ui.navigation.SystemStatus
import org.matrix.vector.manager.ui.navigation.Web
import org.matrix.vector.manager.ui.navigation.TopLevelRoute
import org.matrix.vector.manager.ui.navigation.rememberNavigator
import org.matrix.vector.manager.ui.screens.home.HomeScreen
import org.matrix.vector.manager.ui.screens.home.CrashTraceScreen
import org.matrix.vector.manager.ui.screens.home.SystemStatusScreen
import org.matrix.vector.manager.ui.screens.logs.LogTraceScreen
import org.matrix.vector.manager.ui.screens.logs.LogsScreen
import org.matrix.vector.manager.ui.screens.modules.ModulesScreen
import org.matrix.vector.manager.ui.screens.modules.ScopeScreen
import org.matrix.vector.manager.ui.screens.repo.RepoDetailsScreen
import org.matrix.vector.manager.ui.screens.repo.RepoScreen
import org.matrix.vector.manager.ui.screens.web.WebScreen

/**
 * The app shell.
 *
 * [NavigationSuiteScaffold] picks the navigation container from the window size — a bottom bar on a
 * phone, a rail when there is width to spare. That is not decoration: from targetSdk 37 an app may
 * no longer lock itself to portrait or declare itself non-resizable on large screens, so the shell
 * has to work unfolded and in landscape regardless. The scaffold also owns where that container
 * sits, so the destinations below it are laid out beside or above it rather than under it.
 *
 * Which panels that container holds, in which order, is the reader's — see NavPanels.
 */
@Composable
fun VectorApp() {
    val navigator = rememberNavigator()

    // Where the launch intent asked to open. The activity has no back stack to act on, so it leaves
    // the destination here and this is the first place there is one — on a cold start the splash is
    // still playing when the intent arrives.
    val pending by DeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val destination = DeepLink.consume() ?: return@LaunchedEffect
        // Already there, so nothing to do — and doing it anyway would not be nothing: switching
        // tabs empties the back stack and builds it again, and the scope editor's draft lives in a
        // ViewModel scoped to the entry that would be thrown away with it. The reader who taps the
        // notification of the module already open in front of them is the case this covers.
        //
        // It is not what keeps a rotation harmless. Whether an offer is a launch or a recreation
        // replaying the intent it was created with is decided in DeepLink, which knows what it last
        // applied; here there is only where the reader is standing.
        if (navigator.current == (destination.detail ?: destination.tab)) return@LaunchedEffect
        // The tab goes down first and the screen on top of it: a notification about a module opens
        // that module's scope editor, and back from there should be the module list rather than the
        // door out of the app it just opened. Switching also discards whatever detail screen was
        // already up, so the reader is not left with a stale one buried underneath.
        navigator.switchTo(destination.tab)
        destination.detail?.let { navigator.go(it) }
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        val editing = navigator.editingPanels
        // The container shows only at the root of a panel. On a detail screen none of the items is
        // the current destination, and a navigation bar highlighting nothing is worse than none.
        val atRoot = !navigator.canGoBack

        // Driving the scaffold's own state rather than dropping the items: hiding the items alone
        // leaves the container laid out, so a detail screen — the in-app browser especially —
        // keeps a dead strip of navigation-bar-sized space at the bottom.
        val suiteState = rememberNavigationSuiteScaffoldState()
        LaunchedEffect(atRoot) { if (atRoot) suiteState.show() else suiteState.hide() }

        // Computed rather than left to the scaffold's default because PanelBar has to be told which
        // axis it is laying items along, and the scaffold keeps that decision to itself otherwise.
        val suiteType =
            NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())

        NavigationSuiteScaffold(
            navigationItems = {
                PanelBar(
                    panels = navigator.panels,
                    current = navigator.currentTopLevel,
                    editing = editing,
                    suiteType = suiteType,
                    onSelect = { route -> navigator.switchTo(route) },
                    onEdit = { navigator.editingPanels = true },
                    onToggleHidden = { key, hidden -> navigator.setPanelHidden(key, hidden) },
                    onMove = { from, to -> navigator.movePanel(from, to) },
                )
            },
            navigationSuiteType = suiteType,
            state = suiteState,
            primaryActionContent = {
                if (editing) PanelEditDone(onDone = { navigator.editingPanels = false })
            },
        ) {
            NavDisplay(
                backStack = navigator.backStack,
                onBack = { navigator.back() },
                // Naming any decorator replaces NavDisplay's default, which is the saveable-state
                // one alone, so it is repeated here; the scene-setup decorator NavDisplay applies
                // internally is untouched. The ViewModel one is what this list is for: it scopes a
                // ViewModelStore per entry, so opening the scope editor for a second module builds
                // a second ViewModel instead of reusing the first (they would otherwise share one
                // default key under the activity's store).
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                entryProvider = entryProvider { registerRoutes(navigator) },
            )
        }

        // After the scaffold on purpose. Back callbacks are dispatched last-registered-first and
        // BackHandler registers from an effect, which run in composition order, so this one
        // outranks the handler NavDisplay installs and edit mode ends before the stack is touched.
        BackHandler(enabled = editing) { navigator.editingPanels = false }
    }
}

/**
 * Every destination, registered.
 *
 * All four panels keep their entry whether or not the reader has hidden them. A saved stack names
 * its keys by class, and entryProvider throws for one it was never given, so dropping the
 * registration of a hidden panel would turn a stale saved stack into a crash.
 */
private fun EntryProviderScope<NavKey>.registerRoutes(navigator: Navigator) {
    entry<TopLevelRoute.Home> {
        HomeScreen(
            onOpenStatus = { navigator.go(SystemStatus) },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            onOpenCanary = { navigator.go(Canary) },
            onOpenReport = { navigator.go(Troubleshoot) },
            onOpenUpdate = { navigator.go(FrameworkUpdate()) },
        )
    }
    entry<TopLevelRoute.Modules> {
        ModulesScreen(
            onModuleClick = { packageName, userId -> navigator.go(Scope(packageName, userId)) },
            onOpenStore = { packageName -> navigator.go(StoreDetail(packageName)) },
        )
    }
    entry<TopLevelRoute.Store> {
        RepoScreen(onModuleClick = { packageName -> navigator.go(StoreDetail(packageName)) })
    }
    entry<TopLevelRoute.Logs> { LogsScreen(onOpenTrace = { text -> navigator.go(LogTrace(text)) }) }

    entry<Scope> { route ->
        ScopeScreen(
            packageName = route.packageName,
            userId = route.userId,
            onNavigateBack = { navigator.back() },
        )
    }
    entry<StoreDetail> { route ->
        RepoDetailsScreen(packageName = route.packageName, onNavigateBack = { navigator.back() })
    }
    entry<SystemStatus> {
        SystemStatusScreen(
            onNavigateBack = { navigator.back() },
            onOpenCrash = { navigator.go(CrashTrace) },
        )
    }
    entry<CrashTrace> { CrashTraceScreen(onNavigateBack = { navigator.back() }) }
    entry<LogTrace> { route ->
        LogTraceScreen(text = route.text, onNavigateBack = { navigator.back() })
    }
    entry<Troubleshoot> {
        TroubleshootScreen(
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            onOpenCanary = { navigator.go(Canary) },
        )
    }
    entry<Canary> {
        CanaryScreen(
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            onInstall = { versionCode -> navigator.go(FrameworkUpdate(versionCode)) },
        )
    }
    entry<FrameworkUpdate> { route ->
        FrameworkUpdateScreen(
            openOnVersionCode = route.versionCode.takeIf { it > 0 },
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
        )
    }
    entry<Web> { route -> WebScreen(url = route.url, onNavigateBack = { navigator.back() }) }
}

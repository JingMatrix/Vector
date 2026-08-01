package org.matrix.vector.manager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.matrix.vector.manager.R

/**
 * Every destination, as a type.
 *
 * Navigation 3 models the back stack as a plain observable list of these rather than a graph of
 * route strings, so an argument like a module's user id is a constructor parameter and cannot be
 * mis-parsed out of a URL-shaped route.
 */
@Serializable sealed interface Route : NavKey

/**
 * Every panel that exists, and the order a fresh install starts with.
 *
 * Not the order on screen: the reader's own order, and which panels they have hidden, live in
 * SettingsRepository under `nav_panels` and are modelled by NavPanels. What is declared here is the
 * catalogue and the default.
 *
 * Hiding a panel never removes it from this file. The back stack persists NavKeys by class name —
 * NavKeySerializer resolves them with a bare `Class.forName` and has no fallback — and NavDisplay's
 * entryProvider throws for a key it was never given, so a saved stack naming a panel that had been
 * deleted would be a crash rather than a stale tab. Hiding is a fact about the navigation
 * container, and about nothing else.
 */
@Serializable
sealed interface TopLevelRoute : Route {
    @Serializable data object Home : TopLevelRoute

    @Serializable data object Modules : TopLevelRoute

    @Serializable data object Store : TopLevelRoute

    @Serializable data object Logs : TopLevelRoute
}

@Serializable data class Scope(val packageName: String, val userId: Int) : Route

@Serializable data class StoreDetail(val packageName: String) : Route

@Serializable data object SystemStatus : Route

/**
 * The newest recorded crash, frame by frame.
 *
 * Carries no argument: there is only ever one crash worth opening — the newest — and the screen
 * reads it from disk itself, so the route survives the process death that following a crash report
 * is unusually likely to involve.
 */
@Serializable data object CrashTrace : Route

/**
 * A stack trace found in the log, on a screen of its own.
 *
 * Carries the text rather than a line number, because the log window it came from is paged and
 * filtered and may have moved on by the time this is opened — and because the text is the whole of
 * what the screen needs. A trace is a few kilobytes at worst, which the back stack can hold.
 */
@Serializable data class LogTrace(val text: String) : Route

/** CI builds, as prereleases anyone can download. */
@Serializable data object Canary : Route

/** What to try, and what to bring, before opening an issue. */
@Serializable data object Troubleshoot : Route

/**
 * What is in a build, and the installer's output while it is flashed.
 *
 * [versionCode] names the build to open on, which is how the canary list hands one over; 0 means
 * "whichever is worth offering", the screen's own default.
 */
@Serializable data class FrameworkUpdate(val versionCode: Long = 0) : Route

/** GitHub, shown in the built-in viewer rather than handed to a browser. */
@Serializable data class Web(val url: String) : Route

/**
 * Label and icon for a bar item. Titles come from resources; no hard-coded English.
 *
 * [key] is the only stable identity this type has, and so the only thing that is ever written to
 * preferences: R8 rewrites class names in a release build, and an ordinal would quietly name a
 * different panel the day a fifth one is declared. See NavPanels for what is stored.
 */
data class TopLevelDestination(
    val key: String,
    val route: TopLevelRoute,
    val icon: ImageVector,
    val labelRes: Int,
)

val TOP_LEVEL_DESTINATIONS: List<TopLevelDestination> =
    listOf(
        TopLevelDestination("home", TopLevelRoute.Home, Icons.Rounded.Home, R.string.nav_home),
        TopLevelDestination(
            "modules",
            TopLevelRoute.Modules,
            Icons.Rounded.Extension,
            R.string.nav_modules,
        ),
        // A cloud, not a shopfront. Nothing here is sold, and the tab's real subject is "modules
        // that live somewhere else and can be brought here".
        TopLevelDestination(
            "store",
            TopLevelRoute.Store,
            Icons.Rounded.CloudDownload,
            R.string.nav_store,
        ),
        TopLevelDestination(
            "logs",
            TopLevelRoute.Logs,
            Icons.AutoMirrored.Rounded.ReceiptLong,
            R.string.nav_logs,
        ),
    )

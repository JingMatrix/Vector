package org.matrix.vector.manager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ReceiptLong
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

/** The four destinations in the navigation bar. Order here is the order on screen. */
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

/** CI builds, as prereleases anyone can download. */
@Serializable data object Canary : Route

/** What to try, and what to bring, before opening an issue. */
@Serializable data object Troubleshoot : Route

/** What is in the newest build, and the installer's output while it is flashed. */
@Serializable data object FrameworkUpdate : Route

/** GitHub, shown in the built-in viewer rather than handed to a browser. */
@Serializable data class Web(val url: String) : Route

/** Label and icon for a bar item. Titles come from resources; no hard-coded English. */
data class TopLevelDestination(
    val route: TopLevelRoute,
    val icon: ImageVector,
    val labelRes: Int,
)

val TOP_LEVEL_DESTINATIONS: List<TopLevelDestination> =
    listOf(
        TopLevelDestination(TopLevelRoute.Home, Icons.Rounded.Home, R.string.nav_home),
        TopLevelDestination(TopLevelRoute.Modules, Icons.Rounded.Extension, R.string.nav_modules),
        // A cloud, not a shopfront. Nothing here is sold, and the tab's real subject is "modules
        // that live somewhere else and can be brought here".
        TopLevelDestination(TopLevelRoute.Store, Icons.Rounded.CloudDownload, R.string.nav_store),
        TopLevelDestination(TopLevelRoute.Logs, Icons.Rounded.ReceiptLong, R.string.nav_logs),
    )

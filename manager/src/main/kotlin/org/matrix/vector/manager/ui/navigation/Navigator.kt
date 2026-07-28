package org.matrix.vector.manager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * The back stack, as an object with intent-revealing operations.
 *
 * Navigation 3 hands you the stack as a plain observable list, so this is a handful of operations
 * over that list rather than a graph definition.
 *
 * Switching tabs truncates to a single root entry, so the stack can never grow without bound and
 * system back leaves the app instead of retracing every tab that was visited.
 */
@Stable
class Navigator(val backStack: NavBackStack<NavKey>) {

    val current: NavKey?
        get() = backStack.lastOrNull()

    /** Which bar item is highlighted — always the root of the stack. */
    val currentTopLevel: TopLevelRoute
        get() = backStack.firstOrNull() as? TopLevelRoute ?: TopLevelRoute.Home

    val canGoBack: Boolean
        get() = backStack.size > 1

    /** Push a detail destination on top of the current tab. */
    fun go(route: Route) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    /** Select a bar item, discarding whatever detail screens were open. */
    fun switchTo(tab: TopLevelRoute) {
        if (backStack.size == 1 && backStack.firstOrNull() == tab) return
        backStack.clear()
        backStack.add(tab)
    }

    /** Returns false when there is nothing left to pop, so the caller can let the system exit. */
    fun back(): Boolean {
        if (!canGoBack) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator in composition") }

@Composable
fun rememberNavigator(): Navigator {
    // rememberNavBackStack persists across process death via SavedState, which matters here:
    // parasitically the manager's activity state is hand-managed by the zygisk hooker, so
    // anything that relies on the system restoring it needs to survive that path too.
    val backStack = rememberNavBackStack(TopLevelRoute.Home)
    return remember(backStack) { Navigator(backStack) }
}

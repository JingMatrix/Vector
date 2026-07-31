package org.matrix.vector.manager.ui.navigation

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Where a launch intent asked the app to open: a tab, and at most one screen above it.
 *
 * [tab] is not decoration. Pushing [detail] onto whatever the reader happened to be looking at
 * would leave them one back press away from a screen they never opened, and on a cold start — where
 * the stack is nothing but Home — one back press away from leaving the app the notification just
 * brought them into. Laying the tab down first gives the destination somewhere to go back to.
 */
data class PendingDestination(val tab: TopLevelRoute, val detail: Route? = null)

/**
 * The destination a launch intent named, handed from the activity to the composition.
 *
 * The framework encodes the module a notification is about in the intent's data as
 * `module://<packageName>:<userId>`; the daemon copies that Uri onto the manager's launch intent,
 * and it survives the parasitic redirection intact, because the zygisk hooker rewrites the resolved
 * activity and never the intent. So by the time the activity starts, the intent really does say
 * which module the reader tapped a notification about — but the activity has no back stack to act
 * on. That belongs to the composition, which on a cold start does not exist yet because the splash
 * is still playing. This is the hand-off across that gap.
 *
 * [consume] empties the flow, and emptying it is the point: a destination left behind would be
 * applied again on the next recomposition after a configuration change, dragging the reader back to
 * the module's scope editor every time they rotated the phone away from it.
 */
object DeepLink {

    private val _pending = MutableStateFlow<PendingDestination?>(null)

    /** Non-null while a launch intent's destination is waiting for the shell to apply it. */
    val pending: StateFlow<PendingDestination?> = _pending.asStateFlow()

    /**
     * Records where [intent] asks the app to open, replacing whatever was waiting.
     *
     * The newest launch wins, and an intent naming nothing this understands clears the field rather
     * than leaving it. That looks like the more destructive of the two choices and is the safer
     * one: this object outlives the activity, and a destination can be offered and never applied —
     * `SplashGate` holds the shell out of the composition for the best part of a second, and a back
     * press inside that window finishes the activity before anything consumes it. Left in place, it
     * would be applied to the *next* launch instead, so opening the app from the launcher would
     * drop the reader into the scope editor of a module they were last told about.
     *
     * Nothing is steered by the clearing itself. The status notification carries no data at all and
     * the `*#*#832867#*#*` dialer code arrives under its own `android_secret_code` scheme; neither
     * has a screen in mind, and Home — or wherever the reader last was — is the right answer for
     * both.
     */
    fun offer(intent: Intent?) {
        _pending.value = parse(intent)
    }

    /** Takes the waiting destination, leaving nothing for a later recomposition to re-apply. */
    fun consume(): PendingDestination? = _pending.getAndUpdate { null }

    private fun parse(intent: Intent?): PendingDestination? {
        val data = intent?.data ?: return null
        if (data.scheme == MODULE_SCHEME) return moduleScope(data)

        // The bare strings the pre-Compose manager accepted. Nothing in this build sends one — the
        // pinned launcher shortcut carries no data, and what the framework sends is either nothing,
        // the module scheme above or the dialer code's own — but they were this app's launch
        // contract for years and honouring one costs a branch. "settings" is deliberately absent:
        // settings are sheets raised from Home rather than a destination of their own, so there is
        // no screen to open and guessing at one would be worse than ignoring the request.
        return when (data.toString()) {
            "modules" -> PendingDestination(TopLevelRoute.Modules)
            "logs" -> PendingDestination(TopLevelRoute.Logs)
            "repo" -> PendingDestination(TopLevelRoute.Store)
            else -> null
        }
    }

    /**
     * `module://<packageName>:<userId>`, taken apart exactly the way the framework put it together.
     *
     * The authority is built with `encodedAuthority("$packageName:$userId")` and split back out
     * with a plain `split(":", limit = 2)` on the daemon's own side, so doing the same here is what
     * keeps the two ends agreeing about where the package name stops.
     *
     * [Uri.getHost] and [Uri.getPort] look like the obvious reading and quietly answer something
     * else. Neither can fail: an authority the platform cannot find a numeric port in is handed
     * back whole as the host, with -1 for the port — so `module://com.example.foo:bad` would open
     * the scope editor for a package literally named `com.example.foo:bad` under user -1 rather
     * than being rejected. Splitting by hand makes a user id that is not a number no link at all.
     */
    private fun moduleScope(data: Uri): PendingDestination? {
        val parts = data.encodedAuthority?.split(":", limit = 2) ?: return null
        if (parts.size != 2) return null
        val packageName = parts[0]
        if (packageName.isEmpty()) return null
        val userId = parts[1].toIntOrNull() ?: return null
        return PendingDestination(TopLevelRoute.Modules, Scope(packageName, userId))
    }
}

/** The framework's scheme for "open the manager on this module". */
private const val MODULE_SCHEME = "module"

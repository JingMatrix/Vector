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
 * and it survives the parasitic redirection intact. That redirection touches the intent in exactly
 * one way — `ParasiticManagerHooker` rewrites the *component* of every intent passing through
 * `ActivityClientRecord` to this manager's `MainActivity` — while `ParasiticManagerSystemHooker`
 * answers `system_server`'s resolution with a copy of the host activity's `ActivityInfo` and leaves
 * the intent alone. Neither touches the data Uri, and neither touches the extras, which is the
 * answer to the next reader wondering what else may safely ride on a launch intent. (The hooker
 * does more besides, including capturing and restoring saved state; none of it is the intent.) So
 * by the time the activity starts, the intent really does
 * say which module the reader tapped a notification about — but the activity has no back stack to
 * act on. That belongs to the composition, which on a cold start does not exist yet because the
 * splash is still playing. This is the hand-off across that gap.
 *
 * [consume] empties the flow, and emptying it is the point: a destination left behind would be
 * applied again on the next recomposition after a configuration change, dragging the reader back to
 * the module's scope editor every time they rotated the phone away from it.
 */
object DeepLink {

    private val _pending = MutableStateFlow<PendingDestination?>(null)

    /**
     * What [consume] last handed to the shell, for as long as this process lives.
     *
     * This is the only thing that tells a launch apart from a replay of one. `getIntent()` answers
     * the intent the activity was *created* with for the whole life of the task — the platform
     * documents that on `Activity.onNewIntent` itself — so every recreation of the activity offers
     * that same intent again, minutes or hours after its destination was already applied.
     *
     * A process kill empties this, and a task restored from recents afterwards is handed the
     * original intent once more, so the link is applied there a second time. What that costs is the
     * restored position and nothing else: `Navigator` persists the back stack across process death
     * through SavedState, so the reader does come back to a stack they will then be moved off — but
     * no unapplied edit can survive the kill, the scope editor's draft being plain state flows in a
     * ViewModel. Landing on the link's destination is what a cold start from that notification does
     * anyway, so it is the same screen reached a different way rather than work thrown away.
     */
    private var lastApplied: PendingDestination? = null

    /** Non-null while a launch intent's destination is waiting for the shell to apply it. */
    val pending: StateFlow<PendingDestination?> = _pending.asStateFlow()

    /**
     * Records where the intent the activity was *created* with asks the app to open.
     *
     * A creation is not a launch. Rotation, the automatic dark-mode flip at sunset, a font or
     * locale change, an unfold and a restore from recents all build the activity again and hand it
     * the intent it was originally started with, so a destination that was applied long ago is
     * offered here over and over. Taking it would clear the back stack under a reader who has since
     * navigated elsewhere — `Navigator.switchTo` empties the stack, and the scope editor's draft
     * goes with the entry it was scoped to — which is why an offer naming [lastApplied] is dropped
     * on the floor here rather than judged in the shell: by the time the shell sees a destination
     * it can only ask where the reader is standing, not whether this link has already had its turn.
     *
     * The cost of that rule is one miss, and it is narrower than it first looks because [forget]
     * closes the common half of it: an activity that finishes takes the memory with it, so backing
     * out of the manager and tapping a second notice for the same module still works even though
     * the process was cached in between.
     *
     * What is left is the parasitic shape with the activity still alive. The resolved activity is a
     * copy of the host's, so this manifest's `singleTop` is not necessarily the launch mode the
     * system applies and the daemon's `openManager` asks for none; a second notice for the module
     * already applied can therefore arrive at a fresh [onCreate] beside the living one, be dropped
     * here, and open Home. It is the smaller failure of the two — one notification that opens the
     * wrong screen, against a stack emptied under the reader on every rotation — and it needs the
     * same module to be announced twice inside one activity's life.
     *
     * An intent naming nothing this understands still clears the field, exactly as in
     * [offerFromNewIntent]; only a repeat of the destination already applied is ignored.
     */
    fun offerFromCreate(intent: Intent?) {
        val destination = parse(intent)
        if (destination != null && destination == lastApplied) return
        _pending.value = destination
    }

    /**
     * Records where an intent delivered to the *running* activity asks the app to open.
     *
     * This one is a launch by definition — the system only delivers it because something started
     * the manager again — so it takes even when it names the destination last applied. That is what
     * makes a second tap on the same module's notification move a reader who has wandered off.
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
    fun offerFromNewIntent(intent: Intent?) {
        _pending.value = parse(intent)
    }

    /**
     * Forgets what was last applied, because the screen it was applied to has gone.
     *
     * [lastApplied] exists to tell a rebuilt activity from a new launch, and that distinction stops
     * meaning anything once the activity finishes: there is no stack left to empty and no reader
     * standing anywhere, so whatever starts the manager next is a real launch whoever it names.
     *
     * Without this the memory would outlive the activity — a Kotlin object lives as long as the
     * process, and backing out of the manager leaves that process cached — and the *next*
     * notification about the same module would be dropped as a replay and open Home instead. That
     * is the failure this whole file exists to prevent, arriving by the back door.
     */
    fun forget() {
        lastApplied = null
    }

    /**
     * Takes the waiting destination, leaving nothing for a later recomposition to re-apply.
     *
     * Taking one is also what marks it applied, and it counts as applied even when the shell then
     * decides the reader is already standing there: either way the reader has been given what the
     * link asked for, and a later recreation offering it again is a replay.
     */
    fun consume(): PendingDestination? = _pending.getAndUpdate { null }?.also { lastApplied = it }

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

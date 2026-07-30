package org.matrix.vector.manager.ui.navigation

import androidx.compose.runtime.Immutable

/**
 * Which panels the navigation container shows, in which order, and which of them are hidden.
 *
 * Every rule the arrangement has to obey lives here rather than at the surfaces that display it, so
 * that the navigation container and the appearance sheet cannot each remember a different half of
 * it: [visible] is never empty, [start] is the first visible panel rather than Home, and
 * [withHidden] refuses to hide the last one standing. A surface that asks the right question here
 * cannot produce a state the rest of the app has no answer for.
 *
 * [order] holds every panel, hidden ones included. Hiding is the reader's opinion of the container
 * and not a deletion — a panel that is not drawn still needs its route type and its NavDisplay
 * registration, because a back stack saved before it was hidden still names it.
 */
@Immutable
data class NavPanels(val order: List<TopLevelDestination>, val hidden: Set<String>) {

    /** Every panel in the reader's order, hidden ones included — what edit mode shows. */
    val all: List<TopLevelDestination> = order.ifEmpty { TOP_LEVEL_DESTINATIONS }

    /**
     * What the navigation container shows. Never empty.
     *
     * The `ifEmpty` is not reachable through [withHidden] or [decodeNavPanels], both of which
     * refuse to leave nothing behind. It is here because a container with no items has no defined
     * behaviour at all — Navigator.switchTo clears the stack before it adds, and NavDisplay has no
     * empty-stack branch — and this is the one place that can promise it never happens.
     */
    val visible: List<TopLevelDestination> =
        all.filterNot { it.key in hidden }.ifEmpty { all.take(1) }

    /** The panel a cold start opens on, and the fallback for a root that is no longer shown. */
    val start: TopLevelRoute = visible.first().route

    fun isHidden(destination: TopLevelDestination): Boolean = destination.key in hidden

    fun isVisible(route: TopLevelRoute): Boolean = visible.any { it.route == route }

    /** False on the last visible panel: a badge that does nothing is never offered. */
    fun canHide(destination: TopLevelDestination): Boolean =
        !isHidden(destination) && visible.size > 1

    /**
     * Hide or restore the panel with this [key], or `this` when that would change nothing.
     *
     * The refusal to hide the last visible panel is enforced here rather than at the badge that
     * asked, so that the screen-reader action, the appearance sheet and anything added later
     * inherit the same answer [canHide] gives the badge instead of each re-deciding it.
     */
    fun withHidden(key: String, hide: Boolean): NavPanels {
        if (all.none { it.key == key }) return this
        if (hide == (key in hidden)) return this
        if (hide && visible.size <= 1) return this
        return copy(hidden = if (hide) hidden + key else hidden - key)
    }

    /**
     * Move the panel at [from] to [to]. Both are indices into [all].
     *
     * Out-of-range or equal indices return `this` rather than throwing: a drag that ends where it
     * started is the common case, and a gesture whose captured index went stale because the list
     * changed underneath it must not take the app down.
     */
    fun withMoved(from: Int, to: Int): NavPanels {
        if (from == to || from !in all.indices || to !in all.indices) return this
        val moved = all.toMutableList()
        moved.add(to, moved.removeAt(from))
        return copy(order = moved)
    }
}

/**
 * The arrangement as one preference string: keys in order, comma separated, a hidden one prefixed
 * with '!' — `"logs,home,!store,modules"`.
 *
 * One delimited string rather than a set of keys, because `putStringSet` does not preserve order
 * and the order is the whole point. Route keys rather than ordinals or class names, because R8
 * rewrites class names in a release build and an ordinal would silently mean a different panel the
 * day a fifth one is declared.
 */
fun encodeNavPanels(panels: NavPanels): String =
    panels.all.joinToString(",") { if (panels.isHidden(it)) "!${it.key}" else it.key }

/**
 * Total: any string at all decodes to a usable [NavPanels] — the empty one a fresh install has, one
 * written by a build that knew a different set of panels, one edited by hand.
 *
 * Unknown and duplicate keys are dropped, and every entry of TOP_LEVEL_DESTINATIONS the string does
 * not name is appended at the end and visible. That last rule is what makes a newly added panel
 * appear for someone who arranged theirs a year ago, rather than being invisible to precisely the
 * readers who care most about the arrangement. If what survives would hide everything, the first
 * entry is un-hidden.
 */
fun decodeNavPanels(stored: String): NavPanels {
    val order = mutableListOf<TopLevelDestination>()
    val hidden = mutableSetOf<String>()
    for (raw in stored.split(',')) {
        val token = raw.trim()
        val hide = token.startsWith('!')
        val key = if (hide) token.substring(1) else token
        val destination = TOP_LEVEL_DESTINATIONS.firstOrNull { it.key == key } ?: continue
        if (order.any { it.key == key }) continue
        order += destination
        if (hide) hidden += key
    }
    // Appended rather than slotted back into its declared position: someone who has arranged their
    // panels has said where the ones they know about go and has said nothing at all about this one,
    // so the end is the only honest place for it.
    for (destination in TOP_LEVEL_DESTINATIONS) {
        if (order.none { it.key == destination.key }) order += destination
    }
    // The one invariant a stored string can break by itself, since nothing stops the file being
    // edited or written by an older build than this one.
    if (order.all { it.key in hidden }) hidden -= order.first().key
    return NavPanels(order, hidden)
}

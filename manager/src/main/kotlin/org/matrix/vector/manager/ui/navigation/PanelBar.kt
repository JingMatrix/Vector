package org.matrix.vector.manager.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import org.matrix.vector.manager.R

/** What is left of a hidden panel in edit mode: Material's disabled alpha, saying the same. */
private const val HIDDEN_ALPHA = 0.38f

/** How much the dragged item grows, so a finger is visibly carrying it rather than pushing it. */
private const val DRAG_LIFT = 1.08f

/**
 * The four panels, as the navigation container's items.
 *
 * A plain composable, not a scope extension: the scaffold's `navigationItems` slot has no receiver,
 * and whatever this emits becomes a direct child of ShortNavigationBar or WideNavigationRail. It
 * must therefore emit its items as flat siblings — ShortNavigationBar measures every direct child
 * as one equal-width slot, so wrapping the four in a Row would hand a single slot the whole bar.
 *
 * [editing] shows all four, hidden ones dimmed, with a badge and a drag; otherwise it shows
 * [NavPanels.visible] and a long press asks for [onEdit]. Both index arguments of [onMove] are
 * indices into [NavPanels.all], which is what [editing] is showing when a drag is possible at all.
 */
@Composable
fun PanelBar(
    panels: NavPanels,
    current: TopLevelRoute,
    editing: Boolean,
    suiteType: NavigationSuiteType,
    onSelect: (TopLevelRoute) -> Unit,
    onEdit: () -> Unit,
    onToggleHidden: (key: String, hidden: Boolean) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val horizontal = isHorizontal(suiteType)
    val items = if (editing) panels.all else panels.visible
    // Rebuilt whenever the arrangement it describes stops being the one on screen. A drag cannot
    // outlive either change — leaving edit mode ends it, and the axis only flips on a rotation —
    // so there is nothing in flight to lose, and a stale slot table is exactly how a reorder ends
    // up dropping an item in the wrong place.
    val drag = remember(items.size, horizontal) { PanelDrag(items.size) }

    items.forEachIndexed { index, destination ->
        PanelItem(
            destination = destination,
            index = index,
            count = items.size,
            selected = destination.route == current,
            editing = editing,
            hidden = panels.isHidden(destination),
            canHide = panels.canHide(destination),
            horizontal = horizontal,
            drag = drag,
            onSelect = { onSelect(destination.route) },
            onEdit = onEdit,
            onToggleHidden = { hidden -> onToggleHidden(destination.key, hidden) },
            onMove = onMove,
        )
    }
}

/**
 * The way out of edit mode that is not the back gesture.
 *
 * Goes in the scaffold's `primaryActionContent`, which the suite draws above a bar and as the
 * header of a rail — the one place that is present on both axes without this having to know which
 * it is on. A FloatingActionButton because that is what the slot is documented to hold; anything
 * flatter reads as one more navigation item rather than as the way out.
 */
@Composable
fun PanelEditDone(onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    FloatingActionButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onDone()
        }
    ) {
        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.panels_done))
    }
}

/**
 * Whether [type] lays its items along the bottom of the window rather than down its side.
 *
 * The library's own `isNavigationBar` is private and NavigationSuiteType is a value class with no
 * `values()`, so this is re-derived by comparison against the three bar values. Public so that
 * VectorApp and this file cannot come to different answers about the same window.
 */
fun isHorizontal(type: NavigationSuiteType): Boolean =
    type == NavigationSuiteType.ShortNavigationBarCompact ||
        type == NavigationSuiteType.ShortNavigationBarMedium ||
        type == NavigationSuiteType.NavigationBar

/**
 * One panel: the container's item, and in edit mode the badge and the drag over the top of it.
 *
 * The Box is the slot the container measured, so everything that has to move the whole item —
 * [zIndex], the drag offset, the lift — goes on it, and everything that describes the panel itself
 * goes on the item inside it. The badge is a later sibling than the item so that it wins the hit
 * test over the item's own click.
 */
@Composable
private fun PanelItem(
    destination: TopLevelDestination,
    index: Int,
    count: Int,
    selected: Boolean,
    editing: Boolean,
    hidden: Boolean,
    canHide: Boolean,
    horizontal: Boolean,
    drag: PanelDrag,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onToggleHidden: (Boolean) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val name = stringResource(destination.labelRes)
    val rearrange = stringResource(R.string.settings_rearrange_panels)
    val moveEarlier = stringResource(R.string.panels_move_earlier)
    val moveLater = stringResource(R.string.panels_move_later)

    val dragged = drag.from == index
    // Both are kept as State and read inside the placement and layer lambdas below rather than
    // unwrapped here, so an animation frame re-places the item instead of recomposing it. LogPan
    // keeps its pan offset the same way and for the same reason.
    val lift = animateFloatAsState(if (dragged) DRAG_LIFT else 1f, label = "panelLift")
    // Only the items the dragged one has crossed animate; the dragged one follows the finger
    // exactly, which is the whole difference between carrying something and nudging it.
    val shift = animateFloatAsState(drag.displacement(index), label = "panelShift")

    // Two detectors on one node would fight — a drag detector swallows the long press it starts
    // from — so the item runs exactly one of them, chosen by what it is currently for. Both are
    // keyed on everything they close over, since a pointerInput block captures its lambda at its
    // keys and a stale capture is how a drag ends up reordering the index it began at yesterday.
    val slotGesture =
        if (editing) {
            Modifier.pointerInput(drag, index, horizontal, editing) {
                detectDragGestures(
                    onDragStart = { drag.start(index) },
                    onDragEnd = {
                        val from = drag.from
                        val to = drag.to
                        drag.cancel()
                        if (from >= 0 && from != to) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onMove(from, to)
                        }
                    },
                    onDragCancel = { drag.cancel() },
                    onDrag = { change, amount ->
                        change.consume()
                        val along = if (horizontal) amount.x else amount.y
                        if (drag.drag(along)) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                )
            }
        } else {
            Modifier.pointerInput(index, editing) {
                awaitPanelLongPress {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEdit()
                }
            }
        }

    Box(
        modifier =
            Modifier.zIndex(if (dragged) 1f else 0f)
                // Ahead of the offset below it on purpose: an outer modifier is placed by the
                // container and an inner one by it, so what is recorded here is the slot the bar
                // or the rail assigned rather than wherever the finger has since dragged the item.
                .then(
                    if (!editing) Modifier
                    else
                        Modifier.onGloballyPositioned {
                            val position = it.positionInParent()
                            drag.reportSlot(index, if (horizontal) position.x else position.y)
                        }
                )
                // absoluteOffset rather than offset: the displacements are computed from recorded
                // positions and from a raw drag delta, both of which count pixels rightwards, and
                // the mirroring `offset` applies in an RTL locale would undo exactly one of them.
                .absoluteOffset {
                    val along = if (drag.from == index) drag.offset else shift.value
                    if (horizontal) IntOffset(along.roundToInt(), 0)
                    else IntOffset(0, along.roundToInt())
                }
                .graphicsLayer {
                    scaleX = lift.value
                    scaleY = lift.value
                }
                .then(slotGesture),
        contentAlignment = Alignment.Center,
    ) {
        val itemSemantics =
            if (editing) {
                // A drag is unreachable by touch exploration, so the two moves it can make are
                // also offered as actions on the item a screen reader is already focused on.
                Modifier.semantics {
                    val moves = mutableListOf<CustomAccessibilityAction>()
                    if (index > 0) {
                        moves +=
                            CustomAccessibilityAction(moveEarlier) {
                                onMove(index, index - 1)
                                true
                            }
                    }
                    if (index < count - 1) {
                        moves +=
                            CustomAccessibilityAction(moveLater) {
                                onMove(index, index + 1)
                                true
                            }
                    }
                    customActions = moves
                }
            } else {
                // Declared rather than detected: the gesture above resolves the long press on the
                // pointer, which touch exploration never delivers. Nothing on Android teaches
                // long-press on a navigation bar anyway, so this label is the only place a screen
                // reader is told the gesture exists at all.
                Modifier.semantics {
                    onLongClick(label = rearrange) {
                        onEdit()
                        true
                    }
                }
            }
        val itemModifier =
            // The bar hands its slot a fixed size, and before this Box stood between them the item
            // received that size directly; filling it back up keeps the whole slot tappable rather
            // than only the icon and label in the middle of it. The rail measures loosely and gets
            // no such modifier — filling there would stretch one item down the whole rail.
            (if (horizontal) Modifier.fillMaxSize() else Modifier)
                .graphicsLayer { alpha = if (hidden) HIDDEN_ALPHA else 1f }
                .then(itemSemantics)
        // The label doubles as the item's accessibility name, so the icon carries no
        // contentDescription of its own — otherwise TalkBack announces every selected tab twice.
        val icon: @Composable () -> Unit = { Icon(destination.icon, contentDescription = null) }
        val label: @Composable () -> Unit = { Text(name) }
        // Nothing to select while rearranging: a tap in edit mode is either the badge or the start
        // of a drag, and moving to another panel underneath the arrangement being edited is not
        // something anyone asked for.
        val onClick: () -> Unit = { if (!editing) onSelect() }

        if (horizontal) {
            ShortNavigationBarItem(
                selected = selected,
                onClick = onClick,
                icon = icon,
                label = label,
                modifier = itemModifier,
            )
        } else {
            WideNavigationRailItem(
                selected = selected,
                onClick = onClick,
                icon = icon,
                label = label,
                // The suite keeps its rail collapsed; an expanded item here would lay its label
                // beside the icon in a rail that is not wide enough for it.
                railExpanded = false,
                modifier = itemModifier,
            )
        }

        if (editing && (hidden || canHide)) {
            PanelBadge(
                hidden = hidden,
                name = name,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onToggleHidden(!hidden)
                },
            )
        }
    }
}

/**
 * The minus or plus at the corner of a panel being rearranged.
 *
 * Drawn at eighteen points and hit at twenty-four: it has to read as a mark on the item rather than
 * as a second button beside it, and at 360dp each of four slots is around ninety, so the target it
 * needs costs nothing. It is offered only where it does something — [NavPanels.canHide] answers
 * that — because a badge that refuses is worse than no badge.
 */
@Composable
private fun BoxScope.PanelBadge(hidden: Boolean, name: String, onClick: () -> Unit) {
    val description =
        stringResource(if (hidden) R.string.panels_show else R.string.panels_hide, name)
    val container =
        if (hidden) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
    val content =
        if (hidden) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onErrorContainer

    Box(
        modifier =
            Modifier.align(Alignment.TopEnd)
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(18.dp),
            shape = CircleShape,
            color = container,
            contentColor = content,
            // Enough to lift it off the icon underneath without reading as a floating control.
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (hidden) Icons.Rounded.Add else Icons.Rounded.Remove,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * Long press to enter edit mode, resolved on the initial pointer pass.
 *
 * `combinedClickable` on the slot is the obvious way and it does not work. The item inside carries
 * its own `selectable`, which is the nearer node and therefore handles the release first, so a long
 * press on Logs would open edit mode *and* switch to Logs. Watching the pointer on
 * [PointerEventPass.Initial] gets ahead of it: nothing is consumed while the press might still turn
 * out to be a tap — in which case the item's own click handles it, ripple and indicator and all —
 * and everything from the moment the press has been held long enough is consumed, which is what
 * cancels the click that would otherwise land on the way up.
 *
 * Timing it out by hand rather than through foundation's own `waitForLongPress`: that one takes the
 * pass to watch, which is exactly what is wanted here, but it and its `LongPressResult` are
 * `internal` — public in the bytecode, invisible to Kotlin.
 */
private suspend fun PointerInputScope.awaitPanelLongPress(onLongPress: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(PointerEventPass.Initial)
            }
            // Let go, or taken over by something else, before the press became a hold.
            return@awaitEachGesture
        } catch (_: PointerEventTimeoutCancellationException) {
            onLongPress()
        }
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) return@awaitEachGesture
        }
    }
}

/**
 * A reorder in flight, shared by every item in the container.
 *
 * The axis never appears here. [PanelBar] hands over the one number that differs between a bottom
 * bar and a rail — where each slot sits along whichever direction the container runs in — so the
 * arithmetic below is written once and both layouts get the same behaviour rather than two
 * implementations that drift.
 *
 * Slot positions rather than a single pitch, because the rail spaces its items apart and the bar
 * does not: the distance between two recorded positions is right in both, whereas an item's own
 * measured extent is short by the gap in the rail and would leave every shift a few points behind
 * the finger.
 */
@Stable
private class PanelDrag(count: Int) {

    /** Which item is being carried, as an index into the displayed list, or -1 for none. */
    var from by mutableIntStateOf(-1)
        private set

    /** Where it would land if the finger lifted now. */
    var to by mutableIntStateOf(-1)
        private set

    /** How far it has travelled from its own slot. Read during placement, so no recomposition. */
    var offset by mutableFloatStateOf(0f)
        private set

    // Deliberately not snapshot state: written from a layout callback and read from the gesture,
    // never composed against. Observing them would invalidate the very layout pass that produced
    // them — the same reason LogPan keeps its measured widths as plain fields.
    private val slots = FloatArray(count)

    private val active: Boolean
        get() = from in slots.indices

    /**
     * Record where the container placed the item at [index].
     *
     * Ignored while a drag is running. The items are displaced then, and reading the displacement
     * back in as the slot table would walk the targets along under the finger.
     */
    fun reportSlot(index: Int, position: Float) {
        if (!active && index in slots.indices) slots[index] = position
    }

    fun start(index: Int) {
        from = index
        to = index
        offset = 0f
    }

    /** Advance by [delta] pixels along the axis. True when the target slot changed. */
    fun drag(delta: Float): Boolean {
        if (!active) return false
        offset += delta
        val target = nearest(slots[from] + offset)
        if (target == to) return false
        to = target
        return true
    }

    fun cancel() {
        from = -1
        to = -1
        offset = 0f
    }

    /**
     * How far the item at [index] is pushed aside by the drag in flight, in pixels along the axis.
     *
     * Every item the dragged one has crossed moves into the slot next to its own, and does so by
     * the real distance between those two slots rather than by an assumed pitch, so an uneven
     * arrangement lands as exactly as an even one.
     */
    fun displacement(index: Int): Float {
        if (!active || index == from) return 0f
        return when {
            index in (from + 1)..to -> slots[index - 1] - slots[index]
            index in to until from -> slots[index + 1] - slots[index]
            else -> 0f
        }
    }

    private fun nearest(position: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (index in slots.indices) {
            val distance = abs(slots[index] - position)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }
}

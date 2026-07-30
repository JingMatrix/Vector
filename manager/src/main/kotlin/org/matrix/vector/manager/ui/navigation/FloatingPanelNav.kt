package org.matrix.vector.manager.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch
import org.matrix.vector.manager.R
import org.matrix.vector.manager.di.ServiceLocator

/** Drawn size of the ball, which is also its touch target — hence not smaller than 48dp. */
private val BALL_SIZE = 52.dp

/** How much clear space is left between the parked ball and the edge it is parked against. */
private val BALL_INSET = 10.dp

/** How far from the ball a panel comes to rest once the arc is open. */
private val FAN_RADIUS = 116.dp

private val FAN_ITEM_SIZE = 48.dp

/** Wide enough for a panel name at `labelSmall`, and the width the arc is clamped by. */
private val FAN_ITEM_WIDTH = 88.dp

private val FAN_LABEL_GAP = 6.dp

/** Room kept below a fanned panel for its name, so a label cannot land off the window. */
private val FAN_LABEL_ROOM = 22.dp

/** How far apart two panels sit on the arc when the window has room for the whole fan. */
private val FAN_STEP = Math.toRadians(46.0).toFloat()

/**
 * How far off a panel's own direction the finger may point and still be pointing at it, as the
 * cosine of that angle so the test is a dot product rather than an `atan2` per panel per event.
 *
 * Wider than half the step on purpose: neighbouring cones overlap and the nearest one wins, so the
 * only thing this number really decides is how far the finger has to stray before it has chosen
 * nothing at all.
 */
private val FAN_CONE = cos(Math.toRadians(32.0)).toFloat()

/**
 * Within this fraction of [FAN_RADIUS] of the ball, the finger has chosen nothing.
 *
 * There has to be somewhere to let go that does not navigate, or a held press that turned out to be
 * a mistake would have no ending but an unwanted panel. Near the ball — where the finger already is
 * when the arc opens — is the one place nobody reaches by accident on the way to a panel.
 */
private const val FAN_DEAD_ZONE = 0.45f

/**
 * The panels as a ball you can put where you like, for when there is no bar at all.
 *
 * Not a [androidx.compose.foundation.layout.BoxScope] extension and it takes no `Alignment`: it
 * fills the space it is given and places the ball itself, because the arc has to know the window it
 * must stay inside, and an alignment handed in from outside would describe the ball while telling
 * this nothing about the room left around it.
 *
 * It is drawn inside the app window, as the last child of the shell's content Box. Never a `Popup`,
 * never a `Dialog`, and above all never a system overlay: parasitically this app *is*
 * `com.android.shell`, and a manager that asked for `SYSTEM_ALERT_WINDOW` would be asking the
 * shell's uid for permission to draw over every other app on the device. A floating control is
 * worth exactly none of that.
 *
 * It reads `WindowInsets.safeDrawing` itself, as bounds rather than as padding: with the navigation
 * container set to `NavigationSuiteType.None` the scaffold consumes no insets at all, so nothing
 * above this has reserved the system bars and the ball would otherwise park itself under the
 * gesture handle. Bounds rather than padding because the tap that dismisses the open arc has to
 * cover the whole window, insets included, while the ball itself must stay out of them.
 *
 * Two ways in, on purpose. Holding the ball blooms the arc and the same finger picks from it, which
 * is the fast one; a plain tap latches the arc open so each panel is an ordinary target, which is
 * the only one a screen reader can use. A drag-only selector is unreachable by touch exploration,
 * so the latched path is not polish — it is the accessible path, and every fanned panel is its own
 * focusable, clickable node with its own description rather than a hit test over one canvas.
 */
@Composable
fun FloatingPanelNav(
    panels: NavPanels,
    current: TopLevelRoute,
    onSelect: (TopLevelRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = ServiceLocator.settings
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val rtl = layoutDirection == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    // The gesture below outlives its recompositions — see the pointerInput keys — so the callback
    // is read through a State rather than captured, or a release could call last frame's lambda.
    val select by rememberUpdatedState(onSelect)

    // Read once and written on release. The two accessors carry no flow precisely so that dragging
    // the ball does not recompose the ball, and the value is only ever authored from here.
    var atEnd by remember { mutableStateOf(settings.floatingNavAtEnd()) }
    var yFraction by remember { mutableFloatStateOf(settings.floatingNavY()) }

    var latched by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var highlighted by remember { mutableIntStateOf(-1) }
    // True from the moment the arc starts to bloom until it has finished collapsing, which is what
    // keeps the panels composed long enough to animate out instead of vanishing.
    var fanned by remember { mutableStateOf(false) }
    val open = held || latched

    // Read inside placement and layer lambdas rather than during composition, so that dragging the
    // ball re-places it without recomposing anything at all. The ball's own animation is created
    // further down, where the window's shape is known.
    val bloom = remember { Animatable(0f) }
    val bloomSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()

    LaunchedEffect(open) {
        if (open) {
            fanned = true
            bloom.animateTo(1f, bloomSpec)
        } else {
            bloom.animateTo(0f, bloomSpec)
            fanned = false
        }
    }

    // Ahead of NavDisplay's own handler because this composable is the shell content's last child
    // and back callbacks fire last-registered-first: a latched arc closes before the stack moves.
    BackHandler(enabled = latched) { latched = false }

    // Every coordinate below is counted from the left of the window, because that is what the
    // insets, the constraints and a pointer's own position are counted from. The alignment has to
    // be the absolute one to match: Alignment.TopStart places a child against the *right* edge in
    // an RTL locale, and values-ar makes that reachable. Which side the ball parks on is decided by
    // `atEnd` against the layout direction, in one place, rather than by the layout mirroring it.
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        val insets = WindowInsets.safeDrawing
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        val ballRadius = with(density) { BALL_SIZE.toPx() } / 2f
        val ballInset = with(density) { BALL_INSET.toPx() }
        val radius = with(density) { FAN_RADIUS.toPx() }
        val itemRadius = with(density) { FAN_ITEM_SIZE.toPx() } / 2f
        val itemHalfWidth = with(density) { FAN_ITEM_WIDTH.toPx() } / 2f
        val labelRoom = with(density) { (FAN_LABEL_GAP + FAN_LABEL_ROOM).toPx() }

        // Where the ball's centre may sit, and where a fanned panel's centre may sit. The second
        // is the taller of the two allowances because a panel carries its name underneath it.
        val ballBounds =
            Rect(
                left = insets.getLeft(density, layoutDirection) + ballInset + ballRadius,
                top = insets.getTop(density) + ballInset + ballRadius,
                right = width - insets.getRight(density, layoutDirection) - ballInset - ballRadius,
                bottom = height - insets.getBottom(density) - ballInset - ballRadius,
            )
        val fanBounds =
            Rect(
                left = insets.getLeft(density, layoutDirection) + itemHalfWidth,
                top = insets.getTop(density) + itemRadius,
                right = width - insets.getRight(density, layoutDirection) - itemHalfWidth,
                bottom = height - insets.getBottom(density) - itemRadius - labelRoom,
            )

        // Seeded during composition rather than from an effect, because an effect can land after
        // the frame it was scheduled in has already drawn and the ball would flash in the corner
        // of the window on the way to where it was left.
        val ball =
            remember {
                Animatable(
                    resting(ballBounds, atEnd != rtl, yFraction, height),
                    Offset.VectorConverter,
                )
            }

        // Keyed on the room available rather than on where the ball is: this is what puts it back
        // after a rotation or a fold, which is exactly why the position is persisted as a side and
        // a fraction rather than as a coordinate. A move by hand animates itself and must not be
        // snapped out from under the finger, so `atEnd` is read here and not keyed on.
        LaunchedEffect(ballBounds) {
            ball.snapTo(resting(ballBounds, atEnd != rtl, yFraction, height))
        }

        val visible = panels.visible
        val deadZone = radius * FAN_DEAD_ZONE

        // One gesture loop rather than a stack of detectors. A long press, a tap and a drag on the
        // same node cannot be split across `detectTapGestures` and `detectDragGestures`: the tap
        // detector consumes everything from the moment its long press fires, which starves exactly
        // the drag this needs to keep following afterwards.
        val gesture =
            Modifier.pointerInput(
                visible,
                ballBounds,
                fanBounds,
                radius,
                width,
                height,
                layoutDirection,
            ) {
                // This block restarts whenever a key changes, which can happen in the middle of a
                // press. The press it was in will never be released, so the hold it announced is
                // released here instead — otherwise the arc stays bloomed with nothing left
                // holding it and no gesture able to close it.
                held = false
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)

                    if (longPress != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // Held before unlatched, never the other way round: the two are separate
                        // writes, and dropping both for the instant between them would tell the
                        // arc to collapse and then to bloom again on a press that never closed it.
                        held = true
                        latched = false
                        highlighted = -1
                        // The arc is a pure function of where the ball is, so the drawing below
                        // and this hit test cannot describe different arcs. The ball does not move
                        // while the arc is open, so one reading of it serves the whole gesture.
                        val centre = ball.value
                        val corner = Offset(centre.x - ballRadius, centre.y - ballRadius)
                        val inward = if (atEnd != rtl) -1f else 1f
                        val points = fanPoints(visible.size, centre, inward, radius, fanBounds)
                        val released =
                            drag(down.id) { change ->
                                val hit = pick(points, centre, change.position + corner, deadZone)
                                if (hit != highlighted) {
                                    if (hit >= 0) {
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.SegmentTick
                                        )
                                    }
                                    highlighted = hit
                                }
                                change.consume()
                            }
                        val chosen = highlighted
                        held = false
                        highlighted = -1
                        // A cancelled gesture is not a choice. `drag` returns false when the
                        // pointer was taken away rather than lifted, and navigating on that would
                        // mean a panel opening because a phone call arrived.
                        if (released && chosen in visible.indices) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            select(visible[chosen].route)
                        }
                        return@awaitEachGesture
                    }

                    // Not a long press, which is two different gestures: the finger came up inside
                    // the timeout, or it moved far enough to be dragging the ball. Whether the
                    // pointer is still down is the only thing that tells them apart, and it is
                    // still the current event that ended the wait.
                    val moving = currentEvent.changes.firstOrNull { it.id == down.id }?.pressed
                    if (moving != true) {
                        latched = !latched
                        haptics.performHapticFeedback(
                            if (latched) HapticFeedbackType.ContextClick
                            else HapticFeedbackType.ToggleOff
                        )
                        return@awaitEachGesture
                    }

                    latched = false
                    // An absolute target rather than reading the animation back on every event:
                    // the snaps are launched, and a base read inside one of them could be a frame
                    // behind the finger.
                    var target = ball.value
                    drag(down.id) { change ->
                        target = within(target + change.positionChange(), ballBounds)
                        scope.launch { ball.snapTo(target) }
                        change.consume()
                    }
                    val atRight = target.x > width / 2f
                    atEnd = atRight != rtl
                    yFraction = (target.y / height).coerceIn(0f, 1f)
                    settings.setFloatingNavAtEnd(atEnd)
                    settings.setFloatingNavY(yFraction)
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    scope.launch {
                        ball.animateTo(resting(ballBounds, atRight, yFraction, height), settleSpec)
                    }
                }
            }

        // Composed before the ball and the panels so that it takes only the taps they do not, and
        // present only while the arc is latched — the rest of the time every touch that is not on
        // the ball itself belongs to the screen underneath.
        if (latched) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { latched = false }
                }
            )
        }

        if (fanned) {
            val centre = ball.value
            val inward = if (atEnd != rtl) -1f else 1f
            val points = fanPoints(visible.size, centre, inward, radius, fanBounds)
            visible.forEachIndexed { index, destination ->
                FanItem(
                    destination = destination,
                    selected = destination.route == current,
                    highlighted = index == highlighted,
                    // While the finger is picking, only the panel under it is named — four labels
                    // at once would be four things to read on a gesture that is already decided.
                    // Latched, nothing is under a finger, so every panel has to say what it is.
                    labelled = latched || index == highlighted,
                    onClick = {
                        latched = false
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        select(destination.route)
                    },
                    modifier =
                        // absoluteOffset, like the ball's: these are window coordinates, and the
                        // mirroring `offset` applies under RTL would put the arc on the far side
                        // of the window from the ball it belongs to.
                        Modifier.absoluteOffset {
                                val grown = bloom.value
                                val point = points[index]
                                IntOffset(
                                    (centre.x + (point.x - centre.x) * grown - itemHalfWidth)
                                        .roundToInt(),
                                    (centre.y + (point.y - centre.y) * grown - itemRadius)
                                        .roundToInt(),
                                )
                            }
                            .graphicsLayer { alpha = bloom.value },
                )
            }
        }

        val colors = MaterialTheme.colorScheme
        val ballLabel =
            stringResource(
                if (latched) R.string.panels_ball_close else R.string.panels_ball_open
            )
        Surface(
            shape = CircleShape,
            color = if (open) colors.primary else colors.primaryContainer,
            contentColor = if (open) colors.onPrimary else colors.onPrimaryContainer,
            shadowElevation = 6.dp,
            modifier =
                Modifier.absoluteOffset {
                        IntOffset(
                            (ball.value.x - ballRadius).roundToInt(),
                            (ball.value.y - ballRadius).roundToInt(),
                        )
                    }
                    .size(BALL_SIZE)
                    // The gesture above is invisible to touch exploration, so the node states its
                    // own click action: performing it is what a screen reader's double tap does,
                    // and it lands on the latched arc, which is the path that can then be walked.
                    .semantics(mergeDescendants = true) {
                        contentDescription = ballLabel
                        role = Role.Button
                        onClick {
                            latched = !latched
                            true
                        }
                    }
                    .then(gesture),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // The surface above carries the description; announcing the glyph as well would
                // name the same control twice.
                Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/**
 * One panel in the open arc.
 *
 * [highlighted] is the panel the finger is over, [selected] the one the app is already on, and the
 * two have to be told apart without relying on colour: under Material You the hues come from the
 * wallpaper, so the highlight also grows and the current panel also wears a ring.
 */
@Composable
private fun FanItem(
    destination: TopLevelDestination,
    selected: Boolean,
    highlighted: Boolean,
    labelled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(destination.labelRes)
    val lift by animateFloatAsState(if (highlighted) 1.18f else 1f, label = "panelLift")

    Column(
        modifier = modifier.width(FAN_ITEM_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (highlighted) colors.primary else colors.surfaceContainerHigh,
            contentColor = if (highlighted) colors.onPrimary else colors.onSurfaceVariant,
            shadowElevation = if (highlighted) 8.dp else 3.dp,
            border = if (selected) BorderStroke(2.dp, colors.primary) else null,
            modifier =
                Modifier.size(FAN_ITEM_SIZE).scale(lift).semantics {
                    contentDescription = label
                },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(destination.icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        if (labelled) {
            Spacer(Modifier.height(FAN_LABEL_GAP))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (highlighted) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The circle above already carries this name as its description, so the text under
                // it is decoration for the eyes and must not be read out a second time.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * Where each panel's centre goes, in the coordinates of the whole overlay.
 *
 * Angles are measured from "straight inward" and positive downwards, so one piece of arithmetic
 * serves both edges and the side the ball is parked on only decides which way [inward] points —
 * `-1` from the right edge, `+1` from the left.
 *
 * The arc opens inward and stays inside [bounds] by construction rather than by clipping. How far
 * it may reach up and down is turned into an angle first, the step between panels is narrowed until
 * that many of them fit, and the whole fan is then tipped away from whichever edge is too close.
 * A ball parked at the bottom of the window therefore fans upwards rather than fanning off-screen
 * and having half its panels clamped into a heap in the corner.
 */
private fun fanPoints(
    count: Int,
    centre: Offset,
    inward: Float,
    radius: Float,
    bounds: Rect,
): List<Offset> {
    if (count <= 0 || radius <= 0f) return emptyList()
    val up = -asin(((centre.y - bounds.top) / radius).coerceIn(0f, 1f))
    val down = asin(((bounds.bottom - centre.y) / radius).coerceIn(0f, 1f))
    val step = if (count == 1) 0f else min(FAN_STEP, (down - up) / (count - 1))
    val half = step * (count - 1) / 2f
    val tilt = within(0f, up + half, down - half)
    return List(count) { index ->
        val angle = tilt + (index - (count - 1) / 2f) * step
        // The arithmetic above already keeps the arc inside the window on any shape this app can
        // be handed. These two calls are what make "never outside the window" true rather than
        // merely overwhelmingly likely — a window narrower than the arc is wide has no honest
        // answer, and a panel stacked on its neighbour is a better one than a panel nobody can see.
        Offset(
            within(centre.x + inward * radius * cos(angle), bounds.left, bounds.right),
            within(centre.y + radius * sin(angle), bounds.top, bounds.bottom),
        )
    }
}

/**
 * Which panel the finger at [at] is pointing at, or -1 for none.
 *
 * Direction rather than distance, so the panels behave as sectors around the ball: overshooting one
 * still chooses it, which matters because the arc is drawn at a radius the thumb has to stretch to
 * and a selector that only answered inside the circles would be a selector that missed.
 */
private fun pick(points: List<Offset>, centre: Offset, at: Offset, deadZone: Float): Int {
    val reach = at - centre
    val length = reach.getDistance()
    if (length < deadZone) return -1
    var best = -1
    var closest = FAN_CONE
    points.forEachIndexed { index, point ->
        val toPanel = point - centre
        val span = toPanel.getDistance()
        if (span <= 0f) return@forEachIndexed
        val alignment = (reach.x * toPanel.x + reach.y * toPanel.y) / (length * span)
        if (alignment > closest) {
            closest = alignment
            best = index
        }
    }
    return best
}

/** Where the ball sits when nobody is holding it: against one side, [fraction] of the way down. */
private fun resting(bounds: Rect, atRight: Boolean, fraction: Float, height: Float): Offset =
    Offset(
        if (atRight) bounds.right else bounds.left,
        within(fraction * height, bounds.top, bounds.bottom),
    )

/** [point] pulled inside [bounds], which is how a dragged ball is kept in its own window. */
private fun within(point: Offset, bounds: Rect): Offset =
    Offset(within(point.x, bounds.left, bounds.right), within(point.y, bounds.top, bounds.bottom))

/**
 * [value] pulled inside [low]..[high], tolerating the case where the two have met or crossed.
 *
 * `coerceIn` answers that case by throwing, and it is reachable here in two ordinary ways: a window
 * shorter than the arc it is being asked to hold, and an arc whose room is exactly what it needs,
 * where float arithmetic can leave the lower bound a hair above the upper one. Meeting in the
 * middle is the only thing either case can mean.
 */
private fun within(value: Float, low: Float, high: Float): Float =
    if (low < high) value.coerceIn(low, high) else (low + high) / 2f

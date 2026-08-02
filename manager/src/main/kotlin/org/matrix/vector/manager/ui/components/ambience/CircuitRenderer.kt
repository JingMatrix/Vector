package org.matrix.vector.manager.ui.components.ambience

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Signal traces.
 *
 * The most on-theme of the set: this app manages a framework that injects code into running
 * processes, and the header quietly draws the picture of that — faint traces, and a touch that
 * sends a **pulse travelling down the nearest one**, lighting the trace ahead of it and leaving it
 * dark behind.
 *
 * The board is *routed* rather than drawn. Traces run along the lanes of a lattice and step between
 * neighbouring lanes at its columns, and every run they occupy is claimed, so no two are ever drawn
 * along the same line — which is the whole difference between a board and a plate of spaghetti.
 * Crossings are welcome, though: a trace that meets copper already laid steps aside and carries on
 * over it, the way a board uses its layers, and every trace is routed through to the right-hand
 * side rather than being allowed to peter out somewhere in the middle. Corners are mitred, because
 * a right angle is the one thing a real router never leaves behind: copper turns through two 45°
 * elbows.
 *
 * The signal itself is a swell of light travelling along the copper: widest and brightest under its
 * head and drawn down to nothing behind, breathing a little as it goes — the one thing on the board
 * that is not still.
 *
 * Swipe sideways to route a fresh board, up and down to change how fast the signals run.
 */
class CircuitRenderer : AmbienceRenderer {

    private companion object {
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 3f

        const val MIN_SPEED = 0.2f
        const val MAX_SPEED = 5f

        /** Lanes and columns of the routing lattice at rest. */
        const val BASE_LANES = 10
        const val BASE_COLUMNS = 12

        /** How often a trace steps to a neighbouring lane at a column it passes. */
        const val JOG_CHANCE = 0.42f

        /** How far a trace may drift from its own lane before it is pulled back, and how firmly. */
        const val HOME_DRIFT = 2
        const val HOME_PULL = 0.75f

        /** How often a trace comes in from the left edge rather than starting on a land. */
        const val EDGE_START_CHANCE = 0.6f

        /** How often a trace stops on a land short of the right edge rather than running off it. */
        const val TERMINATE_CHANCE = 0.3f

        /** How far aside a blocked trace will look for a free lane before it gives up. */
        const val SIDESTEP_REACH = 3

        /** How much of the width a pulse covers in a second, before [speed]. */
        const val PULSE_SPEED = 0.3f

        /**
         * The body of light behind a signal: how long it is as a fraction of the width, how many
         * samples the ribbon is built from, how wide it is under the head as a multiple of the
         * trace's own stroke, and how much that width breathes as it travels.
         */
        const val TAIL_LENGTH = 0.11f
        const val TAIL_STEPS = 20
        const val TAIL_WIDTH = 5.2f
        const val TAIL_SWELL = 0.12f

        /** Average gap between unprompted pulses. */
        const val PULSE_INTERVAL_MS = 5_000f

        /** How long a board lasts before it re-routes itself. */
        const val ROUTE_INTERVAL_MS = 60_000f

        /** How much of the width a swipe must cover before the board is re-routed. */
        const val REROUTE_FRACTION = 0.25f
    }

    /** A via, sitting on the mitred elbow where a trace changes lane. */
    private class Pad(val center: Offset, val distance: Float)

    /** A routed polyline, plus its cumulative lengths for pulse travel. */
    private class Trace(val points: List<Offset>) {
        val lengths: List<Float> = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
        val total: Float = lengths.sum().coerceAtLeast(1f)

        /**
         * The elbows, found rather than recorded.
         *
         * Every segment of a routed trace is horizontal or vertical except the mitres, so a segment
         * that is neither is an elbow, and its middle is where the via goes.
         */
        val pads: List<Pad> = buildList {
            var travelled = 0f
            for (i in lengths.indices) {
                val a = points[i]
                val b = points[i + 1]
                if (abs(b.x - a.x) > 0.5f && abs(b.y - a.y) > 0.5f) {
                    add(
                        Pad(
                            Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f),
                            travelled + lengths[i] / 2f,
                        )
                    )
                }
                travelled += lengths[i]
            }
        }

        /** Where a pulse sits after travelling [distance] along the trace. */
        fun pointAt(distance: Float): Offset {
            var remaining = distance.coerceIn(0f, total)
            for (i in lengths.indices) {
                if (remaining <= lengths[i]) {
                    val t = if (lengths[i] == 0f) 0f else remaining / lengths[i]
                    val a = points[i]
                    val b = points[i + 1]
                    return Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                }
                remaining -= lengths[i]
            }
            return points.last()
        }
    }

    /** [phase] is where this signal is in its breathing, so two of them never swell in step. */
    private class Pulse(val trace: Trace, var distance: Float, val phase: Float) {
        /** How lit each via the pulse has crossed still is. */
        val litPads = mutableMapOf<Int, Float>()
    }

    private var traces: List<Trace> = emptyList()

    /**
     * How densely the board is laid out.
     *
     * Zooming out routes a finer lattice — more lanes, more columns, so more traces with shorter
     * runs between turns, a busier board seen from further back. Zooming in gives a few wide traces
     * with long straight stretches. The stroke follows it too, so a dense board does not turn into
     * a grey wash.
     */
    override var scale: Float = 1f
        set(value) {
            val next = value.coerceIn(MIN_SCALE, MAX_SCALE)
            if (next == field) return
            field = next
            if (sized != Size.Zero) route(sized)
        }

    /**
     * How fast the signals run, on the same vertical drag the code rain and the snow use.
     *
     * The same gesture in the same place should mean the same thing whichever ambience is on. Here
     * it is the difference between a board idling and a board under load — and the resting speed is
     * deliberately unhurried, because this sits behind text somebody is reading.
     */
    override var speed: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SPEED, MAX_SPEED)
        }

    private val pulses = mutableListOf<Pulse>()
    private var sized = Size.Zero

    /**
     * The die the board rolls for itself, kept rather than made on the frame path.
     *
     * [route] seeds its own from [layoutSeed], because a board has to come out the same way twice;
     * an unprompted pulse only has to be unpredictable.
     */
    private val random = Random(0xC1AC17)

    /** Rises to 1 while a freshly routed board fades in after a swipe. */
    private var reveal = 1f

    /** Where the tails are in their swell. */
    private var wag = 0f

    /**
     * The ribbon behind a signal, reused rather than built again every frame.
     *
     * A header redraws sixty times a second for as long as somebody is reading the activity feed,
     * and a [Path] and a list per signal per frame is a steady drip of garbage for a background.
     */
    private val tailPath = Path()
    private val tailEdge = FloatArray((TAIL_STEPS + 1) * 2)

    /** Bumped on every re-route so the layout is genuinely different each time. */
    private var layoutSeed = 1

    /** Counts down to the next unprompted pulse. */
    private var nextPulseMs = 2200f

    /** Counts down to the next unprompted re-route. */
    private var nextRouteMs = ROUTE_INTERVAL_MS

    override val isAnimating: Boolean
        // The board runs itself rather than only reacting: a status header is mostly looked at
        // rather than played with, and one that waits to be touched reads as dead. The wait for
        // the next pulse is counted down in update(), which a parked frame loop stops calling.
        get() = true

    private fun seed(size: Size) {
        if (sized == size && traces.isNotEmpty()) return
        sized = size
        route(size)
    }

    /**
     * Lays a fresh board. Called on first draw and again on every swipe.
     *
     * One pass per lane, in a shuffled order so the long traces are not always the top ones. A
     * trace walks rightwards along its lane, claiming each run as it goes and stepping to a
     * neighbouring lane now and then, and it carries on until it has reached the right-hand side.
     * Copper already laid is routed *around* rather than run over: a claimed run makes the trace
     * step aside, which is what keeps two traces from ever being drawn along the same line.
     */
    private fun route(size: Size) {
        val random = Random(0xB0A2D + layoutSeed * 7919)
        val lanes = (BASE_LANES / scale).roundToInt().coerceIn(5, 22)
        val columns = (BASE_COLUMNS / scale).roundToInt().coerceIn(5, 30)

        val top = size.height * 0.12f
        val laneGap = (size.height * 0.76f) / (lanes - 1)
        val columnWidth = size.width / columns
        // Both bounds matter: cutting more than half a lane apart turns the step into a pure
        // diagonal ramp, and cutting more than a third of a run turns the straight stretch between
        // two elbows into a chevron.
        val cut = min(laneGap * 0.45f, columnWidth * 0.32f)

        // Which runs and which steps are already copper. Keyed rather than gridded because the
        // lattice is small and this is read far more often than it is written.
        val takenRuns = HashSet<Int>()
        val takenSteps = HashSet<Int>()

        traces = buildList {
            for (start in (0 until lanes).shuffled(random)) {
                var lane = start
                var column =
                    if (random.nextFloat() < EDGE_START_CHANCE) 0
                    else random.nextInt(1, maxOf(2, columns / 2))
                // Every trace is routed to the right-hand side, because a trace that peters out in
                // the middle of the header reads as one the router gave up on. Some run off the
                // edge and some stop on a land just short of it, which is the difference between a
                // trace leaving the board and one arriving somewhere.
                val end =
                    if (random.nextFloat() < TERMINATE_CHANCE) columns - random.nextInt(1, 3)
                    else columns
                if (end - column < 2) continue

                val corners = mutableListOf(column to lane)
                while (column < end) {
                    if (lane * 64 + column in takenRuns) {
                        // Blocked by copper already laid: step aside rather than stop, nearest
                        // lane first. A run cannot be shared, but the step across other traces to
                        // reach a free one is fine — a board has layers, and a crossing reads as
                        // one of them.
                        var stepped = false
                        aside@ for (reach in 1..SIDESTEP_REACH) {
                            for (step in intArrayOf(reach, -reach)) {
                                val next = lane + step
                                if (next !in 0 until lanes) continue
                                if (next * 64 + column in takenRuns) continue
                                if (!claimStep(takenSteps, column, lane, next)) continue
                                corners += column to lane
                                lane = next
                                corners += column to lane
                                stepped = true
                                break@aside
                            }
                        }
                        if (!stepped) break
                    }
                    takenRuns.add(lane * 64 + column)
                    column++
                    if (column >= end) break
                    if (random.nextFloat() >= JOG_CHANCE) continue

                    // A trace that has wandered a couple of lanes from the one it started on
                    // prefers the step that takes it back, so the board keeps its horizontal
                    // grain rather than every trace cascading toward the same edge. Then the way
                    // the die fell, and then the other way: a trace boxed in below steps up
                    // rather than giving up on the turn.
                    val away = lane - start
                    val first =
                        if (abs(away) >= HOME_DRIFT && random.nextFloat() < HOME_PULL) {
                            if (away > 0) -1 else 1
                        } else if (random.nextBoolean()) 1 else -1
                    for (step in intArrayOf(first, -first)) {
                        val next = lane + step
                        if (next !in 0 until lanes) continue
                        if (next * 64 + column in takenRuns) continue
                        if (!claimStep(takenSteps, column, lane, next)) continue
                        corners += column to lane
                        lane = next
                        corners += column to lane
                        break
                    }
                }
                corners += column to lane

                val points = mutableListOf<Offset>()
                corners.forEach { (atColumn, atLane) ->
                    val point = Offset(atColumn * columnWidth, top + atLane * laneGap)
                    if (points.lastOrNull() != point) points += point
                }
                if (points.size > 1) add(Trace(mitre(points, cut)))
            }
        }
    }

    /**
     * Claims the vertical step between two lanes at one column, if it is free along its length.
     *
     * Two traces sharing a step would be drawn on top of each other, which is the one thing the
     * lattice exists to prevent. Crossing another trace's *run* on the way is fine, and is where
     * the board gets the crossings that stop it reading as a set of ruled lines.
     */
    private fun claimStep(taken: HashSet<Int>, column: Int, from: Int, to: Int): Boolean {
        val low = min(from, to)
        val high = maxOf(from, to)
        for (lane in low until high) if (column * 64 + lane in taken) return false
        for (lane in low until high) taken.add(column * 64 + lane)
        return true
    }

    /** Replaces each right angle with the two 45° elbows a router would have left there. */
    private fun mitre(points: List<Offset>, cut: Float): List<Offset> {
        if (points.size < 3) return points
        val out = mutableListOf(points.first())
        for (i in 1 until points.size - 1) {
            val before = points[i - 1]
            val corner = points[i]
            val after = points[i + 1]
            val into = hypot(corner.x - before.x, corner.y - before.y)
            val away = hypot(after.x - corner.x, after.y - corner.y)
            if (into < 0.001f || away < 0.001f) continue
            // Never past the middle of either leg, so two corners in a row cannot eat each other.
            val back = min(cut, into * 0.45f) / into
            val on = min(cut, away * 0.45f) / away
            out +=
                Offset(
                    corner.x + (before.x - corner.x) * back,
                    corner.y + (before.y - corner.y) * back,
                )
            out +=
                Offset(corner.x + (after.x - corner.x) * on, corner.y + (after.y - corner.y) * on)
        }
        out += points.last()
        return out
    }

    override fun update(dt: Float, size: Size) {
        if (size.width <= 0f || size.height <= 0f) return
        seed(size)
        val seconds = dt / 1000f

        if (reveal < 1f) reveal = (reveal + dt / 420f).coerceAtMost(1f)

        // Carried rather than derived from a clock, so a change of speed bends the sway from where
        // it is instead of jumping it to somewhere else in the swing. A signal under load flicks
        // faster, but never in proportion — a tail that whipped would read as a fault.
        wag += dt * (0.4f + 0.6f * speed) / 150f

        // The board works on its own. A signal every few seconds is what makes it read as a
        // living circuit rather than a wallpaper that happens to respond to taps.
        nextPulseMs -= dt
        if (nextPulseMs <= 0f && traces.isNotEmpty()) {
            nextPulseMs = PULSE_INTERVAL_MS * (0.65f + random.nextFloat() * 0.7f)
            fire(traces.random(random), 0f)
        }

        // And re-routes itself now and then, so the picture is never the same for long.
        nextRouteMs -= dt
        if (nextRouteMs <= 0f) {
            nextRouteMs = ROUTE_INTERVAL_MS
            reroute(size)
        }

        // Read every frame rather than stored on the pulse, so a drag reaches the signals already
        // in flight instead of only the next one.
        val travel = size.width * PULSE_SPEED * speed * seconds
        pulses.forEach { pulse ->
            val before = pulse.distance
            pulse.distance += travel

            // Light every via the pulse just crossed, so the board reacts to the signal passing
            // rather than only showing the signal itself.
            pulse.trace.pads.forEachIndexed { index, pad ->
                if (pad.distance in before..pulse.distance) pulse.litPads[index] = 1f
            }
            pulse.litPads.keys.toList().forEach { key ->
                val decayed = pulse.litPads.getValue(key) - dt / 700f
                if (decayed <= 0f) pulse.litPads.remove(key) else pulse.litPads[key] = decayed
            }
        }
        pulses.removeAll { it.distance > it.trace.total && it.litPads.isEmpty() }
    }

    /** How much of the width a sideways drag has covered since the last re-route. */
    private var swipedX = 0f

    /**
     * Sideways re-routes the board, up and down changes the speed.
     *
     * The traces are generated, not drawn by hand, so there is no reason the user should be stuck
     * with the one they were given — and watching a new board lay itself out is half the appeal.
     *
     * Each axis reads only itself, the way the code rain does: a drag is almost never purely one or
     * the other, so the vertical component is ignored while the finger is clearly travelling
     * sideways, and a re-route never shoves the speed somewhere nobody asked for.
     */
    override fun onDrag(pan: Offset, at: Offset, size: Size) {
        if (size.width <= 0f || size.height <= 0f) return

        if (abs(pan.x) > abs(pan.y) * 1.5f) {
            swipedX += pan.x / size.width
            if (abs(swipedX) >= REROUTE_FRACTION) {
                swipedX = 0f
                reroute(size)
                nextRouteMs = ROUTE_INTERVAL_MS
            }
            return
        }

        val delta = pan.y / size.height
        if (abs(delta) < 0.0005f) return
        speed *= 1f + delta * 1.6f
    }

    private fun reroute(size: Size) {
        layoutSeed++
        pulses.clear()
        route(size)
        reveal = 0f
    }

    private fun fire(trace: Trace, start: Float) {
        if (pulses.size >= 6) pulses.removeAt(0)
        pulses += Pulse(trace, start, random.nextFloat() * 2f * PI.toFloat())
    }

    override fun onTap(position: Offset, size: Size) {
        seed(size)
        // The trace whose route passes closest to the finger is the one that carries the signal.
        val nearest =
            traces.minByOrNull { trace ->
                trace.points.minOf { hypot(it.x - position.x, it.y - position.y) }
            } ?: return

        // Start the pulse level with the touch rather than at the board edge, so the tap feels
        // like the source of the signal.
        var travelled = 0f
        var start = 0f
        var closest = Float.MAX_VALUE
        nearest.lengths.forEachIndexed { index, length ->
            val a = nearest.points[index]
            val b = nearest.points[index + 1]
            val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
            val distance = hypot(mid.x - position.x, mid.y - position.y)
            if (distance < closest) {
                closest = distance
                start = travelled
            }
            travelled += length
        }

        fire(nearest, start)
    }

    override fun DrawScope.render(tint: Color) {
        val width = size.height * 0.005f * scale.coerceAtMost(1.8f)

        traces.forEachIndexed { traceIndex, trace ->
            // On a fresh board the traces draw themselves in left to right, one slightly after
            // the next, so a re-route looks like a board being laid rather than a hard cut.
            val stagger = (reveal * traces.size - traceIndex).coerceIn(0f, 1f)
            if (stagger <= 0f) return@forEachIndexed

            var drawn = 0f
            val target = trace.total * stagger
            trace.points.zipWithNext { a, b ->
                val segment = hypot(b.x - a.x, b.y - a.y)
                if (drawn >= target) return@zipWithNext
                val fraction = ((target - drawn) / segment).coerceIn(0f, 1f)
                drawLine(
                    // The board is the picture rather than a texture behind one, and much below
                    // this it disappears against a light wallpaper.
                    tint.copy(alpha = 0.13f),
                    a,
                    Offset(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction),
                    strokeWidth = width,
                )
                drawn += segment
            }

            if (stagger < 1f) return@forEachIndexed

            // The lands where a trace starts and ends: drawn as rings, so a trace that stops short
            // of the edge reads as having arrived somewhere rather than as unfinished.
            val land = Stroke(width = width * 0.9f)
            drawCircle(tint.copy(alpha = 0.20f), width * 2.6f, trace.points.first(), style = land)
            drawCircle(tint.copy(alpha = 0.20f), width * 2.6f, trace.points.last(), style = land)

            // Vias, on the elbows. One the signal just crossed flares.
            trace.pads.forEachIndexed { index, pad ->
                // Scanned rather than filtered: this runs for every via on every frame, and
                // there are never more than a handful of pulses to look through.
                var lit = 0f
                pulses.forEach { pulse ->
                    if (pulse.trace === trace) {
                        lit = maxOf(lit, pulse.litPads[index] ?: 0f)
                    }
                }
                drawCircle(
                    color = tint.copy(alpha = 0.17f + 0.45f * lit),
                    radius = width * (1.6f + 2.4f * lit),
                    center = pad.center,
                )
            }
        }

        pulses.forEach { pulse ->
            if (pulse.distance > pulse.trace.total) return@forEach

            // A signal is a swell of current, so it is drawn as one: a body of light along the
            // trace, at its widest and brightest under the head and drawn down to nothing behind.
            //
            // As one tapering ribbon rather than a chain of segments, because a chain beads
            // visibly the moment its stroke is wider than the step between samples, and a row of
            // beads reads as something being towed. The thickness breathes a little as the signal
            // travels, which is what a pulse does and what the swing this once spent on a sideways
            // sway is better spent on.
            val tail = size.width * TAIL_LENGTH
            val swell = 1f + TAIL_SWELL * sin(wag + pulse.phase)
            var samples = 0
            tailPath.reset()
            for (i in 0..TAIL_STEPS) {
                val along = i / TAIL_STEPS.toFloat()
                val behind = pulse.distance - tail * along
                if (behind < 0f) break
                val point = pulse.trace.pointAt(behind)
                // The heading, taken a little further along, so the ribbon lies across the trace
                // and follows it round the elbows rather than cutting the corner.
                val ahead = pulse.trace.pointAt(behind + width)
                val dx = ahead.x - point.x
                val dy = ahead.y - point.y
                val length = hypot(dx, dy).coerceAtLeast(0.001f)
                val half = width * TAIL_WIDTH * swell * (1f - along) * (1f - along) / 2f
                val acrossX = -dy / length * half
                val acrossY = dx / length * half
                if (samples == 0) tailPath.moveTo(point.x + acrossX, point.y + acrossY)
                else tailPath.lineTo(point.x + acrossX, point.y + acrossY)
                // The far edge is kept until the near one has been walked, so the outline closes
                // in one pass and the ribbon costs nothing but the two arrays it is written into.
                tailEdge[samples * 2] = point.x - acrossX
                tailEdge[samples * 2 + 1] = point.y - acrossY
                samples++
            }

            val head = pulse.trace.pointAt(pulse.distance)
            if (samples > 1) {
                for (i in samples - 1 downTo 0) {
                    tailPath.lineTo(tailEdge[i * 2], tailEdge[i * 2 + 1])
                }
                tailPath.close()
                drawPath(
                    tailPath,
                    Brush.linearGradient(
                        0f to tint.copy(alpha = 0.85f),
                        0.4f to tint.copy(alpha = 0.38f),
                        1f to tint.copy(alpha = 0f),
                        start = head,
                        end = pulse.trace.pointAt((pulse.distance - tail).coerceAtLeast(0f)),
                    ),
                )
            }

            // The head is the rounded end of that body rather than a ball towing it: its radius is
            // half the ribbon's width, so the two meet flush. The glow behind it is a hint of one —
            // enough to lift the head off the board, not enough to smudge it.
            drawCircle(color = tint.copy(alpha = 0.10f), radius = width * 3.4f, center = head)
            drawCircle(
                color = tint.copy(alpha = 0.92f),
                radius = width * TAIL_WIDTH / 2f,
                center = head,
            )
        }
    }
}

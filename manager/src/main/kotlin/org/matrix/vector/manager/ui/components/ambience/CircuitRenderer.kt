package org.matrix.vector.manager.ui.components.ambience

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Signal traces.
 *
 * The most on-theme of the set: this app manages a framework that injects code into running
 * processes, and the header quietly draws the picture of that — faint orthogonal traces, and a
 * touch that sends a **pulse travelling down the nearest one**, lighting the trace ahead of it and
 * leaving it dark behind.
 *
 * Traces are laid out with right angles only, the way a board is routed. Diagonals would read as
 * decoration; right angles read as a circuit.
 */
class CircuitRenderer : AmbienceRenderer {

    private companion object {
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 3f

        /** Average gap between unprompted pulses. */
        const val PULSE_INTERVAL_MS = 5_000f

        /** How long a board lasts before it re-routes itself. */
        const val ROUTE_INTERVAL_MS = 60_000f
    }

    /** A polyline of right-angled segments, plus its cumulative lengths for pulse travel. */
    private class Trace(val points: List<Offset>) {
        val lengths: List<Float> =
            points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
        val total: Float = lengths.sum().coerceAtLeast(1f)

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

    private class Pulse(val trace: Trace, var distance: Float, val speed: Float) {
        /** How lit the trace behind the pulse still is, per junction it has passed. */
        val litJunctions = mutableMapOf<Int, Float>()
    }

    private var traces: List<Trace> = emptyList()

    /**
     * How densely the board is laid out.
     *
     * Zooming out routes more traces with shorter runs between turns — a busier board seen from
     * further back; zooming in gives a few wide traces with long straight stretches. The stroke
     * follows it too, so a dense board does not turn into a grey wash.
     */
    override var scale: Float = 1f
        set(value) {
            val next = value.coerceIn(MIN_SCALE, MAX_SCALE)
            if (next == field) return
            field = next
            if (sized != Size.Zero) route(sized)
        }
    private val pulses = mutableListOf<Pulse>()
    private var sized = Size.Zero

    /** Rises to 1 while a freshly routed board fades in after a swipe. */
    private var reveal = 1f

    /** Bumped on every re-route so the layout is genuinely different each time. */
    private var layoutSeed = 1

    /** Counts down to the next unprompted pulse. */
    private var nextPulseMs = 2200f

    /** Counts down to the next unprompted re-route. */
    private var nextRouteMs = ROUTE_INTERVAL_MS

    override val isAnimating: Boolean
        // The board runs itself rather than only reacting: a status header is mostly looked at
        // rather than played with, and one that waits to be touched reads as dead.
        get() = true

    private fun seed(size: Size) {
        if (sized == size && traces.isNotEmpty()) return
        sized = size
        route(size)
    }

    /** Lays a fresh board. Called on first draw and again on every swipe. */
    private fun route(size: Size) {
        val random = Random(0xB0A2D + layoutSeed * 7919)
        traces =
            List(((6 + random.nextInt(4)) / scale).roundToInt().coerceIn(2, 26)) {
                val startY = size.height * (0.12f + random.nextFloat() * 0.76f)
                val points = mutableListOf(Offset(0f, startY))
                var x = 0f
                var y = startY
                // Walk rightwards, stepping vertically now and then — routed, not drawn.
                while (x < size.width) {
                    val run = size.width * (0.10f + random.nextFloat() * 0.22f) * scale
                    x = (x + run).coerceAtMost(size.width)
                    points += Offset(x, y)
                    if (x < size.width && random.nextFloat() < 0.72f) {
                        val rise = size.height * (0.08f + random.nextFloat() * 0.20f)
                        y = (y + if (random.nextBoolean()) rise else -rise)
                            .coerceIn(size.height * 0.08f, size.height * 0.92f)
                        points += Offset(x, y)
                    }
                }
                Trace(points)
            }
    }

    override fun update(dt: Float, size: Size) {
        if (size.width <= 0f || size.height <= 0f) return
        seed(size)
        val seconds = dt / 1000f

        if (reveal < 1f) reveal = (reveal + dt / 420f).coerceAtMost(1f)

        // The board works on its own. A signal every few seconds is what makes it read as a
        // living circuit rather than a wallpaper that happens to respond to taps.
        nextPulseMs -= dt
        if (nextPulseMs <= 0f && traces.isNotEmpty()) {
            nextPulseMs = PULSE_INTERVAL_MS * (0.65f + Random(layoutSeed * 31 + pulses.size).nextFloat() * 0.7f)
            fire(traces.random(Random(System.nanoTime())), 0f, size)
        }

        // And re-routes itself now and then, so the picture is never the same for long.
        nextRouteMs -= dt
        if (nextRouteMs <= 0f) {
            nextRouteMs = ROUTE_INTERVAL_MS
            reroute(size)
        }

        pulses.forEach { pulse ->
            val before = pulse.distance
            pulse.distance += pulse.speed * seconds

            // Light every junction the pulse just crossed, so the board reacts to the signal
            // passing rather than only showing the signal itself.
            var travelled = 0f
            pulse.trace.lengths.forEachIndexed { index, length ->
                travelled += length
                if (travelled in before..pulse.distance) pulse.litJunctions[index + 1] = 1f
            }
            pulse.litJunctions.keys.toList().forEach { key ->
                val decayed = (pulse.litJunctions.getValue(key) - dt / 700f)
                if (decayed <= 0f) pulse.litJunctions.remove(key)
                else pulse.litJunctions[key] = decayed
            }
        }
        pulses.removeAll { it.distance > it.trace.total && it.litJunctions.isEmpty() }
    }

    /**
     * A swipe re-routes the board.
     *
     * The traces are generated, not drawn by hand, so there is no reason the user should be stuck
     * with the one they were given — and watching a new board lay itself out is half the appeal.
     */
    private var dragged = 0f

    override fun onDrag(pan: Offset, at: Offset, size: Size) {
        if (size.height <= 0f) return
        dragged += hypot(pan.x, pan.y)
        if (dragged < size.height * 0.25f) return
        dragged = 0f
        reroute(size)
        nextRouteMs = ROUTE_INTERVAL_MS
    }

    private fun reroute(size: Size) {
        layoutSeed++
        pulses.clear()
        route(size)
        reveal = 0f
    }

    private fun fire(trace: Trace, start: Float, size: Size) {
        if (pulses.size >= 6) pulses.removeAt(0)
        pulses += Pulse(trace, start, size.width * 1.15f)
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
        val start =
            nearest.points
                .zip(nearest.points.drop(1))
                .fold(0f to Float.MAX_VALUE) { acc, (a, b) ->
                    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    val d = hypot(mid.x - position.x, mid.y - position.y)
                    if (d < acc.second) {
                        val travelled =
                            nearest.points
                                .take(nearest.points.indexOf(a) + 1)
                                .zipWithNext { p, q -> hypot(q.x - p.x, q.y - p.y) }
                                .sum()
                        travelled to d
                    } else acc
                }
                .first

        fire(nearest, start, size)
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

            // Junction pads, where the route turns. A pad the signal just crossed flares.
            trace.points.drop(1).dropLast(1).forEachIndexed { index, point ->
                val lit =
                    pulses
                        .filter { it.trace === trace }
                        .maxOfOrNull { it.litJunctions[index + 1] ?: 0f } ?: 0f
                drawCircle(
                    color = tint.copy(alpha = 0.17f + 0.45f * lit),
                    radius = width * (1.6f + 2.4f * lit),
                    center = point,
                )
            }
        }

        pulses.forEach { pulse ->
            if (pulse.distance > pulse.trace.total) return@forEach
            // A short lit stretch behind the head, so the signal reads as moving rather than as a
            // dot that happens to be somewhere.
            val tailSteps = 14
            val tailLength = size.width * 0.22f
            for (i in 0 until tailSteps) {
                val back = tailLength * i / tailSteps
                val d = pulse.distance - back
                if (d < 0f) break
                val fade = 1f - i / tailSteps.toFloat()
                drawCircle(
                    color = tint.copy(alpha = 0.42f * fade * fade),
                    radius = width * (1.5f * fade + 0.4f),
                    center = pulse.trace.pointAt(d),
                )
            }
            // The head: a solid core inside a soft halo, so it reads as something energetic
            // rather than as a ring travelling along a line.
            val head = pulse.trace.pointAt(pulse.distance)
            drawCircle(color = tint.copy(alpha = 0.10f), radius = width * 5.5f, center = head)
            drawCircle(color = tint.copy(alpha = 0.22f), radius = width * 3.2f, center = head)
            drawCircle(color = tint.copy(alpha = 0.75f), radius = width * 1.5f, center = head)
        }
    }
}

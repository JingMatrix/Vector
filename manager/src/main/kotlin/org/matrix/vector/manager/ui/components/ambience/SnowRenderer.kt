package org.matrix.vector.manager.ui.components.ambience

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Snowfall you can pick apart.
 *
 * Flakes are drawn as actual six-armed crystals — three crossed arms with a pair of barbs on each,
 * slowly rotating — rather than dots. A dot field reads as static; a crystal reads as a snowflake
 * even at eight pixels across, and the slow spin is what sells it.
 *
 * Two things answer a touch, and which one you get depends on whether you hit anything:
 * - **On a flake:** it bursts. The six arms detach, spin outward and fade over about a second, and
 *   the flake is gone. Slow on purpose — a fast pop would read as a glitch rather than an event.
 * - **On empty space:** a new flake *grows* there from nothing over a second and a half, then
 *   joins the fall. So the field is something you can prune and reseed rather than only disturb.
 */
class SnowRenderer : AmbienceRenderer {

    private companion object {
        const val BASE_FLAKES = 22
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 3f

        /** Slow enough to read as almost-still air; fast enough to read as a squall. */
        const val MIN_SPEED = 0.2f
        const val MAX_SPEED = 5f
    }

    private class Flake(
        var x: Float,
        var y: Float,
        val fullRadius: Float,
        val fallSpeed: Float,
        val swayPhase: Float,
        val swayRate: Float,
        val spin: Float,
        /** 0 while growing in from a tap, 1 once fully formed. */
        var growth: Float = 1f,
    ) {
        var angle: Float = 0f
    }

    /** A burst flake's arm, flying outward. */
    private class Shard(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        val length: Float,
        val spin: Float,
    ) {
        var age = 0f
        var angle = 0f
    }

    private val random = Random(0x5E6C)
    private val flakes = mutableListOf<Flake>()
    private val shards = mutableListOf<Shard>()
    private var clock = 0f
    private var sized = Size.Zero

    private val shardLifeMs = 1100f
    private val growMs = 1500f

    override val isAnimating: Boolean
        get() = true

    /**
     * Crystal size, and with it how many there are.
     *
     * Inversely: zooming out gives a fine dense drizzle, zooming in a few large crystals. Trading
     * count against size is what makes it read as one snowfall seen closer or further rather than
     * as two different settings.
     */
    override var scale: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SCALE, MAX_SCALE)
        }

    /**
     * How hard it is snowing, on the same vertical drag the code rain uses.
     *
     * The same gesture in the same place should mean the same thing whichever ambience is on, and
     * "how fast is this moving" is the one question every moving render can answer. Snow reads it
     * as weather: slowed right down it is the still air after a fall, pushed up it is a squall.
     *
     * The floor is above zero on purpose. A snowfall frozen mid-air reads as a rendering fault
     * rather than as a setting, and there is already a way to have no motion at all — the ambience
     * picker.
     */
    override var speed: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SPEED, MAX_SPEED)
        }

    override fun onDrag(pan: Offset, at: Offset, size: Size) {
        if (size.height <= 0f) return
        val delta = pan.y / size.height
        if (abs(delta) < 0.0005f) return
        speed *= 1f + delta * 1.6f
    }

    private fun target(): Int = (BASE_FLAKES / scale).roundToInt().coerceIn(6, 90)

    private fun seed(size: Size) {
        if (sized == size && flakes.isNotEmpty()) return
        sized = size
        flakes.clear()
        repeat(target()) { flakes += newFlake(size, random.nextFloat() * size.height) }
    }

    private fun newFlake(size: Size, atY: Float, atX: Float? = null, growing: Boolean = false) =
        Flake(
            x = atX ?: (random.nextFloat() * size.width),
            y = atY,
            fullRadius = size.height * (0.018f + random.nextFloat() * 0.030f) * scale,
            // Bigger crystals read as nearer, so they fall faster.
            fallSpeed = 10f + random.nextFloat() * 22f,
            swayPhase = random.nextFloat() * 2f * PI.toFloat(),
            swayRate = 0.3f + random.nextFloat() * 0.6f,
            spin = (random.nextFloat() - 0.5f) * 24f,
            growth = if (growing) 0f else 1f,
        )

    override fun update(dt: Float, size: Size) {
        if (size.width <= 0f || size.height <= 0f) return
        seed(size)
        // Follow the scale without restarting the snowfall: new crystals drift in from above and
        // surplus ones are taken from the top, so a pinch never blanks the field.
        val want = target()
        while (flakes.size < want) flakes += newFlake(size, -random.nextFloat() * size.height)
        while (flakes.size > want) flakes.removeAt(flakes.indexOf(flakes.minByOrNull { it.y }))
        clock += dt
        val seconds = dt / 1000f

        flakes.forEach { flake ->
            if (flake.growth < 1f) {
                flake.growth = (flake.growth + dt / growMs).coerceAtMost(1f)
            }
            flake.angle += flake.spin * seconds * speed
            val sway = sin(clock / 1000f * flake.swayRate + flake.swayPhase) * size.width * 0.010f
            // Sway and spin scale with the fall as well: a crystal that drifts and turns at full
            // rate while descending slowly does not read as slow snow, it reads as broken snow.
            flake.x += sway * seconds * speed
            flake.y += flake.fallSpeed * seconds * flake.growth * speed

            if (flake.y - flake.fullRadius > size.height) {
                flake.y = -flake.fullRadius
                flake.x = random.nextFloat() * size.width
            }
            if (flake.x < -flake.fullRadius) flake.x = size.width + flake.fullRadius
            if (flake.x > size.width + flake.fullRadius) flake.x = -flake.fullRadius
        }

        shards.forEach { shard ->
            // The debris of a burst crystal ages in real time whatever the speed — its lifetime is
            // how long the reader gets to watch it come apart, which is not a property of the
            // weather.
            shard.age += dt
            shard.x += shard.vx * seconds * speed
            shard.y += shard.vy * seconds * speed
            shard.angle += shard.spin * seconds * speed
        }
        shards.removeAll { it.age > shardLifeMs }
    }

    override fun onTap(position: Offset, size: Size) {
        seed(size)

        // Generous hit area — these are small targets and a miss that silently spawns a flake
        // instead would feel like the tap was ignored.
        val hit =
            flakes
                .filter { it.growth > 0.5f }
                .minByOrNull { hypot(it.x - position.x, it.y - position.y) }
                ?.takeIf { hypot(it.x - position.x, it.y - position.y) < it.fullRadius * 2.2f }

        if (hit != null) {
            burst(hit)
            flakes.remove(hit)
        } else if (flakes.size < 40) {
            flakes += newFlake(size, position.y, position.x, growing = true)
        }
    }

    private fun burst(flake: Flake) {
        val radius = flake.fullRadius * flake.growth
        repeat(6) { arm ->
            val theta = flake.angle * PI.toFloat() / 180f + arm * PI.toFloat() / 3f
            // Slow: the point is to watch it come apart, not to see it vanish.
            val speed = radius * (2.2f + random.nextFloat() * 1.6f)
            shards +=
                Shard(
                    x = flake.x,
                    y = flake.y,
                    vx = cos(theta) * speed,
                    vy = sin(theta) * speed - radius * 0.6f,
                    length = radius,
                    spin = (random.nextFloat() - 0.5f) * 220f,
                )
        }
    }

    override fun DrawScope.render(tint: Color) {
        flakes.forEach { flake ->
            val radius = flake.fullRadius * flake.growth
            if (radius <= 0.4f) return@forEach
            val depth = (flake.fullRadius / (size.height * 0.048f)).coerceIn(0.35f, 1f)
            val alpha = (0.10f + 0.14f * depth) * flake.growth
            rotate(degrees = flake.angle, pivot = Offset(flake.x, flake.y)) {
                drawCrystal(Offset(flake.x, flake.y), radius, tint.copy(alpha = alpha), width = radius * 0.16f)
            }
        }

        shards.forEach { shard ->
            val t = shard.age / shardLifeMs
            val alpha = 0.22f * (1f - t) * (1f - t)
            if (alpha < 0.004f) return@forEach
            rotate(degrees = shard.angle, pivot = Offset(shard.x, shard.y)) {
                val half = shard.length * (1f - t * 0.35f)
                drawLine(
                    color = tint.copy(alpha = alpha),
                    start = Offset(shard.x - half, shard.y),
                    end = Offset(shard.x + half, shard.y),
                    strokeWidth = shard.length * 0.16f,
                )
            }
        }
    }

    /** Three crossed arms with barbs — the smallest shape that still reads as a snowflake. */
    private fun DrawScope.drawCrystal(centre: Offset, radius: Float, color: Color, width: Float) {
        for (arm in 0 until 3) {
            val theta = arm * PI.toFloat() / 3f
            val dx = cos(theta) * radius
            val dy = sin(theta) * radius
            drawLine(
                color = color,
                start = Offset(centre.x - dx, centre.y - dy),
                end = Offset(centre.x + dx, centre.y + dy),
                strokeWidth = width,
            )
            // A barb near each tip, angled back along the arm.
            for (side in listOf(1f, -1f)) {
                val tip = Offset(centre.x + dx * side, centre.y + dy * side)
                val inner = Offset(centre.x + dx * side * 0.55f, centre.y + dy * side * 0.55f)
                for (branch in listOf(0.55f, -0.55f)) {
                    val bTheta = theta + branch
                    drawLine(
                        color = color,
                        start = inner,
                        end =
                            Offset(
                                inner.x + cos(bTheta) * radius * 0.34f * side,
                                inner.y + sin(bTheta) * radius * 0.34f * side,
                            ),
                        strokeWidth = width * 0.75f,
                    )
                }
                // A dot at the tip keeps the silhouette from looking like a bare cross.
                drawCircle(color = color, radius = width * 0.7f, center = tip)
            }
        }
    }
}

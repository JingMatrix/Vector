package org.matrix.vector.manager.ui.components.ambience

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.matrix.vector.manager.R

/**
 * What the status header's open space is doing.
 *
 * The header needs breathing room above the status line, and empty space that exists only because
 * a layout needed it reads as a mistake. Giving it something to *be* — a surface that answers when
 * touched — turns the same pixels from an accident into the most characterful part of the app.
 *
 * Every option must stay in the background's role: it draws in the header's own on-container
 * colour at low alpha, never competes with the text over it, and never moves fast enough to pull
 * the eye while someone is reading. [None] exists for people who want none of it, and skips the
 * frame loop entirely rather than animating something invisible.
 */
enum class AmbienceKind(val key: String, val labelRes: Int) {
    /** Snowfall. Tap a flake to burst it; tap empty space and one grows there. */
    Snow("snow", R.string.ambience_snow),
    /** A carved maze with one wanderer in it. Tap to move it, swipe for a new maze. */
    Maze("maze", R.string.ambience_maze),
    /**
     * Signal traces carrying several pulses at once. Tap to fire one, swipe to re-route.
     *
     * Kept beside [Maze] rather than replaced by it because they are opposites and both are worth
     * having: a circuit is a designed path many signals share, a maze is an undesigned one a single
     * wanderer has to solve.
     */
    Circuit("circuit", R.string.ambience_circuit),
    /** Falling code. Hold to stop the rain and pick a glyph out of it; pinch to go deeper. */
    Matrix("matrix", R.string.ambience_matrix),
    None("none", R.string.ambience_none);

    companion object {
        fun from(key: String?): AmbienceKind = entries.firstOrNull { it.key == key } ?: Maze
    }
}

/**
 * A self-contained little simulation.
 *
 * Deliberately mutable and frame-driven rather than built from Compose animations: these have
 * dozens of independent particles with their own lifetimes, which `animate*AsState` models badly.
 * The renderer owns its state, the header owns the clock.
 */
interface AmbienceRenderer {
    /** [dt] is milliseconds since the previous frame. */
    fun update(dt: Float, size: Size)

    fun DrawScope.render(tint: Color)

    fun onTap(position: Offset, size: Size)

    /** A press held down; the surface may freeze, grab something, or both. */
    fun onLongPress(position: Offset, size: Size) {}

    /** The held press ended. */
    fun onRelease() {}

    /**
     * A drag, reported as the movement since the previous event.
     *
     * The increment rather than the distance from where the finger went down, because the surface
     * has no reliable notion of where that was: the transform detector reports no gesture boundary,
     * so a remembered origin leaks from one drag into the next — the maze would rebuild on a nudge
     * and the rain would hit its speed ceiling on the first flick. A renderer that wants a total
     * accumulates one and decides for itself when it has seen enough.
     */
    fun onDrag(pan: Offset, at: Offset, size: Size) {}

    /**
     * How large this render draws itself, as a multiple of its resting size.
     *
     * Every ambience answers a pinch, and every one answers it the same way: a *scale*, not a
     * camera. Zooming out gives more of the thing — finer drizzle, a bigger maze, more traces —
     * and zooming in gives fewer and larger. None of these fields has the parallax cues that would
     * sell a viewer moving through them, so a simulated camera reads as sliding rather than as
     * approaching.
     *
     * The surface owns the number so it can be persisted; the renderer only has to honour it.
     */
    var scale: Float

    /**
     * How fast it moves, as a multiple of its resting speed.
     *
     * Only meaningful where there is continuous motion — the maze wanderer and the circuit pulses
     * move on their own schedule, so they ignore it.
     */
    var speed: Float
        get() = 1f
        set(_) {}

    /**
     * Which of the render's own variations it is drawing, cycled by a double tap.
     *
     * Only the code rain has any: its glyphs are half-width katakana, which is what makes the
     * effect read as *code* rather than as prose — and is also a cultural reference not everyone
     * wants on their home screen. Rather than argue about the default, the surface offers other
     * alphabets and remembers which was chosen.
     *
     * An index rather than an enum because the surface persists it without knowing what any
     * renderer's variations are, and a renderer is free to have none.
     */
    var variant: Int
        get() = 0
        set(_) {}

    /** A double tap. Distinct from [onTap], which seeds rather than switches. */
    fun onDoubleTap() {}

    /**
     * Whether a double tap means anything here.
     *
     * Asked because listening for one is not free: a detector that must wait to see whether a
     * second tap follows delays *every* single tap by the double-tap timeout. Only the code rain
     * has variations, so only the code rain pays for them.
     */
    val hasVariants: Boolean
        get() = false

    /**
     * False when nothing is moving, letting the header park the frame loop.
     *
     * A status header is on screen the whole time someone reads the activity feed, so an ambience
     * with nothing to do should cost nothing.
     */
    val isAnimating: Boolean
}

fun rendererFor(kind: AmbienceKind): AmbienceRenderer? =
    when (kind) {
        AmbienceKind.Snow -> SnowRenderer()
        AmbienceKind.Maze -> MazeRenderer()
        AmbienceKind.Circuit -> CircuitRenderer()
        AmbienceKind.Matrix -> MatrixRenderer()
        AmbienceKind.None -> null
    }

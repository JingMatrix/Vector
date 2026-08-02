package org.matrix.vector.manager.ui.components.ambience

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Falling code.
 *
 * Columns of glyphs descending at their own speeds, each with a bright head and a tail that dims
 * behind it, and glyphs that occasionally flip to something else mid-fall — the flicker is what
 * makes it read as *code* rather than as text scrolling past.
 *
 * Three ways to interact, and the point of all of them is that the rain is normally too fast to
 * read:
 * - **Hold** and it stops dead. The whole field freezes so it can actually be read, and the glyph
 *   under your finger is lifted out of the column — drawn larger and brighter, held between the
 *   fingertip and the surface it came from. Let go and it drops back and the rain resumes.
 * - **Pinch** to change the glyph size. Larger glyphs mean fewer, wider columns and a rain that can
 *   be read at a glance; smaller ones mean a dense fine drizzle. It is a scale, not a camera: a
 *   flat field of text has no parallax cues, so a simulated approach reads as the columns sliding
 *   sideways.
 * - **Tap** to seed a new column at that point, so a bare stretch can be filled in.
 */
class MatrixRenderer : AmbienceRenderer {

    /**
     * One falling column.
     *
     * [weight] is only variety — heavier columns fall a little faster and draw a little brighter,
     * so the field does not read as a metronome. It is deliberately *not* a depth coordinate: the
     * size of a glyph is one global number, so what a pinch changes is legibility rather than an
     * imaginary camera position.
     */
    private class Column(
        /** Position across the field, 0 (left edge) to 1 (right edge). */
        val lane: Float,
        var head: Float,
        val weight: Float,
        val length: Int,
        val glyphs: MutableList<Char>,
    )

    private val random = Random(0x4D41)
    private val columns = mutableListOf<Column>()
    private var sized = Size.Zero
    private var clock = 0f

    /**
     * Glyph size, as a multiple of the resting size.
     *
     * Held in these terms because it is what the surface persists and what a pinch multiplies, but
     * what it really sets is [across] — how many columns fit over the header's width — and the
     * glyph size is worked back from that. The bounds are therefore the column bounds read the
     * other way round: thirty across at the largest, a hundred and twenty at the smallest. See
     * [MIN_SCALE].
     */
    override var scale: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SCALE, MAX_SCALE)
        }

    /**
     * How fast the rain falls, as a multiple of its resting speed.
     *
     * A vertical drag sets it, which is the one axis the rain itself already means: dragging down
     * pushes it along, dragging up holds it back. Pinch was already spoken for by the glyph size,
     * and the two are genuinely different questions — how much can I read at once, and how long do
     * I get to read it.
     */
    override var speed: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SPEED, MAX_SPEED)
        }

    private var frozen = false
    private var heldAt: Offset? = null
    private var heldGlyph: Char? = null
    /** Eases the freeze so the rain slows to a stop rather than snapping. */
    private var motion = 1f

    override val isAnimating: Boolean
        // Still frozen? Then the only thing that could change is the held glyph's own pulse, and
        // that is worth a frame. Otherwise the rain is always moving.
        get() = true

    /**
     * The alphabets a double tap cycles through.
     *
     * Half-width katakana is what the original used, and it is why the effect reads as *code* and
     * not as prose: the glyphs are dense, unfamiliar and uniform in width. Unfamiliarity is doing
     * the work — which is exactly why the other sets are chosen for the same property rather than
     * for being Latin. Hexadecimal and the punctuation of a source file are both alphabets a reader
     * does not parse into words at a glance, so they keep the effect while dropping the reference.
     *
     * Katakana stays first, and so stays the default: this offers a way out for someone who does
     * not want it, which is not the same as deciding nobody should have it.
     */
    private val alphabets: List<List<Char>> =
        listOf(
            buildList {
                for (c in 'ｱ'..'ﾝ') add(c)
                for (c in '0'..'9') add(c)
                addAll("VECTORXPOSED".toList())
            },
            // Hexadecimal, which is what a hex dump of anything looks like.
            buildList {
                for (c in '0'..'9') add(c)
                for (c in 'A'..'F') add(c)
            },
            // The punctuation that makes text look like source rather than like sentences.
            "{}[]()<>/\\|;:=+-*&^%$#@!?~".toList(),
            // Latin letters and digits, for a reader who wants none of the above.
            buildList {
                for (c in 'A'..'Z') add(c)
                for (c in '0'..'9') add(c)
            },
        )

    override var variant: Int = 0
        set(value) {
            val next = ((value % alphabets.size) + alphabets.size) % alphabets.size
            if (next == field) return
            field = next
            // Re-rolled rather than left to cycle out on its own: a switch that takes twenty
            // seconds to become visible does not read as an answer to the gesture.
            columns.forEach { column ->
                for (i in column.glyphs.indices) column.glyphs[i] = randomGlyph()
            }
        }

    override val hasVariants: Boolean
        get() = true

    override fun onDoubleTap() {
        variant += 1
    }

    private fun randomGlyph(): Char =
        alphabets[variant].let { it[random.nextInt(it.size)] }

    private fun seed(size: Size) {
        if (sized == size && columns.isNotEmpty()) return
        sized = size
        columns.clear()
        repeat(target()) { columns += newColumn(size) }
    }

    /**
     * Lanes taken in golden-ratio steps rather than drawn at random.
     *
     * Eighty random numbers on the width do not cover it evenly: they clump into doubled streams
     * in one place and leave the header bare in another, and at the far end of a zoom in — where
     * thirty large glyphs have to carry the whole field — the gaps are most of what you see.
     * Stepping by the golden ratio instead spreads every prefix of the sequence as evenly as that
     * many points can be spread, so the field looks like the count it is carrying whether it is
     * holding thirty streams or a hundred and twenty, and columns can still be added one at a time
     * without knowing how many will follow.
     */
    private var lanesTaken = 0

    private fun nextLane(): Float {
        lanesTaken++
        return (lanesTaken * 0.618_034f) % 1f
    }

    private fun newColumn(size: Size, atLane: Float? = null, atHead: Float? = null): Column {
        val length = 3 + random.nextInt(6)
        return Column(
            lane = atLane ?: nextLane(),
            head = atHead ?: (-random.nextFloat() * size.height * 1.6f),
            weight = 0.45f + random.nextFloat() * 0.85f,
            length = length,
            glyphs = MutableList(length + 1) { randomGlyph() },
        )
    }

    /**
     * How many columns fit side by side across the header at the current zoom.
     *
     * This is the number the pinch actually sets. The glyph size follows from it rather than the
     * other way round, because "how much of this can I read at once" is the question somebody
     * pinching a field of text is asking, and a size in pixels answers it differently on every
     * screen. [MIN_COLUMNS] and [MAX_COLUMNS] are therefore a limit on how large and how small a
     * glyph is allowed to get, expressed in the only units that mean the same thing everywhere.
     */
    private fun across(): Int =
        (COLUMNS / scale).roundToInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    /**
     * The height of one glyph cell.
     *
     * Worked back from the width a column is allowed: a monospace glyph advances about six tenths
     * of its font size, and the font is drawn at [GLYPH_HEIGHT] of the cell, so the cell that puts
     * [across] columns across the header is the width of one divided by the two together.
     */
    private fun cell(): Float = sized.width / (across() * GLYPH_ADVANCE * GLYPH_HEIGHT)

    /** How many columns to carry: one to each lane that fits, plus whatever was tapped in. */
    private fun target(): Int = (across() + seeded).coerceAtMost(MAX_COLUMNS)

    /** Columns the reader has tapped in, kept across a pinch so a seeded stream is not stolen. */
    private var seeded = 0

    override fun update(dt: Float, size: Size) {
        if (size.width <= 0f || size.height <= 0f) return
        seed(size)
        clock += dt

        // Follow the glyph size without restarting the rain: new streams arrive from above the
        // top edge, and the ones taken away are those that have already fallen furthest, so a
        // pinch never blanks a stretch of the header.
        val want = target()
        while (columns.size < want) columns += newColumn(size)
        while (columns.size > want) {
            columns.removeAt(columns.indexOf(columns.maxByOrNull { it.head }))
        }

        // Ease into and out of the freeze.
        val motionTarget = if (frozen) 0f else 1f
        motion += (motionTarget - motion) * (dt / 220f).coerceAtMost(1f)

        val seconds = dt / 1000f
        val cell = cell()
        columns.forEach { column ->
            // Speed follows the glyph size, so zooming in does not turn the rain into a crawl.
            column.head += cell * column.weight * 1.5f * seconds * motion * speed

            // Glyphs flicker as they fall; this is the detail that makes it look alive.
            if (motion > 0.05f && random.nextFloat() < dt / 340f) {
                column.glyphs[random.nextInt(column.glyphs.size)] = randomGlyph()
            }

            if (column.head - column.length * cell > size.height) {
                column.head = -random.nextFloat() * size.height * 0.6f
                for (i in column.glyphs.indices) column.glyphs[i] = randomGlyph()
            }
        }
    }

    override fun onTap(position: Offset, size: Size) {
        seed(size)
        if (target() >= MAX_COLUMNS) return
        // Seeded right where the finger went down, so it is plainly the one you just made, and
        // counted, so the next frame does not take it straight back off the field.
        seeded++
        columns += newColumn(size, atLane = position.x / size.width, atHead = position.y)
    }

    override fun onLongPress(position: Offset, size: Size) {
        seed(size)
        frozen = true
        heldAt = position
        // Whichever column the finger is over gives up its glyph. Nearer columns win ties,
        // because those are the ones the eye was on.
        val column = columns.minByOrNull { abs(screenX(it, sized) - position.x) }
        heldGlyph = column?.glyphs?.firstOrNull() ?: randomGlyph()
    }

    override fun onRelease() {
        frozen = false
        heldAt = null
        heldGlyph = null
    }

    /**
     * A vertical drag changes how fast it falls.
     *
     * Scaled against the header's own height, so the same physical gesture does the same thing on
     * any screen, and multiplicative so it is as easy to slow a fast rain as to speed a slow one.
     */
    override fun onDrag(pan: Offset, at: Offset, size: Size) {
        if (size.height <= 0f || size.width <= 0f) return

        // Sideways reshuffles, downwards changes the speed, and the two do not fight because each
        // reads only its own axis. A drag is almost never purely one or the other, so the vertical
        // component is ignored while the finger is clearly travelling sideways; otherwise every
        // reshuffle would also shove the speed somewhere the reader did not ask for.
        val sideways = abs(pan.x) > abs(pan.y) * 1.5f
        if (sideways) {
            swipedX += pan.x / size.width
            if (abs(swipedX) >= RESHUFFLE_FRACTION) {
                swipedX = 0f
                reshuffle(size)
            }
            return
        }

        val delta = pan.y / size.height
        if (abs(delta) < 0.0005f) return
        speed *= 1f + delta * 1.6f
    }

    /**
     * A fresh fall: same alphabet, new arrangement.
     *
     * Not a reset — the speed, the zoom and the chosen alphabet are the reader's settings and
     * survive. What changes is the thing that cannot be chosen: which lanes are busy, how long the
     * streams are, where each one happens to be. A rain that has been watched for a while settles
     * into a recognisable pattern, and this is the way to ask for another one.
     *
     * The heads start above the top rather than at their old positions, so the new fall arrives
     * from off-screen instead of appearing mid-air.
     */
    private fun reshuffle(size: Size) {
        columns.clear()
        repeat(target()) { columns += newColumn(size) }
    }

    /** How much of the width a sideways drag must cover before the rain is redrawn. */
    private var swipedX = 0f

    /** Where a column lands on screen. Lanes are fixed; only the glyphs on them change size. */
    private fun screenX(column: Column, size: Size): Float = column.lane * size.width

    override fun DrawScope.render(tint: Color) {
        val measurer = textMeasurer ?: return
        if (sized.width < 1f) return

        // The simulation works in pixels; text is specified in sp, so the conversion goes
        // through the draw scope's own density rather than a guess.
        val style = TextStyle(fontFamily = FontFamily.Monospace)
        val cell = cell()
        val glyphSize = cell * GLYPH_HEIGHT
        if (glyphSize < 2f) return

        columns.forEach { column ->
            val x = screenX(column, size)
            if (x < -cell || x > size.width + cell) return@forEach

            for (i in 0..column.length) {
                val y = column.head - i * cell
                if (y < -cell || y > size.height + cell) continue

                val fade = 1f - i / (column.length + 1f)
                // Weight varies brightness only, so columns differ from one another without the
                // field pretending to a depth it cannot show.
                val weightAlpha = (column.weight / 1.3f).coerceIn(0.25f, 1f)
                val alpha = (if (i == 0) 0.50f else 0.26f * fade * fade) * weightAlpha
                if (alpha < 0.005f) continue

                drawText(
                    textMeasurer = measurer,
                    text = column.glyphs.getOrElse(i) { ' ' }.toString(),
                    style =
                        style.copy(color = tint.copy(alpha = alpha), fontSize = glyphSize.toSp()),
                    topLeft = Offset(x, y),
                )
            }
        }

        // The glyph lifted out of the rain, held under the finger.
        val held = heldAt
        val glyph = heldGlyph
        if (held != null && glyph != null) {
            val pulse = 0.82f + 0.18f * kotlin.math.sin(clock / 260f)
            drawText(
                textMeasurer = measurer,
                text = glyph.toString(),
                style =
                    style.copy(
                        color = tint.copy(alpha = 0.85f),
                        fontSize = (glyphSize * 2.1f * pulse).toSp(),
                    ),
                topLeft = Offset(held.x - glyphSize * 0.6f, held.y - glyphSize * 1.5f),
            )
        }
    }

    /**
     * Text needs a measurer, which a [DrawScope] does not carry. The surface injects it.
     *
     * Kept as a plain field rather than a constructor parameter so every renderer can share one
     * factory signature.
     */
    var textMeasurer: TextMeasurer? = null

    private companion object {
        /**
         * How many columns fit across the header: at rest, and the range the pinch may take it to.
         *
         * This is the glyph size, said in the units that matter. Thirty columns across is as large
         * as a glyph is allowed to be — beyond that the rain reads as a headline someone left
         * behind the text — and a hundred and twenty is as small, which is where the glyphs stop
         * being glyphs and become the texture of glyphs.
         */
        const val COLUMNS = 80
        const val MIN_COLUMNS = 30
        const val MAX_COLUMNS = 120

        /**
         * How a monospace glyph sits in its cell: it advances about six tenths of its font size,
         * and the font is drawn at a little over four fifths of the cell's height. Together they
         * turn the width one column is allowed into the height of a cell.
         */
        const val GLYPH_ADVANCE = 0.6f
        const val GLYPH_HEIGHT = 0.82f

        /**
         * How far the pinch goes, derived from the counts above rather than chosen separately.
         *
         * The column count is the honest limit — fewer than thirty across and the glyphs are too
         * large to be rain, more than a hundred and twenty and they are too small to be glyphs —
         * so the zoom stops exactly where the count would otherwise have to be clamped. Bounding
         * the glyph size independently would let the pinch carry on moving after the field had
         * stopped answering it, which reads as a broken gesture.
         */
        val MIN_SCALE = COLUMNS.toFloat() / MAX_COLUMNS
        val MAX_SCALE = COLUMNS.toFloat() / MIN_COLUMNS

        /** A quarter of the width: past a flick, short of a deliberate sweep. */
        const val RESHUFFLE_FRACTION = 0.25f

        const val MIN_SPEED = 0.15f
        const val MAX_SPEED = 6f
    }
}

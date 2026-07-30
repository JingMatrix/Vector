package org.matrix.vector.manager.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A complete Material 3 colour scheme generated from one colour.
 *
 * Android's own generator is only reachable through `dynamicColorScheme`, which reads the
 * *wallpaper* and nothing else — there is no public API that takes a colour and hands back a
 * scheme. So a user who wants Vector in their own colour, or anyone below Android 12 where dynamic
 * colour does not exist at all, would otherwise be stuck with a single hand-written palette. This
 * file removes that limit without pulling in a dependency.
 *
 * The work happens in **CIE LCh**, which is the same idea as Google's HCT: hold the hue and the
 * colourfulness of the seed fixed, and walk the *lightness* up and down to build a tonal ramp. That
 * is what makes a generated scheme feel like one colour rather than thirteen — every role is the
 * same hue at a different tone. Lightness here is L\*, perceptually uniform, so "tone 40" really is
 * forty percent of the way from black to white to the eye, which is what the Material contrast
 * pairings assume.
 *
 * The one subtlety is gamut. Most (lightness, hue) pairs cannot hold the seed's full chroma in
 * sRGB — a vivid yellow simply does not exist at tone 20 — so each tone binary-searches the most
 * chroma it can keep before the conversion falls off the edge of the display. Clipping the channels
 * instead, which is the obvious shortcut, shifts the hue and is exactly why naive generators
 * produce ramps that drift from blue to purple as they darken.
 */
object SeedScheme {

    /**
     * The Winged Victory's patina — the deepest of the eight tones in `ic_winged_victory.xml` — and
     * the app's identity colour when nothing else is chosen.
     */
    const val DEFAULT_SEED: Int = 0xFF6ABFCF.toInt()

    /**
     * Presets worth offering before someone reaches for the wheel.
     *
     * Chosen to be far apart in hue and all viable as an accent at both ends of the tone scale —
     * a palette of near-neighbours would look like a bug.
     */
    val PRESETS: List<Int> =
        listOf(
            DEFAULT_SEED,
            0xFF4C6FFF.toInt(),
            0xFF7C4DFF.toInt(),
            0xFFD4467A.toInt(),
            0xFFE0603A.toInt(),
            0xFFD9A227.toInt(),
            0xFF3F9B54.toInt(),
            0xFF5B6570.toInt(),
        )

    /** Builds the scheme a seed implies, for one of the two brightnesses. */
    fun of(seed: Int, dark: Boolean): ColorScheme {
        val (_, chroma, hue) = Color(seed).toLch()

        // The standard Material "tonal spot" recipe: the accent is pushed to a floor of chroma so
        // that a washed-out seed still yields a usable accent, the supporting roles are
        // deliberately quieter, and the neutrals keep a trace of the hue so that surfaces feel
        // related to the accent instead of grey next to it.
        val primary = Ramp(hue, chroma.coerceAtLeast(48f))
        val secondary = Ramp(hue, 16f)
        val tertiary = Ramp(hue + 60f, 24f)
        val neutral = Ramp(hue, 4f)
        val variant = Ramp(hue, 8f)
        // Error stays red whatever the seed. A destructive action must not become negotiable
        // because someone picked a green theme.
        val error = Ramp(25f, 84f)

        return if (dark) {
            darkColorScheme(
                primary = primary[80],
                onPrimary = primary[20],
                primaryContainer = primary[30],
                onPrimaryContainer = primary[90],
                inversePrimary = primary[40],
                secondary = secondary[80],
                onSecondary = secondary[20],
                secondaryContainer = secondary[30],
                onSecondaryContainer = secondary[90],
                tertiary = tertiary[80],
                onTertiary = tertiary[20],
                tertiaryContainer = tertiary[30],
                onTertiaryContainer = tertiary[90],
                error = error[80],
                onError = error[20],
                errorContainer = error[30],
                onErrorContainer = error[90],
                background = neutral[6],
                onBackground = neutral[90],
                surface = neutral[6],
                onSurface = neutral[90],
                surfaceVariant = variant[30],
                onSurfaceVariant = variant[80],
                surfaceTint = primary[80],
                inverseSurface = neutral[90],
                inverseOnSurface = neutral[20],
                outline = variant[60],
                outlineVariant = variant[30],
                scrim = Color.Black,
                surfaceBright = neutral[24],
                surfaceDim = neutral[6],
                surfaceContainerLowest = neutral[4],
                surfaceContainerLow = neutral[10],
                surfaceContainer = neutral[12],
                surfaceContainerHigh = neutral[17],
                surfaceContainerHighest = neutral[22],
            )
        } else {
            lightColorScheme(
                primary = primary[40],
                onPrimary = primary[100],
                primaryContainer = primary[90],
                onPrimaryContainer = primary[10],
                inversePrimary = primary[80],
                secondary = secondary[40],
                onSecondary = secondary[100],
                secondaryContainer = secondary[90],
                onSecondaryContainer = secondary[10],
                tertiary = tertiary[40],
                onTertiary = tertiary[100],
                tertiaryContainer = tertiary[90],
                onTertiaryContainer = tertiary[10],
                error = error[40],
                onError = error[100],
                errorContainer = error[90],
                onErrorContainer = error[10],
                background = neutral[98],
                onBackground = neutral[10],
                surface = neutral[98],
                onSurface = neutral[10],
                surfaceVariant = variant[90],
                onSurfaceVariant = variant[30],
                surfaceTint = primary[40],
                inverseSurface = neutral[20],
                inverseOnSurface = neutral[95],
                outline = variant[50],
                outlineVariant = variant[80],
                scrim = Color.Black,
                surfaceBright = neutral[98],
                surfaceDim = neutral[87],
                surfaceContainerLowest = neutral[100],
                surfaceContainerLow = neutral[96],
                surfaceContainer = neutral[94],
                surfaceContainerHigh = neutral[92],
                surfaceContainerHighest = neutral[90],
            )
        }
    }

    /** The tones the palette preview shows, light to dark. */
    val PREVIEW_TONES = intArrayOf(95, 90, 80, 70, 60, 50, 40, 30, 20, 10)

    /** One hue's worth of colour, at every tone. Memoised because a scheme asks for ~40 of them. */
    class Ramp(private val hue: Float, private val chroma: Float) {
        private val cache = HashMap<Int, Color>(16)

        operator fun get(tone: Int): Color = cache.getOrPut(tone) { lchToColor(tone.toFloat(), chroma, hue) }
    }

    /** A colour built directly from a point on the wheel, for the picker's own display. */
    fun wheelColor(hue: Float, chroma: Float, tone: Float = 60f): Color = lchToColor(tone, chroma, hue)

    /** The wheel position a colour occupies: chroma as radius, hue as angle. */
    fun Color.toWheel(): Pair<Float, Float> {
        val (_, c, h) = toLch()
        return c to h
    }

    // ---- colour space conversions -------------------------------------------------------------
    //
    // sRGB <-> linear RGB <-> CIE XYZ (D65) <-> CIE Lab <-> LCh. Standard formulae; the constants
    // are the sRGB primaries and the D65 white point.

    private const val WHITE_X = 95.047f
    private const val WHITE_Y = 100.0f
    private const val WHITE_Z = 108.883f

    private fun Color.toLch(): Triple<Float, Float, Float> {
        val r = linearize(red)
        val g = linearize(green)
        val b = linearize(blue)

        val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) * 100f
        val y = (0.2126f * r + 0.7152f * g + 0.0722f * b) * 100f
        val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) * 100f

        val fx = labF(x / WHITE_X)
        val fy = labF(y / WHITE_Y)
        val fz = labF(z / WHITE_Z)

        val l = 116f * fy - 16f
        val a = 500f * (fx - fy)
        val bb = 200f * (fy - fz)

        val chroma = hypot(a, bb)
        var hue = Math.toDegrees(atan2(bb, a).toDouble()).toFloat()
        if (hue < 0f) hue += 360f
        return Triple(l, chroma, hue)
    }

    /**
     * The most saturated colour of this hue that sRGB can actually show at this lightness.
     *
     * Binary search rather than arithmetic because the sRGB gamut is not a nice shape in LCh — its
     * boundary depends on all three coordinates at once. Twelve iterations lands well within a
     * quarter-unit of chroma, below what an eye resolves. A scheme asks for a few dozen of these
     * per theme change; the only caller that wants them in bulk is the colour wheel, which renders
     * off the main thread and caches the result.
     */
    private fun lchToColor(lightness: Float, chroma: Float, hueDegrees: Float): Color {
        val l = lightness.coerceIn(0f, 100f)
        if (chroma <= 0.01f) return labToColor(l, 0f, 0f)

        val hue = Math.toRadians(hueDegrees.toDouble())
        val cosH = cos(hue).toFloat()
        val sinH = sin(hue).toFloat()

        fun fits(c: Float): Boolean = inGamut(l, c * cosH, c * sinH)

        if (fits(chroma)) return labToColor(l, chroma * cosH, chroma * sinH)

        var low = 0f
        var high = chroma
        repeat(12) {
            val mid = (low + high) / 2f
            if (fits(mid)) low = mid else high = mid
        }
        return labToColor(l, low * cosH, low * sinH)
    }

    private fun inGamut(l: Float, a: Float, b: Float): Boolean {
        val (r, g, bl) = labToLinear(l, a, b)
        // A hair of tolerance: the round trip is float maths, and rejecting a colour that is out
        // of gamut by one part in ten thousand costs visible chroma for nothing.
        return r >= -0.0001f && r <= 1.0001f &&
            g >= -0.0001f && g <= 1.0001f &&
            bl >= -0.0001f && bl <= 1.0001f
    }

    private fun labToColor(l: Float, a: Float, b: Float): Color {
        val (r, g, bl) = labToLinear(l, a, b)
        return Color(
            red = delinearize(r),
            green = delinearize(g),
            blue = delinearize(bl),
        )
    }

    private fun labToLinear(l: Float, a: Float, b: Float): Triple<Float, Float, Float> {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f

        val x = labInverseF(fx) * WHITE_X / 100f
        val y = labInverseF(fy) * WHITE_Y / 100f
        val z = labInverseF(fz) * WHITE_Z / 100f

        val r = 3.2406f * x - 1.5372f * y - 0.4986f * z
        val g = -0.9689f * x + 1.8758f * y + 0.0415f * z
        val bl = 0.0557f * x - 0.2040f * y + 1.0570f * z
        return Triple(r, g, bl)
    }

    private fun labF(t: Float): Float =
        if (t > 0.008856f) cbrt(t.toDouble()).toFloat() else (7.787f * t + 16f / 116f)

    private fun labInverseF(t: Float): Float {
        val cubed = t * t * t
        return if (cubed > 0.008856f) cubed else (t - 16f / 116f) / 7.787f
    }

    private fun linearize(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f
        else ((channel + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun delinearize(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f
        else (1.055f * c.toDouble().pow(1.0 / 2.4) - 0.055f).toFloat()
    }

    /** `#RRGGBB`, for showing the seed as something a user can write down or type back in. */
    fun Color.toHex(): String =
        "#%02X%02X%02X"
            .format((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())
}

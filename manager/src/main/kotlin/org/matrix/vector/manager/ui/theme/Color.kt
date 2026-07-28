package org.matrix.vector.manager.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The fallback palette, used below Android 12 and whenever the user turns dynamic colour off.
 *
 * It is seeded from the Winged Victory's own patina, `#6ABFCF` — the deepest of the eight tones in
 * `ic_winged_victory.xml`. Above API 31 the wallpaper decides instead, which is why nothing in this
 * app may rely on a specific hue to carry meaning: `StatusHeader` and `UpdatableVersion` carry
 * state by shape, icon, label and motion as well as by colour.
 */
val VectorLightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF00687A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFADEDFF),
        onPrimaryContainer = Color(0xFF001F27),
        inversePrimary = Color(0xFF55D6F2),
        secondary = Color(0xFF4B6269),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCEE7EF),
        onSecondaryContainer = Color(0xFF061F25),
        tertiary = Color(0xFF5A5B7E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0E0FF),
        onTertiaryContainer = Color(0xFF171837),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF5FAFC),
        onBackground = Color(0xFF171C1E),
        surface = Color(0xFFF5FAFC),
        onSurface = Color(0xFF171C1E),
        surfaceVariant = Color(0xFFDBE4E7),
        onSurfaceVariant = Color(0xFF3F484B),
        surfaceTint = Color(0xFF00687A),
        inverseSurface = Color(0xFF2B3134),
        inverseOnSurface = Color(0xFFECF1F4),
        outline = Color(0xFF6F797B),
        outlineVariant = Color(0xFFBFC8CB),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFF5FAFC),
        surfaceDim = Color(0xFFD6DBDE),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEFF4F7),
        surfaceContainer = Color(0xFFE9EEF1),
        surfaceContainerHigh = Color(0xFFE3E9EB),
        surfaceContainerHighest = Color(0xFFDEE3E6),
    )

val VectorDarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF55D6F2),
        onPrimary = Color(0xFF003641),
        primaryContainer = Color(0xFF004E5C),
        onPrimaryContainer = Color(0xFFADEDFF),
        inversePrimary = Color(0xFF00687A),
        secondary = Color(0xFFB2CBD3),
        onSecondary = Color(0xFF1D343A),
        secondaryContainer = Color(0xFF334A51),
        onSecondaryContainer = Color(0xFFCEE7EF),
        tertiary = Color(0xFFC3C3EB),
        onTertiary = Color(0xFF2C2D4D),
        tertiaryContainer = Color(0xFF424365),
        onTertiaryContainer = Color(0xFFE0E0FF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0E1416),
        onBackground = Color(0xFFDEE3E6),
        surface = Color(0xFF0E1416),
        onSurface = Color(0xFFDEE3E6),
        surfaceVariant = Color(0xFF3F484B),
        onSurfaceVariant = Color(0xFFBFC8CB),
        surfaceTint = Color(0xFF55D6F2),
        inverseSurface = Color(0xFFDEE3E6),
        inverseOnSurface = Color(0xFF2B3134),
        outline = Color(0xFF899295),
        outlineVariant = Color(0xFF3F484B),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF343A3C),
        surfaceDim = Color(0xFF0E1416),
        surfaceContainerLowest = Color(0xFF090F11),
        surfaceContainerLow = Color(0xFF171C1E),
        surfaceContainer = Color(0xFF1B2022),
        surfaceContainerHigh = Color(0xFF252B2D),
        surfaceContainerHighest = Color(0xFF303638),
    )

/**
 * Collapses every background role to true black, for OLED panels.
 *
 * Applied on top of whichever dark scheme is in use — including a dynamic one — so the accent
 * colours the user's wallpaper produced survive.
 */
fun ColorScheme.toAmoled(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF101010),
        surfaceContainerHigh = Color(0xFF161616),
        surfaceContainerHighest = Color(0xFF1C1C1C),
    )

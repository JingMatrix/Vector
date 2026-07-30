package org.matrix.vector.manager.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Collapses every background role to true black, for OLED panels.
 *
 * Applied on top of whichever dark scheme is in use — including a dynamic one — so the accent
 * colours the user's wallpaper produced survive.
 *
 * Which palette is in force is never this app's choice: from API 31 the wallpaper decides it, and
 * below that, or whenever the user turns dynamic colour off, the seed they picked does. So nothing
 * in this app may rely on a specific hue to carry meaning — `StatusHeader` and `UpdatableVersion`
 * carry state by shape, icon, label and motion as well as by colour.
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

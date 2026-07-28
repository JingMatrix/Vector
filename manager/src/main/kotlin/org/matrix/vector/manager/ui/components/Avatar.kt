package org.matrix.vector.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.cos
import kotlin.math.sin

/**
 * A contributor's GitHub avatar, with a monogram fallback while it loads or when there is no
 * network — which, for this app, is a normal condition rather than an error.
 *
 * When [laurelled] the avatar is wreathed. The laurel is the Winged Victory's own iconography and
 * the only place the brand motif appears outside the launcher icon and the splash, so it stays
 * meaningful: it marks the most active contributor over the window the feed covers.
 */
@Composable
fun ContributorAvatar(
    login: String,
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    laurelled: Boolean = false,
    /** Ringed while this person is one of the authors the rail is filtered to. */
    selected: Boolean = false,
) {
    // The wreath's space is reserved whether or not it is drawn, so one laurelled avatar does
    // not make its column taller than the rest of the row.
    val wreathPadding = size * 0.22f
    Box(
        modifier = modifier.size(size + wreathPadding * 2),
        contentAlignment = Alignment.Center,
    ) {
        if (laurelled) {
            Laurel(
                modifier = Modifier.size(size + wreathPadding * 2),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box(
            modifier =
                Modifier.size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .then(
                        // Drawn over the image rather than around the whole column, so the ring
                        // reads as belonging to the face and not to the row.
                        if (selected)
                            Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = login.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = login,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape),
                )
            }
        }
    }
}

/**
 * Two symmetric arcs of leaves, open at the top, drawn rather than shipped as an asset so it takes
 * the theme's colour and any size without a second drawable.
 */
@Composable
fun Laurel(modifier: Modifier = Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier = modifier) { drawLaurel(color) }
}

private fun DrawScope.drawLaurel(color: Color) {
    val radius = size.minDimension / 2f * 0.90f
    val centre = Offset(size.width / 2f, size.height / 2f)
    val leafLength = radius * 0.42f
    val leafWidth = leafLength * 0.40f
    val leaves = 5
    // Real laurel leaves splay outward from the binding rather than lying flat along the arc;
    // without this the ovals read as a dotted ring instead of a wreath.
    val splayDeg = 26f

    // Angles are measured clockwise from twelve o'clock, so a leaf at 30° sits upper-right.
    // Each side runs 30° → 150°, which leaves the crown open at the top and the stems meeting
    // at the bottom — the shape of an actual wreath rather than a full ring.
    val startDeg = 32f
    val endDeg = 148f

    for (side in listOf(1f, -1f)) {
        for (i in 0 until leaves) {
            val t = i / (leaves - 1f)
            val angleDeg = startDeg + t * (endDeg - startDeg)
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val x = centre.x + side * radius * sin(angleRad).toFloat()
            val y = centre.y - radius * cos(angleRad).toFloat()

            // Leaves grow towards the base, the way a wreath is bound.
            val scale = 0.55f + 0.45f * t
            // Tangential to the arc, then splayed outward.
            val rotation = side * (angleDeg + splayDeg)

            rotate(degrees = rotation, pivot = Offset(x, y)) {
                drawOval(
                    color = color.copy(alpha = 0.35f + 0.45f * t),
                    topLeft = Offset(x - leafLength * scale / 2f, y - leafWidth * scale / 2f),
                    size = Size(leafLength * scale, leafWidth * scale),
                )
            }
        }
    }
}

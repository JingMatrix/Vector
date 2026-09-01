package org.matrix.vector.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.matrix.vector.ui.theme.Mono

/**
 * A version number, marked when something newer exists.
 *
 * One treatment for the framework and for modules, applied to *the version text itself* rather than
 * as a badge somewhere near it. A badge is a second object the reader has to associate with a first
 * one, and each screen invents its own place to put it — which is how the same fact ends up looking
 * like three different facts. Marking the number says it where it is already being read: this is the
 * version you have, and it is not the newest.
 *
 * The mark is a shape and a colour, never colour alone: the header's tint follows the user's
 * wallpaper under Material You, so a hue that reads as "attention" on one device is the resting
 * colour on another.
 *
 * It breathes, slowly and shallowly. An update is not urgent — nothing is broken — so it must be
 * findable at a glance without behaving like an alert. The motion stops dead when there is no
 * update, which is what makes its presence mean something.
 */
@Composable
fun UpdatableVersion(
    text: String,
    hasUpdate: Boolean,
    modifier: Modifier = Modifier,
    /** Whether an over-long version scrolls past, as [ScrollingLabel], instead of being cut. */
    marquee: Boolean = false,
    style: TextStyle = Mono,
    color: Color = LocalContentColor.current,
    markColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    if (text.isBlank()) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasUpdate) {
            val transition = rememberInfiniteTransition(label = "update mark")
            val breath by
                transition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(tween(1_600), repeatMode = RepeatMode.Reverse),
                    label = "update breath",
                )
            Icon(
                Icons.Rounded.ArrowCircleUp,
                contentDescription = null,
                tint = markColor,
                modifier = Modifier.size(14.dp).alpha(breath),
            )
            Spacer(Modifier.width(5.dp))
        }
        val ink = if (hasUpdate) markColor else color
        // Ellipsised only where it cannot scroll: a marquee draws its own text past the edge, and
        // an ellipsis on top of that is a full stop in the middle of a moving line.
        if (marquee) ScrollingLabel(text = text, style = style, color = ink)
        else
            Text(
                text = text,
                style = style,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
    }
}

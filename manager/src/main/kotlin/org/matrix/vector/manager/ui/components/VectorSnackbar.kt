package org.matrix.vector.manager.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * What a message is telling you, which decides how it looks.
 *
 * A snackbar that says "force stopped" and a snackbar that says "could not uninstall" should not be
 * the same object — the second one is a failure the user has to react to, and making both a plain
 * grey bar means neither registers.
 */
enum class SnackbarTone {
    /** Something happened. Nothing to react to. */
    Neutral,
    /** It worked. */
    Success,
    /** It is happening now and will take a while. */
    Working,
    /** It did not work. */
    Failure,
}

/** A message with a tone attached, carried through the standard [SnackbarHostState] channel. */
class VectorSnackbarVisuals(
    override val message: String,
    val tone: SnackbarTone = SnackbarTone.Neutral,
    override val duration: SnackbarDuration =
        if (tone == SnackbarTone.Failure) SnackbarDuration.Long else SnackbarDuration.Short,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
}

/** Shows a toned message, replacing whatever is on screen. */
suspend fun SnackbarHostState.show(message: String, tone: SnackbarTone = SnackbarTone.Neutral) {
    currentSnackbarData?.dismiss()
    showSnackbar(VectorSnackbarVisuals(message, tone))
}

/**
 * The app's snackbar.
 *
 * Material's default is a dark slab with a hard 4dp corner: inverse-surface, so it is dark on a
 * light theme and light on a dark one. That inversion is deliberate in the spec and wrong here — a
 * message that is the opposite colour to everything around it reads as belonging to the system
 * rather than to the app.
 *
 * So it sits on the app's own raised surface and earns its prominence from elevation and shape
 * instead of from inversion, and leads with an icon so the outcome is legible before the sentence
 * is read.
 */
@Composable
fun VectorSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val visuals = data.visuals as? VectorSnackbarVisuals
        val tone = visuals?.tone ?: SnackbarTone.Neutral
        val colors = MaterialTheme.colorScheme

        val container =
            when (tone) {
                SnackbarTone.Failure -> colors.errorContainer
                else -> colors.surfaceContainerHighest
            }
        val content =
            when (tone) {
                SnackbarTone.Failure -> colors.onErrorContainer
                else -> colors.onSurface
            }
        val accent =
            when (tone) {
                SnackbarTone.Success -> colors.primary
                SnackbarTone.Failure -> colors.error
                else -> colors.primary
            }

        // Lifted rather than inverted: it still reads as laid over the screen without being the
        // opposite colour to it.
        Snackbar(
            modifier = Modifier.padding(horizontal = 12.dp).shadow(6.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            containerColor = container,
            contentColor = content,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier.size(28.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    ToneIcon(tone = tone, tint = accent)
                }
                Spacer(Modifier.width(12.dp))
                Text(text = data.visuals.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ToneIcon(tone: SnackbarTone, tint: Color) {
    val icon: ImageVector =
        when (tone) {
            SnackbarTone.Success -> Icons.Rounded.CheckCircle
            SnackbarTone.Failure -> Icons.Rounded.ErrorOutline
            SnackbarTone.Working -> Icons.Rounded.Bolt
            SnackbarTone.Neutral -> Icons.Rounded.Info
        }

    if (tone == SnackbarTone.Working) {
        // Work that takes ten seconds needs to look like it is still happening; a static icon on a
        // message that says "optimizing" is indistinguishable from one that has stalled.
        val spin = rememberInfiniteTransition(label = "working")
        val angle by
            spin.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(1600, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "workingAngle",
            )
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp).rotate(angle))
    } else {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
    }
}

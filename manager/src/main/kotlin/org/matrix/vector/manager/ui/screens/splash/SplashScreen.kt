package org.matrix.vector.manager.ui.screens.splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.matrix.vector.manager.R

/** How long the animation itself needs, so the statue is never cut off mid-fade. */
private const val ANIMATION_MS = 700L

/**
 * The Winged Victory, fading and scaling in — Vector, from *Victoria*.
 *
 * The handover is gated on the daemon rather than on a fixed timer, because arriving at Home before
 * the binder is up shows "Not Activated" on a device that is activated. [DAEMON_TIMEOUT_MS] is the
 * ceiling, so a daemon that never answers costs that much and no more.
 *
 * [ANIMATION_MS] is then spent after the handshake rather than overlapped with it, so a binder that
 * resolves instantly still leaves the fade its full length instead of a flash of half-drawn artwork.
 */

/** The splash artwork, also summoned by the header's easter egg. */
@Composable
fun WingedVictory() {
    var started by remember { mutableStateOf(false) }
    val alpha by
        animateFloatAsState(
            targetValue = if (started) 1f else 0f,
            animationSpec = tween(durationMillis = ANIMATION_MS.toInt()),
            label = "splashAlpha",
        )
    val scale by
        animateFloatAsState(
            targetValue = if (started) 1f else 0.8f,
            animationSpec = tween(durationMillis = ANIMATION_MS.toInt()),
            label = "splashScale",
        )

    val appName = stringResource(R.string.app_name)

    LaunchedEffect(Unit) { started = true }

    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .semantics { contentDescription = appName },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_winged_victory),
            contentDescription = null,
            tint = Color.Unspecified,
            // The drawable is a 108dp square whose figure runs nearly the full height of its
            // viewport, so it is given 92% of both dimensions and fitted inside — which keeps its
            // proportions in portrait and landscape without stretching or clipping.
            modifier = Modifier.fillMaxSize(0.82f).scale(scale).alpha(alpha),
        )
    }
}

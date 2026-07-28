package org.matrix.vector.manager.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.matrix.vector.manager.R
import org.matrix.vector.manager.ui.components.ambience.AmbienceKind
import org.matrix.vector.manager.ui.components.ambience.AmbientSurface
import org.matrix.vector.manager.ui.theme.VectorMono

/** The three states the framework can be in, plus the moment before we know. */
enum class FrameworkState {
    Checking,
    Active,
    Degraded,
    Inactive,
}

/**
 * The framework's state, as the top of the app.
 *
 * There is no app bar above it and no separate status row. A bar naming the app spends a whole row
 * on what the launcher icon, the task switcher and the system already say; that row goes instead to
 * the one thing that is genuinely unknown on opening the app.
 *
 * The header is full-bleed and runs under the status bar, tinted by state, with only its bottom
 * corners rounded so it reads as a single pane hanging from the top edge rather than a card
 * floating on a background. Under Material You the tint comes from the wallpaper, which is what
 * makes it feel like part of the device rather than part of an app.
 *
 * Because hue is the user's wallpaper's to choose, state is *also* carried by shape, icon, label
 * and motion — see [StatusIndicator]. Colour alone is never the signal.
 */
@Composable
fun StatusHeader(
    state: FrameworkState,
    version: String?,
    apiVersion: Int?,
    hasUpdate: Boolean,
    onOpenUpdate: () -> Unit,
    ambience: AmbienceKind,
    onOpenStatus: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLanguage: () -> Unit,
    onBrandTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    val container by
        animateColorAsState(
            when (state) {
                FrameworkState.Active -> colors.primaryContainer
                FrameworkState.Degraded -> colors.tertiaryContainer
                FrameworkState.Inactive -> colors.errorContainer
                FrameworkState.Checking -> colors.surfaceContainer
            },
            animationSpec = tween(420),
            label = "headerContainer",
        )
    val onContainer by
        animateColorAsState(
            when (state) {
                FrameworkState.Active -> colors.onPrimaryContainer
                FrameworkState.Degraded -> colors.onTertiaryContainer
                FrameworkState.Inactive -> colors.onErrorContainer
                FrameworkState.Checking -> colors.onSurfaceVariant
            },
            animationSpec = tween(420),
            label = "headerOnContainer",
        )

    // The brand rides with the state, so the two read as one sentence: *Vector — Active*. The name
    // is set lighter than the state, so the eye still lands on the word that changes.
    val stateWord =
        stringResource(
            when (state) {
                FrameworkState.Active -> R.string.status_active
                FrameworkState.Degraded -> R.string.status_degraded
                FrameworkState.Inactive -> R.string.status_inactive
                FrameworkState.Checking -> R.string.status_checking
            }
        )
    val brand = stringResource(R.string.app_name)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                // Square at the top so it meets the screen edge, rounded at the bottom so it
                // reads as one pane hanging from it.
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(
                    // A shallow wash rather than a flat fill: enough depth that the pane has a
                    // top and a bottom, far short of a decorative gradient.
                    Brush.verticalGradient(
                        listOf(container, container.copy(alpha = 0.82f).compositeOverSurface())
                    )
                )
    ) {
        // matchParentSize, NOT fillMaxSize: a Box child that fills its maximum constraint drags
        // the Box to full height with it, and the header would swallow the whole screen.
        // matchParentSize sizes to whatever the *content* settled on without influencing it.
        AmbientSurface(
            kind = ambience,
            tint = onContainer,
            modifier = Modifier.matchParentSize(),
        )

        Column(
            modifier =
                Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 20.dp)
        ) {
            // The ambient surface gets the upper part of the pane to itself; the status settles at
            // the bottom, where it sits on the surface rather than floating above a gap.
            Spacer(Modifier.height(66.dp))

            Row(verticalAlignment = Alignment.Top) {
                // The indicator is the details button. It is the thing the user is already
                // looking at when they wonder *why* it says what it says, so it should be the
                // thing that answers — a separate chevron was a second control for one intent.
                //
                // Centred on the *headline* rather than on the whole block: against the block it
                // lands level with the gap between "Vector Active" and the version line and reads
                // as belonging to neither, while against the headline it sits square with the word
                // it is the state of.
                Box(
                    modifier = Modifier.height(HEADLINE_ROW),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusIndicator(
                        state = state,
                        tint = onContainer,
                        onClick = onOpenStatus,
                        contentDescription = stringResource(R.string.status_open_details),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    // The buttons live *inside* the headline row rather than beside the whole
                    // block, so the stack and the state word share a centre line by construction
                    // and the eye reads one row rather than three loose objects.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            // The wordmark is its own target, because something is hidden behind
                            // it.
                            Text(
                                text = brand,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Normal,
                                color = onContainer.copy(alpha = 0.62f),
                                modifier =
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onBrandTap,
                                    ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stateWord,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = onContainer,
                            )
                        }
                        // Neither is a gear. What they open governs how the app *presents*
                        // itself — its colours and its language — rather than what it does, and
                        // the icons should say so. Stacked because they belong together.
                        // Deliberately tighter than a default icon button: the pair sets the
                        // height of the row it shares with the wordmark, and at the standard 48 dp
                        // each it would push the version line a finger's width from the name it
                        // belongs to.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = onOpenAppearance,
                                modifier = Modifier.size(ICON_BUTTON),
                            ) {
                                Icon(
                                    Icons.Rounded.Palette,
                                    contentDescription = stringResource(R.string.appearance_title),
                                    tint = onContainer,
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                            IconButton(
                                onClick = onOpenLanguage,
                                modifier = Modifier.size(ICON_BUTTON),
                            ) {
                                Icon(
                                    Icons.Rounded.Language,
                                    contentDescription = stringResource(R.string.language_title),
                                    tint = onContainer,
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                        }
                    }
                    val detail =
                        buildList {
                                version?.let { add(it) }
                                apiVersion?.let { add("API $it") }
                            }
                            .joinToString("  ·  ")
                    if (detail.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        // The version line becomes the way in to the update, because it is the
                        // thing the mark is attached to: a reader who has noticed that their
                        // version is marked has already looked at exactly the right words.
                        UpdatableVersion(
                            text = detail,
                            hasUpdate = hasUpdate,
                            color = onContainer.copy(alpha = 0.75f),
                            markColor = onContainer,
                            // Tappable whether or not there is an update. Checking on demand is
                            // a thing people do, and a control that only exists once there is news
                            // cannot be found before there is any — so the answer "you are up to
                            // date" would have been the one answer unreachable from here.
                            modifier =
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onOpenUpdate,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The indicator. Its corner radius animates between three values — a rounded square when active, a
 * softer form when degraded, a circle when inactive — so a state change is legible as motion
 * rather than as a colour swap alone.
 *
 * When active it breathes: a slow, low-amplitude pulse that reads as "running" at a glance, and
 * stops dead in the other two states, so stillness itself carries meaning.
 */
@Composable
private fun StatusIndicator(
    state: FrameworkState,
    tint: Color,
    onClick: () -> Unit,
    contentDescription: String,
) {
    val corner by
        animateFloatAsState(
            when (state) {
                FrameworkState.Active -> 34f
                FrameworkState.Degraded -> 42f
                else -> 50f
            },
            animationSpec = tween(420),
            label = "indicatorCorner",
        )

    val breathing = rememberInfiniteTransition(label = "indicatorBreath")
    val pulse by
        breathing.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(1900), RepeatMode.Reverse),
            label = "indicatorPulse",
        )

    val icon =
        when (state) {
            FrameworkState.Active -> Icons.Rounded.Check
            FrameworkState.Degraded -> Icons.Rounded.PriorityHigh
            FrameworkState.Inactive -> Icons.Rounded.Close
            FrameworkState.Checking -> null
        }

    Box(
        modifier =
            Modifier.size(52.dp)
                .scale(if (state == FrameworkState.Active) pulse else 1f)
                .clip(RoundedCornerShape(percent = corner.toInt()))
                .background(tint.copy(alpha = 0.15f))
                .clickable(onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            // The label beside it already names the state, and the box carries the description,
            // so the glyph must not be announced a third time.
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        }
    }
}

/** Keeps the gradient's lower stop opaque; a translucent stop would show the list scrolling under. */
@Composable
private fun Color.compositeOverSurface(): Color {
    val surface = MaterialTheme.colorScheme.surface
    return Color(
        red = red * alpha + surface.red * (1 - alpha),
        green = green * alpha + surface.green * (1 - alpha),
        blue = blue * alpha + surface.blue * (1 - alpha),
        alpha = 1f,
    )
}

/** One of the two stacked buttons beside the wordmark. */
private val ICON_BUTTON = 38.dp

/**
 * The height of the row the wordmark shares with those buttons.
 *
 * Derived rather than guessed, because the status indicator is centred against it: the stack is
 * what makes that row taller than its text, so if the buttons change size the indicator has to
 * follow or it stops lining up with the word it belongs to.
 */
private val HEADLINE_ROW = ICON_BUTTON * 2

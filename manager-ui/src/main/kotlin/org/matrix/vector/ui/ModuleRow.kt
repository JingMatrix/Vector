package org.matrix.vector.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The module's icon, and the slot it is drawn in whether or not it is selected. */
private val ICON_SIZE = 48.dp

/** Room for a version and its mark. Anything longer scrolls past instead of pushing. */
private val VERSION_WIDTH = 104.dp

/** The strip along the bottom of a row that the reach sits in — reserved only when there is one. */
private val REACH_BAND = REACH_ICON_SIZE + 2.dp

/**
 * One module in a list — the shared design behind Vector's Manage screen and LSPatch's.
 *
 * The icon is left exactly as the module ships it, never wrapped in a coloured well that would make
 * every module look like it belonged to the manager rather than to its author. The **module's own
 * name carries its state** through [nameColor]: the accent when it is running, the error colour when
 * the framework is too old for it. Two columns for the three questions a row answers — what it is
 * (icon, and the API it needs), and what it does (name and description). How it is configured — the
 * version and, when there is one, the reach — is laid *over* the second column rather than given a
 * third, so the description keeps the full width.
 *
 * Everything a host does not have collapses cleanly: pass no reach ([reachIcons]/[reachLeading]) and
 * the reach strip reserves no space; pass no [onIconClick] and the icon is inert; pass a [note] and it
 * stands in for the description (a load failure, an incompatibility). LSPatch, whose modules are just
 * installed APKs, leaves all of that at its defaults and still gets the same row Vector does.
 *
 * The reach — who this row touches — is a first-class part of the row, not a slot a host positions:
 * a module's scope (the apps it hooks) and an app's reach (the modules hooking it) are the same
 * relationship seen from either end, so the row draws both as a bottom-right [IconCluster] itself.
 * A host supplies only the data — the preview [reachIcons], the full [reachCount] behind them, and an
 * optional [reachLeading] mark (the framework) — and cannot place it wrongly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModuleRow(
    icon: @Composable () -> Unit,
    name: String,
    versionName: String,
    description: String,
    apiBadge: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    nameColor: Color = Color.Unspecified,
    hasUpdate: Boolean = false,
    /** With an update in hand the version becomes the link to it; without one it is inert. */
    onVersionClick: (() -> Unit)? = null,
    /** A module that is off recedes rather than merely changing colour. */
    dimmed: Boolean = false,
    selected: Boolean = false,
    onIconClick: (() -> Unit)? = null,
    onIconLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** Shown *instead of* the description when present — a state the module is in, not what it does. */
    note: (@Composable () -> Unit)? = null,
    /**
     * Icons of who this row touches, drawn bottom-right. Pass the full set — the row itself keeps only
     * the first [REACH_PREVIEW_LIMIT] for the preview, so no caller has to cap it. Slots draw at
     * [REACH_ICON_SIZE], the size the reach band is sized to.
     */
    reachIcons: List<@Composable () -> Unit> = emptyList(),
    /** The full reach behind [reachIcons], for the "+N" tail. Defaults to the number of icons given. */
    reachCount: Int = reachIcons.size,
    /** An iconless reach member drawn first — the framework, a scope target with no launcher icon. */
    reachLeading: (@Composable () -> Unit)? = null,
    /**
     * A badge pinned to the *left* of the reach band, opposite the icons on the right.
     *
     * Where [reachLeading] joins the right-hand cluster, this sits at the far left of the same
     * bottom line — for a status the row wants on that line but apart from the reach, such as an
     * app's patch mode beside the modules it reaches. Reserves the band on its own, so a row with no
     * reach icons still shows it.
     */
    reachStart: (@Composable () -> Unit)? = null,
) {
    val hasReach = reachIcons.isNotEmpty() || reachLeading != null || reachStart != null
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable(name) { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // Intrinsic height so the reach can push to the bottom of whatever the description made.
                .height(IntrinsicSize.Min)
                .alpha(if (dimmed) 0.45f else 1f)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The icon is the selection handle. Double-tapping it is the host's chance to toggle without
        // leaving the list; a bare tap only reports state, since a one-tap toggle would fire whenever
        // a thumb brushed the list.
        Column(
            modifier =
                if (onIconClick != null)
                    Modifier.contextClickable(onClick = onIconClick, onLongClick = onIconLongClick)
                else Modifier,
            // Against the text, not centred over the badge: the badge below is wider than the icon,
            // so centring left a gap between the icon and the edge the names all start from.
            horizontalAlignment = Alignment.End,
        ) {
            // Fixed at the icon's size whatever is drawn inside, so selecting a module cannot resize
            // its row — a tick larger than the icon would grow this box and reflow the list.
            Box(modifier = Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
                icon()
                if (selected) {
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .clip(CircleShape)
                                .background(colors.primary.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colors.onPrimary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            apiBadge()
        }

        Spacer(Modifier.width(16.dp))

        // A Box, not a third column: reserving a column for the version and reach would take width
        // from every line of the description whether or not anything was there. They overlap the text
        // and are kept clear of it by vertical placement instead.
        Box(
            Modifier.weight(1f)
                .then(
                    if (onClick != null)
                        Modifier.contextClickable(onClick = onClick, onLongClick = onLongClick)
                    else Modifier
                )
        ) {
            Column(Modifier.padding(bottom = if (hasReach) REACH_BAND else 0.dp)) {
                // The title's band. Both halves are fixed and both scroll, so however long a name or
                // version string becomes neither can reach the other.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScrollingLabel(
                        text = name,
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                        color = nameColor,
                        modifier = Modifier.weight(1f),
                    )
                    if (versionName.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        UpdatableVersion(
                            text = versionName,
                            hasUpdate = hasUpdate,
                            marquee = true,
                            color = colors.onSurfaceVariant,
                            modifier =
                                Modifier.width(VERSION_WIDTH)
                                    .then(
                                        if (onVersionClick == null) Modifier
                                        else
                                            Modifier.clip(RoundedCornerShape(6.dp))
                                                .clickable { onVersionClick() }
                                    ),
                        )
                    }
                }

                if (note != null) {
                    // A state the module is in stands above what it does: unsaid, a module doing
                    // nothing is indistinguishable from a switch that turned itself off.
                    Spacer(Modifier.height(4.dp))
                    note()
                } else if (description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            // Whether there is more to read is a property of the layout, so the
                            // control appears only when it would do something.
                            onTextLayout = { truncated = it.hasVisualOverflow || expanded },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (truncated) {
                            Icon(
                                imageVector =
                                    if (expanded) Icons.Rounded.ExpandLess
                                    else Icons.Rounded.ExpandMore,
                                contentDescription =
                                    stringResource(
                                        if (expanded) R.string.modules_collapse
                                        else R.string.modules_expand
                                    ),
                                tint = colors.primary,
                                modifier =
                                    Modifier.padding(start = 4.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .clickable { expanded = !expanded },
                            )
                        }
                    }
                }
            }

            // The reach, in the band the row already left empty under the last line — drawn by the row
            // itself at the bottom-right, so a module's scope and an app's reach land in the same
            // corner without any host positioning it. Allowed to run left past where a column would
            // have ended, so it costs the description no width.
            if (hasReach) {
                // Pinned to the far left of the same bottom line the icons sit on the right of.
                reachStart?.let {
                    Box(Modifier.align(Alignment.BottomStart), contentAlignment = Alignment.Center) {
                        it()
                    }
                }
                // The row owns the preview cap, so a caller passes its whole reach and never has to
                // keep a private "take(N)" in step with everyone else's.
                val shown = reachIcons.take(REACH_PREVIEW_LIMIT)
                IconCluster(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    icons = shown,
                    remainder = reachCount - shown.size,
                    leading = reachLeading,
                )
            }
        }
    }
}

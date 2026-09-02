package org.matrix.vector.ui.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.matrix.vector.ui.LocalDialogLocalizer
import org.matrix.vector.ui.SheetHeading
import org.matrix.vector.ui.sheetRowColors

/**
 * Where a version stands relative to the running build — the one distinction each row is marked by.
 *
 * [Installed] is the build actually running (its dot wears the accent). [Diverged] carries the same
 * version number but was not made from this release — another branch, or a working tree with changes
 * — so it looks installed by the number alone and must be told apart. [Older] sits below the running
 * build. [None] is any other version (a newer one on offer, or a sibling channel's build).
 */
enum class VersionStatus { Installed, Diverged, Older, None }

/**
 * One row of the version-history sheet, with everything it needs already resolved by the caller.
 *
 * Presentation-only on purpose: each app computes its own semantics (what "installed" or "diverged"
 * means, how a build is titled, how a date and channel read in the reader's language) and hands the
 * sheet the finished strings. That is what lets one sheet serve both the framework updater and the
 * manager self-updater without either's release model leaking into the shared library.
 */
data class VersionHistoryItem(
    /** Stable identity handed back to [VersionHistorySheet]'s onSelect — a tag, or anything unique. */
    val id: String,
    /** The build's name, one line: "Vector v2.0 canary 3060" / "LSPatch v1.1 (481)". */
    val title: String,
    /** The line beneath it, already formatted and localised: usually "<date>  ·  <channel>". */
    val subtitle: String,
    /** A short status word ("Installed" / "Same number" / "Older"), or null when there is nothing to say. */
    val statusLabel: String?,
    val status: VersionStatus,
    /** Whether this row is the one the screen is currently showing. */
    val selected: Boolean,
)

private val STATUS_WIDTH = 96.dp

/**
 * The shared version-history picker: every known build of one thing, newest first, each row its own
 * name, date, channel and status, tapping one to switch the screen to it.
 *
 * A scrollable list rather than a dropdown, so an older build carries the same weight as the newest
 * and nothing is hidden past an edge. The dots are a radio group and behave like one: the filled
 * one is the build the screen is showing, and it moves to whichever row is tapped. What is
 * *installed* is a separate fact and is told separately — the dot's colour and the status word
 * beside it — because the two rows are only the same one until the reader picks something else.
 *
 * Localised through [LocalDialogLocalizer] like every shared sheet: a sheet is its own window and
 * drops the host's in-app language override on the way in, so it is re-applied inside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionHistorySheet(
    heading: String,
    items: List<VersionHistoryItem>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LocalDialogLocalizer.current {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SheetHeading(heading, Icons.Rounded.History)
                items.forEach { item ->
                    ListItem(
                        modifier =
                            Modifier.clickable {
                                onSelect(item.id)
                                onDismiss()
                            },
                        supportingContent = {
                            Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        // The filled dot is the row the screen is showing, because that is what a
                        // list of dots means everywhere else: tapping one moves it. The installed
                        // build is still marked, by the colour of its dot and by the word in the
                        // status column — reading it off the fill instead would leave the reader
                        // who has just tapped an older build with no dot against the row they are
                        // looking at, and a filled one against a row they are not.
                        leadingContent = {
                            Icon(
                                if (item.selected) Icons.Rounded.RadioButtonChecked
                                else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint =
                                    when {
                                        item.status == VersionStatus.Installed -> colors.primary
                                        // Same number, different build: where the reader *appears* to
                                        // be and is not.
                                        item.status == VersionStatus.Diverged -> colors.tertiary
                                        item.selected -> colors.onSurface
                                        else -> colors.outline
                                    },
                            )
                        },
                        // A fixed column so a one-word status on most rows and a longer one on the
                        // divergent row do not each start the build's name in a different place; the
                        // label wraps inside its own width where it costs nothing.
                        trailingContent = {
                            Box(
                                modifier = Modifier.width(STATUS_WIDTH),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                item.statusLabel?.let { label ->
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.End,
                                        color =
                                            when (item.status) {
                                                VersionStatus.Installed -> colors.primary
                                                VersionStatus.Diverged -> colors.tertiary
                                                else -> colors.onSurfaceVariant
                                            },
                                    )
                                }
                            }
                        },
                        colors = sheetRowColors,
                    ) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

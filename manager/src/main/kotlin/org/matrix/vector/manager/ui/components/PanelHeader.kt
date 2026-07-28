package org.matrix.vector.manager.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The top of a list panel, as three rows of fixed height.
 *
 * Modules, Store and Logs are the same kind of screen — a title, a few actions, one line of state,
 * a search field and a long list — so they share one header rather than each growing its own.
 * Headers of three different heights move the search field as tabs are switched, and that is the
 * one control a thumb learns the position of: moving it is felt long before it is noticed.
 *
 * So the whole block is laid out here, at a **fixed height**, including the search field: a title
 * row that may carry actions on the right, a line of description under it, and the field. Fixing the
 * height rather than measuring it is what makes the layout predictable — a description that appears
 * only once a catalogue has loaded, or a line counter that is empty until a log is read, then costs
 * nothing below it and shifts nothing.
 *
 * The scope editor deliberately does not use this. It is a screen you arrive at and leave again, so
 * it carries a back arrow and the name of what you came for, and the shape of the panels you
 * navigate *between* is the wrong shape for it.
 */
@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    description: (@Composable () -> Unit)? = null,
    search: (@Composable () -> Unit)? = null,
    /**
     * Takes the place of the title and description rows while it is non-null.
     *
     * For modes that replace what the panel is *about* without replacing what it *does* — module
     * selection is the one — so the search field below stays live and, more importantly, stays
     * exactly where it was. The override gets the two rows' combined height and no more, which is
     * what keeps a contextual bar from becoming a band of empty colour.
     */
    titleOverlay: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().height(PANEL_HEADER_HEIGHT)) {
        if (titleOverlay != null) {
            Box(modifier = Modifier.fillMaxWidth().height(TITLE_ROW + DESCRIPTION_ROW)) {
                titleOverlay()
            }
        } else {
            Row(
                modifier =
                    Modifier.fillMaxWidth().height(TITLE_ROW).padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                actions?.invoke(this)
            }

            // Always present, whether or not it has anything to say, so the field below never
            // moves.
            Box(
                modifier =
                    Modifier.fillMaxWidth().height(DESCRIPTION_ROW).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                description?.invoke()
            }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            search?.invoke()
        }
    }
}

private val TITLE_ROW = 56.dp
private val DESCRIPTION_ROW = 26.dp

/** Title row, description row and search field, and the same on every panel. */
val PANEL_HEADER_HEIGHT = TITLE_ROW + DESCRIPTION_ROW + 68.dp

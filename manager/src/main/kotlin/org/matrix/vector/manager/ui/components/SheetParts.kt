package org.matrix.vector.manager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The three pieces every settings sheet in this app is built from.
 *
 * Shared rather than copied into each sheet, because copies drift and two sheets end up looking
 * *almost* the same — the tell is a heading indented differently, or a switch row whose subtitle
 * wraps at another width. A new sheet inherits the pattern, and changing the pattern changes every
 * sheet at once.
 */
@Composable
fun SheetHeading(text: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * A row of choices, wrapping onto as many lines as it needs.
 *
 * Wrapping rather than scrolling sideways. A chip that reflows moves every chip after it, so an
 * option sits somewhere different in each language — but scrolling *hides* the options past the
 * edge, and an option nobody knows about is worse than one that moved. In a sheet the vertical room
 * costs nothing, so everything is shown at once.
 */
@Composable
fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/**
 * One switch, with the sentence that says what turning it on costs.
 *
 * The whole row is the target, not just the switch, and the switch itself takes no callback so a
 * tap cannot be counted twice.
 *
 * Toggleable rather than merely clickable, because a plain clickable carries no state: a screen
 * reader announces such a row as a button and reads the title, leaving no way to hear whether the
 * setting is on or off — the one thing the row exists to say.
 */
@Composable
fun ToggleRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    ListItem(
        modifier =
            Modifier.toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/**
 * One thing the sheet can do.
 *
 * The same shape as [ToggleRow] minus the switch, so a sheet that mixes settings and actions still
 * reads as one list rather than two borrowed idioms.
 */
@Composable
fun SheetAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    tint: Color? = null,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = tint ?: LocalContentColor.current)
        },
    )
}

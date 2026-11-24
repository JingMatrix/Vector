package org.matrix.vector.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One rounded control that holds the search input *and* whatever narrows the list.
 *
 * Search and filtering answer the same question — *which of these am I looking at* — so splitting
 * them across a text field and a separate row of chips would spend two rows and two mental steps on
 * one intent. Everything that narrows the list lives in [trailing], on the same line.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    clearContentDescription: String = "Clear search",
    trailing: @Composable () -> Unit = {},
) {
    // The field owns a TextFieldState, but the public contract stays a hoisted String so callers
    // keep driving the query from their own ViewModels. Two one-way syncs bridge the two: each
    // write is guarded on the values already differing, so a local edit reaches the caller and a
    // caller-driven reset (the clear button, a tab switch) reaches the field, but neither bounces
    // back into a loop.
    val state = rememberTextFieldState(query)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { if (it != query) onQueryChange(it) }
    }
    LaunchedEffect(query) {
        if (query != state.text.toString()) state.setTextAndPlaceCursorAtEnd(query)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(16.dp))
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                decorator =
                    TextFieldDecorator { inner ->
                        if (state.text.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // A search field is a fixed-height control, so its hint may never
                                // wrap: a long localized placeholder that runs to a second line would
                                // grow the whole bar, breaking the fixed header every panel shares.
                                // One line, always — the field cannot change shape by language.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    },
            )
            if (state.text.isNotEmpty()) {
                IconButton(onClick = { state.clearText() }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = clearContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing()
            Spacer(Modifier.width(4.dp))
        }
    }
}

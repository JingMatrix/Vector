package org.matrix.vector.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * A label that scrolls its own text past rather than taking the width of whatever sits beside it.
 *
 * Version names are written by whoever published the release, not by us, and a repository will hand
 * back things like `1.58.245-ai-ui-label+B520-20260828T1203Z-full`. Left to wrap, one of those eats
 * the whole row and squeezes its neighbour — a date, a chevron — into a column one character wide,
 * which is unreadable in a way that looks like a rendering fault rather than a long name.
 *
 * So the rule the modules list already follows applies wherever a published version is drawn: the
 * label is one line inside a bounded width, and the part that does not fit scrolls past once. The
 * caller gives it that width — usually `Modifier.weight(1f, fill = false)`, which hands the row's
 * fixed parts their natural size first and leaves this with the remainder.
 */
@Composable
fun ScrollingLabel(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.basicMarquee(iterations = 1, repeatDelayMillis = 2_000),
    )
}

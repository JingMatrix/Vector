package org.matrix.vector.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * How long the text waits before it moves, and how many times it goes.
 *
 * One pass, after a pause long enough to read the beginning of the line first. A label that keeps
 * moving is a distraction on a screen someone is working on, and a label that starts moving the
 * instant it appears is read from the middle. It says its piece once and settles.
 *
 * These live here and nowhere else. Two labels that scroll at different speeds on the same screen
 * look like two kinds of thing, and nobody choosing a delay at a call site is thinking about the
 * other four.
 */
private const val PASSES = 1
private const val PAUSE_MS = 2_000

/**
 * One line of text that scrolls itself rather than being cut short or taking a neighbour's width.
 *
 * The names this app draws are written by other people — a module's title, a package, a version
 * name a repository hands back like `1.58.245-ai-ui-label+B520-20260828T1203Z-full` — and any of
 * them can be longer than the row it lands in. Wrapping is not an option in a fixed row: the text
 * eats its neighbour, and a date beside it ends up a column one character wide. Truncating is
 * usually worse than it looks, because the tail is so often the part that tells two builds apart.
 *
 * So the line is one line inside whatever width the caller gives it — usually
 * `Modifier.weight(1f, fill = false)`, which serves the row's fixed parts first and leaves this the
 * remainder — and the part that does not fit scrolls past once.
 *
 * The whole of it is here so that it is one behaviour. Everything that scrolls a name in either
 * manager comes through this: the modules list, the scope screen's title, a version with an update
 * mark on it, and the three places the store prints a release's name.
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
        modifier = modifier.basicMarquee(iterations = PASSES, repeatDelayMillis = PAUSE_MS),
    )
}

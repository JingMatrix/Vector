package org.matrix.vector.manager.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.matrix.vector.manager.ui.theme.LocalizedOverlay

/**
 * Material's dialog, in the language the user chose.
 *
 * A dialog is its own window, and Compose gives every window a fresh set of Android composition
 * locals taken from that window's context — which undoes the app's in-composition language
 * override on the way in. A plain `AlertDialog` therefore speaks the *phone's* language while the
 * screen behind it speaks the reader's.
 *
 * The override cannot be re-applied around the call, because the crossing happens inside it. It has
 * to happen in each slot, which is exactly what this wrapper exists to not forget: every dialog in
 * the app goes through here, so the fix cannot be omitted by writing a new one.
 */
@Composable
fun VectorAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { LocalizedOverlay(confirmButton) },
        modifier = modifier,
        dismissButton = dismissButton?.let { { LocalizedOverlay(it) } },
        icon = icon?.let { { LocalizedOverlay(it) } },
        title = title?.let { { LocalizedOverlay(it) } },
        text = text?.let { { LocalizedOverlay(it) } },
    )
}

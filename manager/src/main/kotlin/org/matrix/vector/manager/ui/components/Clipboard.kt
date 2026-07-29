package org.matrix.vector.manager.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.matrix.vector.manager.BuildConfig

/**
 * Puts text on the clipboard, or does nothing.
 *
 * Every screen in this app copies for the same reason — the text is on its way into a bug report —
 * and so every screen wants the same label on the clip and the same silence when there is no
 * clipboard service to hand. Parasitically there may not be: the manager is running inside
 * `com.android.shell` then, and a failure to copy is not worth a crash on the screen someone opened
 * *because* something had already gone wrong.
 */
fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(BuildConfig.MANAGER_PACKAGE_NAME, text))
}

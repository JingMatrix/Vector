package org.matrix.vector.ui

import android.app.Activity
import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A row's whole gesture set: tap it, hold it, right-click it.
 *
 * The secondary button means what the hold means, so it runs onLongClick: a pointer asks a row for
 * its menu with a right click or a two-finger tap on a touchpad. Compose reads a press from any
 * button as the start of an ordinary tap, so the button is recognised here instead — from the
 * pointer event where Compose reports it, and from the window's own events otherwise, which is what
 * watchSecondaryButton is for. Which row the pointer is over stays Compose's answer.
 */
fun Modifier.contextClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    combinedClickable(
            onClick = {
                // A click made with the secondary button belongs to the menu, not to the row.
                if (onLongClick != null && SecondaryButton.claim()) onLongClick() else onClick()
            },
            onLongClick = onLongClick,
        )
        .then(if (onLongClick == null) Modifier else Modifier.onSecondaryPress(onLongClick))

/**
 * Watch this activity's window for the secondary button, for as long as it lives.
 *
 * Call it once per host that draws rows. It wraps the window's callback to read the button off
 * every event and pass all of them on untouched, answering and consuming nothing. A dialog or a
 * popup is its own window, so rows inside one are covered by the pointer pass alone.
 */
fun Activity.watchSecondaryButton() {
    // A window with no callback is allowed, and there is nothing to wrap then.
    val inner = window?.callback ?: return
    window.callback = SecondaryButtonCallback(inner)
}

/**
 * The window's callback, wrapped to read the button off the events it passes on.
 *
 * Both dispatch methods, because a press arrives on both paths: in the touch stream carrying the
 * button state, and as its own ACTION_BUTTON_PRESS among the generic motion events. The reading
 * happens on both sides of the dispatch, since Compose fires the click inside it and the gesture
 * ends with the same event.
 */
private class SecondaryButtonCallback(private val inner: Window.Callback) :
    Window.Callback by inner {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        SecondaryButton.arriving(event)
        val handled = inner.dispatchTouchEvent(event)
        SecondaryButton.left(event)
        return handled
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        SecondaryButton.arriving(event)
        val handled = inner.dispatchGenericMotionEvent(event)
        SecondaryButton.left(event)
        return handled
    }
}

/**
 * Whether the pointer's secondary button is down, as the platform last said.
 *
 * One flag for one pointer. Any event carrying or naming the button sets it, and it is let go of
 * three ways: claimed by the click made with it, cleared when that gesture ends, and cleared by the
 * next press that does not carry it.
 */
private object SecondaryButton {

    private var held = false

    /** Before the hierarchy sees the event, so a click inside the dispatch reads the truth. */
    fun arriving(event: MotionEvent) {
        when {
            event.carriesSecondaryButton -> held = true
            event.actionMasked == MotionEvent.ACTION_DOWN -> held = false
        }
    }

    /** After it, because the click lands inside the dispatch and the gesture ends with it. */
    fun left(event: MotionEvent) {
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            held = false
        }
    }

    /** The answer, taken rather than read: one press is one context click. */
    fun claim(): Boolean = held.also { held = false }
}

/**
 * Whether this event has the secondary button on it.
 *
 * buttonState is which buttons are held as of the event. actionButton is the one an
 * ACTION_BUTTON_PRESS or ACTION_BUTTON_RELEASE announces, and is no button at all on every other
 * action, so the action itself needs no asking about. A stylus counts, its barrel button standing
 * in for the right one.
 */
private val MotionEvent.carriesSecondaryButton: Boolean
    get() {
        val secondary = MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        return buttonState and secondary != 0 || actionButton and secondary != 0
    }

/**
 * The secondary press, taken before anything underneath can read it as a tap.
 *
 * On the Initial pass, which runs before the one combinedClickable listens on, and every event of
 * the press is consumed: foundation asks for a press nothing else has taken. The action runs on the
 * way down, on the first event that reports the button held, so a pointer that announces its
 * buttons a moment after its touch is not missed.
 */
private fun Modifier.onSecondaryPress(action: () -> Unit): Modifier =
    pointerInput(action) {
        awaitPointerEventScope {
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!event.buttons.isSecondaryPressed) {
                    fired = false
                    continue
                }
                event.changes.forEach { it.consume() }
                if (!fired) {
                    fired = true
                    action()
                }
            }
        }
    }

package org.matrix.vector.manager.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.matrix.vector.manager.data.repository.LaunchShortcut
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.navigation.DeepLink
import org.matrix.vector.manager.ui.screens.splash.SplashGate
import org.matrix.vector.manager.ui.theme.LocalizedContent
import org.matrix.vector.manager.ui.theme.VectorTheme

/**
 * The only activity.
 *
 * Parasitically, every activity has to be tracked by hand by the zygisk hooker: it captures and
 * restores their saved state itself and rewrites every launch intent to this class, because
 * `system_server` does not know these spoofed activities exist. A single activity is what the
 * injection model wants, not a style preference.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede super.onCreate. Handing off from the platform splash is what keeps an
        // unthemed frame from appearing between the system splash and the Compose one.
        val splash = installSplashScreen()

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Idempotent, and safe whether or not the daemon already called Constants.setBinder.
        // Configures Coil, among the rest: it used to be done here, which left the debug demo host
        // without it.
        ServiceLocator.attach(this)

        // Started here rather than from the panels that need it: the splash is dead time the app
        // is spending anyway, and these reads are what makes a panel's first visit slower than its
        // second. By the time the splash has played, most of them have already answered.
        ServiceLocator.prefetch()

        // The launcher copies the label and icon when the shortcut is pinned and keeps its copy, so
        // a pinned shortcut otherwise represents Vector with whatever build pinned it for as long as
        // it lives. A no-op unless one is pinned, and unless this manager is the parasitic one.
        LaunchShortcut.update(this)

        // Keep the platform splash up only until the first frame is ready to draw; the Compose
        // splash then plays and decides for itself when the daemon has been given long enough.
        splash.setKeepOnScreenCondition { false }

        // The launch intent can name where to open — the module a notification was about.
        //
        // Offered on every creation, including a restored one. Parasitically the zygisk hooker
        // saves and restores this activity's state itself, so an activity started by a notification
        // arrives *with* a bundle and cannot tell itself apart from a rotation by looking at one:
        // guarding on `savedInstanceState == null`, which is the obvious reading, skipped the offer
        // on exactly the launch that mattered and left the previous tap's destination to be applied
        // instead — one module's notification opened another module's scope editor.
        //
        // What separates the two is not the bundle but the destination, so that judgement belongs
        // to DeepLink, which remembers the one it last applied; `intent` here is a field the
        // platform keeps answering with the intent this activity was *created* with, so a rotation
        // offers a destination the reader may have left behind hours ago.
        DeepLink.offerFromCreate(intent)

        setContent { LocalizedContent { VectorTheme { SplashGate { VectorApp() } } } }
    }

    /**
     * A second launch while the manager is already up.
     *
     * Installed normally, `launchMode` is `singleTop`, so tapping a notification reuses this
     * activity instead of starting another one and the new intent arrives here rather than at
     * [onCreate]. Without this the app would stay on whatever it was already showing and the
     * notification would look broken.
     *
     * Parasitically that is not guaranteed and this may never run. `ParasiticManagerSystemHooker`
     * answers the resolution with a copy of the com.android.shell host activity's `ActivityInfo`,
     * overriding only its process name, theme and flags, so the launch mode the system works from
     * is the host's rather than this manifest's, and the daemon's `openManager` adds only
     * `FLAG_ACTIVITY_NEW_TASK`. That is why nothing about the deep link is decided by which of the
     * two callbacks ran: [DeepLink] judges the destination instead, and both paths lead there.
     *
     * [setIntent] is not bookkeeping. The platform leaves `getIntent()` answering the intent this
     * activity was created with — `Activity.onNewIntent`'s own documentation says so and points at
     * this call — so without it every later recreation would re-offer the *first* notification's
     * module, long after a second one moved the reader somewhere else.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.offerFromNewIntent(intent)
    }

    /**
     * Ends the deep link's memory along with the screen it was applied to.
     *
     * [DeepLink] is an object, so what it last applied outlives this activity and would go on
     * suppressing a repeat of that destination in a process the platform merely kept cached — so
     * backing out of the manager and then tapping a second notice about the same module would open
     * Home. Once the activity is finishing there is no stack left to protect, which is the only
     * thing that memory is for.
     *
     * Gated on [isFinishing] because a configuration change destroys this activity too, and
     * forgetting there would let the recreation's replayed intent straight through.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) DeepLink.forget()
    }
}

package org.matrix.vector.manager.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.matrix.vector.manager.data.repository.LaunchShortcut
import org.matrix.vector.manager.di.ServiceLocator
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

        setContent { LocalizedContent { VectorTheme { SplashGate { VectorApp() } } } }
    }
}

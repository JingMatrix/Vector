package org.matrix.vector.manager.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
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
        ServiceLocator.attach(this)

        // Coil is configured explicitly rather than through its manifest hooks: parasitically this
        // app's manifest is never installed, so nothing that self-registers there ever runs.
        // It shares the one OkHttp client, which carries the DoH configuration and the disk cache.
        SingletonImageLoader.setSafe { platformContext: PlatformContext ->
            ImageLoader.Builder(platformContext)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { ServiceLocator.http }))
                }
                .build()
        }

        // Started here rather than from the panels that need it: the splash is dead time the app
        // is spending anyway, and these reads are what makes a panel's first visit slower than its
        // second. By the time the splash has played, most of them have already answered.
        ServiceLocator.prefetch()

        // Keep the platform splash up only until the first frame is ready to draw; the Compose
        // splash then plays and decides for itself when the daemon has been given long enough.
        splash.setKeepOnScreenCondition { false }

        setContent { LocalizedContent { VectorTheme { SplashGate { VectorApp() } } } }
    }
}

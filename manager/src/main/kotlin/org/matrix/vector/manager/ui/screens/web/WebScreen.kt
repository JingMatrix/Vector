package org.matrix.vector.manager.ui.screens.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.matrix.vector.manager.R

/**
 * GitHub, inside Vector.
 *
 * Handing a link to an external browser is a jarring context switch, and doubly so parasitically:
 * the app the system knows about is the shell process, so the user leaves something that does not
 * look like an app and has no obvious way back. Keeping the page here means reviewing a pull
 * request or reading a discussion never costs them their place.
 *
 * Three things this has to get right, and the first is the one that is easy to get wrong:
 *
 * **The page must be in the app's theme, not the system's.** A `WebView` sets
 * `prefers-color-scheme` from the theme of the context it was constructed with, which resolves
 * through that context's configuration — so with the activity's own context, Vector in light mode
 * on a dark-themed phone renders a black GitHub page under a white app bar. The WebView is built
 * against a configuration context whose night bit is forced to match the Compose theme instead, and
 * algorithmic darkening is allowed so that sites with no dark mode of their own are darkened rather
 * than left glaring.
 *
 * **The seam must not show.** The bar, the Scaffold container and the WebView's own background are
 * all the same surface colour, and the WebView's is set before the page paints — so there is no
 * white flash on load and no hard line between chrome and content.
 *
 * **It should get out of the way.** The bar retracts as the page scrolls down and returns on the
 * way up, which is what makes a full-screen reading surface feel immersive rather than framed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(url: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val surface = MaterialTheme.colorScheme.surface

    var progress by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(Uri.parse(url).host.orEmpty()) }
    var secure by remember { mutableStateOf(url.startsWith("https")) }
    var barVisible by remember { mutableStateOf(true) }

    // The night bit is read from the context the WebView is constructed with, so forcing it here is
    // what makes the page follow Vector's own theme rather than the system's.
    val themedContext =
        remember(dark) {
            val config =
                Configuration(context.resources.configuration).apply {
                    uiMode =
                        (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES
                            else Configuration.UI_MODE_NIGHT_NO
                }
            context.createConfigurationContext(config)
        }

    val webView =
        remember(themedContext) {
            WebView(themedContext).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                // Painted before the page arrives, so loading never flashes white on a dark theme.
                setBackgroundColor(surface.toArgb())

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    // Only reaches content that defines no dark styles of its own; GitHub does, and
                    // keeps using them.
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                }

                // Past the first 120px only, and with a 12px deadband either way, so the bar does
                // not flicker on the small offsets a tap or a settling fling produces.
                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    val delta = scrollY - oldScrollY
                    if (delta > 12 && scrollY > 120) barVisible = false
                    else if (delta < -12) barVisible = true
                }

                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url ?: return false
                            val scheme = target.scheme?.lowercase()
                            // Anything that is not web traffic — intent://, market:// — has to
                            // leave, because a WebView cannot render it.
                            if (scheme != "http" && scheme != "https") {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, target)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            progress = 5
                            barVisible = true
                            url?.let {
                                host = Uri.parse(it).host.orEmpty()
                                secure = it.startsWith("https")
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            progress = 100
                            title = view.title.orEmpty()
                        }
                    }
                loadUrl(url)
            }
        }

    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else onNavigateBack()
    }

    Scaffold(
        containerColor = surface,
        topBar = {
            AnimatedVisibility(
                visible = barVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = title.ifBlank { host },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            // The origin, stated plainly. A browser inside another app should
                            // never leave any doubt about whose page is being shown.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (secure) {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.height(11.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = host,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (webView.canGoBack()) webView.goBack() else onNavigateBack()
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val current = webView.url ?: url
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(current))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (_: ActivityNotFoundException) {
                                    // Nothing registered for http on this device. The page is
                                    // already open here, so there is nothing to tell the user.
                                }
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = stringResource(R.string.web_open_external),
                            )
                        }
                    },
                    // Matches the page's own surface so chrome and content read as one plane
                    // rather than two stacked rectangles.
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = surface,
                            scrolledContainerColor = surface,
                        ),
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(surface)) {
            // A hairline that only exists while loading; no permanent divider to break the plane.
            val shown by animateFloatAsState(
                targetValue = if (progress in 1..99) 1f else 0f,
                label = "webProgressAlpha",
            )
            if (shown > 0f) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        view.stopLoading()
                        view.destroy()
                    },
                )
            }
        }
    }
}

/** Whether a surface colour is dark enough that the page should render its own dark mode. */
private fun androidx.compose.ui.graphics.Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

@Composable
private fun Row(
    verticalAlignment: Alignment.Vertical,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = androidx.compose.foundation.layout.Row(verticalAlignment = verticalAlignment, content = content)

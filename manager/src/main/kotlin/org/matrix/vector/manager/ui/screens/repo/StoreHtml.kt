package org.matrix.vector.manager.ui.screens.repo

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.screens.web.forWebView

/**
 * Repository-supplied HTML — a README, or a release's notes — rendered inside Vector.
 *
 * **Why a WebView and not a Compose renderer.** The field the repository serves is `readmeHTML`:
 * HTML that GitHub has already rendered. The raw-markdown `readme` field is absent from every
 * response the API returns, so a "markdown renderer" here would in fact have to be an HTML engine.
 * Across the READMEs of the fifteen most-starred modules that means 181 `<a>`, 157 `<li>`, 136
 * `<p>`, 90 `<div>`, 77 `<svg>`, 50 `<img>`, 43 `<td>` across 5 `<table>`, plus `<picture>` with
 * theme-switched sources, nested lists, `<blockquote>` and `<del>` — a tokenizer, an inline-span
 * builder, table layout and async image loading, all hand-written because no new dependency is
 * allowed, and still worse than WebKit on the long tail. That is the wrong place to spend it, on a
 * page most people read once before installing.
 *
 * **So it has to be sandboxed properly, because the content is hostile in practice and not just in
 * theory.** One of the 809 READMEs in the catalogue ships a `googlesyndication` ad `<script>` and
 * an ad slot. What keeps that inert here:
 *
 * - **No base URL.** Passing one — `https://github.com`, say — would hand arbitrary module-authored
 *   HTML that host's origin. A null base URL lands the document in an opaque origin instead, where
 *   there is nothing worth reaching.
 * - **JavaScript and DOM storage off.** Both, and they must stay off: enabling scripting for a
 *   rendering fix would give module authors script execution.
 * - **Every subresource goes through the app's own HTTP client**, which is what gives images the
 *   DoH resolver and the disk cache, and is where anything that is not an image over http(s) is
 *   refused. It does not stop a README from reporting the reader's IP to a host it points at — only
 *   proxying would, and there is nothing to proxy through — but it does bound what can be loaded.
 * - **Links do not navigate in place.** They are handed to the caller, which opens them in the
 *   in-app browser where the origin is stated in the bar.
 *
 * The page is styled from the live [ColorScheme], so it follows the app's theme and dynamic colour
 * instead of hardcoding a foreground that only reads against one of them.
 */
@Composable
internal fun StoreHtmlPane(
    html: String,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit,
) {
    // The night bit is baked into the context the WebView is *constructed* with, and `AndroidView`
    // runs its factory exactly once for the life of the node — recomposition never calls it again.
    // Remembering a differently-themed WebView when the app's own dark switch flips would build one
    // that is never attached: the visible page would keep the old palette for good and the orphan
    // would be destroyed by nothing. Only re-keying the node re-runs a factory, which is what `key`
    // is for here. The system's night mode recreates the activity and needs none of this; Vector's
    // in-app dark and AMOLED switches do not.
    val dark = MaterialTheme.colorScheme.surface.isDark()
    key(dark) { HtmlPane(html, dark, modifier, onOpenUrl) }
}

@Composable
private fun HtmlPane(
    html: String,
    dark: Boolean,
    modifier: Modifier,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // A WebView reads prefers-color-scheme from the configuration of the context it was built
    // with. The stylesheet below covers our own markup, but a README using <picture> with a
    // dark-mode <source> picks its image from this. The same context is what decides whether the
    // pane may fetch anything, which parasitically it otherwise may not — see [forWebView].
    val themedContext = remember(dark) { context.forWebView(dark) }

    val webView =
        remember(themedContext) {
            WebView(themedContext).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setBackgroundColor(Color.Transparent.toArgb())
                claimVerticalDrags()
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.textZoom = 90
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url ?: return true
                            val scheme = target.scheme?.lowercase()
                            if (scheme == "http" || scheme == "https") {
                                onOpenUrl(target.toString())
                                return true
                            }
                            // An in-page anchor resolves against about:blank; a table of contents
                            // should still work, so that one is allowed to scroll in place.
                            if (target.toString().startsWith("about:blank")) return false
                            // intent://, market://, anything else: refused rather than handed to
                            // the system, because the author of this page is a stranger.
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? = fetchSubresource(ServiceLocator.http, request)
                    }
            }
        }

    // Keyed on the colours themselves rather than on the ColorScheme instance: the scheme is not
    // guaranteed to compare equal across recompositions, and a false miss here reloads the page.
    val palette = remember(colors) { Palette(colors) }
    val document = remember(html, rtl, palette) { document(html, palette, rtl) }

    // Loaded from an effect rather than from AndroidView's `update`, which runs on every
    // recomposition and would reload the page — and throw away the reader's scroll position —
    // every time anything on the screen changed.
    LaunchedEffect(webView, document) {
        webView.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
        onRelease = { view ->
            // Without this every visit leaks a WebView and its renderer process. The pane lives
            // inside a pager, which builds and abandons them freely.
            view.stopLoading()
            view.destroy()
        },
    )
}

/**
 * Fetches a subresource through the shared client, or refuses it.
 *
 * Runs on a WebView background thread, so the blocking call is correct here. The returned stream is
 * closed by the WebView, which is what releases the connection; closing the response here instead
 * would truncate the image.
 */
private fun fetchSubresource(
    client: OkHttpClient,
    request: WebResourceRequest,
): WebResourceResponse? {
    val url = request.url ?: return blocked()
    when (url.scheme?.lowercase()) {
        // Inline images and the document itself. Inert, and nothing to fetch.
        "data", "about", null -> return null
        "http", "https" -> Unit
        else -> return blocked()
    }
    if (!request.method.equals("GET", ignoreCase = true)) return blocked()

    return try {
        val response = client.newCall(Request.Builder().url(url.toString()).build()).execute()
        val type = response.body.contentType()
        if (!response.isSuccessful || type?.type != "image") {
            response.close()
            blocked()
        } else {
            WebResourceResponse("${type.type}/${type.subtype}", "UTF-8", response.body.byteStream())
        }
    } catch (_: Exception) {
        blocked()
    }
}

/**
 * A 1×1 transparent GIF. A refused subresource should render as nothing, not as a broken image.
 *
 * It has to be a real, decodable image. An empty body, or one under a type no `<img>` can decode,
 * is a decode failure, and WebKit answers that with its broken-image glyph and the alt text beside
 * it — which is what a README linking a chart that has since started 404ing would show.
 */
private fun blocked() =
    WebResourceResponse("image/gif", null, ByteArrayInputStream(TRANSPARENT_GIF))

private val TRANSPARENT_GIF =
    byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
        0x01, 0x00, 0x01, 0x00, // 1 × 1
        0x80.toByte(), 0x00, 0x00, // global colour table present, background 0, square pixels
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // the table: two entries, both black
        0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, // colour 0 is transparent
        0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // image descriptor
        0x02, 0x02, 0x44, 0x01, 0x00, // one pixel of LZW
        0x3B, // trailer
    )

private fun Color.isDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

private fun Color.css(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** The five colours the stylesheet needs, as a value that compares by content. */
private data class Palette(
    val text: String,
    val muted: String,
    val accent: String,
    val fill: String,
    val rule: String,
    val dark: Boolean,
) {
    constructor(
        colors: ColorScheme
    ) : this(
        text = colors.onSurface.css(),
        muted = colors.onSurfaceVariant.css(),
        accent = colors.primary.css(),
        fill = colors.surfaceContainerHigh.css(),
        rule = colors.outlineVariant.css(),
        dark = colors.surface.isDark(),
    )
}

/**
 * The document, styled from the app's own palette.
 *
 * `dir` follows the app's layout direction rather than the content's, so a README sits the way the
 * rest of the screen does. GitHub's heading permalinks are hidden: they are inline `<svg>` octicons
 * anchoring to a page there is no way to link to from here.
 */
private fun document(body: String, palette: Palette, rtl: Boolean): String {
    val (text, muted, accent, fill, rule) = palette
    return """
        <!DOCTYPE html>
        <html dir="${if (rtl) "rtl" else "ltr"}">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          :root { color-scheme: ${if (palette.dark) "dark" else "light"}; }
          body {
            margin: 0; padding: 16px 16px 32px;
            background: transparent; color: $text;
            font-family: sans-serif; line-height: 1.6;
            overflow-wrap: break-word; -webkit-text-size-adjust: 100%;
          }
          a { color: $accent; text-decoration: none; }
          a.anchor { display: none; }
          h1, h2 { border-bottom: 1px solid $rule; padding-bottom: .3em; }
          h1, h2, h3, h4 { line-height: 1.3; }
          code, kbd, samp { background: $fill; padding: .15em .35em; border-radius: 4px; }
          pre { background: $fill; padding: 12px; border-radius: 8px; overflow-x: auto; }
          pre code { background: none; padding: 0; }
          blockquote {
            margin: 0; padding: 0 1em; color: $muted;
            border-inline-start: 3px solid $rule;
          }
          img { max-width: 100%; height: auto; }
          /* GitHub task lists, which release notes use and module READMEs do not. Without this
             the checkbox sits beside a bullet, which reads as two markers for one item. */
          li.task { list-style: none; margin-inline-start: -1.1em; }
          li.task input { margin-inline-end: .45em; vertical-align: middle; }
          hr { border: none; border-top: 1px solid $rule; }
          table { border-collapse: collapse; display: block; overflow-x: auto; }
          th, td { border: 1px solid $rule; padding: 6px 12px; }
          summary { color: $accent; }
        </style>
        </head>
        <body>$body</body>
        </html>
    """
        .trimIndent()
}

/**
 * Keeps a vertical drag inside the page, and lets a sideways one through.
 *
 * A WebView does not stop the Compose hierarchy above it from claiming a gesture. On a screen where
 * this pane is one of three swipeable tabs, that means reading a long README slides the tab across:
 * every real drag on a phone held in one hand has a sideways component, and the pager takes it. The
 * lists on the other tabs are protected by disabling the pager while they are scrolling, but a
 * WebView has no scroll state Compose can see, so it says so itself.
 *
 * Decided once per gesture, at the moment the finger passes the touch slop, and by comparing the
 * two axes rather than by a threshold on one — the question is not "how far" but "which way did
 * they mean". A vertical answer disallows interception for the rest of the gesture; a sideways one
 * leaves the pager free to take it, so deliberately swiping to the next tab still works from here.
 *
 * Returns false throughout: this listens, it never consumes, and the page scrolls exactly as it
 * would have.
 */
@SuppressLint("ClickableViewAccessibility")
private fun WebView.claimVerticalDrags() {
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    var startX = 0f
    var startY = 0f
    var decided = false
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                decided = false
            }
            MotionEvent.ACTION_MOVE ->
                if (!decided) {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    if (dx > slop || dy > slop) {
                        decided = true
                        view.parent?.requestDisallowInterceptTouchEvent(dy >= dx)
                    }
                }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
        }
        false
    }
}

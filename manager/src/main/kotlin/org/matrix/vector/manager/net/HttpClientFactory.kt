package org.matrix.vector.manager.net

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.matrix.vector.manager.data.repository.SettingsRepository

/**
 * The one HTTP client the manager uses, for the module store, the GitHub feed and avatars alike.
 *
 * Two things it must get right:
 * - **A disk cache.** Every remote surface renders from cache first and treats the network as an
 *   upgrade, because the manager is routinely opened with no connectivity. The cache also makes
 *   GitHub's conditional requests cheap: a `304 Not Modified` does not count against the 60
 *   requests/hour an unauthenticated client gets, so revalidation is effectively free.
 * - **DNS over HTTPS, as a fallback rather than a replacement.** Users on censored networks cannot
 *   resolve the module repository or GitHub over plain DNS. See [VectorDns] for why it must never
 *   be the only path: a network that blocks Cloudflare as well would then leave the Store
 *   permanently empty.
 */
object HttpClientFactory {

    private const val CACHE_DIR = "http_cache"
    private const val CACHE_SIZE_BYTES = 16L * 1024 * 1024

    fun create(context: Context, settings: SettingsRepository): OkHttpClient {
        val cache = Cache(File(context.cacheDir, CACHE_DIR), CACHE_SIZE_BYTES)

        val base =
            OkHttpClient.Builder()
                .cache(cache)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

        // The resolver reads the setting on every lookup, so the switch takes effect immediately
        // and the shared client — with its connection pool and its disk cache — is never rebuilt.
        // `base` is passed in as the bootstrap client because a DoH client must not itself resolve
        // through DoH.
        return base.newBuilder().dns(VectorDns(settings, base)).build()
    }
}

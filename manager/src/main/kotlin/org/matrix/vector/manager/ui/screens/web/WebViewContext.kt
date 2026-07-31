package org.matrix.vector.manager.ui.screens.web

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Process

/**
 * The context a `WebView` has to be built with, because both of these are read once, at
 * construction, and cannot be set afterwards.
 *
 * **The theme.** A `WebView` resolves `prefers-color-scheme` through the configuration of the
 * context it was constructed with, so the activity's own context renders a black GitHub page under
 * a white app bar whenever the app's theme disagrees with the system's. The night bit is forced to
 * match the Compose theme instead.
 *
 * **Whether it may use the network at all.** `AwSettings` sets `mBlockNetworkLoads` from
 * `context.checkSelfPermission(INTERNET)` in its constructor, and a blocked load is implemented as
 * `LOAD_ONLY_FROM_CACHE`, so an empty cache makes every page fail as Chromium's own
 * `net::ERR_CACHE_MISS` error page — on a device whose networking is perfectly fine.
 *
 * That check is by uid, and parasitically our uid is 2000. AOSP's `packages/Shell` did not request
 * `INTERNET` until Android 12, and `PermissionManagerService.checkUidPermission` reads the grants
 * of the package that owns the uid — consulting `platform.xml`'s `assign-permission … uid="shell"`
 * only when *no* package owns it, which is never true here. So below Android 12 the platform's
 * honest answer for the host is DENIED, and the in-app browser could not load a single page.
 *
 * The process does have networking: the zygisk module adds `GID_INET` to the manager's fork and
 * makes `Zygote` set `setAllowNetworkingForProcess`, which is why OkHttp fetches the catalogue and
 * downloads module APKs in this same process. None of that changes what the platform *answers*, and
 * the answer is all `AwSettings` looks at. `setBlockNetworkLoads(false)` is not a way round it
 * either — it throws `SecurityException` while the permission is missing.
 *
 * So this context answers the one question, and only where the platform says no: not gated on the
 * SDK level, because an OEM that strips the permission from its own shell package needs the same
 * treatment. Nothing outside the two `WebView`s is affected, and a genuine network failure still
 * arrives as a genuine network error rather than as a cache miss.
 */
internal fun Context.forWebView(dark: Boolean): Context {
    val configuration =
        Configuration(resources.configuration).apply {
            uiMode =
                (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
    val themed = createConfigurationContext(configuration)
    if (themed.checkSelfPermission(INTERNET) == PackageManager.PERMISSION_GRANTED) return themed

    return object : ContextWrapper(themed) {
        override fun checkSelfPermission(permission: String): Int =
            if (permission == INTERNET) PackageManager.PERMISSION_GRANTED
            else super.checkSelfPermission(permission)

        // Older WebView builds ask this instead, about this process. A question about any other
        // uid is somebody else's business and is passed through.
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == INTERNET && uid == Process.myUid()) PackageManager.PERMISSION_GRANTED
            else super.checkPermission(permission, pid, uid)
    }
}

private const val INTERNET = "android.permission.INTERNET"

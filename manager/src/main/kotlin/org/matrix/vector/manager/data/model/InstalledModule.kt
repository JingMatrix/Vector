package org.matrix.vector.manager.data.model

import android.content.pm.ApplicationInfo

/** An installed Xposed module, as the Modules screen sees it. */
data class InstalledModule(
    val packageName: String,
    val userId: Int,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val description: String,
    val minVersion: Int,
    val targetVersion: Int,
    val isLegacy: Boolean,
    /** False when the module declares no Xposed API version at all, on either scale. */
    val declaresApiVersion: Boolean,
    /** When the module was last installed or updated — how you find the one you just added. */
    val lastUpdateTime: Long,
    val isEnabled: Boolean,
    val applicationInfo: ApplicationInfo, // The row's icon is rasterised from this.
) {
    /**
     * The API version this module *is*, as opposed to the one it asks for.
     *
     * `module.prop` carries two different numbers: `minApiVersion`, the author's stated floor, and
     * `targetApiVersion`, what the module was built against. Of the two, only the second decides
     * anything. The daemon's `FileSystem.loadModule` tries `targetApiVersion` first — 101 or above
     * loads as modern — then falls back to an `assets/xposed_init`, which makes it legacy, and
     * refuses a module that declares exactly 100 with no such entry. `minApiVersion` is read
     * nowhere in the daemon or the framework, so showing it as "API n" would report a number
     * nothing acts on.
     *
     * Legacy modules keep their own number, because there is no target on that scale — it comes
     * from the `xposedminversion` manifest entry and is all they have.
     */
    val apiVersion: Int =
        when {
            isLegacy -> minVersion
            targetVersion > 0 -> targetVersion
            else -> minVersion
        }
}

package org.matrix.vector.manager.data.model

import android.content.pm.PackageInfo
import android.os.Build

/**
 * A package's version code, on every release this app runs on.
 *
 * `PackageInfo.getLongVersionCode` arrived in API 28 and the `int` field it replaces was deprecated
 * in the same release, so on a supported device one of the two is always the wrong one to call. The
 * minimum here is API 27: reading the long unguarded is a `NoSuchMethodError` on Android 8.1, not a
 * lint opinion, and it would take out the app list, the module list and the store's installed-check
 * — every screen that names a version.
 *
 * Reading the deprecated field is not a loss below 28. The high half it drops, `versionCodeMajor`,
 * cannot be set by a package installed on a release that has no concept of it.
 */
val PackageInfo.versionCodeCompat: Long
    @Suppress("DEPRECATION")
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else versionCode.toLong()

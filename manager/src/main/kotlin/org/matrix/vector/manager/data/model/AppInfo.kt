package org.matrix.vector.manager.data.model

import android.content.pm.ApplicationInfo

/** Represents an installed application for the Scope configuration screen. */
data class AppInfo(
    val packageName: String,
    val userId: Int,
    val appName: String,
    val isSystemApp: Boolean,
    val isGame: Boolean,
    val isSelectedInScope: Boolean,
    /**
     * In the scope without anyone having put it there, and not removable.
     *
     * Nothing in the scope table says so — the daemon derives this target while it rebuilds its
     * configuration — so it is stamped on the row by the screen that knows the rule, exactly as
     * [isSelectedInScope] and [isRecommended] are.
     */
    val isImplicitInScope: Boolean = false,
    val isRecommended: Boolean,
    /** When the package was last installed or updated, for the "recently updated" sort. */
    val lastUpdateTime: Long,
    /** When it was first installed — a different question, and the list sorts on both. */
    val firstInstallTime: Long,
    /**
     * The installed version, which with [lastUpdateTime] is the module detection cache's key.
     *
     * Defaulted because not every [AppInfo] comes from a real package; the stand-in row for the
     * system server has no version to report and is never inspected.
     */
    val versionCode: Long = 0,
    val applicationInfo: ApplicationInfo,
)

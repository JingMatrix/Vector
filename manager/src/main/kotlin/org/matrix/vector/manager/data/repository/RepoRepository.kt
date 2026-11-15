package org.matrix.vector.manager.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.matrix.vector.manager.data.model.versionCodeCompat
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logI
import org.matrix.vector.manager.logW
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreLog
import org.matrix.vector.ui.store.StoreRepository

/**
 * The Store's data for Vector: [StoreRepository], with the daemon as what knows the device.
 *
 * Only the two things that are Vector's own live here. Installed versions come from the daemon and
 * are re-read whenever its binder arrives, and log lines go through [logI] and [logW], which is
 * what puts them in the Verbose tab and the exported zip.
 */
class RepoRepository(client: OkHttpClient, daemon: DaemonClient, scope: CoroutineScope) :
    StoreRepository(client, scope, VectorLog, { daemon.installedVersions() }) {

    init {
        scope.launch {
            // Re-read whenever a binder arrives, including a reconnect. The map is deliberately
            // *not* cleared when the daemon goes away: which packages are installed is a fact
            // about the device, not about the framework, and dropping every "Installed" badge
            // because the daemon died would state something untrue.
            ServiceLocator.service.collect { service -> if (service != null) refreshInstalled() }
        }
    }
}

private val VectorLog = StoreLog { priority, message, cause ->
    if (priority >= Log.WARN) logW(message, cause) else logI(message, cause)
}

/**
 * What each package is at, across every user, or null when the daemon cannot be reached.
 *
 * One `getInstalledPackagesFromAllUsers` call and no `ModuleDetection`: the Store already knows
 * that every name it asks about is a module, so it does not need the much more expensive discovery
 * the Modules screen runs, which opens every APK to find out.
 *
 * Every user rather than user 0, which matters to an install recording what it produced: on a
 * device with a work profile a local `getPackageInfo` would answer differently from this map, and a
 * note written from the one can never match the other.
 */
private suspend fun DaemonClient.installedVersions(): Map<String, RepoVersion>? {
    val packages =
        getInstalledPackagesFromAllUsers(0, false)
            .onFailure { e -> logW("store: installed versions unavailable", e) }
            .getOrNull() ?: return null
    val versions = HashMap<String, RepoVersion>(packages.size)
    for (info in packages) {
        val version = RepoVersion(info.versionCodeCompat, info.versionName.orEmpty())
        // The daemon reports every user, so the same package arrives more than once. The highest
        // version wins, because that is the one an update would have to beat.
        val known = versions[info.packageName]
        if (known == null || version.versionCode > known.versionCode) {
            versions[info.packageName] = version
        }
    }
    return versions
}

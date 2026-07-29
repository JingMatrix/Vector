package org.matrix.vector.manager.data.repository
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.vector.manager.data.model.AppInfo
import org.matrix.vector.manager.data.model.ModuleDetectionCache
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logW

/** Fetches and caches the list of installed applications from the daemon. */
class AppRepository(
    private val daemonClient: DaemonClient,
    private val packageManager: PackageManager,
    private val moduleDetection: ModuleDetectionCache,
) {
    @Volatile private var cachedApps: List<AppInfo>? = null
    @Volatile private var cachedModulePackages: Set<String>? = null

    /**
     * Drops the cache so the next read goes back to the daemon.
     *
     * Called from the package added, replaced and removed broadcasts: without it a module installed
     * while the manager is open would not appear for the life of the process.
     */
    fun invalidate() {
        cachedApps = null
        cachedModulePackages = null
    }

    suspend fun getInstalledApps(forceRefresh: Boolean = false): List<AppInfo> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && cachedApps != null) {
                return@withContext cachedApps!!
            }

            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_META_DATA

            val result =
                daemonClient.getInstalledPackagesFromAllUsers(flags, filterNoProcess = true)
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (failure !is CancellationException) {
                    logW("apps: installed package list unavailable from daemon", failure)
                }
                return@withContext emptyList()
            }

            val packages = result.getOrNull() ?: emptyList()
            val PER_USER_RANGE = 100000

            val appList =
                packages.mapNotNull { pkg ->
                    val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isGame =
                        appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                            (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0

                    val userId = appInfo.uid / PER_USER_RANGE

                    AppInfo(
                        packageName = pkg.packageName,
                        userId = userId,
                        appName = appInfo.loadLabel(packageManager).toString(),
                        isSystemApp = isSystem,
                        isGame = isGame,
                        isSelectedInScope = false, // To be merged later in the ViewModel
                        isRecommended = false,
                        lastUpdateTime = pkg.lastUpdateTime,
                        firstInstallTime = pkg.firstInstallTime,
                        versionCode = pkg.longVersionCode,
                        applicationInfo = appInfo,
                    )
                }

            cachedApps = appList
            return@withContext appList
        }

    /**
     * Which installed packages are themselves Xposed modules.
     *
     * Answering this means putting every installed package through module detection, which opens
     * the APK of any it has not seen before, so it is computed once per process and held until the
     * app list is invalidated. The scope screen needs it on open — modules are hidden from the
     * hookable-app list by default — and paying that cost on every scope screen would be a visible
     * stall each time.
     *
     * Through the shared [ModuleDetectionCache] rather than straight to `ModuleDetection`, so a
     * package the Modules panel has already inspected is a map lookup here, and stays one across a
     * cold start.
     */
    suspend fun modulePackages(): Set<String> =
        withContext(Dispatchers.IO) {
            cachedModulePackages?.let {
                return@withContext it
            }
            val packages =
                getInstalledApps()
                    .asSequence()
                    .filter {
                        moduleDetection
                            .inspect(
                                it.applicationInfo,
                                packageManager,
                                it.versionCode,
                                it.lastUpdateTime,
                            )
                            .isModule
                    }
                    .map { it.packageName }
                    .toSet()
            cachedModulePackages = packages
            packages
        }
}

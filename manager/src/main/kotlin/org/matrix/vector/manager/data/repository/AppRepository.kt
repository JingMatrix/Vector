package org.matrix.vector.manager.data.repository
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.vector.manager.data.model.AppInfo
import org.matrix.vector.manager.data.model.ModuleDetectionCache
import org.matrix.vector.manager.data.model.versionCodeCompat
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logW
import org.matrix.vector.ui.module.MATCH_ANY_USER

/** Fetches and caches the list of installed applications from the daemon. */
class AppRepository(
    private val daemonClient: DaemonClient,
    private val packageManager: PackageManager,
    private val moduleDetection: ModuleDetectionCache,
    private val scope: CoroutineScope,
) {
    @Volatile private var cachedApps: List<AppInfo>? = null
    @Volatile private var cachedModulePackages: Set<String>? = null

    /**
     * Bumped by every [invalidate], so a read already in flight when one lands can tell.
     *
     * That read has by definition missed whatever the package event carried, and writing its answer
     * into the cache afterwards would hold the stale list until the *next* event — on a device
     * where nothing else changes, indefinitely. The counter is sampled when a read starts and again
     * before it publishes: on a mismatch the answer still goes back to the caller who asked for it,
     * because it is the best that read can offer, but it is not cached for anyone else.
     */
    private val generation = AtomicInteger(0)

    /** Guards [inFlight] alone. Never held across a fetch — that is what lets a fetch be shared. */
    private val lock = Mutex()

    /**
     * The enumeration running right now, so concurrent callers join it rather than each start one.
     *
     * Enumerating is expensive in a way the call site cannot see: the daemon answers
     * `getInstalledPackagesFromAllUsers` with `filterNoProcess = true` by asking the package
     * manager for the full component list of every installed package, several hundred of them on a
     * normal device. The scope editor asks twice at once — once from `load`, once for the module
     * packages it filters by — and with a cold cache both used to go through. That is issue #917:
     * any install or uninstall drops the cache, so the next module's scope screen ran two of those
     * enumerations against each other and sat on its spinner until they finished.
     *
     * Started on the application scope rather than the caller's, so leaving the screen part-way
     * through does not throw the work away: the next visit finds the cache warm rather than paying
     * for the same enumeration again.
     *
     * Retired by the next reader that finds it finished rather than by the job itself. A fetch that
     * succeeded has filled the cache, so the check above this one answers before the field is ever
     * consulted; a fetch that failed deliberately cached nothing, and dropping the finished job is
     * exactly what lets the next caller retry instead of joining a failure for the life of the
     * process.
     */
    private var inFlight: Deferred<List<AppInfo>>? = null

    /** The same arrangement for [moduleScanPackages], which asks the daemon a different question. */
    @Volatile private var cachedModuleScan: List<PackageInfo>? = null

    private val moduleScanLock = Mutex()

    private var moduleScanInFlight: Deferred<Result<List<PackageInfo>>>? = null

    /**
     * Drops the cache so the next read goes back to the daemon.
     *
     * Called from the package added, replaced and removed broadcasts: without it a module installed
     * while the manager is open would not appear for the life of the process.
     */
    fun invalidate() {
        generation.incrementAndGet()
        cachedApps = null
        cachedModulePackages = null
        cachedModuleScan = null
    }

    /**
     * The raw package list the Modules panel scans, shared the same way [getInstalledApps] is.
     *
     * A second enumeration rather than a filter over the first, because the two ask the daemon
     * different questions: this one wants `MATCH_ANY_USER` and uninstalled packages so a module
     * held only by a work profile is still seen, and it must *not* set `filterNoProcess`, because a
     * module with no components of its own is still a module.
     *
     * Shared because the module list is the heaviest caller of it and had nothing stopping it
     * running against itself. Every package event drops the caches and bumps the revision this
     * feeds, and that event arrives twice by design — once from the platform, once from the
     * daemon's re-broadcast — so a single install started two full enumerations, and a device whose
     * packages churn kept dozens alive at once. That is not a slow path, it is a broken one: each
     * enumeration pulls every package with its metadata, per user, as a chunked
     * `ParceledListSlice`, and the daemon's binder heap is a fixed megabyte. One report has 409
     * `binder_alloc_buf ... failed, no address space` against the daemon, with 687 buffers
     * outstanding and 577 KB free in blocks too small to hold a 197 KB reply — system_server's
     * answers failing with ENOSPC not because any one of them was too large, but because sixty
     * threads were asking at once.
     *
     * Answers a [Result] rather than a bare list: a failed enumeration is not a device with no
     * packages, and the caller has to be able to tell. Only a success is cached.
     */
    suspend fun moduleScanPackages(): Result<List<PackageInfo>> {
        cachedModuleScan?.let {
            return Result.success(it)
        }
        val job =
            moduleScanLock.withLock {
                cachedModuleScan?.let {
                    return Result.success(it)
                }
                moduleScanInFlight?.takeIf { it.isActive }
                    ?: scope.async(Dispatchers.IO) { fetchModuleScanPackages() }
                        .also { moduleScanInFlight = it }
            }
        return job.await()
    }

    private suspend fun fetchModuleScanPackages(): Result<List<PackageInfo>> {
        val startedAt = generation.get()
        val flags =
            PackageManager.GET_META_DATA or
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                MATCH_ANY_USER

        val result = daemonClient.getInstalledPackagesFromAllUsers(flags, filterNoProcess = false)
        val packages = result.getOrElse { return result }
        if (generation.get() == startedAt) cachedModuleScan = packages
        return Result.success(packages)
    }

    suspend fun getInstalledApps(): List<AppInfo> {
        cachedApps?.let {
            return it
        }
        val job =
            lock.withLock {
                // Re-read under the lock. A fetch that finished while this call was waiting has
                // already published, and joining a job only to learn what the field beside it now
                // holds is a suspension for nothing.
                cachedApps?.let {
                    return it
                }
                inFlight?.takeIf { it.isActive }
                    ?: scope.async(Dispatchers.IO) { fetchInstalledApps() }.also { inFlight = it }
            }
        return job.await()
    }

    private suspend fun fetchInstalledApps(): List<AppInfo> {
        val startedAt = generation.get()
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_META_DATA

        val result = daemonClient.getInstalledPackagesFromAllUsers(flags, filterNoProcess = true)
        val failure = result.exceptionOrNull()
        if (failure != null) {
            if (failure !is CancellationException) {
                logW("apps: installed package list unavailable from daemon", failure)
            }
            return emptyList()
        }

        val packages = result.getOrNull() ?: emptyList()
        val PER_USER_RANGE = 100000

        val appList =
            packages.mapNotNull { pkg ->
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // FLAG_IS_GAME was replaced by the category in API 26 and is deprecated, but an
                // app built before that still ships it and sets no category, so both are read.
                @Suppress("DEPRECATION")
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
                    versionCode = pkg.versionCodeCompat,
                    applicationInfo = appInfo,
                )
            }

        if (generation.get() == startedAt) cachedApps = appList
        return appList
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
            val startedAt = generation.get()
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
            if (generation.get() == startedAt) cachedModulePackages = packages
            packages
        }
}

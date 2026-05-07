package org.matrix.vector.daemon.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageParser
import android.system.Os
import android.util.Log
import hidden.HiddenApiBridge
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.lsposed.lspd.models.Application
import org.lsposed.lspd.models.Module
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.VectorDaemon
import org.matrix.vector.daemon.ipc.InjectedModuleService
import org.matrix.vector.daemon.system.*
import org.matrix.vector.daemon.utils.InstallerVerifier
import org.matrix.vector.daemon.utils.applySqliteHelperWorkaround
import org.matrix.vector.daemon.utils.getRealUsers

private const val TAG = "VectorConfigCache"

object ConfigCache {
  // Module preference operations are delegated to PreferenceStore
  // Writable operations of modules are delegated to ModuleDatabase

  @Volatile
  var state = DaemonState()
    private set

  val dbHelper = Database() // Kept public for PreferenceStore and ModuleDatabase

  private val cacheUpdateChannel = Channel<Unit>(Channel.CONFLATED)

  init {
    VectorDaemon.scope.launch {
      for (request in cacheUpdateChannel) {
        performCacheUpdate()
      }
    }
    applySqliteHelperWorkaround()
  }

  private fun ensureCacheReady() {
    if (!state.isCacheReady && packageManager?.asBinder()?.isBinderAlive == true) {
      synchronized(this) {
        if (!state.isCacheReady) {
          Log.i(TAG, "System services are ready. Mapping modules and scopes.")
          updateManager(false)
          setupMiscPath()
          performCacheUpdate()
          state = state.copy(isCacheReady = true)
        }
      }
    }
  }

  fun updateManager(uninstalled: Boolean) {
    if (uninstalled) {
      state = state.copy(managerUid = -1)
      return
    }
    runCatching {
          val info =
              packageManager?.getPackageInfoCompat(BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME, 0, 0)
          val uid = info?.applicationInfo?.uid
          val installedApkPath = info?.applicationInfo?.sourceDir
          if (uid == null || installedApkPath == null) {
            Log.i(TAG, "Manager is not installed")
            state = state.copy(managerUid = -1)
            return
          }

          InstallerVerifier.verifyInstallerSignature(installedApkPath)
          Log.i(TAG, "Manager verified and found at UID: $uid")
          state = state.copy(managerUid = uid)
        }
        .onFailure { state = state.copy(managerUid = -1) }
  }

  private fun setupMiscPath() {
    if (state.miscPath != null) return

    val pathStr = PreferenceStore.getModulePrefs("lspd", 0, "config")["misc_path"] as? String
    val path =
        if (pathStr == null) {
          val newPath = Paths.get("/data/misc", UUID.randomUUID().toString())
          PreferenceStore.updateModulePref("lspd", 0, "config", "misc_path", newPath.toString())
          newPath
        } else {
          Paths.get(pathStr)
        }
    state = state.copy(miscPath = path)

    runCatching {
          val perms =
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx--x--x"))
          Files.createDirectories(state.miscPath!!, perms)
          FileSystem.setSelinuxContextRecursive(state.miscPath!!, "u:object_r:xposed_data:s0")
        }
        .onFailure { Log.e(TAG, "Failed to create misc directory", it) }
  }

  fun isManager(uid: Int): Boolean {
    ensureCacheReady()
    return uid == state.managerUid || uid == BuildConfig.MANAGER_INJECTED_UID
  }

  fun requestCacheUpdate() {
    cacheUpdateChannel.trySend(Unit)
  }

  /** Builds a completely new Immutable State and atomically swaps it. */
  private fun performCacheUpdate() {
    if (packageManager == null) return

    Log.d(TAG, "Executing Cache Update...")
    val db = dbHelper.readableDatabase
    val oldState = state

    val newModules = mutableMapOf<String, Module>()
    val obsoleteModules = mutableSetOf<String>()
    val obsoletePaths = mutableMapOf<String, String>()

    db.query(
            "modules",
            arrayOf("module_pkg_name", "apk_path"),
            "enabled = 1",
            null,
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            val pkgName = cursor.getString(0)
            var apkPath = cursor.getString(1)
            if (pkgName == "lspd") continue

            val oldModule = oldState.modules[pkgName]

            var pkgInfo: android.content.pm.PackageInfo? = null
            val users = userManager?.getRealUsers() ?: emptyList()
            for (user in users) {
              pkgInfo = packageManager?.getPackageInfoCompat(pkgName, MATCH_ALL_FLAGS, user.id)
              if (pkgInfo?.applicationInfo != null) break
            }

            if (pkgInfo?.applicationInfo == null) {
              Log.w(TAG, "Failed to find package info of $pkgName")
              obsoleteModules.add(pkgName)
              continue
            }

            val appInfo = pkgInfo.applicationInfo

            if (oldModule != null &&
                appInfo?.sourceDir != null &&
                apkPath != null &&
                oldModule.apkPath != null &&
                FileSystem.toGlobalNamespace(apkPath).exists() &&
                apkPath == oldModule.apkPath &&
                File(appInfo.sourceDir).parent == File(apkPath).parent) {

              if (oldModule.appId == -1) oldModule.applicationInfo = appInfo
              newModules[pkgName] = oldModule
              continue
            }

            val realApkPath = getModuleApkPath(appInfo!!)
            if (realApkPath == null) {
              Log.w(TAG, "Failed to find path of $pkgName")
              obsoleteModules.add(pkgName)
              continue
            } else {
              apkPath = realApkPath
              obsoletePaths[pkgName] = realApkPath
            }

            val preLoadedApk = FileSystem.loadModule(apkPath, state.isDexObfuscateEnabled)
            if (preLoadedApk != null) {
              val module =
                  Module().apply {
                    packageName = pkgName
                    this.apkPath = apkPath
                    appId = appInfo.uid
                    applicationInfo = appInfo
                    service = oldModule?.service ?: InjectedModuleService(pkgName)
                    file = preLoadedApk
                  }
              newModules[pkgName] = module
            } else {
              Log.w(TAG, "Failed to parse DEX/ZIP for $pkgName, skipping.")
              obsoleteModules.add(pkgName)
            }
          }
        }

    if (packageManager?.asBinder()?.isBinderAlive == true) {
      obsoleteModules.forEach { ModuleDatabase.removeModule(it) }
      obsoletePaths.forEach { (pkg, path) -> ModuleDatabase.updateModuleApkPath(pkg, path, true) }
    }

    val newScopes = mutableMapOf<ProcessScope, MutableList<Module>>()
    db.query(
            "scope INNER JOIN modules ON scope.mid = modules.mid",
            arrayOf("app_pkg_name", "module_pkg_name", "user_id"),
            "enabled = 1",
            null,
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            val appPkg = cursor.getString(0)
            val modPkg = cursor.getString(1)
            val userId = cursor.getInt(2)

            val module = newModules[modPkg] ?: continue

            if (appPkg == "system") {
              newScopes
                  .getOrPut(ProcessScope("system_server", 1000)) { mutableListOf() }
                  .add(module)
              continue
            }

            val pkgInfo =
                packageManager?.getPackageInfoWithComponents(appPkg, MATCH_ALL_FLAGS, userId)
            if (pkgInfo?.applicationInfo == null) continue

            val processNames = pkgInfo.fetchProcesses()
            if (processNames.isEmpty()) continue

            val appUid = pkgInfo.applicationInfo!!.uid

            for (processName in processNames) {
              val processScope = ProcessScope(processName, appUid)
              newScopes.getOrPut(processScope) { mutableListOf() }.add(module)

              if (modPkg == appPkg) {
                val appId = appUid % PER_USER_RANGE
                userManager?.getRealUsers()?.forEach { user ->
                  val moduleUid = user.id * PER_USER_RANGE + appId
                  if (moduleUid != appUid) {
                    val moduleSelf = ProcessScope(processName, moduleUid)
                    newScopes.getOrPut(moduleSelf) { mutableListOf() }.add(module)
                  }
                }
              }
            }
          }
        }

    // --- ATOMIC STATE SWAP ---
    state = oldState.copy(modules = newModules, scopes = newScopes)

    Log.d(TAG, "Cache Update Complete. Map Swap successful.")
    // Log.d(TAG, "cached modules:")
    // newModules.forEach { (pkg, mod) -> Log.d(TAG, "$pkg ${mod.apkPath}") }

    // Log.d(TAG, "cached scopes:")
    // newScopes.forEach { (ps, modules) ->
    //   Log.d(TAG, "${ps.processName}/${ps.uid}")
    //   modules.forEach { mod -> Log.d(TAG, "\t${mod.packageName}") }
    // }
  }

  fun getModuleScope(packageName: String): MutableList<Application>? {
    if (packageName == "lspd") return null
    val result = mutableListOf<Application>()
    dbHelper.readableDatabase
        .query(
            "scope INNER JOIN modules ON scope.mid = modules.mid",
            arrayOf("app_pkg_name", "user_id"),
            "modules.module_pkg_name = ?",
            arrayOf(packageName),
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            result.add(
                Application().apply {
                  this.packageName = cursor.getString(0)
                  this.userId = cursor.getInt(1)
                })
          }
        }
    return result
  }

  fun getAutoInclude(packageName: String): Boolean {
    if (packageName == "lspd") return false

    var isAutoInclude = false
    dbHelper.readableDatabase
        .query(
            "modules",
            arrayOf("auto_include"),
            "module_pkg_name = ?",
            arrayOf(packageName),
            null,
            null,
            null)
        .use { cursor ->
          if (cursor.moveToFirst()) {
            isAutoInclude = cursor.getInt(0) == 1
          }
        }
    return isAutoInclude
  }

  fun getAutoIncludeModules(): List<String> {
    val result = mutableListOf<String>()
    ConfigCache.dbHelper.readableDatabase
        .query("modules", arrayOf("module_pkg_name"), "auto_include = 1", null, null, null, null)
        .use { cursor ->
          val idx = cursor.getColumnIndexOrThrow("module_pkg_name")
          while (cursor.moveToNext()) {
            val pkgName = cursor.getString(idx)
            if (pkgName != "lspd") result.add(pkgName)
          }
        }
    return result
  }

  fun getModulesForProcess(processName: String, uid: Int): List<Module> {
    ensureCacheReady()
    if (processName == "system_server") {
      Log.w(TAG, "Skip unexpected module queries for $processName")
      return emptyList()
    }
    return state.scopes[ProcessScope(processName, uid)] ?: emptyList()
  }

  fun getModuleByUid(uid: Int): Module? =
      state.modules.values.firstOrNull { it.appId == uid % PER_USER_RANGE }

  fun getModulesForSystemServer(): List<Module> {
    val modules = mutableListOf<Module>()
    if (!android.os.SELinux.checkSELinuxAccess(
        "u:r:system_server:s0", "u:r:system_server:s0", "process", "execmem")) {
      Log.e(TAG, "Skipping system_server injection: sepolicy execmem denied")
      return modules
    }

    val currentState = state

    dbHelper.readableDatabase
        .query(
            "scope INNER JOIN modules ON scope.mid = modules.mid",
            arrayOf("module_pkg_name", "apk_path"),
            "app_pkg_name=? AND enabled=1",
            arrayOf("system"),
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            val pkgName = cursor.getString(0)
            val apkPath = cursor.getString(1)

            val cached = currentState.modules[pkgName]
            if (cached != null) {
              modules.add(cached)
              continue
            }

            val statPath = FileSystem.toGlobalNamespace("/data/user_de/0/$pkgName").absolutePath
            val module =
                Module().apply {
                  packageName = pkgName
                  this.apkPath = apkPath
                  appId = runCatching { Os.stat(statPath).st_uid }.getOrDefault(-1)
                  service = InjectedModuleService(pkgName)
                }

            runCatching {
                  @Suppress("DEPRECATION")
                  val pkg = PackageParser().parsePackage(File(apkPath), 0, false)
                  module.applicationInfo = pkg.applicationInfo
                }
                .onFailure {
                  Log.w(TAG, "PackageParser failed for $apkPath, using fallback ApplicationInfo")
                  module.applicationInfo = ApplicationInfo().apply { packageName = pkgName }
                }

            // Always apply the critical paths manually, even on fallback
            module.applicationInfo?.apply {
              sourceDir = apkPath
              dataDir = statPath
              deviceProtectedDataDir = statPath
              HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(this, statPath)
              processName = pkgName
              uid = module.appId
            }

            FileSystem.loadModule(apkPath, state.isDexObfuscateEnabled)?.let {
              module.file = it
              modules.add(module)
              // We intentionally don't mutate state.modules here. Cache update will catch it.
            }
          }
        }
    return modules
  }

  fun getModuleApkPath(info: ApplicationInfo): String? {
    val apks = mutableListOf<String>()
    info.sourceDir?.let { apks.add(it) }
    info.splitSourceDirs?.let { apks.addAll(it) }

    return apks.firstOrNull { apk ->
      runCatching {
            java.util.zip.ZipFile(apk).use { zip ->
              zip.getEntry("META-INF/xposed/java_init.list") != null ||
                  zip.getEntry("assets/xposed_init") != null
            }
          }
          .getOrDefault(false)
    }
  }

  fun getInstalledModules(): List<ApplicationInfo> {
    val allPackages =
        packageManager?.getInstalledPackagesFromAllUsers(MATCH_ALL_FLAGS, false) ?: emptyList()
    return allPackages
        .mapNotNull { it.applicationInfo }
        .filter { info -> getModuleApkPath(info) != null }
  }

  fun shouldSkipProcess(scope: ProcessScope): Boolean {
    ensureCacheReady()
    return !state.scopes.containsKey(scope)
  }

  fun getPrefsPath(packageName: String, uid: Int): String {
    setupMiscPath()
    val basePath = state.miscPath ?: throw IllegalStateException("Fatal: miscPath not initialized!")

    val userId = uid / PER_USER_RANGE
    val userSuffix = if (userId == 0) "" else userId.toString()
    val path = basePath.resolve("prefs$userSuffix").resolve(packageName)

    val module = state.modules[packageName]
    if (module != null && module.appId == uid % PER_USER_RANGE) {
      runCatching {
            // Ensure the directory exists first
            if (!Files.exists(path)) {
              Files.createDirectories(path)
            }

            Files.walk(path).use { stream ->
              stream.forEach { p ->
                val pathStr = p.toString()

                // Change Owner
                Os.chown(pathStr, uid, uid)

                // Set Permissions using Octal
                // Root folder must be word-readable for monitoring
                val mode =
                    when {
                      p == path -> "755".toInt(8) // Root folder: 755
                      Files.isDirectory(p) -> "711".toInt(8) // Sub-folders: 711
                      else -> "744".toInt(8) // Files: 744
                    }

                Os.chmod(pathStr, mode)
              }
            }
          }
          .onFailure { Log.e(TAG, "Failed to prepare prefs path: $path", it) }
    }
    return path.toString()
  }
}

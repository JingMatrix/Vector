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
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.VectorDaemon
import org.matrix.vector.daemon.ipc.FrameworkService
import org.matrix.vector.daemon.ipc.InjectedModuleService
import org.matrix.vector.daemon.ipc.ModuleAppService
import org.matrix.vector.daemon.system.*
import org.matrix.vector.daemon.utils.InstallerVerifier
import org.matrix.vector.daemon.utils.applySqliteHelperWorkaround
import org.matrix.vector.daemon.utils.getRealUsers

private const val TAG = "VectorConfigCache"

/** Returns app ids whose [LoadedModule] object changed between two published generations. */
internal fun moduleGenerationAppIds(
    oldModules: Map<String, LoadedModule>,
    newModules: Map<String, LoadedModule>,
): Set<Int> {
  val changed = mutableSetOf<Int>()
  oldModules.forEach { (packageName, oldModule) ->
    if (newModules[packageName] !== oldModule && oldModule.appId >= 0) {
      changed += oldModule.appId
    }
  }
  newModules.forEach { (packageName, newModule) ->
    if (oldModules[packageName] !== newModule && newModule.appId >= 0) {
      changed += newModule.appId
    }
  }
  return changed
}

object ConfigCache {
  // Module preference operations are delegated to PreferenceStore
  // Writable operations of modules are delegated to ModuleDatabase

  @Volatile
  var state = DaemonState()
    private set


  // Module package -> the packages it claims, for modules whose module.prop fixes the scope.
  // Absent means the module places no restriction on its scope.
  @Volatile private var staticScopes: Map<String, Set<String>> = emptyMap()

  /** The packages [modulePackage] claims, or null when it does not fix its scope. */
  fun staticScopeOf(modulePackage: String): Set<String>? = staticScopes[modulePackage]

  private val cacheUpdateChannel = Channel<Unit>(Channel.CONFLATED)

  // Held for the whole of a rebuild, so that only one runs at a time. The channel serialises the
  // requests that arrive through it, but not the rebuild the readiness latch starts directly on a
  // binder thread — two could run at once, read the database in different orders, and whichever
  // reached the swap last would publish a module set assembled from a half-written configuration.
  private val rebuildLock = Any()

  init {
    VectorDaemon.scope.launch {
      for (request in cacheUpdateChannel) {
        // Guarded, because the loop *is* the mechanism. A throw escaping here ends the `for`, the
        // coroutine completes, and every later requestCacheUpdate() drops its request into a
        // channel nobody is reading — the configuration silently stops taking effect for the life
        // of the daemon, with one stack trace hours earlier as the only evidence. One bad APK
        // reaching the parser is enough to cause it.
        runCatching { performCacheUpdate() }
            .onFailure { Log.e(TAG, "Cache update failed; the next request will try again", it) }
      }
    }
    applySqliteHelperWorkaround()
  }

  private fun ensureCacheReady() {
    if (!state.isCacheReady && packageManager?.asBinder()?.isBinderAlive == true) {
      // The rebuild lock, not `this`: this is the one place that starts a rebuild without going
      // through the channel, so it has to queue behind a rebuild already in flight rather than
      // merely behind the short swaps that publish one.
      synchronized(rebuildLock) {
        if (!state.isCacheReady) {
          Log.i(TAG, "System services are ready. Mapping modules and scopes.")
          updateManager(false)
          setupMiscPath()
          performCacheUpdate()
          synchronized(this) { state = state.copy(isCacheReady = true) }
        }
      }
    }
  }

  fun updateManager(uninstalled: Boolean) {
    // Resolved and verified outside the lock, then written inside it. The verification opens the
    // manager's APK and checks its signature, which is not work to do while a rebuild waits — but
    // the assignment itself has to be atomic against that rebuild's swap, or whichever finishes
    // last wins and this one silently loses.
    val resolved =
        if (uninstalled) null
        else
            runCatching {
                  val info =
                      packageManager?.getPackageInfoCompat(
                          BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME, 0, 0)
                  val uid = info?.applicationInfo?.uid
                  val installedApkPath = info?.applicationInfo?.sourceDir
                  if (uid == null || installedApkPath == null) {
                    Log.i(TAG, "Manager is not installed")
                    return@runCatching null
                  }

                  InstallerVerifier.verifyInstallerSignature(installedApkPath)
                  Log.i(TAG, "Manager verified and found at UID: $uid")
                  uid
                }
                .getOrNull()

    synchronized(this) { state = state.copy(managerUid = resolved ?: -1) }
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
    synchronized(this) { state = state.copy(miscPath = path) }

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
    synchronized(rebuildLock) {
      if (packageManager == null) return

      Log.d(TAG, "Executing Cache Update...")
      val oldState = state

      val newModules = mutableMapOf<String, LoadedModule>()
      val newStaticScopes = mutableMapOf<String, Set<String>>()

      // Which users actually hold each module, which is what bounds where it may be injected.
      //
      // A module is one package and one APK for the whole device — Android has no way to hold two
      // different builds under one package name — so the configuration keys it by package alone:
      // one enabled flag, one scope set. What genuinely varies per user is whether that package is
      // installed at all, and its uid and data directory when it is. This map is that dimension,
      // and the scope expansion below refuses to cross it.
      val moduleUsers = mutableMapOf<String, Set<Int>>()
      // Deleted from the configuration: the package is not installed for any user, so what it was
      // configured to do cannot mean anything.
      val obsoleteModules = mutableSetOf<String>()
      val obsoletePaths = mutableMapOf<String, String>()
      // Kept in the configuration and reported: enabled, installed, and not loadable. The two
      // used to be one set, so a module whose APK would not parse was quietly un-enabled — the
      // user had asked for it, the switch went off by itself, and nothing said why.
      val unloadable = mutableMapOf<String, Int>()

      ModuleDatabase.enabledModuleRows().forEach { row ->
        val pkgName = row.packageName
        var apkPath = row.apkPath
        if (pkgName == "lspd") return@forEach

        val oldModule = oldState.modules[pkgName]

        var pkgInfo: android.content.pm.PackageInfo? = null
        val users = userManager?.getRealUsers() ?: emptyList()
        // No users means the question could not be asked, not that the answer is no. `getRealUsers`
        // swallows a null binder and any failure of `IUserManager.getUsers` into an empty list, and
        // an empty list here sends *every* enabled module down the not-installed path below — which
        // deletes its row, its scope and its preferences. A transient failure to reach the user
        // service would wipe the configuration of every module on the device.
        if (users.isEmpty()) {
          Log.w(
              TAG, "No users available; skipping this rebuild rather than assuming nothing exists")
          return
        }
        // Every user, not the first one that answers, because which users hold the module is what
        // keeps it out of a user that never installed it.
        //
        // Whether the query answered is not whether this user holds the package. [MATCH_ALL_FLAGS]
        // carries MATCH_ANY_USER and MATCH_UNINSTALLED_PACKAGES, deliberately - answering for
        // every user is what tells "no user has this any more", which deletes the configuration,
        // from "not in this user", which must not. So asking about user 0 for a module only user
        // 11 holds returns the package, and a boundary built on that admitted every user and
        // enforced nothing.
        //
        // Nor is the uid in the answer a discriminator, which is the other thing it looks like: the
        // ApplicationInfo is generated for the user that was *asked about*. Measured on a device,
        // for a module held only by users 11 and 12, the three queries returned 10136, 1110136 and
        // 1210136 - user 0 included, though user 0 does not have it.
        //
        // `isPackageAvailable` is the per-user installed state and answers correctly: false, true,
        // true for the same module, and the exact inverse for one installed only for user 0. It is
        // the same test the target resolution below has always used. Hidden counts as held, because
        // a locked private space hides its apps without ceasing to hold them.
        //
        // A holder wins the ApplicationInfo, so the data directory the module is handed is one
        // that exists; the lowest user id among them, so it stays put as other users come and go.
        var anyPkgInfo: android.content.pm.PackageInfo? = null
        for (user in users.sortedBy { it.id }) {
          val info = packageManager?.getPackageInfoCompat(pkgName, MATCH_ALL_FLAGS, user.id)
          if (info?.applicationInfo == null) continue
          if (anyPkgInfo == null) anyPkgInfo = info
          if (packageManager?.isPackageAvailable(pkgName, user.id, true) != true) continue
          moduleUsers[pkgName] = moduleUsers[pkgName].orEmpty() + user.id
          if (pkgInfo == null) pkgInfo = info
        }
        // Nothing held it, but something answered: still installed somewhere as far as the package
        // manager is concerned, so it is not obsolete and must not have its configuration deleted.
        if (pkgInfo == null) pkgInfo = anyPkgInfo

        // Gone, not broken. No user has this package any more, so the configuration for it is
        // meaningless and is cleaned up. This is the only case that deletes anything.
        if (pkgInfo?.applicationInfo == null) {
          Log.w(TAG, "Failed to find package info of $pkgName")
          obsoleteModules.add(pkgName)
          return@forEach
        }

        val appInfo = pkgInfo.applicationInfo

        if (oldModule != null &&
            appInfo?.sourceDir != null &&
            apkPath != null &&
            oldModule.apkPath != null &&
            FileSystem.toGlobalNamespace(apkPath).exists() &&
            apkPath == oldModule.apkPath &&
            File(appInfo.sourceDir).parent == File(apkPath).parent) {

          // -1 is what `getModulesForSystemServer` leaves behind when it could not stat the
          // module's data directory before the package manager existed. This is the first point at
          // which the real answer is available, so both halves of it are filled in — the appId as
          // well as the ApplicationInfo, which is what identifies the module to itself.
          if (oldModule.appId == -1) {
            oldModule.applicationInfo = appInfo
            oldModule.appId = appInfo.uid % PER_USER_RANGE
          }
          // This path skips re-reading the APK, so what the module claims has to be carried
          // over; the new map replaces the old one wholesale and would otherwise lose it.
          staticScopes[pkgName]?.let { newStaticScopes[pkgName] = it }
          newModules[pkgName] = oldModule
          return@forEach
        }

        val realApkPath = getModuleApkPath(appInfo!!)
        if (realApkPath == null) {
          // Installed, enabled, and not loadable. Deleting the row here would silently un-enable a
          // module the user did enable, and they would find the switch off with no reason given.
          // The configuration stands; what could not be done is recorded and reported instead.
          Log.w(TAG, "Failed to find path of $pkgName")
          unloadable[pkgName] = IManagerService.MODULE_LOAD_NO_APK
          return@forEach
        }
        apkPath = realApkPath
        obsoletePaths[pkgName] = realApkPath

        FileSystem.readStaticScope(apkPath)?.let { newStaticScopes[pkgName] = it }

        when (val loaded = FileSystem.loadModule(apkPath, state.isDexObfuscateEnabled)) {
          is ModuleLoad.Loaded -> {
            val module =
                LoadedModule().apply {
                  packageName = pkgName
                  this.apkPath = apkPath
                  // An app id, as the name says, not the uid it is read from. Every reader compares
                  // it against `someUid % PER_USER_RANGE`, and the raw uid only agreed with that
                  // while the ApplicationInfo came from user 0 — which it did by luck, user 0
                  // being first in the list and answering for packages it does not even hold.
                  // The resolution above now deliberately prefers a *holder*, so for a module only
                  // user 11 has this reads 1110136, and without the modulo the module would fail
                  // its own authentication in `ModuleAppService.ensureModule` against a caller's
                  // 10136 and never be sent its binder.
                  appId = appInfo.uid % PER_USER_RANGE
                  versionCode = pkgInfo.longVersionCode
                  applicationInfo = appInfo
                  service = oldModule?.service ?: InjectedModuleService(pkgName)
                  code = loaded.apk
                }
            newModules[pkgName] = module
          }
          // As above: a module the framework will not load is broken, not unwanted.
          //
          // And of the reasons it will not load, one is not brokenness at all: a module built
          // against libxposed API 100, which this framework refuses outright, is simply old and
          // needs its author to rebuild it. That one the loader names, so it is passed on rather
          // than reported as "the framework could not load it" alongside a zip that will not parse.
          ModuleLoad.UnsupportedApi -> {
            Log.w(TAG, "Could not load $pkgName: it targets libxposed API 100; skipping.")
            unloadable[pkgName] = IManagerService.MODULE_LOAD_UNSUPPORTED_API
          }
          ModuleLoad.Unusable -> {
            Log.w(TAG, "Could not load $pkgName; skipping.")
            unloadable[pkgName] = IManagerService.MODULE_LOAD_UNUSABLE
          }
        }
      }

      if (packageManager?.asBinder()?.isBinderAlive == true) {
        obsoleteModules.forEach { ModuleDatabase.removeModule(it) }
        obsoletePaths.forEach { (pkg, path) -> ModuleDatabase.updateModuleApkPath(pkg, path, true) }
      }

      // Rows can predate the module declaring a fixed scope, or come from an older build that let
      // them in. Dropping them here is what makes the scope actually fixed rather than merely
      // unreachable through the manager, and it runs before the scope table is read below.
      newStaticScopes.forEach { (modulePkg, claimed) ->
        val dropped = ModuleDatabase.pruneScopeToClaimed(modulePkg, claimed)
        if (dropped > 0) {
          Log.i(TAG, "Dropped $dropped app(s) outside the static scope of $modulePkg")
        }
      }

      val newScopes = mutableMapOf<ProcessScope, MutableList<LoadedModule>>()

      // A module can reach the same process by more than one route: self rows in two users each
      // propagate into the other's, and the scope derived below can name a process a row named as
      // well. Twice in the list is twice loaded, so every insertion goes through here.
      fun addToScope(processName: String, uid: Int, module: LoadedModule) {
        val modules = newScopes.getOrPut(ProcessScope(processName, uid)) { mutableListOf() }
        if (modules.none { it === module }) modules.add(module)
      }

      ModuleDatabase.enabledScopeRows().forEach { scopeRow ->
        val appPkg = scopeRow.appPackage
        val modPkg = scopeRow.modulePackage
        val userId = scopeRow.userId

        val module = newModules[modPkg] ?: return@forEach
        val holders = moduleUsers[modPkg].orEmpty()

        if (appPkg == "system") {
          // system_server is one process for the whole device and belongs to no user, so any user
          // holding the module may hook it and the row is stored under user 0 whoever asked. It is
          // the one target the boundary below does not apply to, because there is no second copy
          // of it to keep a module out of.
          if (holders.isNotEmpty()) addToScope("system_server", 1000, module)
          return@forEach
        }

        // The user boundary. A row names one app instance, and reaching it means running the
        // module's code in that user — so a user that never installed the module is not somewhere
        // its rows may take it. A module held only by user 11 stays out of user 0's processes even
        // when a row points at one.
        //
        // Nothing enforced this before. The row was expanded on the strength of the *target*
        // resolving for that user, and the module followed wherever it pointed; a module installed
        // for user 11 alone was observed loading into and hooking a user 0 app.
        if (userId !in holders) return@forEach

        val pkgInfo = packageManager?.getPackageInfoWithComponents(appPkg, MATCH_ALL_FLAGS, userId)
        if (pkgInfo?.applicationInfo == null) return@forEach

        val processNames = pkgInfo.fetchProcesses()
        if (processNames.isEmpty()) return@forEach

        val appUid = pkgInfo.applicationInfo!!.uid

        for (processName in processNames) {
          addToScope(processName, appUid, module)

          // A module in its own scope hooks itself in every user that has it — the copies share
          // one APK, so what one copy hooks in itself the others may expect too. Over the users
          // holding the module rather than over every user on the device: a uid in a user without
          // the package names no process that can ever start, so it was only ever dead weight.
          if (modPkg == appPkg) {
            val appId = appUid % PER_USER_RANGE
            holders.forEach { holder ->
              val moduleUid = holder * PER_USER_RANGE + appId
              if (moduleUid != appUid) addToScope(processName, moduleUid, module)
            }
          }
        }
      }

      // A legacy module reports being active by hooking a method in its own app, so it has to be
      // in its own scope before it can say anything at all. The manager used to add that row on
      // every save and hide it again on read; #796 dropped both halves, and every legacy module
      // has reported itself inactive since (#816).
      //
      // Derived here rather than stored, so a configuration written by those builds needs no
      // repair and nothing that replaces the scope table can drop it again. Legacy is the
      // loader's own verdict, so a module built against API 101 keeps its own process to itself.
      newModules.values
          .filter { it.code?.legacy == true }
          .forEach { module ->
            // The users holding it, for the same reason as the self-scope above: the other users
            // have no copy for the module to report itself active in.
            moduleUsers[module.packageName].orEmpty().forEach { userId ->
              val pkgInfo =
                  packageManager?.getPackageInfoWithComponents(
                      module.packageName, MATCH_ALL_FLAGS, userId) ?: return@forEach
              val moduleUid = pkgInfo.applicationInfo?.uid ?: return@forEach
              pkgInfo.fetchProcesses().forEach { processName ->
                addToScope(processName, moduleUid, module)
              }
            }
          }

      // --- ATOMIC STATE SWAP ---
      //
      // Against the *current* state, not against the copy taken at the top of this function. A
      // rebuild takes tens of milliseconds and makes binder calls throughout, and the other three
      // writers — updateManager, setupMiscPath, the readiness latch — mutate the same field from
      // other threads meanwhile. Writing `oldState.copy(...)` back would revert whichever of them
      // landed during the rebuild: a manager reinstalled mid-rebuild would stop being recognised as
      // the manager, having been recognised a moment earlier.
      //
      // `oldState` is still the right thing to *read* from above: reusing an already-parsed module
      // is a decision about what was loaded when the rebuild started.
      val (publishedModules, changedModuleAppIds) = synchronized(this) {
        // Compare with the state being replaced, not the snapshot from the start of this rebuild.
        // A concurrent writer may have updated the state while package and APK queries ran above.
        val currentModules = state.modules
        val changedAppIds = moduleGenerationAppIds(currentModules, newModules)
        state = state.copy(modules = newModules, scopes = newScopes, unloadable = unloadable)
        // Swapped here rather than sixty lines earlier, so that the claims and the modules they
        // belong to become visible together. Between the two assignments a reader could see the new
        // static scopes against the old module set.
        staticScopes = newStaticScopes
        currentModules to changedAppIds
      }

      if (changedModuleAppIds.isNotEmpty()) {
        // Only the module generations that changed need to release their provider binders. Keeping
        // other modules' deliveries and failure runs avoids re-feeding an unrelated crash loop.
        // A module that is already running may not emit another uid transition, so immediately
        // offer the affected uids a delivery for the newly published generation.
        ModuleAppService.uidClear(changedModuleAppIds).forEach { ModuleAppService.uidStarts(it) }
      }

      Log.d(TAG, "Cache Update Complete. Map Swap successful.")

      // Targets are removed only after the module set has been published.
      (publishedModules.keys - newModules.keys).forEach {
        FrameworkService.forgetHotReloadTargets(it)
      }
      FrameworkService.backfillLoadedVersions()

      // Ask stale opt-in targets to load the generation that was just installed.
      newModules.values.forEach { ModuleAppService.autoHotReload(it) }
      // Log.d(TAG, "cached modules:")
      // newModules.forEach { (pkg, mod) -> Log.d(TAG, "$pkg ${mod.apkPath}") }

      // Log.d(TAG, "cached scopes:")
      // newScopes.forEach { (ps, modules) ->
      //   Log.d(TAG, "${ps.processName}/${ps.uid}")
      //   modules.forEach { mod -> Log.d(TAG, "\t${mod.packageName}") }
      // }
    }
  }

  fun getModulesForProcess(processName: String, uid: Int): List<LoadedModule> {
    ensureCacheReady()
    if (processName == "system_server") {
      Log.w(TAG, "Skip unexpected module queries for $processName")
      return emptyList()
    }
    return state.scopes[ProcessScope(processName, uid)] ?: emptyList()
  }

  fun getModuleByUid(uid: Int): LoadedModule? =
      state.modules.values.firstOrNull { it.appId == uid % PER_USER_RANGE }

  /**
   * A module's device-protected data directory, found by looking rather than by assuming user 0.
   *
   * This runs while system_server is starting, so there is no package manager to ask and the
   * directory the installer made is the only record of the module on disk. A module installed for a
   * secondary user alone has no `/data/user_de/0` entry, so hardcoding that one both left the
   * module's paths pointing at nothing and made its app id -1 — which then travelled into
   * `ApplicationInfo.uid` as the identity of a module about to be loaded into the system server.
   *
   * Lowest user id first, so the owner's copy wins when there is one.
   */
  private fun resolveModuleDataDir(pkgName: String): String? {
    val userDirs = FileSystem.toGlobalNamespace("/data/user_de").listFiles() ?: return null
    return userDirs
        .sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
        .map { FileSystem.toGlobalNamespace("/data/user_de/${it.name}/$pkgName").absolutePath }
        .firstOrNull { runCatching { Os.stat(it) }.isSuccess }
  }

  fun getModulesForSystemServer(): List<LoadedModule> {
    val modules = mutableListOf<LoadedModule>()
    if (!android.os.SELinux.checkSELinuxAccess(
        "u:r:system_server:s0", "u:r:system_server:s0", "process", "execmem")) {
      Log.e(TAG, "Skipping system_server injection: sepolicy execmem denied")
      return modules
    }

    val currentState = state

    ModuleDatabase.systemServerModuleRows().forEach { row ->
          run {
            val pkgName = row.packageName
            // A row with no recorded path has never been resolved; the rebuild will fill it in,
            // and injecting from a null path is not something to attempt in the meantime.
            val apkPath = row.apkPath ?: return@forEach

            val cached = currentState.modules[pkgName]
            if (cached != null) {
              stageNativeLibrariesFor(cached)
              modules.add(cached)
              return@forEach
            }

            val statPath =
                resolveModuleDataDir(pkgName)
                    ?: FileSystem.toGlobalNamespace("/data/user_de/0/$pkgName").absolutePath
            val module =
                LoadedModule().apply {
                  packageName = pkgName
                  this.apkPath = apkPath
                  // An app id, matching what the rebuild stores, so it means the same thing
                  // whichever user's directory answered above.
                  appId =
                      runCatching { Os.stat(statPath).st_uid % PER_USER_RANGE }.getOrDefault(-1)
                  service = InjectedModuleService(pkgName)
                }

                runCatching {
                  @Suppress("DEPRECATION")
                  val pkg = PackageParser().parsePackage(File(apkPath), 0, false)
                  // A raw parse carries no version; backfillLoadedVersions supplies it later.
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

            FileSystem.loadModule(apkPath, state.isDexObfuscateEnabled).apkOrNull?.let {
              module.code = it
              stageNativeLibrariesFor(module)
              modules.add(module)
              // We intentionally don't mutate state.modules here. Cache update will catch it.
            }
          }
        }
    FileSystem.pruneStagedNativeLibraries(
        state.miscPath, modules.mapTo(mutableSetOf()) { it.packageName })
    return modules
  }

  /**
   * Hands a module bound for system_server the copy of its native libraries it can actually map.
   *
   * Only that scope needs one. Every other process may execute straight out of /data/app, so the
   * in-APK entries the loader already builds serve them, and staging for them would buy nothing but
   * disk. A module that ships no library, or whose staging failed, keeps a null here and loads
   * exactly as it did before.
   */
  private fun stageNativeLibrariesFor(module: LoadedModule) {
    val file = module.code ?: return
    // system_server asks for its modules early enough that the cache may not have been built yet,
    // and this is the same reason getPrefsPath does not trust the field either.
    setupMiscPath()
    val misc = state.miscPath ?: return
    file.nativeLibraryDir =
        FileSystem.stageNativeLibraries(misc, module.packageName, module.apkPath)
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

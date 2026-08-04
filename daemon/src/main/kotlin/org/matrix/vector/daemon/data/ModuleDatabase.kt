package org.matrix.vector.daemon.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.matrix.vector.ipc.ScopeEntry
import org.matrix.vector.daemon.system.NotificationManager

private const val TAG = "VectorModuleDatabase"

/**
 * The module configuration: what the user asked for.
 *
 * This is the source of truth, and the only thing here that talks to the database. It answers the
 * questions a manager asks — is this module enabled, what is its scope, does it auto-include — and
 * it performs every write.
 *
 * The distinction from [ConfigCache] is the whole point of splitting them, and getting it wrong has
 * already cost one bug. There are two different notions of "module" in this daemon:
 *
 *  * **Configuration**, here: what was asked for. Cheap, transactional, and it must always answer a
 *    reader with the reader's own writes already applied.
 *  * **Realisation**, in [ConfigCache]: what can actually be loaded into a process right now — the
 *    resolved APK path, the parsed DEX, the app id. Expensive to build (package manager calls, zip
 *    reads and DEX parsing; measured at 39 ms for five modules) and therefore rebuilt off the
 *    binder thread, coalesced, and *eventually* consistent.
 *
 * `enabledModules()` used to be answered from the realisation, which is a configuration question
 * asked of a store that is allowed to lag. The manager enabled a module, read back immediately, and
 * was told the state from before its own write. Every other configuration query already read the
 * database; that one line was the outlier, which is why the symptom was so hard to place.
 *
 * The database handle lives here rather than in the cache so the dependency points the right way:
 * the realisation is derived *from* the configuration, and a config query cannot be written inside
 * the cache without deliberately reaching over here for a database to use.
 */
object ModuleDatabase {

  /** The one database handle. [ConfigCache], [PreferenceStore] and the CLI all borrow it. */
  val dbHelper = Database()

  fun getModuleScope(packageName: String): MutableList<ScopeEntry>? {
    if (packageName == "lspd") return null
    val result = mutableListOf<ScopeEntry>()
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
                ScopeEntry().apply {
                  this.packageName = cursor.getString(0)
                  this.userId = cursor.getInt(1)
                })
          }
        }
    return result
  }

  fun getIncludeNewApps(packageName: String): Boolean {
    if (packageName == "lspd") return false

    var isIncludeNewApps = false
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
            isIncludeNewApps = cursor.getInt(0) == 1
          }
        }
    return isIncludeNewApps
  }

  fun modulesIncludingNewApps(): List<String> {
    val result = mutableListOf<String>()
    dbHelper.readableDatabase
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

  /** One enabled module, as configured. The path is what was last resolved, and may be stale. */
  data class EnabledModuleRow(val packageName: String, val apkPath: String?)

  /** One line of a module's scope: which app, for which user. */
  data class ScopeRow(val appPackage: String, val modulePackage: String, val userId: Int)

  /**
   * The rows [ConfigCache] rebuilds itself from.
   *
   * Returned as data rather than as a cursor so that the SQL stays on this side of the line. The
   * cache turns these into loadable modules — resolving paths, parsing DEX — which is its job; it
   * has no business also knowing the shape of the tables.
   */
  fun enabledModuleRows(): List<EnabledModuleRow> {
    val rows = mutableListOf<EnabledModuleRow>()
    dbHelper.readableDatabase
        .query("modules", arrayOf("module_pkg_name", "apk_path"), "enabled = 1", null, null, null, null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            rows += EnabledModuleRow(cursor.getString(0), cursor.getString(1))
          }
        }
    return rows
  }

  /** Every scope line belonging to an enabled module. */
  fun enabledScopeRows(): List<ScopeRow> {
    val rows = mutableListOf<ScopeRow>()
    dbHelper.readableDatabase
        .query(
            "scope INNER JOIN modules ON scope.mid = modules.mid",
            arrayOf("app_pkg_name", "module_pkg_name", "user_id"),
            "enabled = 1",
            null,
            null,
            null,
            null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            rows += ScopeRow(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
          }
        }
    return rows
  }

  /** Enabled modules scoped to the system framework, with the path last resolved for each. */
  fun systemServerModuleRows(): List<EnabledModuleRow> {
    val rows = mutableListOf<EnabledModuleRow>()
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
            rows += EnabledModuleRow(cursor.getString(0), cursor.getString(1))
          }
        }
    return rows
  }

  /**
   * Which modules are enabled, straight from the database.
   *
   * Deliberately not from [ConfigCache]: that cache is rebuilt asynchronously — a write only
   * *requests* an update through a conflated channel — so anyone asking immediately after enabling
   * a module was told the state from before their own write. The manager does exactly that: it
   * writes, then re-reads to confirm, and the stale answer overwrote what it had correctly recorded
   * itself. The row stayed in the wrong section until the app was restarted, at which point the
   * cache had long since caught up and everything looked fine.
   *
   * The cache is still what the injection path reads, which is what it is for — it carries the
   * resolved apk paths and scopes that this query deliberately does not.
   */
  fun enabledModules(): Array<String> {
    val names = mutableListOf<String>()
    dbHelper.readableDatabase
        .query("modules", arrayOf("module_pkg_name"), "enabled = 1", null, null, null, null)
        .use { cursor ->
          while (cursor.moveToNext()) names += cursor.getString(0)
        }
    return names.toTypedArray()
  }

  fun enableModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val db = dbHelper.writableDatabase
    var changed = false

    // First, check if it exists. If not, we need to "discover" it.
    val exists =
        db.compileStatement("SELECT COUNT(*) FROM modules WHERE module_pkg_name = ?")
            .apply { bindString(1, packageName) }
            .simpleQueryForLong() > 0
    if (!exists) {
      val values =
          ContentValues().apply {
            put("module_pkg_name", packageName)
            put("apk_path", "") // defer to cache updating
            put("enabled", 1)
          }
      // `insert` answers -1 rather than throwing: it catches the SQLException itself and logs one
      // line. Taking that for granted reported a write that never landed as a success, and the
      // caller acted on it — the manager left its switch on, and the shade's "not activated yet"
      // notice was cancelled for a module the database had no row for.
      changed = db.insert("modules", null, values) != -1L
    } else {
      val values = ContentValues().apply { put("enabled", 1) }
      changed = db.update("modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0
    }

    if (changed) {
      ConfigCache.requestCacheUpdate()
      // The shade may be telling the user this module "is not activated yet". It has just been
      // activated, and nothing else was ever going to take that notice down: it is only marked
      // auto-cancel, which fires when it is tapped, and the sole cancel path belonged to the scope
      // prompt. The manager cannot do it either — the AIDL exposes no cancel, and a parasitic
      // manager could not cancel a notification posted as "android" in any case.
      //
      // It lives here, at the data layer, rather than in ManagerService because this function is
      // where the activations that go through the module table converge: the manager's toggle
      // (ManagerService.enableModule), the socket CLI's `modules enable`, a manager backup restore
      // — which replays what it read one setModuleEnabled at a time — and setModuleScope's
      // implicit enable below. Putting it one level up would cover the manager alone and leave the
      // other three lying to the user. Reaching out of the database for it is the same reach
      // requestCacheUpdate() above already makes, and for the same reason — a row changed, and
      // something outside has to be told.
      //
      // Not *every* activation, though, and the exception is worth knowing: the socket CLI's
      // `db restore` copies a whole database file over the live one and calls nothing here, so a
      // module the restored file has enabled keeps a stale "not activated yet" notice in the shade
      // until something touches it again. That is a root-shell command that replaces the
      // configuration wholesale, and the notice is one of several things it does not reconcile.
      NotificationManager.cancelModuleUpdated(packageName)
    }
    return changed
  }

  fun disableModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val values = ContentValues().apply { put("enabled", 0) }
    val changed =
        dbHelper.writableDatabase.update(
            "modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (changed) ConfigCache.requestCacheUpdate()
    return changed
  }

  fun setModuleScope(packageName: String, scope: MutableList<ScopeEntry>): Boolean {
    // Last line of defence for staticScope. The manager, the socket CLI, a backup restore and a
    // module's own requestScope all end up here, so refusing here covers every one of them.
    ConfigCache.staticScopeOf(packageName)?.let { claimed ->
      val beyond = scope.map { it.packageName }.distinct().filterNot { claimed.contains(it) }
      if (beyond.isNotEmpty()) {
        Log.w(TAG, "$packageName fixes its scope; refusing to add ${beyond.joinToString()}")
        return false
      }
    }
    enableModule(packageName)
    val db = dbHelper.writableDatabase
    db.beginTransaction()
    try {
      val mid =
          db.compileStatement("SELECT mid FROM modules WHERE module_pkg_name = ?")
              .apply { bindString(1, packageName) }
              .simpleQueryForLong()
      db.delete("scope", "mid = ?", arrayOf(mid.toString()))

      val values = ContentValues().apply { put("mid", mid) }
      for (app in scope) {
        // A module is one package, one APK and one scope set for the whole device — Android cannot
        // hold two different builds under one package name, so there is nothing here to key by
        // user. What [ScopeEntry.userId] names is the *target*: which installed instance of
        // [ScopeEntry.packageName] this row points at. `ConfigCache` refuses to expand a row whose
        // user does not hold the module, which is what keeps a module installed for one user out of
        // another user's processes.
        //
        // The system server is the exception and is stored against user 0 whoever asked for it: it
        // is one process for the whole device belonging to no user, so a module in a work profile
        // hooking the framework is hooking the same system_server as everyone else.
        //
        // Normalised rather than dropped, which is what this used to do. Dropping meant restoring
        // a backup written by an older manager, which recorded the framework under the module's
        // own user, silently lost the one target the module may have cared about — and said
        // nothing about it.
        val userId = if (app.packageName == "system") 0 else app.userId
        values.put("app_pkg_name", app.packageName)
        values.put("user_id", userId)
        db.insertWithOnConflict("scope", null, values, SQLiteDatabase.CONFLICT_IGNORE)
      }
      db.setTransactionSuccessful()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set scope", e)
      return false
    } finally {
      db.endTransaction()
    }
    ConfigCache.requestCacheUpdate()
    return true
  }

  /**
   * Drops every scope row of [packageName] outside [claimed]. Called from within a cache update, so
   * it deliberately does not ask for another one.
   *
   * @return how many rows went.
   */
  fun pruneScopeToClaimed(packageName: String, claimed: Set<String>): Int {
    val db = dbHelper.writableDatabase
    return runCatching {
          val mid =
              db.compileStatement("SELECT mid FROM modules WHERE module_pkg_name = ?")
                  .apply { bindString(1, packageName) }
                  .simpleQueryForLong()
          val placeholders = claimed.joinToString(",") { "?" }
          if (claimed.isEmpty()) {
            db.delete("scope", "mid = ?", arrayOf(mid.toString()))
          } else {
            db.delete(
                "scope",
                "mid = ? AND app_pkg_name NOT IN ($placeholders)",
                arrayOf(mid.toString()) + claimed.toTypedArray())
          }
        }
        .onFailure { Log.e(TAG, "Failed to prune the scope of $packageName", it) }
        .getOrDefault(0)
  }

  fun removeModuleScope(packageName: String, scopePackageName: String, userId: Int): Boolean {
    if (packageName == "lspd") return false
    // Normalised the same way [setModuleScope] normalises it, rather than refused. The framework is
    // stored against user 0 whoever asked for it, so a removal keyed on the caller's own user
    // matches no row at all - which meant a module outside user 0 could take system scope and then
    // never give it back. It reached that state through the very same call: removeScope returns
    // nothing, so the module was told its request had been honoured.
    val storedUserId = if (scopePackageName == "system") 0 else userId
    val db = dbHelper.writableDatabase
    val mid =
        db.compileStatement("SELECT mid FROM modules WHERE module_pkg_name = ?")
            .apply { bindString(1, packageName) }
            .simpleQueryForLong()
    db.delete(
        "scope",
        "mid = ? AND app_pkg_name = ? AND user_id = ?",
        arrayOf(mid.toString(), scopePackageName, storedUserId.toString()))
    ConfigCache.requestCacheUpdate()
    return true
  }

  fun updateModuleApkPath(packageName: String, apkPath: String?, force: Boolean): Boolean {
    if (apkPath == null || packageName == "lspd") return false
    val values =
        ContentValues().apply {
          put("module_pkg_name", packageName)
          put("apk_path", apkPath)
        }
    val db = dbHelper.writableDatabase
    var count =
        db.insertWithOnConflict("modules", null, values, SQLiteDatabase.CONFLICT_IGNORE).toInt()

    if (count < 0) {
      val cached = ConfigCache.state.modules[packageName]
      if (force || cached == null || cached.apkPath != apkPath) {
        count =
            db.updateWithOnConflict(
                "modules",
                values,
                "module_pkg_name=?",
                arrayOf(packageName),
                SQLiteDatabase.CONFLICT_IGNORE)
      } else count = 0
    }
    if (!force && count > 0) ConfigCache.requestCacheUpdate()
    return count > 0
  }

  fun removeModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val res =
        dbHelper.writableDatabase.delete(
            "modules", "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (res) ConfigCache.requestCacheUpdate()
    return res
  }

  fun setIncludeNewApps(packageName: String, enabled: Boolean): Boolean {
    if (packageName == "lspd") return false

    val values = ContentValues().apply { put("auto_include", if (enabled) 1 else 0) }

    val changed =
        dbHelper.writableDatabase.update(
            "modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0

    // If the auto_include flag changes, we should rebuild the scope cache
    if (changed) {
      ConfigCache.requestCacheUpdate()
    }

    return changed
  }
}

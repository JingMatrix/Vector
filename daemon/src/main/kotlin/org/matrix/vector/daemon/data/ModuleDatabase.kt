package org.matrix.vector.daemon.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.lsposed.lspd.models.Application

private const val TAG = "VectorModuleDatabase"

object ModuleDatabase {

  fun enableModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val db = ConfigCache.dbHelper.writableDatabase
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
      db.insert("modules", null, values)
      changed = true
    } else {
      val values = ContentValues().apply { put("enabled", 1) }
      changed = db.update("modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0
    }

    if (changed) ConfigCache.requestCacheUpdate()
    return changed
  }

  fun disableModule(packageName: String): Boolean {
    if (packageName == "lspd") return false
    val values = ContentValues().apply { put("enabled", 0) }
    val changed =
        ConfigCache.dbHelper.writableDatabase.update(
            "modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (changed) ConfigCache.requestCacheUpdate()
    return changed
  }

  fun setModuleScope(packageName: String, scope: MutableList<Application>): Boolean {
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
    val db = ConfigCache.dbHelper.writableDatabase
    db.beginTransaction()
    try {
      val mid =
          db.compileStatement("SELECT mid FROM modules WHERE module_pkg_name = ?")
              .apply { bindString(1, packageName) }
              .simpleQueryForLong()
      db.delete("scope", "mid = ?", arrayOf(mid.toString()))

      val values = ContentValues().apply { put("mid", mid) }
      for (app in scope) {
        if (app.packageName == "system" && app.userId != 0) continue
        values.put("app_pkg_name", app.packageName)
        values.put("user_id", app.userId)
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
    val db = ConfigCache.dbHelper.writableDatabase
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
    if (packageName == "lspd" || (scopePackageName == "system" && userId != 0)) return false
    val db = ConfigCache.dbHelper.writableDatabase
    val mid =
        db.compileStatement("SELECT mid FROM modules WHERE module_pkg_name = ?")
            .apply { bindString(1, packageName) }
            .simpleQueryForLong()
    db.delete(
        "scope",
        "mid = ? AND app_pkg_name = ? AND user_id = ?",
        arrayOf(mid.toString(), scopePackageName, userId.toString()))
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
    val db = ConfigCache.dbHelper.writableDatabase
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
        ConfigCache.dbHelper.writableDatabase.delete(
            "modules", "module_pkg_name = ?", arrayOf(packageName)) > 0
    if (res) ConfigCache.requestCacheUpdate()
    return res
  }

  fun setAutoInclude(packageName: String, enabled: Boolean): Boolean {
    if (packageName == "lspd") return false

    val values = ContentValues().apply { put("auto_include", if (enabled) 1 else 0) }

    val changed =
        ConfigCache.dbHelper.writableDatabase.update(
            "modules", values, "module_pkg_name = ?", arrayOf(packageName)) > 0

    // If the auto_include flag changes, we should rebuild the scope cache
    if (changed) {
      ConfigCache.requestCacheUpdate()
    }

    return changed
  }
}

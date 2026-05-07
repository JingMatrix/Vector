package org.matrix.vector.daemon.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.apache.commons.lang3.SerializationUtilsX

private const val TAG = "VectorPreferenceStore"

object PreferenceStore {

  fun getModulePrefs(
      packageName: String,
      userId: Int,
      group: String,
      db: SQLiteDatabase = ConfigCache.dbHelper.readableDatabase
  ): Map<String, Any> {
    val result = mutableMapOf<String, Any>()

    db.query(
            "configs",
            arrayOf("`key`", "data"),
            "module_pkg_name = ? AND user_id = ? AND `group` = ?",
            arrayOf(packageName, userId.toString(), group),
            null,
            null,
            null)
        .use { cursor -> // We only close the cursor
          while (cursor.moveToNext()) {
            val key = cursor.getString(0)
            val blob = cursor.getBlob(1)
            val obj = SerializationUtilsX.deserialize<Any>(blob)
            if (obj != null) result[key] = obj
          }
        }
    return result
  }

  fun updateModulePref(moduleName: String, userId: Int, group: String, key: String, value: Any?) {
    updateModulePrefs(moduleName, userId, group, mapOf(key to value))
  }

  fun updateModulePrefs(moduleName: String, userId: Int, group: String, diff: Map<String, Any?>) {
    val db = ConfigCache.dbHelper.writableDatabase
    db.beginTransaction()
    try {
      for ((key, value) in diff) {
        if (value is java.io.Serializable) {
          val values =
              ContentValues().apply {
                put("`group`", group)
                put("`key`", key)
                put("data", SerializationUtilsX.serialize(value))
                put("module_pkg_name", moduleName)
                put("user_id", userId.toString())
              }
          db.insertWithOnConflict("configs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
          db.delete(
              "configs",
              "module_pkg_name=? AND user_id=? AND `group`=? AND `key`=?",
              arrayOf(moduleName, userId.toString(), group, key))
        }
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun deleteModulePrefs(moduleName: String, userId: Int? = null, group: String? = null) {
    val db = ConfigCache.dbHelper.writableDatabase
    val whereClause = StringBuilder("module_pkg_name = ?")
    val whereArgs = mutableListOf(moduleName)

    if (userId != null) {
      whereClause.append(" AND user_id = ?")
      whereArgs.add(userId.toString())
    }

    if (group != null) {
      whereClause.append(" AND `group` = ?")
      whereArgs.add(group)
    }

    db.delete("configs", whereClause.toString(), whereArgs.toTypedArray())
  }

  fun isStatusNotificationEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_status_notification"] as? Boolean ?: true

  fun setStatusNotification(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_status_notification", enabled)

  fun isVerboseLogEnabled(): Boolean =
      getModulePrefs("lspd", 0, "config")["enable_verbose_log"] as? Boolean ?: true

  fun setVerboseLog(enabled: Boolean) =
      updateModulePref("lspd", 0, "config", "enable_verbose_log", enabled)

  fun isScopeRequestBlocked(pkg: String): Boolean =
      (getModulePrefs("lspd", 0, "config")["scope_request_blocked"] as? Set<*>)?.contains(pkg) ==
          true
}

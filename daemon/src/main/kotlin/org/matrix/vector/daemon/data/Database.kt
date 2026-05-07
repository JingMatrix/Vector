package org.matrix.vector.daemon.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import org.matrix.vector.daemon.utils.FakeContext

private const val TAG = "VectorDatabase"
private const val DB_VERSION = 4

class Database(context: Context? = FakeContext()) :
    SQLiteOpenHelper(context, FileSystem.dbPath.absolutePath, null, DB_VERSION) {

  override fun onConfigure(db: SQLiteDatabase) {
    super.onConfigure(db)
    db.setForeignKeyConstraintsEnabled(true)
    db.enableWriteAheadLogging()
    // Improve write performance
    db.execSQL("PRAGMA synchronous=NORMAL;")
  }

  override fun onCreate(db: SQLiteDatabase) {
    Log.i(TAG, "Creating new Vector database")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS modules (
            mid integer PRIMARY KEY AUTOINCREMENT,
            module_pkg_name text NOT NULL UNIQUE,
            apk_path text NOT NULL,
            enabled BOOLEAN DEFAULT 0 CHECK (enabled IN (0, 1)),
            auto_include BOOLEAN DEFAULT 0 CHECK (auto_include IN (0, 1))
        );
        """
            .trimIndent())

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS scope (
            mid integer,
            app_pkg_name text NOT NULL,
            user_id integer NOT NULL,
            PRIMARY KEY (mid, app_pkg_name, user_id),
            CONSTRAINT scope_module_constraint FOREIGN KEY (mid) REFERENCES modules (mid) ON DELETE CASCADE
        );
        """
            .trimIndent())

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS configs (
            module_pkg_name text NOT NULL,
            user_id integer NOT NULL,
            `group` text NOT NULL,
            `key` text NOT NULL,
            data blob NOT NULL,
            PRIMARY KEY (module_pkg_name, user_id, `group`, `key`),
            CONSTRAINT config_module_constraint FOREIGN KEY (module_pkg_name) REFERENCES modules (module_pkg_name) ON DELETE CASCADE
        );
        """
            .trimIndent())

    db.execSQL("CREATE INDEX IF NOT EXISTS configs_idx ON configs (module_pkg_name, user_id);")

    // Insert self
    db.execSQL(
        "INSERT OR IGNORE INTO modules (module_pkg_name, apk_path) VALUES ('lspd', ?)",
        arrayOf(FileSystem.managerApkPath.toString()))
  }

  override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.w(TAG, "Downgrading database from $oldVersion to $newVersion")

    // If it's not the known LSPosed version, wipe and start fresh
    if (oldVersion < 101) {
      Log.i(TAG, "Unknown high version ($oldVersion). Resetting database from scratch.")
      wipeDatabase(db)
      onCreate(db)
      return
    }

    Log.i(TAG, "Detected LSPosed database (v$oldVersion). Starting data migration.")

    // Backup existing database file
    runCatching {
      val backupFile = File(FileSystem.dbPath.parent, "modules_config_lsposed.db")
      FileSystem.dbPath.copyTo(backupFile, overwrite = true)
      Log.i(TAG, "LSPosed backup created: ${backupFile.absolutePath}")
    }

    // Prepare migration by renaming LSPosed tables to avoid name collisions
    val lspTables = listOf("modules", "modules_state", "scope", "module_configs")
    for (table in lspTables) {
      runCatching { db.execSQL("ALTER TABLE `$table` RENAME TO `lsp_$table`;") }
    }

    // Create Vector schema
    onCreate(db)

    // Perform Data Migration
    try {
      // Migrate Modules (merging state from modules_state)
      db.execSQL(
          """
            INSERT OR IGNORE INTO modules (module_pkg_name, apk_path, enabled)
            SELECT m.module_pkg_name, m.apk_path, MAX(COALESCE(s.enabled, 0))
            FROM lsp_modules m
            LEFT JOIN lsp_modules_state s ON m.module_pkg_name = s.module_pkg_name
            GROUP BY m.module_pkg_name;
        """)

      // Migrate Scope (Mapping pkg_name to the new auto-increment 'mid')
      db.execSQL(
          """
            INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id)
            SELECT m.mid, ls.app_pkg_name, ls.user_id
            FROM lsp_scope ls
            JOIN modules m ON ls.module_pkg_name = m.module_pkg_name;
        """)

      // Migrate Configs
      db.execSQL(
          """
            INSERT OR IGNORE INTO configs (module_pkg_name, user_id, `group`, `key`, data)
            SELECT module_pkg_name, user_id, group_name, key_name, data
            FROM lsp_module_configs;
        """)

      Log.i(TAG, "Migration from LSPosed successful.")
    } catch (e: Exception) {
      Log.e(TAG, "Migration failed, resetting to clean state.", e)
      wipeDatabase(db)
      onCreate(db)
    } finally {
      // Cleanup leftover LSPosed tables
      val cleanUp =
          lspTables.map { "lsp_$it" } + listOf("android_metadata", "app_configs", "lspd_configs")
      for (table in cleanUp) {
        runCatching { db.execSQL("DROP TABLE IF EXISTS `$table`;") }
      }
    }
  }

  private fun wipeDatabase(db: SQLiteDatabase) {
    db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            val tableName = cursor.getString(0)
            db.execSQL("DROP TABLE IF EXISTS `$tableName`")
          }
        }
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.i(TAG, "Upgrading database from $oldVersion to $newVersion")
    if (oldVersion < 2) {
      db.execSQL("DROP INDEX IF EXISTS configs_idx;")
      db.execSQL("ALTER TABLE scope RENAME TO old_scope;")
      db.execSQL("ALTER TABLE configs RENAME TO old_configs;")
      onCreate(db) // Recreate tables with strict constraints
      runCatching { db.execSQL("INSERT INTO scope SELECT * FROM old_scope;") }
      runCatching { db.execSQL("INSERT INTO configs SELECT * FROM old_configs;") }
      db.execSQL("DROP TABLE old_scope;")
      db.execSQL("DROP TABLE old_configs;")
    }
    if (oldVersion < 3) {
      db.execSQL("UPDATE scope SET app_pkg_name = 'system' WHERE app_pkg_name = 'android';")
    }
    if (oldVersion < 4) {
      runCatching {
        db.execSQL(
            "ALTER TABLE modules ADD COLUMN auto_include BOOLEAN DEFAULT 0 CHECK (auto_include IN (0, 1));")
      }
    }
  }
}

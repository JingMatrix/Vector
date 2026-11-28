package org.matrix.vector.daemon.utils

import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CursorFactory
import android.os.Build
import hidden.HiddenApiBridge
import java.io.File
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.system.packageManager as sysPackageManager

/** A stub context used by the daemon to initilize database, forge intents and notifications. */
class FakeContext(private val fakePackageName: String = "android") : ContextWrapper(null) {

  companion object {
    @Volatile var nullProvider = false
    private var systemAppInfo: ApplicationInfo? = null
    private var fakeTheme: Resources.Theme? = null
  }

  override fun getPackageName(): String = fakePackageName

  override fun getOpPackageName(): String = "android"

  fun getUserId(): Int {
    return 0
  }

  fun getUser(): android.os.UserHandle {
    return HiddenApiBridge.UserHandle(0)
  }

  override fun getApplicationInfo(): ApplicationInfo {
    if (systemAppInfo == null) {
      systemAppInfo =
          runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  sysPackageManager?.getApplicationInfo("android", 0L, 0)
                } else {
                  sysPackageManager?.getApplicationInfo("android", 0, 0)
                }
              }
              .getOrNull()
              ?.apply {
                // The framework package carries no android:process for some parsers (the archive
                // parser fills in nothing at all), and MediaTek's AsyncDrawableCache dereferences
                // processName unguarded — see FileSystem.resources.
                if (processName == null) processName = "android"
              }
    }
    return systemAppInfo
        ?: ApplicationInfo().apply {
          packageName = fakePackageName
          // A blank info would walk MediaTek's drawable-cache guard straight into the null-base
          // ContextWrapper (getSharedPreferences, getSystemService), so claim the system process
          // to keep putCacheList on its early-return path. The system info above is safe on its
          // own: it carries FLAG_SYSTEM.
          processName = "system"
        }
  }

  override fun getContentResolver(): ContentResolver? {
    return if (nullProvider) null else object : ContentResolver(this) {}
  }

  override fun getTheme(): Resources.Theme {
    if (fakeTheme == null) fakeTheme = resources.newTheme()
    return fakeTheme!!
  }

  override fun getResources(): Resources = FileSystem.resources

  // Required for Android 12+
  override fun getAttributionTag(): String? = null

  override fun getDatabasePath(name: String): File {
    return java.io.File(name) // We pass absolute paths, so just return it directly
  }

  override fun openOrCreateDatabase(
      name: String,
      mode: Int,
      factory: CursorFactory?
  ): SQLiteDatabase {
    return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
  }

  override fun openOrCreateDatabase(
      name: String,
      mode: Int,
      factory: CursorFactory,
      errorHandler: DatabaseErrorHandler?
  ): SQLiteDatabase {
    return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
  }
}

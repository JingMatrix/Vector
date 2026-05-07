package org.matrix.vector.daemon.ipc

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.service.IXposedScopeCallback
import io.github.libxposed.service.IXposedService
import java.io.Serializable
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.models.Module
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.NotificationManager
import org.matrix.vector.daemon.system.PER_USER_RANGE
import org.matrix.vector.daemon.system.activityManager

private const val TAG = "VectorModuleService"

class ModuleService(private val loadedModule: Module) : IXposedService.Stub() {

  companion object {
    private val uidSet = ConcurrentHashMap.newKeySet<Int>()
    private val serviceMap = Collections.synchronizedMap(WeakHashMap<Module, ModuleService>())

    fun uidClear() {
      uidSet.clear()
    }

    fun uidStarts(uid: Int) {
      if (uidSet.add(uid)) {
        val module = ConfigCache.getModuleByUid(uid)
        if (module?.file?.legacy == false) {
          val service = serviceMap.getOrPut(module) { ModuleService(module) }
          service.sendBinder(uid)
        }
      }
    }

    fun uidGone(uid: Int) {
      uidSet.remove(uid)
    }
  }

  /**
   * Forges a ContentProvider call to force the module's target app process to receive this Binder
   * IPC endpoint without standard Context.bindService() limits.
   */
  private fun sendBinder(uid: Int) {
    val name = loadedModule.packageName
    runCatching {
          val userId = uid / PER_USER_RANGE
          val authority = name + AUTHORITY_SUFFIX
          val provider =
              activityManager?.getContentProviderExternal(authority, userId, null, null)?.provider

          if (provider == null) {
            Log.d(TAG, "No service provider for $name")
            return
          }

          val extra = Bundle().apply { putBinder("binder", asBinder()) }
          val reply: Bundle? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                provider.call(
                    AttributionSource.Builder(1000).setPackageName("android").build(),
                    authority,
                    SEND_BINDER,
                    null,
                    extra)
              } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                provider.call("android", null, authority, SEND_BINDER, null, extra)
              } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                provider.call("android", authority, SEND_BINDER, null, extra)
              } else {
                provider.call("android", SEND_BINDER, null, extra)
              }

          if (reply != null) Log.d(TAG, "Sent module binder to $name")
          else Log.w(TAG, "Failed to send module binder to $name")
        }
        .onFailure { Log.w(TAG, "Failed to send module binder for uid $uid", it) }
  }

  private fun ensureModule(): Int {
    val appId = Binder.getCallingUid() % PER_USER_RANGE
    if (loadedModule.appId != appId) {
      throw RemoteException(
          "Module ${loadedModule.packageName} is not for uid ${Binder.getCallingUid()}")
    }
    return Binder.getCallingUid() / PER_USER_RANGE
  }

  override fun getApiVersion() = ensureModule().let { IXposedService.LIB_API }

  override fun getFrameworkName() = ensureModule().let { BuildConfig.FRAMEWORK_NAME }

  override fun getFrameworkVersion() = ensureModule().let { BuildConfig.VERSION_NAME }

  override fun getFrameworkVersionCode() = ensureModule().let { BuildConfig.VERSION_CODE }

  override fun getFrameworkProperties(): Long {
    ensureModule()
    var prop = IXposedService.PROP_CAP_SYSTEM or IXposedService.PROP_CAP_REMOTE
    if (ConfigCache.state.isDexObfuscateEnabled)
        prop = prop or IXposedService.PROP_RT_API_PROTECTION
    return prop
  }

  override fun getScope(): List<String> {
    ensureModule()
    return ConfigCache.getModuleScope(loadedModule.packageName)?.map { it.packageName }
        ?: emptyList()
  }

  override fun requestScope(packages: List<String>, callback: IXposedScopeCallback) {
    val userId = ensureModule()
    if (!PreferenceStore.isScopeRequestBlocked(loadedModule.packageName)) {
      packages.forEach { pkg ->
        NotificationManager.requestModuleScope(loadedModule.packageName, userId, pkg, callback)
      }
    } else {
      callback.onScopeRequestFailed("Scope request blocked by user configuration")
    }
  }

  override fun removeScope(packages: List<String>) {
    val userId = ensureModule()
    packages.forEach { pkg ->
      runCatching { ModuleDatabase.removeModuleScope(loadedModule.packageName, pkg, userId) }
          .onFailure { Log.e(TAG, "Error removing scope for $pkg", it) }
    }
  }

  override fun requestRemotePreferences(group: String): Bundle {
    val userId = ensureModule()
    return Bundle().apply {
      putSerializable(
          "map",
          PreferenceStore.getModulePrefs(loadedModule.packageName, userId, group) as Serializable)
    }
  }

  @Suppress("DEPRECATION")
  override fun updateRemotePreferences(group: String, diff: Bundle) {
    val userId = ensureModule()
    val values = mutableMapOf<String, Any?>()

    diff.getSerializable("delete")?.let { deletes ->
      (deletes as Set<*>).forEach { values[it as String] = null }
    }
    diff.getSerializable("put")?.let { puts ->
      (puts as Map<*, *>).forEach { (k, v) -> values[k as String] = v }
    }

    runCatching {
          PreferenceStore.updateModulePrefs(loadedModule.packageName, userId, group, values)
          (loadedModule.service as? InjectedModuleService)?.onUpdateRemotePreferences(group, diff)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemotePreferences(group: String) {
    PreferenceStore.deleteModulePrefs(loadedModule.packageName, ensureModule(), group)
  }

  override fun listRemoteFiles(): Array<String> {
    val userId = ensureModule()
    return runCatching {
          FileSystem.resolveModuleDir(
                  loadedModule.packageName, "files", userId, Binder.getCallingUid())
              .toFile()
              .list() ?: emptyArray()
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun openRemoteFile(path: String): ParcelFileDescriptor {
    val userId = ensureModule()
    FileSystem.ensureModuleFilePath(path)
    return runCatching {
          val file =
              FileSystem.resolveModuleDir(
                      loadedModule.packageName, "files", userId, Binder.getCallingUid())
                  .resolve(path)
                  .toFile()
          ParcelFileDescriptor.open(
              file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemoteFile(path: String): Boolean {
    val userId = ensureModule()
    FileSystem.ensureModuleFilePath(path)
    return runCatching {
          FileSystem.resolveModuleDir(
                  loadedModule.packageName, "files", userId, Binder.getCallingUid())
              .resolve(path)
              .toFile()
              .delete()
        }
        .getOrElse { throw RemoteException(it.message) }
  }
}

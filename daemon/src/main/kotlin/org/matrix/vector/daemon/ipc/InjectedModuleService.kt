package org.matrix.vector.daemon.ipc

import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.service.IXposedService
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.service.ILSPInjectedModuleService
import org.lsposed.lspd.service.IRemotePreferenceCallback
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.PER_USER_RANGE

private const val TAG = "VectorInjectedModuleService"

class InjectedModuleService(private val packageName: String) : ILSPInjectedModuleService.Stub() {

  // Tracks active RemotePreferenceCallbacks linked by config group
  private val callbacks = ConcurrentHashMap<String, MutableSet<IRemotePreferenceCallback>>()

  override fun getFrameworkProperties(): Long {
    var prop = IXposedService.PROP_CAP_SYSTEM or IXposedService.PROP_CAP_REMOTE
    if (ConfigCache.state.isDexObfuscateEnabled) {
      prop = prop or IXposedService.PROP_RT_API_PROTECTION
    }
    return prop
  }

  override fun requestRemotePreferences(
      group: String,
      callback: IRemotePreferenceCallback?
  ): Bundle {
    val bundle = Bundle()
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    bundle.putSerializable(
        "map", PreferenceStore.getModulePrefs(packageName, userId, group) as Serializable)

    if (callback != null) {
      val groupCallbacks = callbacks.getOrPut(group) { ConcurrentHashMap.newKeySet() }
      groupCallbacks.add(callback)
      runCatching { callback.asBinder().linkToDeath({ groupCallbacks.remove(callback) }, 0) }
          .onFailure { Log.w(TAG, "requestRemotePreferences linkToDeath failed", it) }
    }
    return bundle
  }

  override fun openRemoteFile(path: String): ParcelFileDescriptor {
    FileSystem.ensureModuleFilePath(path)
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    return runCatching {
          val dir = FileSystem.resolveModuleDir(packageName, "files", userId, -1)
          ParcelFileDescriptor.open(dir.resolve(path).toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun getRemoteFileList(): Array<String> {
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    return runCatching {
          val dir = FileSystem.resolveModuleDir(packageName, "files", userId, -1)
          dir.toFile().list() ?: emptyArray()
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  // Called by ModuleService when prefs are updated globally
  fun onUpdateRemotePreferences(group: String, diff: Bundle) {
    val groupCallbacks = callbacks[group] ?: return
    for (callback in groupCallbacks) {
      runCatching { callback.onUpdate(diff) }.onFailure { groupCallbacks.remove(callback) }
    }
  }
}

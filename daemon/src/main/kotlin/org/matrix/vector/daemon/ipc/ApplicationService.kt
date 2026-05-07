package org.matrix.vector.daemon.ipc

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.ILSPApplicationService
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.utils.InstallerVerifier
import org.matrix.vector.daemon.utils.ObfuscationManager

private const val TAG = "VectorAppService"

// Hardcoded transaction code from BridgeService
const val BRIDGE_TRANSACTION_CODE =
    ('_'.code shl 24) or ('V'.code shl 16) or ('E'.code shl 8) or 'C'.code
const val DEX_TRANSACTION_CODE =
    ('_'.code shl 24) or ('D'.code shl 16) or ('E'.code shl 8) or 'X'.code
const val OBFUSCATION_MAP_TRANSACTION_CODE =
    ('_'.code shl 24) or ('O'.code shl 16) or ('B'.code shl 8) or 'F'.code

object ApplicationService : ILSPApplicationService.Stub() {

  data class ProcessKey(val uid: Int, val pid: Int)

  private val processes = ConcurrentHashMap<ProcessKey, ProcessInfo>()

  private class ProcessInfo(val key: ProcessKey, val processName: String, val heartBeat: IBinder) :
      IBinder.DeathRecipient {
    init {
      heartBeat.linkToDeath(this, 0)
      processes[key] = this
    }

    override fun binderDied() {
      heartBeat.unlinkToDeath(this, 0)
      processes.remove(key)
    }
  }

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    when (code) {
      DEX_TRANSACTION_CODE -> {
        val shm = FileSystem.getPreloadDex(ConfigCache.state.isDexObfuscateEnabled) ?: return false
        reply?.writeNoException()
        reply?.let { shm.writeToParcel(it, 0) }
        reply?.writeLong(shm.size.toLong())
        return true
      }
      OBFUSCATION_MAP_TRANSACTION_CODE -> {
        val obfuscation = ConfigCache.state.isDexObfuscateEnabled
        val signatures = ObfuscationManager.getSignatures()
        reply?.writeNoException()
        reply?.writeInt(signatures.size * 2)
        for ((key, value) in signatures) {
          reply?.writeString(key)
          reply?.writeString(if (obfuscation) value else key)
        }
        return true
      }
    }
    return super.onTransact(code, data, reply, flags)
  }

  fun registerHeartBeat(uid: Int, pid: Int, processName: String, heartBeat: IBinder): Boolean {
    return runCatching {
          ProcessInfo(ProcessKey(uid, pid), processName, heartBeat)
          true
        }
        .getOrDefault(false)
  }

  fun hasRegister(uid: Int, pid: Int): Boolean = processes.containsKey(ProcessKey(uid, pid))

  private fun ensureRegistered(): ProcessInfo {
    val key = ProcessKey(getCallingUid(), getCallingPid())
    val info = processes[key]
    if (info == null) {
      Log.w(TAG, "Unauthorized IPC call from uid=${key.uid} pid=${key.pid}")
      throw RemoteException("Not registered")
    }
    return info
  }

  private fun getAllModules(): List<Module> {
    val info = ensureRegistered()
    if (info.key.uid == Process.SYSTEM_UID && info.processName == "system") {
      return ConfigCache.getModulesForSystemServer()
    }
    if (ManagerService.isRunningManager(getCallingPid(), info.key.uid)) {
      return emptyList()
    }
    return ConfigCache.getModulesForProcess(info.processName, info.key.uid)
  }

  override fun getModulesList() = getAllModules().filter { !it.file.legacy }

  override fun getLegacyModulesList() = getAllModules().filter { it.file.legacy }

  override fun isLogMuted(): Boolean = !ManagerService.isVerboseLog

  override fun getPrefsPath(packageName: String): String {
    val info = ensureRegistered()
    return ConfigCache.getPrefsPath(packageName, info.key.uid)
  }

  override fun requestInjectedManagerBinder(
      binderList: MutableList<IBinder>
  ): ParcelFileDescriptor? {
    val info = ensureRegistered()
    val pid = info.key.pid
    val uid = info.key.uid

    if (ManagerService.postStartManager(pid) || ConfigCache.isManager(uid)) {
      binderList.add(ManagerService.obtainManagerBinder(info.heartBeat, pid, uid))
    }

    return runCatching {
          // Verify the APK signature before serving it
          InstallerVerifier.verifyInstallerSignature(FileSystem.managerApkPath.toString())
          ParcelFileDescriptor.open(
              FileSystem.managerApkPath.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        .onFailure { Log.e(TAG, "Failed to open or verify manager APK", it) }
        .getOrNull()
  }
}

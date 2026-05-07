package org.matrix.vector.daemon.system

import android.app.IActivityManager
import android.content.IIntentReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ParceledListSlice
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IUserManager
import android.util.Log
import java.io.File
import java.lang.reflect.Method
import java.util.stream.Collectors
import org.matrix.vector.daemon.utils.getRealUsers

private const val TAG = "VectorSystem"
const val PER_USER_RANGE = 100000
const val MATCH_ANY_USER = 0x00400000 // PackageManager.MATCH_ANY_USER
const val MATCH_ALL_FLAGS =
    PackageManager.MATCH_DISABLED_COMPONENTS or
        PackageManager.MATCH_DIRECT_BOOT_AWARE or
        PackageManager.MATCH_DIRECT_BOOT_UNAWARE or
        PackageManager.MATCH_UNINSTALLED_PACKAGES or
        MATCH_ANY_USER

@Throws(Exception::class)
private fun IPackageManager.getPackageInfoCompatThrows(
    packageName: String,
    flags: Int,
    userId: Int
): PackageInfo? {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getPackageInfo(packageName, flags.toLong(), userId)
  } else {
    getPackageInfo(packageName, flags, userId)
  }
}

fun IPackageManager.getPackageInfoCompat(
    packageName: String,
    flags: Int,
    userId: Int
): PackageInfo? {
  return try {
    getPackageInfoCompatThrows(packageName, flags, userId)
  } catch (e: Exception) {
    null
  }
}

/**
 * Checks if the package is truly available for the given user. Apps can be "installed" but
 * disabled/hidden by profile owners.
 */
fun IPackageManager.isPackageAvailable(
    packageName: String,
    userId: Int,
    ignoreHidden: Boolean
): Boolean {
  return runCatching {
        isPackageAvailable(packageName, userId) ||
            (ignoreHidden && getApplicationHiddenSettingAsUser(packageName, userId))
      }
      .getOrDefault(false)
}

/** Fetches PackageInfo alongside its components (Activities, Services, Receivers, Providers). */
fun IPackageManager.getPackageInfoWithComponents(
    packageName: String,
    flags: Int,
    userId: Int
): PackageInfo? {
  val fullFlags =
      flags or
          PackageManager.GET_ACTIVITIES or
          PackageManager.GET_SERVICES or
          PackageManager.GET_RECEIVERS or
          PackageManager.GET_PROVIDERS

  var pkgInfo: PackageInfo? = null

  try {
    // If the binder buffer overflows, it will throw an exception here.
    pkgInfo = getPackageInfoCompatThrows(packageName, fullFlags, userId)
  } catch (e: Exception) {
    // Fallback path: Fetch sequentially if the initial query threw an Exception
    pkgInfo =
        try {
          getPackageInfoCompatThrows(packageName, flags, userId)
        } catch (ignored: Exception) {
          null
        }

    if (pkgInfo != null) {
      runCatching {
        pkgInfo.activities =
            getPackageInfoCompatThrows(packageName, flags or PackageManager.GET_ACTIVITIES, userId)
                ?.activities
      }
      runCatching {
        pkgInfo.services =
            getPackageInfoCompatThrows(packageName, flags or PackageManager.GET_SERVICES, userId)
                ?.services
      }
      runCatching {
        pkgInfo.receivers =
            getPackageInfoCompatThrows(packageName, flags or PackageManager.GET_RECEIVERS, userId)
                ?.receivers
      }
      runCatching {
        pkgInfo.providers =
            getPackageInfoCompatThrows(packageName, flags or PackageManager.GET_PROVIDERS, userId)
                ?.providers
      }
    }
  }

  if (pkgInfo?.applicationInfo == null) return null
  if (pkgInfo.packageName != "android") {
    val sourceDir = pkgInfo.applicationInfo?.sourceDir
    if (sourceDir == null ||
        !File(sourceDir).exists() ||
        !isPackageAvailable(packageName, userId, true)) {
      return null
    }
  }

  return pkgInfo
}

/** Extracts all unique process names associated with a package's components. */
fun PackageInfo.fetchProcesses(): Set<String> {
  val processNames = mutableSetOf<String>()

  val componentArrays = arrayOf(activities, receivers, providers)
  for (components in componentArrays) {
    components?.forEach { processNames.add(it.processName) }
  }

  services?.forEach { service ->
    // Ignore isolated processes as they shouldn't be hooked in the same way
    if ((service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS) == 0) {
      processNames.add(service.processName)
    }
  }

  return processNames
}

fun IPackageManager.queryIntentActivitiesCompat(
    intent: Intent,
    resolvedType: String?,
    flags: Int,
    userId: Int
): List<ResolveInfo> {
  return runCatching {
        val slice =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              queryIntentActivities(intent, resolvedType, flags.toLong(), userId)
            } else {
              queryIntentActivities(intent, resolvedType, flags, userId)
            }
        slice?.list ?: emptyList()
      }
      .getOrElse {
        Log.e(TAG, "queryIntentActivitiesCompat failed", it)
        emptyList()
      }
}

/** Cached method reference to avoid repeated reflection lookups in loops. */
private val getInstalledPackagesMethod: Method? by lazy {
  val isLongFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
  android.content.pm.IPackageManager::class
      .java
      .declaredMethods
      .find {
        it.name == "getInstalledPackages" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0] ==
                (if (isLongFlags) Long::class.javaPrimitiveType else Int::class.javaPrimitiveType)
      }
      ?.apply { isAccessible = true }
}

/** Reflectively calls getInstalledPackages and casts to ParceledListSlice. */
private fun IPackageManager.getInstalledPackagesReflect(
    flags: Any,
    userId: Int
): List<PackageInfo> {
  val method = getInstalledPackagesMethod ?: return emptyList()
  return runCatching {
        val result = method.invoke(this, flags, userId)
        @Suppress("UNCHECKED_CAST") (result as? ParceledListSlice<PackageInfo>)?.list
      }
      .onFailure { Log.e(TAG, "Reflection call failed", it.cause ?: it) }
      .getOrNull() ?: emptyList()
}

fun IPackageManager.getInstalledPackagesFromAllUsers(
    flags: Int,
    filterNoProcess: Boolean
): List<PackageInfo> {
  val result = mutableListOf<PackageInfo>()
  // Assuming userManager is available in this scope as in original code
  val users = userManager?.getRealUsers() ?: emptyList()

  for (user in users) {
    // We pass flags as Any so the reflective invoke handles Long or Int correctly
    val flagParam: Any =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags.toLong() else flags

    val infos = getInstalledPackagesReflect(flagParam, user.id)
    if (infos.isEmpty()) continue

    val validUserApps =
        infos
            .parallelStream()
            .filter {
              it.applicationInfo != null && (it.applicationInfo!!.uid / PER_USER_RANGE) == user.id
            }
            .filter { isPackageAvailable(it.packageName, user.id, true) }
            .collect(Collectors.toList())

    result.addAll(validUserApps)
  }

  if (filterNoProcess) {
    return result
        .parallelStream()
        .filter {
          getPackageInfoWithComponents(
                  it.packageName, MATCH_ALL_FLAGS, it.applicationInfo!!.uid / PER_USER_RANGE)
              ?.fetchProcesses()
              ?.isNotEmpty() == true
        }
        .collect(Collectors.toList())
  }

  return result
}

fun IActivityManager.registerReceiverCompat(
    receiver: IIntentReceiver,
    filter: IntentFilter,
    requiredPermission: String?,
    userId: Int,
    flags: Int
): Intent? {
  val appThread = SystemContext.appThread ?: return null
  return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          registerReceiverWithFeature(
              appThread,
              "android",
              null,
              "null",
              receiver,
              filter,
              requiredPermission,
              userId,
              flags)
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
          registerReceiverWithFeature(
              appThread, "android", null, receiver, filter, requiredPermission, userId, flags)
        } else {
          registerReceiver(
              appThread, "android", receiver, filter, requiredPermission, userId, flags)
        }
      }
      .onFailure { Log.e(TAG, "registerReceiver failed", it) }
      .getOrNull()
}

fun IActivityManager.broadcastIntentCompat(intent: Intent) {
  val appThread = SystemContext.appThread
  runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          broadcastIntentWithFeature(
              appThread,
              null,
              intent,
              null,
              null,
              0,
              null,
              null,
              null,
              null,
              null,
              -1,
              null,
              true,
              false,
              0)
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
          broadcastIntentWithFeature(
              appThread, null, intent, null, null, 0, null, null, null, -1, null, true, false, 0)
        } else {
          broadcastIntent(
              appThread, intent, null, null, 0, null, null, null, -1, null, true, false, 0)
        }
      }
      .onFailure { Log.e(TAG, "broadcastIntent failed", it) }
}

fun IUserManager.getUserName(userId: Int): String {
  return runCatching { getUserInfo(userId)?.name }.getOrNull() ?: userId.toString()
}

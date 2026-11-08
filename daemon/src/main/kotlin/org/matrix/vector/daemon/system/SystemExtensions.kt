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

/**
 * The first uid handed to an installed app, as `android.os.Process.FIRST_APPLICATION_UID`.
 *
 * Below it are the AID_* uids, which carry no user component and are the same process for the whole
 * device however many users exist. Above it, a uid is `user * PER_USER_RANGE + appId`, so dividing
 * by [PER_USER_RANGE] is only a user test for uids on this side of the line.
 */
const val FIRST_APPLICATION_UID = 10000

/**
 * The Private Compute Core uid range of each user, as `android.os.Process.FIRST_PCC_UID` and
 * `LAST_PCC_UID` (Android 17).
 *
 * Android 17 can run an app's processes in a Private Compute Core sandbox. Such a process belongs
 * to the app — its package, its process names, its data directory — but runs under a uid of its
 * own, the app's uid moved by `FIRST_PCC_UID - FIRST_APPLICATION_UID` into this range (dumpsys
 * shows it as `u0a202p202`). Android System Intelligence runs its main process this way.
 */
const val FIRST_PCC_UID = 30000
const val LAST_PCC_UID = 39999

/**
 * The uid of the app a process belongs to: [uid] itself, or for a Private Compute Core process the
 * app uid it was derived from, as `android.os.Process.getAppUidForPrivateComputeCoreUid`.
 *
 * Scopes are recorded under the app's uid, so a process is looked up by this one.
 */
fun appUidOf(uid: Int): Int {
  val appId = uid % PER_USER_RANGE
  return if (appId in FIRST_PCC_UID..LAST_PCC_UID) uid - (FIRST_PCC_UID - FIRST_APPLICATION_UID)
  else uid
}

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

/**
 * Reflectively calls getInstalledPackages and casts to ParceledListSlice.
 *
 * Null when the query did not happen, which an empty list would flatten into "this user holds
 * nothing" — and the manager builds its whole app list out of this.
 */
private fun IPackageManager.getInstalledPackagesReflect(
    flags: Any,
    userId: Int
): List<PackageInfo>? {
  val method = getInstalledPackagesMethod ?: return null
  return runCatching {
        val result = method.invoke(this, flags, userId)
        @Suppress("UNCHECKED_CAST") (result as? ParceledListSlice<PackageInfo>)?.list
      }
      .onFailure { Log.e(TAG, "Reflection call failed", it.cause ?: it) }
      .getOrNull()
}

fun IPackageManager.getInstalledPackagesFromAllUsers(
    flags: Int,
    filterNoProcess: Boolean
): List<PackageInfo> {
  val result = mutableListOf<PackageInfo>()
  // `getRealUsers` swallows a null binder and any failure into an empty list, and no device has
  // no users, so an empty one is a question that could not be asked rather than an answer.
  // `ConfigCache.rebuild` refuses it for the same reason.
  val users = userManager?.getRealUsers().orEmpty()
  if (users.isEmpty()) throw IllegalStateException("No users to enumerate packages for")

  // Any, so the reflective invoke boxes it as Long or Int to match.
  val flagParam: Any =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) flags.toLong() else flags

  for (user in users) {
    // A user the platform will not answer for fails the whole call, as it did before the Kotlin
    // rewrite: carrying on leaves a list short by a whole profile that nothing downstream can
    // tell from the truth. Reachable on an ordinary device, where enumerating every package per
    // user runs the binder buffer out and answers DeadObjectException.
    val infos =
        getInstalledPackagesReflect(flagParam, user.id)
            ?: throw IllegalStateException("No package list for user ${user.id}")
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

// `startActivityAsUserWithFeature` only arrived in Android R: on older platforms the same call
// exists without the calling feature id, and using the newer one throws `NoSuchMethodError`.
fun IActivityManager.startActivityAsUserCompat(intent: Intent, userId: Int): Int {
  val appThread = SystemContext.appThread
  return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          startActivityAsUserWithFeature(
              appThread,
              "android",
              null,
              intent,
              intent.type,
              null,
              null,
              0,
              0,
              null,
              null,
              userId)
        } else {
          startActivityAsUser(
              appThread, "android", intent, intent.type, null, null, 0, 0, null, null, userId)
        }
      }
      .onFailure { Log.e(TAG, "startActivityAsUser failed", it) }
      .getOrDefault(-1)
}

fun IUserManager.getUserName(userId: Int): String {
  return runCatching { getUserInfo(userId)?.name }.getOrNull() ?: userId.toString()
}

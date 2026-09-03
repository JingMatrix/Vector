package org.matrix.vector.daemon.data

import android.os.Process

/** Pure process matching rules shared by the daemon policy and local unit tests. */
object InlineHookProcessPolicy {
  fun matchesSystemUiVirtualPackage(
      configuredPackages: Set<String>,
      processName: String,
      uid: Int
  ): Boolean =
      SYSTEM_UI_VIRTUAL_PACKAGE in configuredPackages &&
          uid == Process.SYSTEM_UID &&
          processName == SYSTEM_UI_PROCESS

  fun matchesPackage(
      expectedUid: Int,
      actualUid: Int,
      processName: String,
      applicationProcessName: String?,
      componentProcesses: Set<String>
  ): Boolean =
      expectedUid == actualUid &&
          (processName == applicationProcessName || processName in componentProcesses)

  fun mayInvalidate(processName: String, uid: Int): Boolean =
      uid != Process.SYSTEM_UID || processName != "system"
}

package org.matrix.vector.daemon.utils

import android.os.SharedMemory

object ObfuscationManager {
  @JvmStatic external fun obfuscateDex(memory: SharedMemory): SharedMemory

  @JvmStatic external fun getSignatures(): Map<String, String>
}

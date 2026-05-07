package org.matrix.vector.daemon.data

import java.nio.file.Path
import org.lsposed.lspd.models.Module
import org.matrix.vector.daemon.BuildConfig

data class ProcessScope(val processName: String, val uid: Int)

/**
 * An immutable snapshot of the Daemon's state. Any updates will generate a new copy of this class
 * and atomically swap the reference.
 */
data class DaemonState(
    // State non configurable for users
    val isDexObfuscateEnabled: Boolean = !BuildConfig.DEBUG,
    // States initialized after system services are ready
    val isCacheReady: Boolean = false,
    val managerUid: Int = -1,
    val miscPath: Path? = null,
    val modules: Map<String, Module> = emptyMap(),
    val scopes: Map<ProcessScope, List<Module>> = emptyMap(),
)

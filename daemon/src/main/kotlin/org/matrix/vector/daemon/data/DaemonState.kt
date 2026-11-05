package org.matrix.vector.daemon.data

import java.nio.file.Path
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.daemon.BuildConfig

data class ProcessScope(val processName: String, val uid: Int)

/**
 * An immutable snapshot of the Daemon's state. Any updates will generate a new copy of this class
 * and atomically swap the reference.
 */
data class DaemonState(
    /**
     * Whether the legacy API classes are renamed, seeded from
     * [PreferenceStore.isDexObfuscateEnabled] when [ConfigCache] is first touched.
     *
     * The default here is only what a daemon that cannot read its own configuration falls back to.
     * It used to be the entire setting, which is why it was marked as not configurable: the
     * framework dex and every module dex were obfuscated in a release build and not in a debug
     * one, with nothing able to say otherwise.
     */
    val isDexObfuscateEnabled: Boolean = !BuildConfig.DEBUG,
    // States initialized after system services are ready
    val isCacheReady: Boolean = false,
    val managerUid: Int = -1,
    val miscPath: Path? = null,
    val modules: Map<String, LoadedModule> = emptyMap(),
    val scopes: Map<ProcessScope, List<LoadedModule>> = emptyMap(),
    /**
     * Modules the user enabled that the framework could not load, and why.
     *
     * The gap between the two notions of "module" this daemon holds — what was asked for, which
     * lives in the database, and what can actually be loaded, which lives here. They can disagree
     * legitimately: an APK whose path cannot be resolved, or whose DEX will not parse, stays
     * configured and never loads. That difference used to be discarded, and the module simply
     * appeared to be off; it is reported now, because it is the one thing a reader in that
     * situation needs to know.
     */
    val unloadable: Map<String, Int> = emptyMap(),
)

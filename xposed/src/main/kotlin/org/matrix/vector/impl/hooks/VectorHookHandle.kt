package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.error.HookFailedError
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap
import org.matrix.vector.nativebridge.HookBridge

/**
 * What the process remembers about the hooks a module has installed.
 *
 * Keyed by module package name rather than by generation, because both things kept here have to
 * outlive one: an id exists so that new code can name a hook old code installed, and the handle list
 * handed to `onHotReloaded` is by definition the previous generation's.
 */
internal object VectorHookRegistry {

    private data class IdKey(val moduleId: String, val executable: Executable, val id: String)

    private val ids = ConcurrentHashMap<IdKey, VectorHookHandle>()
    private val byModule = ConcurrentHashMap<String, MutableSet<VectorHookHandle>>()
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Serialises everything that decides which handle owns a registration, and is also what a hot
     * reload takes to freeze old code without racing a registration already under way.
     */
    fun lockOf(moduleId: String): Any = locks.computeIfAbsent(moduleId) { Any() }

    fun findId(moduleId: String, origin: Executable, id: String): VectorHookHandle? =
        ids[IdKey(moduleId, origin, id)]

    fun claimId(moduleId: String, origin: Executable, id: String, handle: VectorHookHandle) {
        ids[IdKey(moduleId, origin, id)] = handle
    }

    fun releaseId(moduleId: String, origin: Executable, id: String, handle: VectorHookHandle) {
        ids.remove(IdKey(moduleId, origin, id), handle)
    }

    fun track(moduleId: String, handle: VectorHookHandle) {
        byModule.computeIfAbsent(moduleId) { ConcurrentHashMap.newKeySet() }.add(handle)
    }

    fun forget(moduleId: String, handle: VectorHookHandle) {
        byModule[moduleId]?.remove(handle)
    }

    /**
     * The hooks of [moduleId] that are still installed, for `HotReloadedParam#getOldHookHandles`.
     */
    fun liveHandles(moduleId: String): List<HookHandle> =
        byModule[moduleId]?.filter { it.isLive } ?: emptyList()
}

/**
 * The handle a module holds onto a hook it installed.
 *
 * A handle owns exactly one native record at a time, and a replacement mints a new handle and kills
 * this one - the interface is explicit that "after a successful replacement, this handle is no
 * longer valid", so a superseded handle must not be able to act on the record that replaced it,
 * `unhook()` included.
 *
 * [moduleId] is null for the framework's own hooks, which have no module to scope an id to and
 * nothing that could replace them.
 */
class VectorHookHandle
internal constructor(
    private val origin: Executable,
    private val moduleId: String?,
    initialRecord: VectorHookRecord,
) : HookHandle {

    // An invalid handle can still be retained by a module (including through HotReloadedParam).
    // Keep its public identity, but release the callback and its generation when ownership ends.
    private val id = initialRecord.id
    @Volatile private var record: VectorHookRecord? = initialRecord

    internal val isLive: Boolean
        get() = record != null

    override fun getExecutable(): Executable = origin

    override fun getId(): String? = id

    override fun unhook() {
        if (moduleId == null) {
            synchronized(this) {
                val record = record ?: return
                HookBridge.unhookMethod(true, origin, record)
                this.record = null
            }
            return
        }
        synchronized(VectorHookRegistry.lockOf(moduleId)) {
            // Idempotent, as the interface requires - and a handle that has already been superseded
            // has to stay quiet here rather than tear down its own replacement.
            val record = record ?: return
            HookBridge.unhookMethod(true, origin, record)
            this.record = null
            id?.let { VectorHookRegistry.releaseId(moduleId, origin, it, this) }
            VectorHookRegistry.forget(moduleId, this)
        }
    }

    override fun replaceHook(hooker: Hooker): HookHandle {
        @Suppress("SENSELESS_COMPARISON")
        if (hooker == null) throw IllegalArgumentException("hooker is null")

        val moduleId =
            this.moduleId ?: throw IllegalStateException("This hook does not belong to a module")

        synchronized(VectorHookRegistry.lockOf(moduleId)) {
            val record =
                record ?: throw IllegalStateException("This hook handle is no longer valid")
            // Everything but the hooker is inherited, which is what distinguishes this from
            // registering a new hook that happens to carry the same id.
            return swapLocked(
                VectorHookRecord(hooker, record.priority, record.exceptionMode, record.id)
            )
        }
    }

    /**
     * Puts [replacement] where this handle's record is and hands the registration to a fresh handle.
     * Callers hold this module's lock, and [replacement] must carry this record's id.
     */
    internal fun swapLocked(replacement: VectorHookRecord): VectorHookHandle {
        val moduleId = checkNotNull(moduleId)
        val record = record ?: throw IllegalStateException("This hook handle is no longer valid")
        val handle = VectorHookHandle(origin, moduleId, replacement)
        if (!HookBridge.replaceCallback(true, origin, record, replacement, replacement.priority)) {
            // The record was not where we left it, and nothing was changed, so whatever hook is
            // installed now stays installed.
            throw HookFailedError("Cannot replace the hook on $origin")
        }

        // Only this handle drops the immutable record. Snapshots already handed to running
        // invocations still own it, so their callbacks finish on the generation they entered.
        this.record = null
        VectorHookRegistry.forget(moduleId, this)

        VectorHookRegistry.track(moduleId, handle)
        replacement.id?.let { VectorHookRegistry.claimId(moduleId, origin, it, handle) }
        return handle
    }
}

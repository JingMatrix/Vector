package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.error.HookFailedError
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.matrix.vector.util.Utils
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.nativebridge.HookBridge

/**
 * Builder for configuring and registering hooks. [moduleId] scopes hook ids per module and is null
 * for framework hooks; [frozen] gates registration only, so a retired generation can still unhook.
 */
class VectorHookBuilder(
    private val origin: Executable,
    private val moduleId: String? = null,
    private val frozen: (() -> Boolean)? = null,
    private val defaultExceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
) : HookBuilder {

    private var priority = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode = ExceptionMode.DEFAULT
    private var id: String? = null

    override fun setPriority(priority: Int): HookBuilder = apply { this.priority = priority }

    override fun setExceptionMode(mode: ExceptionMode): HookBuilder = apply {
        this.exceptionMode = mode
    }

    override fun setId(id: String?): HookBuilder = apply { this.id = id }

    override fun intercept(hooker: Hooker): HookHandle {
        ensureNotFrozen()
        if (Modifier.isAbstract(origin.modifiers)) {
            throw IllegalArgumentException(
                "$origin is abstract: it has no body to hook. Hook the concrete override instead."
            )
        } else if (origin.declaringClass.classLoader == VectorHookBuilder::class.java.classLoader) {
            throw IllegalArgumentException(
                "$origin belongs to Vector itself. Hooking the framework would hook the code running your hook."
            )
        } else if (
            origin is Method &&
                origin.declaringClass == Method::class.java &&
                origin.name == "invoke"
        ) {
            throw IllegalArgumentException(
                "Method.invoke cannot be hooked: Vector calls it to run the original of every hooked method, so a hook here would call itself forever."
            )
        } else if (
            origin is Method &&
                origin.declaringClass == Constructor::class.java &&
                origin.name == "newInstance"
        ) {
            throw IllegalArgumentException(
                "Constructor.newInstance cannot be hooked: Vector reflects through it the same way it does Method.invoke, so a hook here would recurse."
            )
        } else if (
            origin is Method &&
                origin.declaringClass == Any::class.java &&
                origin.name == "getClass"
        ) {
            // The compiler, not the framework, is what makes this recurse: since AGP 9 R8 compiles
            // Kotlin null checks into Object.getClass(), so the dispatch calls it entering every
            // hooked method. See #798. If you meant a getClass override on another class, hook that
            // class; Class.getMethods() lists the inherited one, which is not what you want.
            throw IllegalArgumentException(
                "Object.getClass cannot be hooked: Vector's dispatch calls it entering every hooked method, so a hook here would call itself forever."
            )
        }

        val resolvedMode =
            if (exceptionMode == ExceptionMode.DEFAULT) defaultExceptionMode else exceptionMode
        val id = this.id
        val record = VectorHookRecord(hooker, priority, resolvedMode, id)

        // A framework hook. No module, so no id to scope and nothing to serialise against.
        val moduleId = this.moduleId ?: return register(record, null)

        synchronized(VectorHookRegistry.lockOf(moduleId)) {
            // Checked again under the lock a reload takes to freeze old code, so a registration
            // cannot slip in between the check above and the snapshot the successor is handed.
            ensureNotFrozen()

            // Same module, same executable, same id: the interface says this replaces the old hook
            // atomically and invalidates its handle, rather than installing a second one. The
            // replacement carries this builder's priority and exception mode, because it is a new
            // hook - only the handle-based replaceHook is specified to inherit them.
            if (id != null) {
                VectorHookRegistry.findId(moduleId, origin, id)?.takeIf { it.isLive }?.let {
                    return it.swapLocked(record)
                }
            }
            return register(record, moduleId)
        }
    }

    private fun ensureNotFrozen() {
        if (frozen?.invoke() == true) {
            throw IllegalStateException(
                "This module generation has been retired by a hot reload and cannot register hooks"
            )
        }
    }

    /** Installs [record] natively and records the handle against its owner. */
    private fun register(record: VectorHookRecord, moduleId: String?): HookHandle {
        if (
            !HookBridge.hookMethod(
                true,
                origin,
                VectorNativeHooker::class.java,
                record.priority,
                record,
            )
        ) {
            throw HookFailedError("Cannot hook $origin")
        }

        val handle = VectorHookHandle(origin, moduleId, record)
        if (moduleId != null) {
            VectorHookRegistry.track(moduleId, handle)
            record.id?.let { VectorHookRegistry.claimId(moduleId, origin, it, handle) }
        }
        return handle
    }

    companion object {
        /**
         * The hooks [moduleId] currently has installed, which is what a reload hands to the new
         * generation. Taken after old code has been frozen, so nothing can be added to it behind
         * the successor's back.
         */
        fun snapshotHandles(moduleId: String): List<HookHandle> =
            VectorHookRegistry.liveHandles(moduleId)

        /** The lock a reload holds while it freezes old code. */
        fun lockOf(moduleId: String): Any = VectorHookRegistry.lockOf(moduleId)
    }
}

/**
 * The native callback entrypoint. Instantiated natively by [HookBridge] when a hooked method is
 * hit.
 */
class VectorNativeHooker<T : Executable>(private val method: T) {

    private val isStatic = Modifier.isStatic(method.modifiers)
    private val returnType = if (method is Method) method.returnType else null

    /** Invoked by C++ via JNI. */
    fun callback(args: Array<Any?>): Any? {
        val thisObject = if (isStatic) null else args[0]
        val actualArgs = if (isStatic) args else args.sliceArray(1 until args.size)

        // Null means every hook was removed after this trampoline was entered.
        val snapshots =
            HookBridge.callbackSnapshot(VectorHookRecord::class.java, method)
                ?: return invokeOriginalSafely(thisObject, actualArgs)

        @Suppress("UNCHECKED_CAST") val modernHooks = snapshots[0] as Array<VectorHookRecord>
        val legacyHooks = snapshots[1]

        // Fast path: No hooks active
        if (modernHooks.isEmpty() && legacyHooks.isEmpty()) {
            return invokeOriginalSafely(thisObject, actualArgs)
        }

        val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
            val delegate = VectorBootstrap.delegate
            if (legacyHooks.isNotEmpty() && delegate != null) {
                delegate.processLegacyHook(method, tObj, tArgs, legacyHooks) {
                    invokeOriginalSafely(tObj, tArgs)
                }
            } else {
                invokeOriginalSafely(tObj, tArgs)
            }
        }

        val rootChain = VectorChain(method, thisObject, actualArgs, modernHooks, 0, terminal)

        val result = rootChain.proceed()

        // Type safety validation before returning to C++
        if (returnType != null && returnType != Void.TYPE) {
            if (result == null) {
                if (returnType.isPrimitive) {
                    throw NullPointerException(
                        "Hook returned null for a primitive return type: $method"
                    )
                }
            } else {
                // Use the JNI bridge for the most reliable type check across ClassLoaders
                if (
                    !HookBridge.instanceOf(result, returnType) &&
                        !isBoxingCompatible(result, returnType)
                ) {
                    Utils.logD(
                        "Hook return type mismatch. Expected ${returnType.name}, got ${result.javaClass.name}"
                    )
                }
            }
        }

        return result
    }

    /** Handles primitive boxing compatibility (e.g., Integer object vs int primitive). */
    private fun isBoxingCompatible(obj: Any, targetType: Class<*>): Boolean {
        if (!targetType.isPrimitive) return false
        return when (targetType) {
            Int::class.javaPrimitiveType -> obj is Int
            Long::class.javaPrimitiveType -> obj is Long
            Boolean::class.javaPrimitiveType -> obj is Boolean
            Double::class.javaPrimitiveType -> obj is Double
            Float::class.javaPrimitiveType -> obj is Float
            Byte::class.javaPrimitiveType -> obj is Byte
            Char::class.javaPrimitiveType -> obj is Char
            Short::class.javaPrimitiveType -> obj is Short
            else -> false
        }
    }

    /** Safely invokes the original method, unwrapping InvocationTargetExceptions. */
    private fun invokeOriginalSafely(tObj: Any?, tArgs: Array<Any?>): Any? {
        return try {
            HookBridge.invokeOriginalMethod(method, tObj, *tArgs)
        } catch (ite: InvocationTargetException) {
            throw ite.cause ?: ite
        }
    }
}

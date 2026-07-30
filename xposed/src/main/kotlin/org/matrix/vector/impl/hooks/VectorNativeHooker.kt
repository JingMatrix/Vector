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
import org.lsposed.lspd.util.Utils
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.nativebridge.HookBridge

/** Builder for configuring and registering hooks. */
class VectorHookBuilder(
    private val origin: Executable,
    // Framework-internal hooks have no module.prop, and must stay protective: letting one of
    // them propagate would take the boot path down with it.
    private val defaultExceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
) : HookBuilder {

    private var priority = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode = ExceptionMode.DEFAULT

    override fun setPriority(priority: Int): HookBuilder = apply { this.priority = priority }

    override fun setExceptionMode(mode: ExceptionMode): HookBuilder = apply {
        this.exceptionMode = mode
    }

    override fun intercept(hooker: Hooker): HookHandle {
        if (Modifier.isAbstract(origin.modifiers)) {
            throw IllegalArgumentException("Cannot hook abstract methods: $origin")
        } else if (origin.declaringClass.classLoader == VectorHookBuilder::class.java.classLoader) {
            throw IllegalArgumentException("Do not allow hooking inner methods")
        } else if (
            origin is Method &&
                origin.declaringClass == Method::class.java &&
                origin.name == "invoke"
        ) {
            throw IllegalArgumentException("Cannot hook Method.invoke")
        } else if (
            origin is Method &&
                origin.declaringClass == Constructor::class.java &&
                origin.name == "newInstance"
        ) {
            // Named alongside Method.invoke by the API: the framework reflects through both, so
            // hooking either recurses into the hook dispatch.
            throw IllegalArgumentException("Cannot hook Constructor.newInstance")
        }

        // Resolve DEFAULT here rather than at throw time: the record is stored natively and
        // reaches VectorChain with no way back to the module, and module.prop cannot change
        // for the life of the process.
        val resolvedMode =
            if (exceptionMode == ExceptionMode.DEFAULT) defaultExceptionMode else exceptionMode
        val record = VectorHookRecord(hooker, priority, resolvedMode)

        // Register natively. HookBridge now stores VectorHookRecord instead of HookerCallback.
        if (
            !HookBridge.hookMethod(true, origin, VectorNativeHooker::class.java, priority, record)
        ) {
            throw HookFailedError("Cannot hook $origin")
        }

        return object : HookHandle {
            override fun getExecutable(): Executable = origin

            override fun unhook() {
                HookBridge.unhookMethod(true, origin, record)
            }
        }
    }
}

/**
 * The native callback entrypoint. Instantiated natively by [HookBridge] when a hooked method is
 * hit.
 */
class VectorNativeHooker<T : Executable>(@JvmField val method: T) {

    // Read from C++ on the re-entrant path, which cannot afford to call Java to ask.
    @JvmField val isStatic = Modifier.isStatic(method.modifiers)

    private val returnType = if (method is Method) method.returnType else null

    /**
     * The trampoline entry point, called by the generated hook method.
     *
     * Native, and implemented in hook_bridge.cpp, so that the re-entrancy check is genuinely the
     * first thing that runs. A Kotlin body cannot promise that: R8 puts the parameter null check
     * ahead of it, and since AGP 9 that check is a call to Object.getClass(), which a module is
     * allowed to hook. See #798.
     */
    external fun callback(args: Array<Any?>): Any?

    /** The dispatch proper, called by [callback] with the guard raised. */
    fun dispatch(args: Array<Any?>): Any? {
        val thisObject = if (isStatic) null else args[0]
        // Not sliceArray: it copies through Arrays.copyOfRange, and this runs on every dispatch of
        // every hooked method. The element type is known here, so build the array directly.
        val actualArgs =
            if (isStatic) args
            else arrayOfNulls<Any?>(args.size - 1).also { System.arraycopy(args, 1, it, 0, it.size) }

        // Retrieve the hook snapshots. Null means every hook was removed after this trampoline was
        // entered, which is indistinguishable from having none.
        val snapshots =
            HookBridge.callbackSnapshot(VectorHookRecord::class.java, method)
                ?: return callIntoModule { invokeOriginalSafely(thisObject, actualArgs) }

        @Suppress("UNCHECKED_CAST") val modernHooks = snapshots[0] as Array<VectorHookRecord>
        val legacyHooks = snapshots[1]

        // Fast path: No hooks active
        if (modernHooks.isEmpty() && legacyHooks.isEmpty()) {
            return callIntoModule { invokeOriginalSafely(thisObject, actualArgs) }
        }

        // No guard of its own: the chain lowers it before calling here, and VectorTerminal takes a
        // nullable array so this lambda has no null check ahead of its first statement.
        val terminal = VectorTerminal { tObj, tArgs ->
            val actual = tArgs ?: emptyArray()
            val delegate = VectorBootstrap.delegate
            if (legacyHooks.isNotEmpty() && delegate != null) {
                delegate.processLegacyHook(method, tObj, actual, legacyHooks) {
                    invokeOriginalSafely(tObj, actual)
                }
            } else {
                invokeOriginalSafely(tObj, actual)
            }
        }

        val rootChain = VectorChain(method, thisObject, actualArgs, modernHooks, 0, terminal)

        // The chain lowers the guard itself, around the hooker and around the terminal. It cannot
        // be lowered here: the chain's own bookkeeping has to stay guarded, or it dispatches on
        // every node it builds.
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
            HookBridge.invokeOriginalMethod(method, tObj, tArgs)
        } catch (ite: InvocationTargetException) {
            throw ite.cause ?: ite
        }
    }
}

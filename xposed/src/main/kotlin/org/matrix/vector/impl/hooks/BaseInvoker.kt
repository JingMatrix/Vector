package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface.CtorInvoker
import io.github.libxposed.api.XposedInterface.Invoker
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.nativebridge.HookBridge

/**
 * Base implementation of the Invoker system. Handles the resolution of [Invoker.Type] to determine
 * whether to execute the original method directly or to construct a partial interceptor chain.
 */
internal abstract class BaseInvoker<T : Invoker<T, U>, U : Executable>(
    protected val executable: U
) : Invoker<T, U> {

    protected var type: Invoker.Type = Invoker.Type.Chain.FULL

    @Suppress("UNCHECKED_CAST")
    override fun setType(type: Invoker.Type): T {
        this.type = type
        return this as T
    }

    /** Resolves the current [type] and executes the underlying method. */
    protected fun proceedInvocation(thisObject: Any?, args: Array<out Any?>): Any? {
        return when (val currentType = type) {
            // Both paths below already report a target exception wrapped and their own argument
            // failures unwrapped, which is what Invoker#invoke documents.
            is Invoker.Type.Origin -> dispatchOriginal(thisObject, args)
            is Invoker.Type.Chain -> {
                val snapshots =
                    HookBridge.callbackSnapshot(VectorHookRecord::class.java, executable)
                        // The executable carries no hooks, so there is no chain to enter. Invokers
                        // default to Type.Chain.FULL, so this is the ordinary case for a module
                        // that obtains an invoker for a method it has not hooked.
                        ?: return dispatchOriginal(thisObject, args)

                @Suppress("UNCHECKED_CAST")
                val allModernHooks = snapshots[0] as Array<VectorHookRecord>
                val legacyHooks = snapshots[1]

                // Filter hooks to respect the maxPriority requested by the module
                val filteredHooks =
                    allModernHooks.filter { it.priority <= currentType.maxPriority }.toTypedArray()

                // No guard of its own; see VectorNativeHooker.
                val terminal = VectorTerminal { tObj, tArgs ->
                    val actual = tArgs ?: emptyArray()
                    val delegate = VectorBootstrap.delegate
                    if (legacyHooks.isNotEmpty() && delegate != null) {
                        delegate.processLegacyHook(executable, tObj, actual, legacyHooks) {
                            invokeOriginal(tObj, actual)
                        }
                    } else {
                        invokeOriginal(tObj, actual)
                    }
                }

                val chain =
                    VectorChain(executable, thisObject, arrayOf(*args), filteredHooks, 0, terminal)
                // Chain#proceed is documented to hand hookers the exception itself, while
                // Invoker#invoke is documented against Method#invoke, which reports it wrapped, so
                // the wrapping belongs at this boundary rather than inside the chain. The paths
                // that skip the chain get this from the dispatch itself and are not re-wrapped.
                try {
                    chain.proceed()
                } catch (e: InvocationTargetException) {
                    throw e
                } catch (e: Throwable) {
                    throw InvocationTargetException(e)
                }
            }
        }
    }

    /**
     * Invokes the original executable, reporting a target exception as Method#invoke does.
     *
     * Lowered here rather than at the call sites: proceedInvocation raises the guard for its own
     * bookkeeping, and two of its paths reach this directly. Running the original with the guard up
     * would hand it a whole call tree in which no module's hooks fire, silently and with a plausible
     * return value. Type.Origin skips the hooks on *this* executable, not on everything it calls.
     */
    private fun dispatchOriginal(thisObject: Any?, args: Array<out Any?>): Any? = callIntoModule {
        // invokeOriginalMethod dispatches through a cached Method.invoke id. For a hooked
        // executable that is applied to lsplant's backup Method, which is correct. For an
        // executable with no hook item at all it is applied to the reflected object we passed in
        // — and if that is a Constructor, the id belongs to a different class. Route those
        // through the non-virtual path instead, which is what invokeSpecial already uses.
        if (
            executable is Constructor<*> &&
                HookBridge.callbackSnapshot(VectorHookRecord::class.java, executable) == null
        ) {
            requireNotNull(thisObject) {
                "A constructor invoked as a method needs a receiver: $executable"
            }
            return@callIntoModule HookBridge.invokeSpecialMethod(
                executable,
                getExecutableShorty(),
                executable.declaringClass,
                thisObject,
                args,
            )
        }
        HookBridge.invokeOriginalMethod(executable, thisObject, args)
    }

    /**
     * The chain terminal. Chain#proceed is documented to throw whatever the original executable
     * threw, so the reflective wrapper comes off here rather than at the public boundary.
     */
    private fun invokeOriginal(thisObject: Any?, args: Array<out Any?>): Any? =
        try {
            dispatchOriginal(thisObject, args)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }

    /** Helper to generate the JNI shorty for non-virtual special invocations. */
    protected fun getExecutableShorty(): CharArray {
        val parameterTypes = executable.parameterTypes
        val shorty = CharArray(parameterTypes.size + 1)
        shorty[0] = getTypeShorty(if (executable is Method) executable.returnType else Void.TYPE)
        for (i in 1..shorty.lastIndex) {
            shorty[i] = getTypeShorty(parameterTypes[i - 1])
        }
        return shorty
    }

    private fun getTypeShorty(type: Class<*>): Char =
        when (type) {
            Int::class.javaPrimitiveType -> 'I'
            Long::class.javaPrimitiveType -> 'J'
            Float::class.javaPrimitiveType -> 'F'
            Double::class.javaPrimitiveType -> 'D'
            Boolean::class.javaPrimitiveType -> 'Z'
            Byte::class.javaPrimitiveType -> 'B'
            Char::class.javaPrimitiveType -> 'C'
            Short::class.javaPrimitiveType -> 'S'
            Void.TYPE -> 'V'
            else -> 'L'
        }
}

/** Invoker implementation specifically for [Method] types. */
internal class VectorMethodInvoker(method: Method) :
    BaseInvoker<VectorMethodInvoker, Method>(method) {

    override fun invoke(thisObject: Any?, vararg args: Any?): Any? {
        return proceedInvocation(thisObject, args)
    }

    override fun invokeSpecial(thisObject: Any, vararg args: Any?): Any? {
        return HookBridge.invokeSpecialMethod(
            executable,
            getExecutableShorty(),
            executable.declaringClass,
            thisObject,
            args,
        )
    }
}

/**
 * Invoker implementation specifically for [Constructor] types. Extends capabilities to allocate and
 * initialize objects safely.
 */
internal class VectorCtorInvoker<T : Any>(constructor: Constructor<T>) :
    BaseInvoker<CtorInvoker<T>, Constructor<T>>(constructor), CtorInvoker<T> {

    override fun invoke(thisObject: Any?, vararg args: Any?): Any? {
        // Invoking a constructor as a method returns nothing (void/null)
        proceedInvocation(thisObject, args)
        return null
    }

    override fun invokeSpecial(thisObject: Any, vararg args: Any?): Any? {
        HookBridge.invokeSpecialMethod(
            executable,
            getExecutableShorty(),
            executable.declaringClass,
            thisObject,
            args,
        )
        return null
    }

    @Suppress("UNCHECKED_CAST")
    override fun newInstance(vararg args: Any?): T {
        // Allocate memory without invoking <init>
        val obj = HookBridge.allocateObject(executable.declaringClass)
        // Drive the invocation (origin or chain) utilizing the allocated object
        proceedInvocation(obj, args)
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    override fun <U : Any> newInstanceSpecial(subClass: Class<U>, vararg args: Any?): U {
        if (!executable.declaringClass.isAssignableFrom(subClass)) {
            throw IllegalArgumentException(
                "$subClass is not inherited from ${executable.declaringClass}"
            )
        }
        val obj = HookBridge.allocateObject(subClass)
        HookBridge.invokeSpecialMethod(
            executable,
            getExecutableShorty(),
            executable.declaringClass,
            obj,
            args,
        )
        return obj
    }
}

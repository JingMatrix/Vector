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
            is Invoker.Type.Origin -> {
                try {
                    HookBridge.invokeOriginalMethod(executable, thisObject, *args)
                } catch (e: InvocationTargetException) {
                    throw e.cause ?: e
                }
            }
            is Invoker.Type.Chain -> {
                val snapshots =
                    HookBridge.callbackSnapshot(VectorHookRecord::class.java, executable)

                @Suppress("UNCHECKED_CAST")
                val allModernHooks = snapshots[0] as Array<VectorHookRecord>
                val legacyHooks = snapshots[1]

                // Filter hooks to respect the maxPriority requested by the module
                val filteredHooks =
                    allModernHooks.filter { it.priority <= currentType.maxPriority }.toTypedArray()

                val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
                    val delegate = VectorBootstrap.delegate
                    if (legacyHooks.isNotEmpty() && delegate != null) {
                        delegate.processLegacyHook(executable, tObj, tArgs, legacyHooks) {
                            HookBridge.invokeOriginalMethod(executable, tObj, *tArgs)
                        }
                    } else {
                        HookBridge.invokeOriginalMethod(executable, tObj, *tArgs)
                    }
                }

                val chain =
                    VectorChain(executable, thisObject, arrayOf(*args), filteredHooks, 0, terminal)
                chain.proceed()
            }
        }
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
            *args,
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
            *args,
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
            *args,
        )
        return obj
    }
}

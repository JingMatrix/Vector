package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Executable
import org.lsposed.lspd.util.Utils

/** Represents a registered hook configuration, stored natively by [HookBridge]. */
data class VectorHookRecord(
    val hooker: XposedInterface.Hooker,
    val priority: Int,
    val exceptionMode: ExceptionMode,
)

/**
 * Core interceptor chain engine. Manages recursive hook execution and enforces [ExceptionMode]
 * protections.
 */
class VectorChain(
    private val executable: Executable,
    private val thisObj: Any?,
    private val args: Array<Any?>,
    private val hooks: Array<VectorHookRecord>,
    private val hookIndex: Int,
    private val terminal: (thisObj: Any?, args: Array<Any?>) -> Any?,
) : Chain {

    // Tracks if this specific chain node has forwarded execution downstream
    internal var proceedCalled: Boolean = false
        private set

    // Stores the actual result/exception from the rest of the chain/original method
    internal var downstreamResult: Any? = null
    internal var downstreamThrowable: Throwable? = null

    override fun getExecutable(): Executable = executable

    override fun getThisObject(): Any? = thisObj

    override fun getArgs(): List<Any?> = args.toList()

    override fun getArg(index: Int): Any? = args[index]

    override fun proceed(): Any? = internalProceed(thisObj, args)

    override fun proceed(currentArgs: Array<Any?>): Any? = internalProceed(thisObj, currentArgs)

    override fun proceedWith(thisObject: Any): Any? = internalProceed(thisObject, args)

    override fun proceedWith(thisObject: Any, currentArgs: Array<Any?>): Any? =
        internalProceed(thisObject, currentArgs)

    private fun internalProceed(thisObject: Any?, currentArgs: Array<Any?>): Any? {
        proceedCalled = true

        // Reached the end of the modern hooks; trigger the original executable (and legacy hooks)
        if (hookIndex >= hooks.size) {
            return executeDownstream { terminal(thisObject, currentArgs) }
        }

        val record = hooks[hookIndex]
        val nextChain =
            VectorChain(executable, thisObject, currentArgs, hooks, hookIndex + 1, terminal)

        return try {
            executeDownstream { record.hooker.intercept(nextChain) }
        } catch (t: Throwable) {
            handleInterceptorException(t, record, nextChain, thisObject, currentArgs)
        }
    }

    /**
     * Executes the block and caches the downstream state so parent chains can recover it if the
     * current interceptor crashes during post-processing.
     */
    private inline fun executeDownstream(block: () -> Any?): Any? {
        return try {
            val result = block()
            downstreamResult = result
            result
        } catch (t: Throwable) {
            downstreamThrowable = t
            throw t
        }
    }

    /** Handles exceptions thrown by a hooker according to its [ExceptionMode]. */
    private fun handleInterceptorException(
        t: Throwable,
        record: VectorHookRecord,
        nextChain: VectorChain,
        recoveryThis: Any?,
        recoveryArgs: Array<Any?>,
    ): Any? {
        // Check if the exception originated from downstream (lower hooks or original method)
        if (nextChain.proceedCalled && t === nextChain.downstreamThrowable) {
            throw t
        }

        // Passthrough mode does not rescue the process from hooker crashes
        if (record.exceptionMode == ExceptionMode.PASSTHROUGH) {
            throw t
        }

        val hookerName = record.hooker.javaClass.name
        if (!nextChain.proceedCalled) {
            // Crash occurred before calling proceed(); skip hooker and continue the chain
            Utils.logD("Hooker [$hookerName] crashed before proceed. Skipping.", t)
            return nextChain.internalProceed(recoveryThis, recoveryArgs)
        } else {
            // Crash occurred after calling proceed(); suppress and restore downstream state
            Utils.logD("Hooker [$hookerName] crashed after proceed. Restoring state.", t)
            nextChain.downstreamThrowable?.let { throw it }
            return nextChain.downstreamResult
        }
    }
}

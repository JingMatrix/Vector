package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Executable
import java.util.Collections
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

    // Immutable, and a snapshot rather than a view: the chain rewrites this array in place when a
    // hooker calls proceed(args) and when a legacy hook edits its arguments, which would otherwise
    // change a list a hooker is still holding.
    override fun getArgs(): List<Any?> = Collections.unmodifiableList(args.toMutableList())

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
            // Recording the recovery keeps this node's cached state consistent: once the hooker's
            // exception has been suppressed, parent nodes must observe the recovered outcome and
            // not the exception we just swallowed.
            executeDownstream {
                handleInterceptorException(t, record, nextChain, thisObject, currentArgs)
            }
        }
    }

    /**
     * Executes the block and caches the downstream state so parent chains can recover it if the
     * current interceptor crashes during post-processing.
     *
     * Exactly one of [downstreamResult] and [downstreamThrowable] is meaningful after this returns,
     * so both are always written; leaving a stale value behind would let a parent node resurrect an
     * exception this node already handled.
     */
    private inline fun executeDownstream(block: () -> Any?): Any? {
        return try {
            val result = block()
            downstreamResult = result
            downstreamThrowable = null
            result
        } catch (t: Throwable) {
            downstreamResult = null
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

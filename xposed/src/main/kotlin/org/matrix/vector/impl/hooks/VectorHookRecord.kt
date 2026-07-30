package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/** Represents a registered hook configuration, stored natively by [HookBridge]. */
data class VectorHookRecord(
    val hooker: XposedInterface.Hooker,
    val priority: Int,
    val exceptionMode: ExceptionMode,
)

/**
 * What the chain ends in: the original executable, and the legacy callbacks around it.
 *
 * A Java interface rather than a Kotlin function type, so the chain - which is Java, see
 * VectorChain - can name it without dragging in kotlin.jvm.functions.
 *
 * [args] is declared nullable although it never is. A non-null parameter makes kotlinc emit an
 * assertion that R8 compiles into Object.getClass(), which would be the first instruction of the
 * lambda implementing this - reached with the guard already down, since the chain lowers it before
 * calling here. That one call is enough to put the dispatch back into itself. See #798.
 */
fun interface VectorTerminal {
    fun run(thisObject: Any?, args: Array<Any?>?): Any?
}

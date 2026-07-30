package org.matrix.vector.impl.hooks

import org.matrix.vector.nativebridge.HookBridge

/**
 * The two sides of the dispatch guard, and the only place that touches it.
 *
 * The guard answers one question: is the code running right now ours, or someone else's? While the
 * answer is "ours", a hooked method entered from here runs its original instead of dispatching
 * again — which is what stops the dispatch from calling itself when a module hooks something the
 * dispatch happens to call. It does not get to choose what that is: since AGP 9, R8 compiles
 * Kotlin's parameter null checks into Object.getClass(), so any non-private method of ours opens
 * with a call a module is allowed to hook. See #798.
 *
 * The guard is raised by the trampoline before any Java frame exists, so the default answer is
 * "ours". Keeping it correct is then a matter of marking the two kinds of crossing, and the reason
 * they live here rather than at each site is that getting one wrong is invisible until a device
 * hangs: this went through two rounds of exactly that. Nothing outside this file may name
 * [HookBridge.suspendDispatch], [HookBridge.resumeDispatch] or [HookBridge.raiseDispatch], and
 * `:xposed:checkDispatchGuard` fails the build if it does.
 *
 * There are four crossings into module code and no others: a hooker, a module lifecycle callback,
 * a legacy before/after callback, and the original method the chain ends in.
 */
internal inline fun <R> callIntoModule(block: () -> R): R {
    val depth = HookBridge.suspendDispatch()
    try {
        return block()
    } finally {
        HookBridge.resumeDispatch(depth)
    }
}

/**
 * Marks framework code that module code can call into — the API surface handed to modules, and
 * anything reached from a hooker.
 *
 * These entry points are reached with the guard already down, so they have to put it back up rather
 * than assume it holds.
 */
internal inline fun <R> enterFramework(block: () -> R): R {
    val depth = HookBridge.raiseDispatch()
    try {
        return block()
    } finally {
        HookBridge.resumeDispatch(depth)
    }
}

/**
 * The same two, for the legacy bridge, which is Java and cannot use the inline forms.
 *
 * Named apart from them on purpose. Sharing the name made the lambda SAM-convert to [Body] and the
 * member overload win over the top-level function, so each of these called itself: the primitives
 * were never reached, and every legacy hook died of a StackOverflowError on its first invocation.
 * The source read correctly; only the bytecode showed it.
 */
object DispatchGuard {

    fun interface Body<R> {
        fun run(): R
    }

    /**
     * The bare primitives, for Java code on the dispatch path that cannot afford the lambda a
     * [Body] would allocate on every call. Pair each with [restore] in a finally.
     */
    @JvmStatic fun raise(): Int = HookBridge.raiseDispatch()

    @JvmStatic fun lower(): Int = HookBridge.suspendDispatch()

    @JvmStatic fun restore(depth: Int) = HookBridge.resumeDispatch(depth)

    @JvmStatic
    fun <R> intoModule(body: Body<R>): R {
        val depth = HookBridge.suspendDispatch()
        try {
            return body.run()
        } finally {
            HookBridge.resumeDispatch(depth)
        }
    }

    @JvmStatic
    fun <R> intoFramework(body: Body<R>): R {
        val depth = HookBridge.raiseDispatch()
        try {
            return body.run()
        } finally {
            HookBridge.resumeDispatch(depth)
        }
    }
}

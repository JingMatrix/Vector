package org.matrix.vector.impl.hooks;

import io.github.libxposed.api.XposedInterface;

import org.lsposed.lspd.util.Utils;

import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Core interceptor chain engine. Manages recursive hook execution and enforces
 * {@link XposedInterface.ExceptionMode} protections.
 *
 * <p>Java rather than Kotlin, and deliberately so. This is the surface the API hands to modules, so
 * every method here is entered from module code with the dispatch guard down, and the guard cannot
 * cover a callee's prologue. In Kotlin, R8 opens each of these with a null check on the parameters,
 * compiled since AGP 9 into {@code Object.getClass()} — a call a module is allowed to hook, made
 * before any statement of ours can raise the guard. A hooker that rebuilds its arguments then
 * re-enters twice per frame, and the nesting cap bounds a tree rather than a chain. The parameter
 * types come from a {@code @NonNull} Java interface and cannot be made nullable, so the only way to
 * not emit the checks was to not write this in Kotlin. javac emits none. See #798.
 */
public final class VectorChain implements XposedInterface.Chain {

    private final Executable executable;
    private final Object thisObj;
    private final Object[] args;
    private final VectorHookRecord[] hooks;
    private final int hookIndex;
    private final VectorTerminal terminal;

    /** Whether this node has forwarded execution downstream. */
    boolean proceedCalled = false;

    /** The outcome of the rest of the chain, cached so a parent can recover it. */
    Object downstreamResult = null;
    Throwable downstreamThrowable = null;

    public VectorChain(
            Executable executable,
            Object thisObj,
            Object[] args,
            VectorHookRecord[] hooks,
            int hookIndex,
            VectorTerminal terminal) {
        this.executable = executable;
        this.thisObj = thisObj;
        this.args = args;
        this.hooks = hooks;
        this.hookIndex = hookIndex;
        this.terminal = terminal;
    }

    @Override
    public Executable getExecutable() {
        return executable;
    }

    @Override
    public Object getThisObject() {
        return thisObj;
    }

    /**
     * Immutable, and a snapshot rather than a view: the chain rewrites the argument array in place
     * when a hooker calls proceed(args) and when a legacy hook edits its arguments, which would
     * otherwise change a list a hooker is still holding.
     */
    @Override
    public List<Object> getArgs() {
        return Collections.unmodifiableList(Arrays.asList(args.clone()));
    }

    @Override
    public Object getArg(int index) {
        return args[index];
    }

    @Override
    public Object proceed() throws Throwable {
        return internalProceed(thisObj, args);
    }

    @Override
    public Object proceed(Object[] currentArgs) throws Throwable {
        return internalProceed(thisObj, currentArgs);
    }

    @Override
    public Object proceedWith(Object thisObject) throws Throwable {
        return internalProceed(thisObject, args);
    }

    @Override
    public Object proceedWith(Object thisObject, Object[] currentArgs) throws Throwable {
        return internalProceed(thisObject, currentArgs);
    }

    /**
     * Lowers the guard for everything below it, and raises nothing.
     *
     * Being Java, the bookkeeping here calls nothing a module can hook: a constructor, an array
     * read, two field writes. So it needs no protection of its own, and one lower covers both calls
     * that leave the framework — the hooker, and the terminal that reaches the original.
     */
    private Object internalProceed(Object thisObject, Object[] currentArgs) throws Throwable {
        int outer = DispatchGuard.lower();
        try {
            proceedCalled = true;

            // Reached the end of the modern hooks; run the original (and the legacy hooks).
            if (hookIndex >= hooks.length) {
                try {
                    Object result = terminal.run(thisObject, currentArgs);
                    downstreamResult = result;
                    downstreamThrowable = null;
                    return result;
                } catch (Throwable t) {
                    downstreamResult = null;
                    downstreamThrowable = t;
                    throw t;
                }
            }

            VectorHookRecord record = hooks[hookIndex];
            VectorChain nextChain =
                    new VectorChain(
                            executable, thisObject, currentArgs, hooks, hookIndex + 1, terminal);

            try {
                Object result = record.getHooker().intercept(nextChain);
                downstreamResult = result;
                downstreamThrowable = null;
                return result;
            } catch (Throwable t) {
                // Recording the recovery keeps this node's cached state consistent: once the
                // hooker's exception has been suppressed, parent nodes must observe the recovered
                // outcome and not the exception we just swallowed.
                try {
                    Object result =
                            handleInterceptorException(
                                    t, record, nextChain, thisObject, currentArgs);
                    downstreamResult = result;
                    downstreamThrowable = null;
                    return result;
                } catch (Throwable t2) {
                    downstreamResult = null;
                    downstreamThrowable = t2;
                    throw t2;
                }
            }
        } finally {
            DispatchGuard.restore(outer);
        }
    }

    /** Handles exceptions thrown by a hooker according to its ExceptionMode. */
    private Object handleInterceptorException(
            Throwable t,
            VectorHookRecord record,
            VectorChain nextChain,
            Object recoveryThis,
            Object[] recoveryArgs)
            throws Throwable {
        // The exception came from downstream — a lower hook or the original method.
        if (nextChain.proceedCalled && t == nextChain.downstreamThrowable) {
            throw t;
        }

        // Passthrough mode does not rescue the process from hooker crashes.
        if (record.getExceptionMode() == XposedInterface.ExceptionMode.PASSTHROUGH) {
            throw t;
        }

        String hookerName = record.getHooker().getClass().getName();
        if (!nextChain.proceedCalled) {
            // Crashed before calling proceed(); skip the hooker and continue the chain.
            Utils.logD("Hooker [" + hookerName + "] crashed before proceed. Skipping.", t);
            return nextChain.internalProceed(recoveryThis, recoveryArgs);
        }
        // Crashed after calling proceed(); suppress it and restore the downstream state.
        Utils.logD("Hooker [" + hookerName + "] crashed after proceed. Restoring state.", t);
        if (nextChain.downstreamThrowable != null) {
            throw nextChain.downstreamThrowable;
        }
        return nextChain.downstreamResult;
    }
}

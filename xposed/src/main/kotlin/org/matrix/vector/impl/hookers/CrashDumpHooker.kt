package org.matrix.vector.impl.hookers

import io.github.libxposed.api.XposedInterface
import org.lsposed.lspd.util.Utils

/**
 * Intercepts uncaught exceptions in the framework to provide diagnostic logging before the process
 * completely terminates.
 */
object CrashDumpHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        try {
            val throwable = chain.args.firstOrNull() as? Throwable
            if (throwable != null) {
                Utils.logE("Crash unexpectedly", throwable)
            }
        } catch (ignored: Throwable) {}
        return chain.proceed()
    }
}

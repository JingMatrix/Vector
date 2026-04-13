package org.matrix.vector.impl.hookers

import io.github.libxposed.api.XposedInterface
import org.matrix.vector.impl.di.VectorBootstrap

/**
 * Intercepts the early ApplicationThread attachment phase. Triggers the legacy compatibility layer
 * to load modern modules into the process.
 */
object AppAttachHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        // Execute the actual attach() method first
        val result = chain.proceed()

        // Delegate legacy module loading via DI
        val activityThread = chain.thisObject
        if (activityThread != null) {
            VectorBootstrap.withLegacy { delegate -> delegate.loadModules(activityThread) }
        }

        return result
    }
}

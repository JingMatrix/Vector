package org.matrix.vector.impl.hookers

import android.os.Build
import io.github.libxposed.api.XposedInterface
import org.matrix.vector.nativebridge.HookBridge

/**
 * Intercepts DEX file parsing to dynamically mark our framework's ClassLoader as trusted. Prevents
 * ART from blocking reflective access by the hooking engine.
 */
object DexTrustHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()

        var classLoader = chain.args.filterIsInstance<ClassLoader>().firstOrNull()
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && classLoader == null) {
            classLoader = DexTrustHooker::class.java.classLoader
        }

        while (classLoader != null) {
            if (classLoader === DexTrustHooker::class.java.classLoader) {
                // Inform the native bridge that this DEX cookie is safe
                HookBridge.setTrusted(result)
                break
            }
            classLoader = classLoader.parent
        }

        return result
    }
}

package org.matrix.vector.impl.core

import java.lang.reflect.Executable
import org.lsposed.lspd.util.Utils
import org.matrix.vector.nativebridge.HookBridge

/**
 * Engine responsible for scanning and deoptimizing prebuilt framework methods. Ensures that hooks
 * placed on heavily inlined methods are respected by the ART runtime.
 */
object VectorDeopter {

    private const val TAG = "VectorDeopter"

    @JvmStatic
    fun deoptMethods(where: String, cl: ClassLoader?) {
        val targets = VectorInlinedCallers.get(where)
        if (targets.isEmpty()) return

        val searchClassLoader = cl ?: ClassLoader.getSystemClassLoader()

        for (target in targets) {
            runCatching {
                    val clazz = Class.forName(target.className, false, searchClassLoader)
                    val executable: Executable =
                        if (target.isConstructor) {
                            clazz.getDeclaredConstructor(*target.params)
                        } else {
                            clazz.getDeclaredMethod(target.methodName, *target.params)
                        }

                    // Allow access if restricted and pass to the native bridge
                    executable.isAccessible = true
                    HookBridge.deoptimizeMethod(executable)
                }
                .onFailure {
                    Utils.Log.v(
                        TAG,
                        "Skipping deopt for ${target.className}#${target.methodName}: ${it.message}",
                    )
                }
        }
    }

    fun deoptBootMethods() {
        deoptMethods(VectorInlinedCallers.KEY_BOOT_IMAGE, null)
    }

    @JvmStatic
    fun deoptResourceMethods() {
        if (Utils.isMIUI) {
            deoptMethods(VectorInlinedCallers.KEY_BOOT_IMAGE_MIUI_RES, null)
        }
    }

    fun deoptSystemServerMethods(sysCL: ClassLoader) {
        deoptMethods(VectorInlinedCallers.KEY_SYSTEM_SERVER, sysCL)
    }
}

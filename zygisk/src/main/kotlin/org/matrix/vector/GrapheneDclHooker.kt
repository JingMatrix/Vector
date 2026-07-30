package org.matrix.vector

import android.content.pm.ApplicationInfo
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.lsposed.lspd.util.Utils

/**
 * Exempts the parasitic manager's host package from GrapheneOS's "Restrict dynamic code loading"
 * (memory DCL) exploit protection.
 *
 * The setting is immutable and enabled for system apps. As the manager runs inside
 * [BuildConfig.InjectedPackageName] (`com.android.shell`, a system app), it cannot load its DEX
 * and fails to start. The hook forces the restriction to "allowed" for that package alone; every
 * other app retains GrapheneOS's verdict. It applies only on GrapheneOS, where the target class
 * exists, and only in system_server, where the value is resolved.
 */
object GrapheneDclHooker {

    private const val ASW_CLASS = "android.ext.settings.app.AswRestrictMemoryDynCodeLoading"

    @JvmStatic
    fun start() {
        val aswClass =
            try {
                XposedHelpers.findClass(ASW_CLASS, this.javaClass.classLoader)
            } catch (_: XposedHelpers.ClassNotFoundError) {
                return // Not GrapheneOS.
            }

        try {
            // Boolean getImmutableValue(Context, int, ApplicationInfo, GosPackageState, StateInfo)
            // returns true (restricted), false (allowed), or null (user-configurable). A non-null
            // result overrides the user toggle and the default, so false forces DCL to be allowed.
            XposedBridge.hookAllMethods(
                aswClass,
                "getImmutableValue",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam<*>): Any? {
                        val appInfo = param.args.getOrNull(2) as? ApplicationInfo
                        if (appInfo?.packageName == BuildConfig.InjectedPackageName) {
                            return false // Allow the manager host to load its DEX.
                        }
                        return XposedBridge.invokeOriginalMethod(
                            param.method,
                            param.thisObject,
                            param.args,
                        )
                    }
                },
            )
        } catch (e: Throwable) {
            Utils.logE("Failed to patch GrapheneOS DCL restriction", e)
        }
    }
}

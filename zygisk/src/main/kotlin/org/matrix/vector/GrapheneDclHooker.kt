package org.matrix.vector

import android.content.pm.ApplicationInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.lsposed.lspd.util.Utils

/**
 * Exempts the parasitic manager's host package from GrapheneOS's "Restrict dynamic code loading"
 * exploit protections.
 *
 * GrapheneOS splits DCL into three settings — memory, storage and WebView — and enforces each one
 * through *two* independent channels, both fed by the same `AswRestrict*DynCodeLoading` verdict:
 *
 *  1. **ART DexFile checks.** `DynCodeLoading.getAppBindFlags` reads the three settings, ships the
 *     bitmask to the app and arms `DexFile.enableDynCodeLoadingChecks`, which rejects the manager's
 *     transplanted `/proc/self/fd/N` DEX with a "DCL via storage" `SecurityException`.
 *  2. **Kernel `grapheneos_flags`.** `SELinuxFlags.get` turns the same settings into the
 *     `DENY_EXECMEM`/`DENY_EXECMOD`/… task flags, written to `/proc/self/attr/grapheneos_flags`
 *     during zygote specialization (writable only from the zygote context, so it cannot be undone
 *     later). With `DENY_EXECMEM` set, LSPlant/Dobby cannot allocate executable memory for its
 *     inline hook and the process aborts with `SIGSEGV` / `TSEC_FLAG_DENY_EXECMEM: op denied`.
 *
 * As the manager runs inside [BuildConfig.InjectedPackageName] (`com.android.shell`, a system app)
 * these verdicts are immutable and enabled. Clearing only channel 1 (e.g. zeroing `getAppBindFlags`)
 * lets the DEX load but leaves `DENY_EXECMEM` armed, so hooking still crashes.
 *
 * Both channels call `AswRestrict*DynCodeLoading.I.get()`, which returns the immutable verdict when
 * `getImmutableValue` is non-null. Forcing that method to `false` for the manager host alone marks
 * DCL as allowed at the source, clearing both channels at once. Every other app keeps GrapheneOS's
 * verdict. It applies only on GrapheneOS, where these classes exist, and only in system_server,
 * where the settings are resolved before the process is specialized.
 */
object GrapheneDclHooker {

    private val ASW_DCL_CLASSES =
        arrayOf(
            "android.ext.settings.app.AswRestrictMemoryDynCodeLoading",
            "android.ext.settings.app.AswRestrictStorageDynCodeLoading",
            "android.ext.settings.app.AswRestrictWebViewDynCodeLoading",
        )

    @JvmStatic
    fun start() {
        // Boolean getImmutableValue(Context, int userId, ApplicationInfo, GosPackageState, StateInfo)
        // returns true (immutable, restricted) for every system app. Returning false instead marks
        // DCL as allowed for the manager host, so neither the DexFile checks nor the kernel
        // execmem/execmod flags derived from the same verdict are armed.
        val exempt =
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val appInfo = param.args.getOrNull(2) as? ApplicationInfo
                    if (appInfo?.packageName == BuildConfig.InjectedPackageName) {
                        param.result = false
                    }
                }
            }

        for (className in ASW_DCL_CLASSES) {
            val aswClass =
                try {
                    XposedHelpers.findClass(className, this.javaClass.classLoader)
                } catch (_: XposedHelpers.ClassNotFoundError) {
                    return // Not GrapheneOS.
                }

            try {
                XposedBridge.hookAllMethods(aswClass, "getImmutableValue", exempt)
            } catch (e: Throwable) {
                Utils.logE("Failed to patch GrapheneOS DCL restriction ($className)", e)
            }
        }
    }
}

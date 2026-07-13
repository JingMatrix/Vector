package org.matrix.vector.core

import android.content.pm.ApplicationInfo
import android.ext.settings.app.AswRestrictMemoryDynCodeLoading
import android.os.IBinder
import android.os.Process
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.util.Utils
import org.matrix.vector.BuildConfig
import org.matrix.vector.ParasiticManagerHooker
import org.matrix.vector.ParasiticManagerSystemHooker
import org.matrix.vector.Startup
import org.matrix.vector.impl.core.VectorServiceClient

/** Main entry point for the Java-side loader, invoked via JNI from the Vector Zygisk module. */
object Main {

    /**
     * Shared initialization logic for both System Server and Application processes.
     *
     * @param isSystem True if this is the system_server process.
     * @param isLateInject True if Zygisk APIs are not invoked via hooks
     * @param niceName The process name (e.g., package name or "system").
     * @param appDir The application's data directory.
     * @param binder The Binder token associated with the application service.
     */
    @JvmStatic
    fun forkCommon(
        isSystem: Boolean,
        isLateInject: Boolean,
        niceName: String,
        appDir: String?,
        binder: IBinder,
    ) {
        // Initialize system-specific resolution hooks if in system_server
        if (isSystem) {
            ParasiticManagerSystemHooker.start()
        }

        if (niceName == "system" || niceName == "com.android.settings") {
            try {
                // Force GrapheneOS to allow changing the restriction on Dynamic Code Loading, and
                // force-disable it for the settings app and the shell
                XposedBridge.hookAllMethods(
                    AswRestrictMemoryDynCodeLoading::class.java,
                    "getImmutableValue",
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam<*>?): Any? {
                            val appInfo = param?.args[2] as? ApplicationInfo?

                            // Settings has to always be allowed so that it can be patched as well.
                            if (appInfo != null && listOf(
                                    "com.android.settings",
                                    "com.android.shell"
                                ).contains(appInfo.packageName)
                            ) {
                                return false
                            }

                            // All system apps are configurable (null)
                            if (appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                                return null
                            }

                            // Defer to original implementation for other apps
                            return XposedBridge.invokeOriginalMethod(
                                param?.method, param?.thisObject, param?.args
                            )
                        }
                    }
                )
            } catch (e: XposedHelpers.ClassNotFoundError) {
                // Ignore, assuming we are not on GrapheneOS
            } catch (e: Exception) {
                Utils.logE("Unknown error patching Graphene", e)
            }
        }

        // Initialize Xposed bridge components
        val appService = ILSPApplicationService.Stub.asInterface(binder)
        Startup.initXposed(isSystem, niceName, appDir, appService)

        // Configure logging levels from the service client
        runCatching { Utils.Log.muted = VectorServiceClient.isLogMuted }
            .onFailure { t -> Utils.logE("Failed to configure logs from service", t) }

        // Check if this process is the designated Vector Manager.
        if (niceName == BuildConfig.ManagerPackageName) {
            val type =
                if (Process.myUid() == BuildConfig.HostPackageUid) "parasitic" else "user-installed"
            if (ParasiticManagerHooker.start()) {
                Utils.logI("Manager ($type) loaded into host, skipping standard bootstrap.")
                return
            }
        }

        // Standard Xposed module loading for third-party apps
        Utils.logV("Loading Vector/Xposed for $niceName (UID: ${Process.myUid()})")
        Startup.bootstrapXposed(isSystem && isLateInject)
    }
}

package org.matrix.vector

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import org.lsposed.lspd.util.Utils
import org.matrix.vector.impl.hookers.HandleSystemServerProcessHooker
import org.matrix.vector.impl.hooks.VectorHookBuilder
import org.matrix.vector.service.BridgeService

/**
 * Handles System-Server side logic for the Parasitic Manager.
 *
 * When a user tries to open the Vector Manager, the system normally wouldn't know how to handle it
 * because it isn't "installed." This class intercepts the activity resolution and tells the system
 * to launch it in a special process.
 */
class ParasiticManagerSystemHooker : HandleSystemServerProcessHooker.Callback {

    companion object {
        @JvmStatic
        fun start() {
            // Register this class as the handler for system_server initialization.
            // This ensures the hook is deferred until the System Server ClassLoader is fully ready.
            HandleSystemServerProcessHooker.callback = ParasiticManagerSystemHooker()
        }
    }

    @SuppressLint("PrivateApi")
    override fun onSystemServerLoaded(classLoader: ClassLoader) {
        runCatching {
                // Android versions change the name of the internal class responsible for activity
                // tracking.
                // We check the most likely candidates based on API levels (9.0 through 14+).
                val supervisorClass =
                    try {
                        // Android 12.0 - 14+
                        Class.forName(
                            "com.android.server.wm.ActivityTaskSupervisor",
                            false,
                            classLoader,
                        )
                    } catch (e: ClassNotFoundException) {
                        try {
                            // Android 10 - 11
                            Class.forName(
                                "com.android.server.wm.ActivityStackSupervisor",
                                false,
                                classLoader,
                            )
                        } catch (e2: ClassNotFoundException) {
                            // Android 8.1 - 9
                            Class.forName(
                                "com.android.server.am.ActivityStackSupervisor",
                                false,
                                classLoader,
                            )
                        }
                    }

                // Locate the exact resolveActivity method
                val resolveMethod =
                    supervisorClass.declaredMethods.first { it.name == "resolveActivity" }

                // Hook the resolution method to inject our redirection logic
                VectorHookBuilder(resolveMethod).intercept { chain ->
                    // Execute the original resolution first
                    val result = chain.proceed()

                    val intent = chain.args[0] as? Intent ?: return@intercept result

                    // Check if this intent is meant for the Vector Manager
                    if (!intent.hasCategory(BuildConfig.ManagerPackageName + ".LAUNCH_MANAGER"))
                        return@intercept result

                    val originalActivityInfo =
                        result as? ActivityInfo
                            ?: run {
                                Utils.logD(
                                    "Redirection: result is not ActivityInfo (was ${result?.javaClass?.name})"
                                )
                                return@intercept result
                            }

                    // We only intercept if it's currently resolving to the shell/fallback
                    if (originalActivityInfo.packageName != BuildConfig.InjectedPackageName)
                        return@intercept result

                    // --- Redirection Logic ---
                    // We create a copy of the ActivityInfo to avoid polluting the system's cache.
                    val redirectedInfo =
                        ActivityInfo(originalActivityInfo).apply {
                            // Force the manager to run in its own dedicated process name
                            processName = BuildConfig.ManagerPackageName

                            // Set a standard theme so transition animations work correctly
                            theme = android.R.style.Theme_DeviceDefault_Settings

                            // Ensure the activity isn't excluded from recents by host flags
                            flags =
                                flags and
                                    (ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS or
                                            ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS)
                                        .inv()
                        }

                    // Notify the bridge service that we are about to start the manager
                    BridgeService.getService()?.preStartManager()

                    redirectedInfo
                }

                Utils.logD("Successfully hooked Activity Supervisor for Manager redirection.")
            }
            .onFailure { Utils.logE("Failed to hook system server activity resolution", it) }
    }
}

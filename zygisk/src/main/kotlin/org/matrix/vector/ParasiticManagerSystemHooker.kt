package org.matrix.vector

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import java.lang.reflect.Field
import org.matrix.vector.util.Utils
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
        hookActivityResolution(classLoader)
        hookSplashScreenSuppression(classLoader)
    }

    private fun hookActivityResolution(classLoader: ClassLoader) {
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

    private fun hookSplashScreenSuppression(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ---------------------------------------------------------
            // Android 12+ (API 31+) - StartingSurfaceController
            // ---------------------------------------------------------
            runCatching {
                    val controllerClass =
                        Class.forName(
                            "com.android.server.wm.StartingSurfaceController",
                            false,
                            classLoader,
                        )
                    val createMethod =
                        controllerClass.declaredMethods.first {
                            it.name == "createSplashScreenStartingSurface"
                        }

                    val activityRecordClass =
                        Class.forName("com.android.server.wm.ActivityRecord", false, classLoader)

                    // Cache the field to avoid overhead during hook execution
                    val infoField: Field =
                        activityRecordClass.getDeclaredField("info").apply { isAccessible = true }

                    VectorHookBuilder(createMethod).intercept { chain ->
                        val activityRecord = chain.args[0]
                        val info = infoField.get(activityRecord) as ActivityInfo

                        if (info.processName == BuildConfig.ManagerPackageName) {
                            Utils.logD("Suppressing Android 12+ Splash Screen for Vector Manager.")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                }
                .onFailure { Utils.logE("Failed to hook StartingSurfaceController", it) }
        } else {
            // ---------------------------------------------------------
            // Android 8.1 - 11 (API 27 - 30) - ActivityRecord
            // ---------------------------------------------------------
            runCatching {
                    val recordClassName =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            "com.android.server.wm.ActivityRecord" // API 29-30
                        } else {
                            "com.android.server.am.ActivityRecord" // API 27-28
                        }

                    val recordClass = Class.forName(recordClassName, false, classLoader)
                    val showMethod =
                        recordClass.declaredMethods.first { it.name == "showStartingWindow" }

                    // Cache the field
                    val infoField: Field =
                        recordClass.getDeclaredField("info").apply { isAccessible = true }

                    VectorHookBuilder(showMethod).intercept { chain ->
                        val activityRecord = chain.thisObject
                        val info = infoField.get(activityRecord) as ActivityInfo

                        if (info.processName == BuildConfig.ManagerPackageName) {
                            Utils.logD("Suppressing Legacy Starting Window for Vector Manager.")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                }
                .onFailure { Utils.logE("Failed to hook Legacy ActivityRecord", it) }
        }
    }
}

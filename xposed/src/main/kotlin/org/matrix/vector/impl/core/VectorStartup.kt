package org.matrix.vector.impl.core

import android.os.Build
import android.os.IBinder
import dalvik.system.DexFile
import org.lsposed.lspd.service.ILSPApplicationService
import org.matrix.vector.impl.hookers.*
import org.matrix.vector.impl.hooks.VectorHookBuilder

/**
 * Modern framework initialization and bootstrap sequence. Deploys interceptors into the ART runtime
 * and handles early process deoptimization.
 */
object VectorStartup {

    @JvmStatic
    fun init(
        isSystem: Boolean,
        processName: String?,
        appDir: String?,
        service: ILSPApplicationService?,
    ) {
        VectorServiceClient.init(service, processName ?: "android")
        VectorDeopter.deoptBootMethods()
    }

    @JvmStatic
    fun bootstrap(isSystem: Boolean, systemServerStarted: Boolean) {
        // Crash Dump Interceptor
        Thread::class
            .java
            .declaredMethods
            .firstOrNull { it.name == "dispatchUncaughtException" }
            ?.let { VectorHookBuilder(it).intercept(CrashDumpHooker) }

        // Process-specific Interceptors
        if (isSystem) {
            val zygoteInitClass = Class.forName("com.android.internal.os.ZygoteInit")
            zygoteInitClass.declaredMethods
                .filter { it.name == "handleSystemServerProcess" }
                .forEach { VectorHookBuilder(it).intercept(HandleSystemServerProcessHooker) }
        } else {
            DexFile::class
                .java
                .declaredMethods
                .filter {
                    it.name == "openDexFile" ||
                        it.name == "openInMemoryDexFile" ||
                        it.name == "openInMemoryDexFiles"
                }
                .forEach { VectorHookBuilder(it).intercept(DexTrustHooker) }
        }

        // Application Load Interceptors
        val loadedApkClass = Class.forName("android.app.LoadedApk")
        loadedApkClass.declaredConstructors.forEach {
            // Hook all constructors of LoadedApk to catch early instantiations securely
            VectorHookBuilder(it).intercept(LoadedApkCtorHooker)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            loadedApkClass.declaredMethods
                .filter { it.name == "createAppFactory" }
                .forEach { VectorHookBuilder(it).intercept(LoadedApkCreateAppFactoryHooker) }
        }

        loadedApkClass.declaredMethods
            .filter { it.name == "createOrUpdateClassLoaderLocked" }
            .forEach { VectorHookBuilder(it).intercept(LoadedApkCreateCLHooker) }

        // ActivityThread Attachment Interceptor
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        activityThreadClass.declaredMethods
            .filter { it.name == "attach" }
            .forEach { VectorHookBuilder(it).intercept(AppAttachHooker) }

        // Late System Server Injection
        if (systemServerStarted) {
            val activityService: IBinder? = android.os.ServiceManager.getService("activity")
            if (activityService != null) {
                val classLoader = activityService.javaClass.classLoader
                if (classLoader != null) {
                    // Manually trigger the routines that the hooks normally would
                    HandleSystemServerProcessHooker.initSystemServer(classLoader, isLate = true)
                    StartBootstrapServicesHooker.dispatchSystemServerLoaded(classLoader)
                }
            }
        }
    }
}

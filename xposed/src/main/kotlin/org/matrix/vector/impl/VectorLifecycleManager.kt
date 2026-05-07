package org.matrix.vector.impl

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.util.Utils.Log

/** Manages the dispatching of modern lifecycle events to loaded modules. */
object VectorLifecycleManager {

    private const val TAG = "VectorLifecycle"

    val activeModules: MutableSet<XposedModule> = ConcurrentHashMap.newKeySet()

    fun dispatchPackageLoaded(
        packageName: String,
        appInfo: ApplicationInfo,
        isFirst: Boolean,
        defaultClassLoader: ClassLoader,
    ) {
        val param =
            object : PackageLoadedParam {
                override fun getPackageName(): String = packageName

                override fun getApplicationInfo(): ApplicationInfo = appInfo

                override fun isFirstPackage(): Boolean = isFirst

                override fun getDefaultClassLoader(): ClassLoader = defaultClassLoader
            }

        activeModules.forEach { module ->
            runCatching { module.onPackageLoaded(param) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Error in onPackageLoaded for ${module.moduleApplicationInfo.packageName}",
                        it,
                    )
                }
        }
    }

    fun dispatchPackageReady(
        packageName: String,
        appInfo: ApplicationInfo,
        isFirst: Boolean,
        defaultClassLoader: ClassLoader,
        classLoader: ClassLoader,
        appComponentFactory: Any?, // Abstracted for API compatibility
    ) {
        val param =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && appComponentFactory != null) {
                PackageReadyParamImplP(
                    packageName,
                    appInfo,
                    isFirst,
                    defaultClassLoader,
                    classLoader,
                    appComponentFactory,
                )
            } else {
                // Fallback for API < 28 (or if factory is null).
                object : PackageReadyParam {
                    override fun getPackageName() = packageName

                    override fun getApplicationInfo() = appInfo

                    override fun isFirstPackage() = isFirst

                    override fun getDefaultClassLoader() = defaultClassLoader

                    override fun getClassLoader() = classLoader

                    override fun getAppComponentFactory(): android.app.AppComponentFactory {
                        throw UnsupportedOperationException(
                            "AppComponentFactory is not available on this API level"
                        )
                    }
                }
            }

        activeModules.forEach { module ->
            runCatching {
                    Log.d(TAG, "dispatchPackageReady $param")
                    module.onPackageReady(param)
                }
                .onFailure {
                    Log.e(
                        TAG,
                        "Error in onPackageReady for ${module.moduleApplicationInfo.packageName}",
                        it,
                    )
                }
        }
    }

    fun dispatchSystemServerStarting(classLoader: ClassLoader) {
        val param =
            object : SystemServerStartingParam {
                override fun getClassLoader(): ClassLoader = classLoader
            }

        activeModules.forEach { module ->
            runCatching { module.onSystemServerStarting(param) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Error in onSystemServerStarting for ${module.moduleApplicationInfo.packageName}",
                        it,
                    )
                }
        }
    }
}

// Isolate the class so the Verifier doesn't crash on Android 8.1 and below
@RequiresApi(Build.VERSION_CODES.P)
private class PackageReadyParamImplP(
    private val packageName: String,
    private val appInfo: ApplicationInfo,
    private val isFirst: Boolean,
    private val defaultClassLoader: ClassLoader,
    private val classLoader: ClassLoader,
    private val appComponentFactory: Any,
) : PackageReadyParam {
    override fun getPackageName() = packageName

    override fun getApplicationInfo() = appInfo

    override fun isFirstPackage() = isFirst

    override fun getDefaultClassLoader() = defaultClassLoader

    override fun getClassLoader() = classLoader

    override fun getAppComponentFactory(): android.app.AppComponentFactory {
        return appComponentFactory as android.app.AppComponentFactory
    }
}

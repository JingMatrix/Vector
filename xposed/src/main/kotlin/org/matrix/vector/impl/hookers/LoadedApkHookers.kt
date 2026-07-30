package org.matrix.vector.impl.hookers

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedInterface
import java.util.Collections
import java.util.WeakHashMap
import org.lsposed.lspd.util.Utils
import org.matrix.vector.impl.VectorLifecycleManager
import org.matrix.vector.impl.di.LegacyPackageInfo
import org.matrix.vector.impl.di.VectorBootstrap

/** Safe reflection helper */
private inline fun <reified T> Any.getFieldValue(name: String): T? {
    var clazz: Class<*>? = this.javaClass
    while (clazz != null) {
        try {
            val field = clazz.getDeclaredField(name)
            field.isAccessible = true
            return field.get(this) as? T
        } catch (ignored: NoSuchFieldException) {
            clazz = clazz.superclass
        }
    }
    return null
}

/** Centralized helper for determining context details */
private object PackageContextHelper {
    private val activityThreadClass by lazy { Class.forName("android.app.ActivityThread") }
    private val currentPkgMethod by lazy {
        activityThreadClass.getDeclaredMethod("currentPackageName").apply { isAccessible = true }
    }
    private val currentProcMethod by lazy {
        activityThreadClass.getDeclaredMethod("currentProcessName").apply { isAccessible = true }
    }

    data class ContextInfo(
        val packageName: String,
        val legacyPackageName: String,
        val processName: String,
        val isFirstPackage: Boolean,
    )

    fun resolve(loadedApk: Any, apkPackageName: String): ContextInfo {
        val boundPackage = currentPkgMethod.invoke(null) as? String
        val boundProcess = currentProcMethod.invoke(null) as? String

        val isFirstPackage =
            boundPackage != null && boundProcess != null && boundPackage == apkPackageName

        val packageName: String
        val processName: String
        if (isFirstPackage) {
            packageName = boundPackage!!
            processName = boundProcess!!
        } else {
            packageName = apkPackageName
            processName = boundPackage ?: apkPackageName
        }

        // The two APIs name this process differently, so keep both names.
        //
        // de.robv modules identify system server by lpparam.packageName == "android", so the
        // android:ui process has always been handed "system" to keep the two apart. The modern
        // API says the reverse: "system" is the virtual name for system server, while "android"
        // stays a real scope target precisely because some of its components declare
        // android:process=":ui". Reporting android:ui as "system" there hid the real package.
        //
        // In system server itself currentPackageName() is null, so isFirstPackage is false and
        // both names are already "android"; only android:ui is affected.
        val legacyPackageName =
            if (isFirstPackage && packageName == "android") "system" else packageName

        return ContextInfo(packageName, legacyPackageName, processName, isFirstPackage)
    }
}

/** Identity-based tracking for LoadedApk instances. */
private object LoadedApkTracker {
    // Tracks LoadedApk instances that are currently in their initial bootstrap phase
    val activeApks: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
}

/** Tracks and prepares Application instances when their LoadedApk is instantiated. */
object LoadedApkCtorHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val loadedApk = chain.thisObject ?: return result

        val packageName = loadedApk.getFieldValue<String>("mPackageName") ?: return result

        VectorBootstrap.withLegacy { delegate ->
            if (!delegate.isResourceHookingDisabled) {
                val resDir = loadedApk.getFieldValue<String>("mResDir")
                delegate.setPackageNameForResDir(packageName, resDir)
            }
        }

        // OnePlus workaround to avoid custom opt crashing
        val isPreload =
            Throwable().stackTrace.any {
                it.className == "android.app.ActivityThread\$ApplicationThread" &&
                    it.methodName == "schedulePreload"
            }
        if (!isPreload) {
            LoadedApkTracker.activeApks.add(loadedApk)
        }

        return result
    }
}

/** Modern API Phase: onPackageLoaded */
@RequiresApi(Build.VERSION_CODES.P)
object LoadedApkCreateAppFactoryHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val loadedApk = chain.thisObject ?: return chain.proceed()

        // Ensure we only dispatch for instances we are tracking
        if (!LoadedApkTracker.activeApks.contains(loadedApk)) return chain.proceed()

        val appInfo = chain.args[0] as ApplicationInfo
        val defaultClassLoader =
            chain.args[1] as? ClassLoader
                ?: return chain.proceed() // Skip dispatch if there's no ClassLoader

        // Only dispatch if on API 29+ per libxposed API specification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val apkPackageName =
                loadedApk.getFieldValue<String>("mPackageName") ?: appInfo.packageName
            val ctx = PackageContextHelper.resolve(loadedApk, apkPackageName)

            VectorLifecycleManager.dispatchPackageLoaded(
                ctx.packageName,
                appInfo,
                ctx.isFirstPackage,
                defaultClassLoader,
            )
        }

        return chain.proceed()
    }
}

/** Modern API Phase: onPackageReady and Legacy Phase: handleLoadPackage */
object LoadedApkCreateCLHooker : XposedInterface.Hooker {
    // intercepting createOrUpdateClassLoaderLocked(List<String> addedPaths)
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val loadedApk = chain.thisObject ?: return chain.proceed()

        // Proceed: Modern modules need onPackageReady even for Split APKs (args[0] != null)
        val isInitialLoad =
            chain.args.firstOrNull() == null && LoadedApkTracker.activeApks.contains(loadedApk)
        val result = chain.proceed()

        try {
            val apkPackageName = loadedApk.getFieldValue<String>("mPackageName") ?: return result
            val appInfo =
                loadedApk.getFieldValue<ApplicationInfo>("mApplicationInfo") ?: return result
            val classLoader = loadedApk.getFieldValue<ClassLoader>("mClassLoader") ?: return result
            val defaultClassLoader =
                loadedApk.getFieldValue<ClassLoader>("mDefaultClassLoader") ?: classLoader

            val ctx = PackageContextHelper.resolve(loadedApk, apkPackageName)

            // Dispatch Modern Lifecycle: onPackageReady
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val appComponentFactory = loadedApk.getFieldValue<Any>("mAppComponentFactory")
                VectorLifecycleManager.dispatchPackageReady(
                    ctx.packageName,
                    appInfo,
                    ctx.isFirstPackage,
                    defaultClassLoader,
                    classLoader,
                    appComponentFactory,
                )
            }

            // Legacy API: Only dispatch once during initial load
            if (isInitialLoad) {
                val mIncludeCode = loadedApk.getFieldValue<Boolean>("mIncludeCode") ?: true
                if (ctx.isFirstPackage || mIncludeCode) {
                    VectorBootstrap.withLegacy { delegate ->
                        delegate.onPackageLoaded(
                            LegacyPackageInfo(
                                ctx.legacyPackageName,
                                ctx.processName,
                                classLoader,
                                appInfo,
                                ctx.isFirstPackage,
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Utils.logE("LoadedApkCreateCLHooker failed in post-proceed phase", t)
        } finally {
            // Cleanup: Once the initial load is done, we remove it from activeApks.
            // Subsequent calls (Split APKs) will now be recognized as non-initial loads.
            LoadedApkTracker.activeApks.remove(loadedApk)
        }

        return result
    }
}

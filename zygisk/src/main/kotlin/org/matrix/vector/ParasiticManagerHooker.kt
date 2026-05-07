package org.matrix.vector

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.app.LoadedApk
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.*
import android.os.*
import android.util.AndroidRuntimeException
import android.util.ArrayMap
import android.webkit.WebViewDelegate
import android.webkit.WebViewFactory
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import hidden.HiddenApiBridge
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.ILSPManagerService
import org.lsposed.lspd.util.Utils
import org.matrix.vector.impl.core.VectorServiceClient

/** The "Parasite" logic. Injects the LSPosed Manager APK into a host process (shell). */
@SuppressLint("StaticFieldLeak")
object ParasiticManagerHooker {
    private const val CHROMIUM_WEBVIEW_FACTORY_METHOD = "create"

    private var managerPkgInfo: PackageInfo? = null
    private var managerFd: Int = -1

    // Manually track Activity states since the system is unaware of our spoofed activities
    private val states = ConcurrentHashMap<String, Bundle>()
    private val persistentStates = ConcurrentHashMap<String, PersistableBundle>()

    private fun logD(msg: String) {
        Utils.logD(
            "ParasiticHooker: pkg=${ActivityThread.currentPackageName()}, prc=${ActivityThread.currentProcessName()} - $msg"
        )
    }

    private fun logE(msg: String, t: Throwable) {
        Utils.logE(
            "ParasiticHooker: pkg=${ActivityThread.currentPackageName()}, prc=${ActivityThread.currentProcessName()} - $msg",
            t,
        )
    }

    /** Constructs a hybrid PackageInfo. Combines the Manager's code with the Host's environment. */
    @Synchronized
    private fun getManagerPkgInfo(appInfo: ApplicationInfo?): PackageInfo? {
        if (managerPkgInfo == null && appInfo != null) {
            runCatching {
                    val ctx: Context = ActivityThread.currentActivityThread().systemContext
                    var sourcePath = "/proc/self/fd/$managerFd"

                    // SDK <= 28 (Android 9) cannot reliably parse APKs via FD paths in all
                    // contexts.
                    // We copy the APK to the host's cache as a workaround.
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                        val dstPath = "${appInfo.dataDir}/cache/lsposed.apk"
                        runCatching {
                                FileInputStream(sourcePath).use { input ->
                                    FileOutputStream(dstPath).use { output ->
                                        input.channel.transferTo(
                                            0,
                                            input.channel.size(),
                                            output.channel,
                                        )
                                    }
                                }
                                sourcePath = dstPath
                            }
                            .onFailure { logE("Failed to copy parasitic APK", it) }
                    }

                    val pkgInfo =
                        ctx.packageManager.getPackageArchiveInfo(
                            sourcePath,
                            PackageManager.GET_ACTIVITIES,
                        ) ?: throw RuntimeException("PackageManager failed to parse $sourcePath")

                    // Transplant identity: Keep host's paths and UID, swap the code source
                    pkgInfo.applicationInfo!!.apply {
                        sourceDir = sourcePath
                        publicSourceDir = sourcePath
                        nativeLibraryDir = appInfo.nativeLibraryDir
                        packageName = appInfo.packageName
                        dataDir =
                            HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(appInfo)
                        deviceProtectedDataDir = appInfo.deviceProtectedDataDir
                        processName = appInfo.processName
                        uid = appInfo.uid
                        // A14 QPR3 Fix: Ensure the flag for code existence is set
                        flags = flags or ApplicationInfo.FLAG_HAS_CODE

                        HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(
                            this,
                            HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(appInfo),
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            HiddenApiBridge.ApplicationInfo_overlayPaths(
                                this,
                                HiddenApiBridge.ApplicationInfo_overlayPaths(appInfo),
                            )
                        }
                        HiddenApiBridge.ApplicationInfo_resourceDirs(
                            this,
                            HiddenApiBridge.ApplicationInfo_resourceDirs(appInfo),
                        )
                    }
                    managerPkgInfo = pkgInfo
                }
                .onFailure { Utils.logE("Failed to construct manager PkgInfo", it) }
        }
        return managerPkgInfo
    }

    /**
     * Passes the IPC binder to the Manager's internal [Constants] class so it can communicate back
     * to the Vector system service.
     */
    private fun sendBinderToManager(classLoader: ClassLoader, binder: IBinder) {
        runCatching {
                val clazz =
                    XposedHelpers.findClass(
                        BuildConfig.ManagerPackageName + ".Constants",
                        classLoader,
                    )
                val ok =
                    XposedHelpers.callStaticMethod(
                        clazz,
                        "setBinder",
                        arrayOf(IBinder::class.java),
                        binder,
                    ) as Boolean
                if (!ok) throw RuntimeException("setBinder returned false")
            }
            .onFailure { Utils.logW("Could not send binder to LSPosed Manager", it) }
    }

    private fun hookForManager(managerService: ILSPManagerService) {
        // Hook 1: Swap ApplicationInfo during host binding
        XposedHelpers.findAndHookMethod(
            ActivityThread::class.java,
            "handleBindApplication",
            "android.app.ActivityThread\$AppBindData",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    logD("ActivityThread#handleBindApplication() starts")
                    val bindData = param.args[0]
                    val hostAppInfo =
                        XposedHelpers.getObjectField(bindData, "appInfo") as ApplicationInfo
                    val parasiticInfo = getManagerPkgInfo(hostAppInfo)?.applicationInfo
                    XposedHelpers.setObjectField(bindData, "appInfo", parasiticInfo)
                }
            },
        )

        // Hook 2: Inject APK path into the ClassLoader
        var classLoaderUnhook: XC_MethodHook.Unhook? = null
        val classLoaderHook =
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val pkgInfo = getManagerPkgInfo(null) ?: return
                    val mAppInfo =
                        XposedHelpers.getObjectField(param.thisObject, "mApplicationInfo")

                    val managerAppInfo = pkgInfo.applicationInfo!!

                    if (mAppInfo == managerAppInfo) {
                        val dexPath = managerAppInfo.sourceDir
                        val pathClassLoader = param.result as ClassLoader

                        logD("Injecting DEX into LoadedApk ClassLoader: $pathClassLoader")
                        val pathList = XposedHelpers.getObjectField(pathClassLoader, "pathList")
                        val dexPaths = XposedHelpers.callMethod(pathList, "getDexPaths") as List<*>

                        if (!dexPaths.contains(dexPath)) {
                            Utils.logW("Manager APK not found in ClassLoader, adding manually...")
                            XposedHelpers.callMethod(pathClassLoader, "addDexPath", dexPath)
                        }
                        sendBinderToManager(pathClassLoader, managerService.asBinder())
                        classLoaderUnhook!!.unhook() // Only need to inject once
                    }
                }
            }
        classLoaderUnhook =
            XposedHelpers.findAndHookMethod(
                LoadedApk::class.java,
                "getClassLoader",
                classLoaderHook,
            )

        // Hook 3: Activity Lifecycle & Intent Redirection
        val activityClientRecordClass =
            XposedHelpers.findClass(
                "android.app.ActivityThread\$ActivityClientRecord",
                ActivityThread::class.java.classLoader,
            )
        val activityHooker =
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    param.args.forEachIndexed { i, arg ->
                        if (arg is ActivityInfo) {
                            val pkgInfo =
                                getManagerPkgInfo(arg.applicationInfo) ?: return@forEachIndexed
                            pkgInfo.activities
                                ?.find {
                                    it.name ==
                                        BuildConfig.ManagerPackageName + ".ui.activity.MainActivity"
                                }
                                ?.let {
                                    it.applicationInfo = pkgInfo.applicationInfo
                                    param.args[i] = it
                                }
                        }
                        if (arg is Intent) {
                            arg.component =
                                ComponentName(
                                    arg.component!!.packageName,
                                    BuildConfig.ManagerPackageName + ".ui.activity.MainActivity",
                                )
                        }
                    }

                    // Captured State Injection
                    if (param.method.getName() == "scheduleLaunchActivity") {
                        var currentAInfo: ActivityInfo? = null
                        val types = (param.method as Method).parameterTypes
                        types.forEachIndexed { idx, type ->
                            when (type) {
                                ActivityInfo::class.java ->
                                    currentAInfo = param.args[idx] as ActivityInfo
                                Bundle::class.java ->
                                    currentAInfo?.let { info ->
                                        states[info.name]?.let { param.args[idx] = it }
                                    }
                                PersistableBundle::class.java ->
                                    currentAInfo?.let { info ->
                                        persistentStates[info.name]?.let { param.args[idx] = it }
                                    }
                            }
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (!activityClientRecordClass.isInstance(param.thisObject)) return
                    param.args.filterIsInstance<ActivityInfo>().forEach { aInfo ->
                        logD("Restoring state for Activity: ${aInfo.name}")
                        states[aInfo.name]?.let {
                            XposedHelpers.setObjectField(param.thisObject, "state", it)
                        }
                        persistentStates[aInfo.name]?.let {
                            XposedHelpers.setObjectField(param.thisObject, "persistentState", it)
                        }
                    }
                }
            }

        XposedBridge.hookAllConstructors(activityClientRecordClass, activityHooker)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            val appThreadClass =
                XposedHelpers.findClass(
                    "android.app.ActivityThread\$ApplicationThread",
                    ActivityThread::class.java.classLoader,
                )
            XposedBridge.hookAllMethods(appThreadClass, "scheduleLaunchActivity", activityHooker)
        }

        // Hook 4: Ignore Receivers (Manager doesn't need to handle host receivers)
        XposedBridge.hookAllMethods(
            ActivityThread::class.java,
            "handleReceiver",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam<*>): Any? {
                    param.args.filterIsInstance<BroadcastReceiver.PendingResult>().forEach {
                        it.finish()
                    }
                    return null
                }
            },
        )

        // Hook 5: Provider Context Spoofing
        XposedBridge.hookAllMethods(
            ActivityThread::class.java,
            "installProvider",
            object : XC_MethodHook() {
                private var originalContext: Context? = null

                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    var ctx: Context? = null
                    var info: ProviderInfo? = null
                    var ctxIdx = -1

                    param.args.forEachIndexed { i, arg ->
                        when (arg) {
                            is Context -> {
                                ctx = arg
                                ctxIdx = i
                            }
                            is ProviderInfo -> info = arg
                        }
                    }

                    val pkgInfo = getManagerPkgInfo(null)
                    if (ctx != null && info != null && pkgInfo != null) {
                        val managerPackage = pkgInfo.applicationInfo!!.packageName
                        if (info.applicationInfo.packageName != managerPackage) return

                        if (originalContext == null) {
                            // Create a fake original context to satisfy internal package checks
                            info.applicationInfo.packageName = "$managerPackage.origin"
                            val compatibilityInfo =
                                HiddenApiBridge.Resources_getCompatibilityInfo(ctx.resources)
                            val originalPkgInfo =
                                ActivityThread.currentActivityThread()
                                    .getPackageInfoNoCheck(info.applicationInfo, compatibilityInfo)
                            XposedHelpers.setObjectField(
                                originalPkgInfo,
                                "mPackageName",
                                managerPackage,
                            )

                            val contextImplClass =
                                XposedHelpers.findClass("android.app.ContextImpl", null)
                            originalContext =
                                XposedHelpers.callStaticMethod(
                                    contextImplClass,
                                    "createAppContext",
                                    ActivityThread.currentActivityThread(),
                                    originalPkgInfo,
                                ) as Context
                            info.applicationInfo.packageName = managerPackage
                        }
                        param.args[ctxIdx] = originalContext
                    }
                }
            },
        )

        // Hook 6: WebView initialization within Parasitic process
        XposedHelpers.findAndHookMethod(
            WebViewFactory::class.java,
            "getProvider",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam<*>): Any? {
                    val existing =
                        XposedHelpers.getStaticObjectField(
                            WebViewFactory::class.java,
                            "sProviderInstance",
                        )
                    if (existing != null) return existing

                    val providerClass =
                        XposedHelpers.callStaticMethod(
                            WebViewFactory::class.java,
                            "getProviderClass",
                        ) as Class<*>
                    return try {
                        val staticFactory =
                            providerClass.getMethod(
                                CHROMIUM_WEBVIEW_FACTORY_METHOD,
                                WebViewDelegate::class.java,
                            )
                        val delegateCtor =
                            WebViewDelegate::class.java.getDeclaredConstructor().apply {
                                isAccessible = true
                            }
                        val instance = staticFactory.invoke(null, delegateCtor.newInstance())
                        XposedHelpers.setStaticObjectField(
                            WebViewFactory::class.java,
                            "sProviderInstance",
                            instance,
                        )
                        logD("WebView provider initialized: $instance")
                        instance
                    } catch (e: Exception) {
                        logE("WebView initialization failed", e)
                        throw AndroidRuntimeException(e)
                    }
                }
            },
        )

        // Hook 7: State Capture on Stop
        val stateCaptureHooker =
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    runCatching {
                            var record = param.args[0]
                            if (record is IBinder) {
                                val activities =
                                    XposedHelpers.getObjectField(param.thisObject, "mActivities")
                                        as ArrayMap<*, *>
                                record = activities[record] ?: return
                            }

                            val saveMethod =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                                    "callActivityOnSaveInstanceState"
                                else "callCallActivityOnSaveInstanceState"
                            XposedHelpers.callMethod(param.thisObject, saveMethod, record)

                            val state = XposedHelpers.getObjectField(record, "state") as? Bundle
                            val pState =
                                XposedHelpers.getObjectField(record, "persistentState")
                                    as? PersistableBundle
                            val aInfo =
                                XposedHelpers.getObjectField(record, "activityInfo") as ActivityInfo

                            state?.let { states[aInfo.name] = it }
                            pState?.let { persistentStates[aInfo.name] = it }
                            logD("Saved state for ${aInfo.name}")
                        }
                        .onFailure { logE("Failed to save activity state", it) }
                }
            }
        XposedBridge.hookAllMethods(
            ActivityThread::class.java,
            "performStopActivityInner",
            stateCaptureHooker,
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            XposedHelpers.findAndHookMethod(
                ActivityThread::class.java,
                "performDestroyActivity",
                IBinder::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                stateCaptureHooker,
            )
        }
    }

    /** Entry point. Checks if the current process should host the parasitic manager. */
    @JvmStatic
    fun start(): Boolean {
        val binderList = mutableListOf<IBinder>()
        return try {
            VectorServiceClient.requestInjectedManagerBinder(binderList)!!.use { pfd ->
                managerFd = pfd.detachFd()
                val managerService = ILSPManagerService.Stub.asInterface(binderList[0])
                hookForManager(managerService)
                Utils.logD("Vector manager injected successfully into process.")
                true
            }
        } catch (e: Throwable) {
            Utils.logE("Parasitic injection failed", e)
            false
        }
    }
}

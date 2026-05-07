package org.matrix.vector.impl

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.*
import java.io.FileNotFoundException
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.service.ILSPInjectedModuleService
import org.lsposed.lspd.util.Utils.Log
import org.matrix.vector.impl.hooks.VectorCtorInvoker
import org.matrix.vector.impl.hooks.VectorHookBuilder
import org.matrix.vector.impl.hooks.VectorMethodInvoker
import org.matrix.vector.nativebridge.HookBridge

/**
 * Main framework context implementation. Provides modules with capabilities to hook executables,
 * request invokers, and interact with the system.
 */
class VectorContext(
    private val packageName: String,
    private val applicationInfo: ApplicationInfo,
    private val service: ILSPInjectedModuleService,
) : XposedInterface {

    private val remotePrefs = ConcurrentHashMap<String, SharedPreferences>()

    override fun getFrameworkName(): String = BuildConfig.FRAMEWORK_NAME

    override fun getFrameworkVersion(): String = BuildConfig.VERSION_NAME

    override fun getFrameworkVersionCode(): Long = BuildConfig.VERSION_CODE

    override fun getFrameworkProperties(): Long {
        return service.getFrameworkProperties()
    }

    override fun hook(origin: Executable): XposedInterface.HookBuilder {
        return VectorHookBuilder(origin)
    }

    override fun hookClassInitializer(origin: Class<*>): XposedInterface.HookBuilder {
        val clinit =
            HookBridge.getStaticInitializer(origin)
                ?: throw IllegalArgumentException("Class ${origin.name} has no static initializer")
        return VectorHookBuilder(clinit)
    }

    override fun deoptimize(executable: Executable): Boolean {
        return HookBridge.deoptimizeMethod(executable)
    }

    override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> {
        return VectorMethodInvoker(method)
    }

    override fun <T : Any> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> {
        return VectorCtorInvoker(constructor)
    }

    override fun getModuleApplicationInfo(): ApplicationInfo = applicationInfo

    override fun getRemotePreferences(name: String): SharedPreferences {
        return remotePrefs.getOrPut(name) { VectorRemotePreferences(service, name) }
    }

    override fun listRemoteFiles(): Array<String> {
        return service.remoteFileList
    }

    override fun openRemoteFile(name: String): ParcelFileDescriptor {
        return service.openRemoteFile(name)
            ?: throw FileNotFoundException("Cannot open remote file: $name")
    }

    override fun log(priority: Int, tag: String?, msg: String) {
        log(priority, tag, msg, null)
    }

    override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) {
        val finalTag = tag ?: "VectorContext"
        val prefix = if (packageName.isNotEmpty()) "$packageName: " else ""
        val fullMsg = buildString {
            append(prefix).append(msg)
            if (tr != null) {
                append("\n").append(android.util.Log.getStackTraceString(tr))
            }
        }
        Log.println(priority, finalTag, fullMsg)
    }
}

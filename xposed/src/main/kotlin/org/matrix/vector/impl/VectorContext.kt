package org.matrix.vector.impl

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModuleInterface.*
import java.io.FileNotFoundException
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import org.matrix.vector.ipc.IModuleService
import org.matrix.vector.util.Log
import org.matrix.vector.impl.hooks.VectorCtorInvoker
import org.matrix.vector.impl.hooks.VectorHookBuilder
import org.matrix.vector.impl.hooks.VectorMethodInvoker
import org.matrix.vector.nativebridge.HookBridge

private const val TAG = "VectorContext"

/** Smallest positive gap between the sorted addresses, i.e. one ArtMethod. */
private fun strideOf(addresses: LongArray): Long {
    val sorted = addresses.sorted()
    var stride = Long.MAX_VALUE
    for (i in 1 until sorted.size) {
        val delta = sorted[i] - sorted[i - 1]
        if (delta in 1 until stride) stride = delta
    }
    return if (stride == Long.MAX_VALUE) 0L else stride
}

/**
 * The size of one ArtMethod, which is a property of the runtime rather than of any class. Derived
 * once from a class with plenty of members so that a class showing only one member to reflection
 * can still be measured against it.
 */
private val artMethodSize: Long by lazy {
    val field = artMethodField ?: return@lazy 0L
    runCatching { strideOf(Any::class.java.declaredMethods.map { field.getLong(it) }.toLongArray()) }
        .getOrDefault(0L)
}

/** ART keeps the ArtMethod address of a reflected member in this field. */
private val artMethodField: Field? by lazy {
    runCatching {
            Executable::class.java.getDeclaredField("artMethod").apply { isAccessible = true }
        }
        .getOrNull()
}

/**
 * Main framework context implementation. Provides modules with capabilities to hook executables,
 * request invokers, and interact with the system.
 */
class VectorContext(
    private val packageName: String,
    private val applicationInfo: ApplicationInfo,
    private val service: IModuleService,
    // What ExceptionMode.DEFAULT resolves to for this module, from module.prop.
    private val defaultExceptionMode: ExceptionMode = ExceptionMode.PROTECTIVE,
) : XposedInterface {

    private val remotePrefs = ConcurrentHashMap<String, SharedPreferences>()
    @Volatile private var frozen = false

    fun freeze() {
        frozen = true
    }

    fun unfreeze() {
        frozen = false
    }

    override fun getFrameworkName(): String = BuildConfig.FRAMEWORK_NAME

    override fun getFrameworkVersion(): String = BuildConfig.VERSION_NAME

    override fun getFrameworkVersionCode(): Long = BuildConfig.VERSION_CODE

    override fun getFrameworkProperties(): Long {
        return service.getFrameworkProperties()
    }

    override fun hook(origin: Executable): XposedInterface.HookBuilder {
        return VectorHookBuilder(origin, packageName, { frozen }, defaultExceptionMode)
    }

    override fun hookClassInitializer(origin: Class<*>): XposedInterface.HookBuilder {
        val clinit =
            findStaticInitializer(origin)
                ?: throw IllegalArgumentException("Class ${origin.name} has no static initializer")
        return VectorHookBuilder(
            asSyntheticMethod(clinit),
            packageName,
            { frozen },
            defaultExceptionMode,
        )
    }

    /**
     * hookClassInitializer documents Chain#getExecutable as "a synthetic Method representing the
     * static initializer", but ART reflects <clinit> as a Constructor because it carries
     * ACC_CONSTRUCTOR. A Method is allocated without running a constructor and given the same
     * Executable state, so it names the same ArtMethod while presenting the documented type.
     */
    private fun asSyntheticMethod(clinit: Executable): Executable {
        if (clinit is Method) return clinit
        return runCatching {
                val method = HookBridge.allocateObject(Method::class.java)
                for (field in Executable::class.java.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    field.set(method, field.get(clinit))
                }
                method as Executable
            }
            .onFailure {
                // Falling back keeps the hook working, at the cost of handing the hooker the type
                // ART produced instead of the one the interface promises.
                Log.w(TAG, "Cannot present <clinit> of ${'$'}{clinit.declaringClass.name} as a Method", it)
            }
            .getOrDefault(clinit)
    }

    /**
     * Resolving <clinit> through JNI runs the class's static initializer, which is the event a hook
     * on it exists to observe, so locate it from the method layout instead. Reflection over
     * declared members does not initialize the class, and ArtMethod addresses are read from the
     * reflected objects rather than through jmethodIDs, which a debuggable process hands out as
     * indices.
     */
    private fun findStaticInitializer(origin: Class<*>): Executable? =
        runCatching {
                val field = artMethodField ?: return null
                val members = ArrayList<Executable>()
                members.addAll(origin.declaredConstructors)
                members.addAll(origin.declaredMethods)
                val addresses = LongArray(members.size) { field.getLong(members[it]) }
                // Prefer the class's own spacing; fall back to the runtime-wide size for a class
                // that shows fewer than two members.
                val stride = strideOf(addresses).takeIf { it > 0 } ?: artMethodSize
                HookBridge.findStaticInitializer(origin, addresses, stride)
            }
            .onFailure { Log.w(TAG, "Static initializer lookup failed for ${origin.name}", it) }
            .getOrNull()

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
        return service.remoteFileNames
    }

    override fun openRemoteFile(name: String): ParcelFileDescriptor {
        return service.openRemoteFile(name)
            ?: throw FileNotFoundException("Cannot open remote file: $name")
    }

    override fun log(priority: Int, tag: String?, msg: String) {
        log(priority, tag, msg, null)
    }

    /**
     * A module's own logging, which is the only channel it has for saying what went wrong.
     *
     * The trace comes from [Log.getStackTraceString], our own, not `android.util.Log`'s — that one
     * discards the trace outright for any [java.net.UnknownHostException] cause chain, so a module
     * reporting a failed request got its message and a blank line after it, with nothing to say
     * whose code the request was made from.
     */
    override fun log(priority: Int, tag: String?, msg: String, tr: Throwable?) {
        val finalTag = tag ?: "VectorContext"
        val prefix = if (packageName.isNotEmpty()) "$packageName: " else ""
        val fullMsg = buildString {
            append(prefix).append(msg)
            if (tr != null) {
                append("\n").append(Log.getStackTraceString(tr))
            }
        }
        Log.println(priority, finalTag, fullMsg)
    }
}

package org.matrix.vector.impl.core

import android.os.Build
import android.os.Bundle
import android.os.Process
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.service.IXposedService
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.matrix.vector.ipc.HotReloadOutcome
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.util.Log
import org.matrix.vector.impl.VectorContext
import org.matrix.vector.impl.VectorLifecycleManager
import org.matrix.vector.impl.hooks.VectorHookBuilder
import org.matrix.vector.impl.utils.VectorModuleClassLoader
import org.matrix.vector.nativebridge.NativeAPI

/**
 * Responsible for loading modules into the target process. Handles ClassLoader isolation and
 * injects the framework context into the module instances.
 */
object VectorModuleManager {

    private const val TAG = "VectorModuleManager"
    private const val NOT_ATTACHED = "Framework not attached"

    // Entries are weak on purpose: activeModules owns the only strong reference, and detach()
    // removes it. A reload holds a local strong list for the cycle instead.
    private class Generation(
        val classLoader: ClassLoader,
        val context: VectorContext,
        entries: List<XposedModule>,
        val isSystemServer: Boolean,
        val processName: String,
    ) {
        private val entryRefs = entries.map { WeakReference(it) }

        fun liveEntries(): List<XposedModule> =
            entryRefs.mapNotNull { it.get() }.filter { VectorLifecycleManager.isActive(it) }
    }

    private val generations = ConcurrentHashMap<String, Generation>()

    // Reloads are serialized per module within this process; the daemon serializes per target.
    private val reloadLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** The class loader a loaded module runs in, or null if no generation is loaded for it. */
    fun getModuleClassLoader(packageName: String): ClassLoader? = generations[packageName]?.classLoader

    /** Packages of the modules currently loaded in this process. */
    fun loadedModulePackages(): Set<String> = generations.keys.toSet()

    /**
     * Loads a module APK, instantiates its entry classes, and binds them to the Vector framework.
     */
    fun loadModule(module: LoadedModule, isSystemServer: Boolean, processName: String): Boolean {
        val (generation, entries) =
            buildGeneration(module, isSystemServer, processName) ?: return false

        entries.forEach { VectorLifecycleManager.activeModules.add(it) }
        generations[module.packageName] = generation

        val param =
            object : ModuleLoadedParam {
                override fun isSystemServer(): Boolean = isSystemServer

                override fun getProcessName(): String = processName
            }
        entries.forEach { entry ->
            runCatching { entry.onModuleLoaded(param) }
                .onFailure { e ->
                    Log.e(TAG, "Error in onModuleLoaded for ${entry.javaClass.name}", e)
                }
        }

        // Native entry points are recorded while the generation is instantiated, which has to do it
        // before the entry classes run. Recording them again here would put every library name in
        // the list the dlopen hook walks twice over, and that list never shrinks.

        Log.d(TAG, "Loaded module ${module.packageName} successfully.")
        return true
    }

    // nothing can be published until the entries exist.
    private fun buildGeneration(
        module: LoadedModule,
        isSystemServer: Boolean,
        processName: String,
    ): Pair<Generation, List<XposedModule>>? =
        stageGeneration(module, isSystemServer, processName)?.instantiate()

    private class StagedGeneration(
        private val module: LoadedModule,
        private val isSystemServer: Boolean,
        private val processName: String,
        private val classLoader: ClassLoader,
        private val context: VectorContext,
        private val entryConstructors: List<Constructor<*>>,
    ) {
        fun instantiate(): Pair<Generation, List<XposedModule>>? {
            module.code.moduleLibraryNames.forEach { libraryName ->
                NativeAPI.recordNativeEntrypoint(libraryName)
            }

            val entries = mutableListOf<XposedModule>()
            val failed = mutableListOf<String>()
            for (constructor in entryConstructors) {
                val className = constructor.declaringClass.name
                try {
                    constructor.isAccessible = true
                    val moduleInstance = constructor.newInstance() as XposedModule

                    moduleInstance.attachFramework(context) {
                        VectorLifecycleManager.detach(moduleInstance)
                    }

                    entries.add(moduleInstance)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to instantiate class $className", t)
                    failed += describeInstantiationFailure(className, t)
                }
            }

            if (entries.isEmpty()) {
                Log.e(
                    TAG,
                    "No entry class of ${module.packageName} could be instantiated: " +
                        failed.joinToString("; "),
                )
                return null
            }

            val generation = Generation(classLoader, context, entries, isSystemServer, processName)
            return generation to entries
        }
    }

    /**
     * Resolves a module's entry classes without running any of its code. Returns null when not one
     * of them can be used, having logged which ones and why.
     */
    private fun stageGeneration(
        module: LoadedModule,
        isSystemServer: Boolean,
        processName: String,
    ): StagedGeneration? {
        try {
            Log.d(TAG, "Loading module ${module.packageName}")

            // Construct the native library search path
            val librarySearchPath = buildString {
                // In system_server the in-APK entries below can only ever be refused: /data/app is
                // apk_data_file, which that domain may read and map but never execute. The daemon
                // stages a copy under a label we own for exactly this reason, and it has to come
                // first, because findLibrary answers with the first candidate it can open.
                if (isSystemServer) {
                    module.code.nativeLibraryDir?.let {
                        append(it).append(File.pathSeparator)
                    }
                }
                val abis =
                    if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS
                    else Build.SUPPORTED_32_BIT_ABIS
                for (abi in abis) {
                    append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator)
                }
            }

            // Create the isolated ClassLoader for the module
            val initLoader = XposedModule::class.java.classLoader
            val moduleClassLoader =
                VectorModuleClassLoader.loadApk(
                    module.apkPath,
                    module.code.preLoadedDexes,
                    librarySearchPath,
                    initLoader,
                    blockLegacyApi = module.code.targetApiVersion >= 102,
                )

            // Security/Integrity Check: Ensure the module isn't bundling its own API classes
            if (
                moduleClassLoader.loadClass(XposedModule::class.java.name).classLoader !==
                    initLoader
            ) {
                Log.e(TAG, "The Xposed API classes are compiled into ${module.packageName}")
                return null
            }

            // Create the Context that will be injected into the module
            val vectorContext =
                VectorContext(
                    packageName = module.packageName,
                    applicationInfo = module.applicationInfo,
                    service = module.service, // Our IPC client
                    defaultExceptionMode =
                        if (module.code.exceptionPassthrough) ExceptionMode.PASSTHROUGH
                        else ExceptionMode.PROTECTIVE,
                )

            val entryConstructors = mutableListOf<Constructor<*>>()
            val unusable = mutableListOf<String>()
            for (className in module.code.moduleClassNames) {
                val entryClass =
                    try {
                        moduleClassLoader.loadClass(className)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to load class $className", t)
                        unusable += "$className could not be loaded: ${describe(t)}"
                        continue
                    }
                Log.v(TAG, "Loading class $entryClass")

                if (!XposedModule::class.java.isAssignableFrom(entryClass)) {
                    Log.e(TAG, "Class $className does not extend XposedModule, skipping it")
                    unusable += "$className does not extend XposedModule"
                    continue
                }

                try {
                    entryConstructors += entryClass.getDeclaredConstructor()
                } catch (e: NoSuchMethodException) {
                    Log.e(TAG, "Class $className declares no no-argument constructor", e)
                    unusable +=
                        "$className declares no no-argument constructor, which the API requires " +
                            "of an entry class; it declares ${describeConstructors(entryClass)}"
                }
            }

            if (entryConstructors.isEmpty()) {
                // Same as the instantiation message: the reasons travel with it.
                Log.e(
                    TAG,
                    "No entry class of ${module.packageName} could be loaded: " +
                        unusable.joinToString("; "),
                )
                return null
            }

            return StagedGeneration(
                module,
                isSystemServer,
                processName,
                moduleClassLoader,
                vectorContext,
                entryConstructors,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error loading module ${module.packageName}", e)
            return null
        }
    }

    private fun describeConstructors(clazz: Class<*>): String =
        clazz.declaredConstructors.joinToString(", ", "(", ")") { constructor ->
            constructor.parameterTypes.joinToString(", ") { it.name }
        }

    private fun describeInstantiationFailure(className: String, t: Throwable): String {
        if (calledFrameworkBeforeAttach(t)) {
            return "$className calls the framework from its constructor, which runs before " +
                "attachFramework; module initialization belongs in onModuleLoaded"
        }
        val cause = if (t is InvocationTargetException) t.targetException else t
        return "$className: ${describe(cause)}"
    }

    private fun calledFrameworkBeforeAttach(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            val message = (current as? IllegalStateException)?.message
            if (message != null && message.contains(NOT_ATTACHED)) {
                return true
            }
            val next = current.cause
            current = if (next === current) null else next
        }
        return false
    }

    fun hotReload(
        modulePackageName: String?,
        extras: Bundle?,
        newModule: LoadedModule?,
    ): HotReloadOutcome {
        val packageName =
            modulePackageName ?: return unsupported("Hot reload was requested without a module")
        val lock = reloadLocks.computeIfAbsent(packageName) { ReentrantLock() }
        if (!lock.tryLock()) {
            return outcome(
                IXposedService.HOT_RELOAD_IN_PROGRESS,
                "A reload of $packageName is already running in this process",
            )
        }
        return try {
            runHotReload(packageName, extras, newModule)
        } catch (t: Throwable) {
            Log.e(TAG, "Hot reload of $packageName failed", t)
            failed(describe(t))
        } finally {
            lock.unlock()
        }
    }

    private fun runHotReload(
        packageName: String,
        extras: Bundle?,
        newModule: LoadedModule?,
    ): HotReloadOutcome {
        if (newModule == null) {
            return unsupported("No new generation of $packageName was supplied")
        }
        val old =
            generations[packageName]
                ?: return unsupported(
                    "$packageName is not loaded in ${VectorServiceClient.processName}"
                )
        if (newModule.code.moduleClassNames.size != 1) {
            return unsupported("$packageName does not declare exactly one Java entry class")
        }

        // Keeps the old generation reachable until onHotReloaded has finished.
        val oldEntries = old.liveEntries()
        if (oldEntries.isEmpty()) {
            // Not a refusal: a null message means onHotReloading returned false, and nothing ran.
            Log.w(TAG, "No attached entry of $packageName can accept a hot reload")
            return unsupported("Every entry of $packageName has detached in this process")
        }

        val staged =
            stageGeneration(newModule, old.isSystemServer, old.processName)
                ?: return unsupported("Cannot stage a new generation of $packageName")

        // Before the callback, so registrations from inside it fail while unhook and replace work.
        // Under the hook registry's lock for this module, because a registration on another thread
        // that has already passed its own check must either finish before the freeze - and so be in
        // the list the successor is handed - or see the freeze and fail. Held for the flag write
        // only: module code never runs inside it.
        synchronized(VectorHookBuilder.lockOf(packageName)) { old.context.freeze() }

        var savedState: Any? = null
        val reloadingParam =
            object : HotReloadingParam {
                override fun getExtras(): Bundle? = extras

                override fun setSavedInstanceState(outState: Any?) {
                    rejectOldGenerationState(outState, old.classLoader)
                    savedState = outState
                }
            }

        val accepted =
            try {
                // One refusal cancels the reload for the whole module.
                oldEntries.all { it.onHotReloading(reloadingParam) }
            } catch (t: Throwable) {
                old.context.unfreeze()
                Log.e(TAG, "onHotReloading of $packageName threw", t)
                return failed(describe(t))
            }
        if (!accepted) {
            old.context.unfreeze()
            Log.d(TAG, "$packageName refused the hot reload")
            return refusal()
        }

        // Captured after the freeze and after old code had its chance to unhook.
        val oldHandles = VectorHookBuilder.snapshotHandles(packageName)

        val built = staged.instantiate()
        if (built == null) {
            old.context.unfreeze()
            return failed("Cannot instantiate the new generation of $packageName")
        }
        val (newGeneration, newEntries) = built

        oldEntries.forEach { VectorLifecycleManager.activeModules.remove(it) }
        // Active before the callback, so an entry detaching from inside it is honoured.
        newEntries.forEach { VectorLifecycleManager.activeModules.add(it) }

        val reloadedParam =
            object : HotReloadedParam {
                override fun isSystemServer(): Boolean = old.isSystemServer

                override fun getProcessName(): String = old.processName

                override fun getExtras(): Bundle? = extras

                override fun getSavedInstanceState(): Any? = savedState

                override fun getOldHookHandles(): List<XposedInterface.HookHandle> = oldHandles
            }

        // Committed before the callback runs, because the interface releases the old generation
        // "after this callback returns or throws" - there is no rollback. A throw here leaves the
        // process running new code that has migrated some of its hooks and not others, and says so
        // through the result; rolling back could not undo the replaceHook calls the new code had
        // already made anyway, and would restore old entries whose hooks now run new hookers.
        generations[packageName] = newGeneration

        var failure: Throwable? = null
        // The default onHotReloaded already unhooks these; doing both would double-unhook.
        newEntries
            .filter { VectorLifecycleManager.isActive(it) }
            .forEach {
                if (failure != null) return@forEach
                runCatching { it.onHotReloaded(reloadedParam) }.onFailure { t -> failure = t }
            }

        // The last framework-owned reference to the old generation goes with this frame: its map
        // entry is gone, its entries are out of activeModules, and oldEntries dies on return.
        failure?.let {
            Log.e(TAG, "onHotReloaded of $packageName threw", it)
            return failed(describe(it), generationChanged = true)
        }

        Log.d(TAG, "Hot reloaded $packageName")
        return outcome(IXposedService.HOT_RELOAD_SUCCEEDED, null, generationChanged = true)
    }

    /**
     * Rejects saved state that the old generation created, which would otherwise keep the retired
     * classloader reachable through the new one. A shallow scan, as the API describes it: a
     * diagnostic aid rather than an object graph verifier.
     */
    private fun rejectOldGenerationState(state: Any?, oldClassLoader: ClassLoader) {
        if (state == null) return
        reject(state, oldClassLoader)
        when (state) {
            is Array<*> -> state.forEach { it?.let { e -> reject(e, oldClassLoader) } }
            is Collection<*> -> state.forEach { it?.let { e -> reject(e, oldClassLoader) } }
            is Map<*, *> ->
                state.forEach { (k, v) ->
                    k?.let { reject(it, oldClassLoader) }
                    v?.let { reject(it, oldClassLoader) }
                }
        }
    }

    private fun reject(value: Any, oldClassLoader: ClassLoader) {
        if (definedBy(value.javaClass, oldClassLoader)) {
            throw IllegalArgumentException(
                "Saved instance state contains ${value.javaClass.name}, which was created under " +
                    "the old module classloader"
            )
        }
    }

    private fun definedBy(clazz: Class<*>, classLoader: ClassLoader): Boolean {
        var loader: ClassLoader? =
            (if (clazz.isArray) clazz.componentType else clazz)?.classLoader
        while (loader != null) {
            if (loader === classLoader) return true
            loader = loader.parent
        }
        return false
    }

    private fun outcome(
        status: Int,
        message: String?,
        refused: Boolean = false,
        generationChanged: Boolean = false,
    ) =
        HotReloadOutcome().apply {
            this.status = status
            this.message = message
            this.refused = refused
            this.generationChanged = generationChanged
        }

    private fun unsupported(message: String) =
        outcome(IXposedService.HOT_RELOAD_UNSUPPORTED, message)

    private fun failed(message: String, generationChanged: Boolean = false) =
        outcome(IXposedService.HOT_RELOAD_FAILED, message, generationChanged = generationChanged)

    private fun refusal() = outcome(IXposedService.HOT_RELOAD_FAILED, null, refused = true)

    private fun describe(t: Throwable) = "${t.javaClass.name}: ${t.message ?: "no message"}"
}

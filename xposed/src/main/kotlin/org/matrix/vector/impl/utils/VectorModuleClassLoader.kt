package org.matrix.vector.impl.utils

import android.os.Build
import android.os.SharedMemory
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import hidden.ByteBufferDexClassLoader
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Enumeration
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import org.matrix.vector.nativebridge.HookBridge

/**
 * Custom ClassLoader for module execution. Utilizes in-memory DEX loading to prevent the need to
 * extract module code to the disk, enhancing both security and performance during the module
 * lifecycle.
 */
class VectorModuleClassLoader : ByteBufferDexClassLoader {

    private val apkPath: String
    private val blockLegacyApi: Boolean
    private val nativeLibraryDirs = mutableListOf<File>()

    @RequiresApi(Build.VERSION_CODES.Q)
    private constructor(
        dexBuffers: Array<ByteBuffer>,
        librarySearchPath: String?,
        parent: ClassLoader?,
        apkPath: String,
        blockLegacyApi: Boolean,
    ) : super(dexBuffers, librarySearchPath, parent) {
        this.apkPath = apkPath
        this.blockLegacyApi = blockLegacyApi
        initNativeDirs(librarySearchPath)
    }

    private constructor(
        dexBuffers: Array<ByteBuffer>,
        parent: ClassLoader?,
        apkPath: String,
        librarySearchPath: String?,
        blockLegacyApi: Boolean,
    ) : super(dexBuffers, parent) {
        this.apkPath = apkPath
        this.blockLegacyApi = blockLegacyApi
        initNativeDirs(librarySearchPath)
    }

    private fun initNativeDirs(librarySearchPath: String?) {
        val searchPath = librarySearchPath ?: ""
        nativeLibraryDirs.addAll(splitPaths(searchPath))
        nativeLibraryDirs.addAll(SYSTEM_NATIVE_LIBRARY_DIRS)
    }

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // API 102 forbids libxposed modules from calling the legacy de.robv APIs. This loader's
        // parent is the framework's own loader, which carries the legacy bridge, so refusing to
        // resolve those names here is what actually enforces it - reflective lookups against this
        // loader included, since Class.forName and loadClass both funnel through this override.
        if (blockLegacyApi && LEGACY_API_PREFIXES.any { name.startsWith(it) }) {
            throw ClassNotFoundException(
                "$name is unavailable to modules targeting Xposed API 102 or higher"
            )
        }

        findLoadedClass(name)?.let {
            return it
        }

        try {
            return Any::class.java.classLoader!!.loadClass(name)
        } catch (ignored: ClassNotFoundException) {}

        var fromSuper: ClassNotFoundException? = null
        try {
            return findClass(name)
        } catch (ex: ClassNotFoundException) {
            fromSuper = ex
        }

        try {
            return parent?.loadClass(name) ?: throw fromSuper
        } catch (cnfe: ClassNotFoundException) {
            throw fromSuper
        }
    }

    override fun findLibrary(libraryName: String): String? {
        val fileName = System.mapLibraryName(libraryName)
        for (file in nativeLibraryDirs) {
            val path = file.path
            if (path.contains(ZIP_SEPARATOR)) {
                val split = path.split(ZIP_SEPARATOR, limit = 2)
                try {
                    JarFile(split[0]).use { jarFile ->
                        val entryName = "${split[1]}/$fileName"
                        val entry = jarFile.getEntry(entryName)
                        if (entry != null && entry.method == ZipEntry.STORED) {
                            return "${split[0]}$ZIP_SEPARATOR$entryName"
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Cannot open ${split[0]}", e)
                }
            } else if (file.isDirectory) {
                val entryPath = File(file, fileName).path
                try {
                    val fd = Os.open(entryPath, OsConstants.O_RDONLY, 0)
                    Os.close(fd)
                    return entryPath
                } catch (ignored: ErrnoException) {}
            }
        }
        return null
    }

    override fun findResource(name: String): URL? {
        return try {
            val urlHandler = VectorURLStreamHandler(apkPath)
            urlHandler.getEntryUrlOrNull(name)
        } catch (e: IOException) {
            null
        }
    }

    override fun findResources(name: String): Enumeration<URL> {
        val url = findResource(name)
        val result = if (url != null) listOf(url) else emptyList()
        return Collections.enumeration(result)
    }

    override fun toString(): String {
        return "VectorModuleClassLoader[module=$apkPath, ${super.toString()}]"
    }

    companion object {
        private const val TAG = "VectorModuleClassLoader"
        private const val ZIP_SEPARATOR = "!/"

        /**
         * What the legacy API is called *here*, which is not what it is called in source: the
         * daemon rewrites `de.robv.android.xposed` in the framework dex and in every module dex
         * when dex obfuscation is on, so the name a module asks this loader for is a different
         * random string on every boot. Matching the literal package would leave the 102 rule
         * unenforced on exactly the builds that have obfuscation turned on.
         *
         * Only that package is refused. `AndroidAppHelper` and the `XResources` family are rewritten
         * by the same table and were once refused with it, but the interface names one package and
         * gives modules targeting 102 no resource API to move to.
         */
        private val LEGACY_API_PREFIXES: Array<String> by lazy {
            runCatching { HookBridge.legacyApiPrefixes() }
                .onFailure { Log.w(TAG, "Cannot resolve the legacy API prefixes", it) }
                .getOrElse { arrayOf("de.robv.android.xposed.") }
        }
        private val SYSTEM_NATIVE_LIBRARY_DIRS = splitPaths(System.getProperty("java.library.path"))

        private fun splitPaths(searchPath: String?): List<File> {
            if (searchPath.isNullOrEmpty()) return emptyList()
            return searchPath.split(File.pathSeparator).map { File(it) }
        }

        /**
         * Loads an APK into memory securely. Maps the provided [SharedMemory] instances into
         * read-only [ByteBuffer]s and cleans up the memory file descriptors once the ClassLoader is
         * fully instantiated.
         */
        @JvmStatic
        @JvmOverloads
        fun loadApk(
            apk: String,
            dexes: List<SharedMemory>,
            librarySearchPath: String,
            parent: ClassLoader?,
            blockLegacyApi: Boolean = false,
        ): ClassLoader {
            return withConsumedDexBuffers(dexes) { dexBuffers ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VectorModuleClassLoader(
                        dexBuffers,
                        librarySearchPath,
                        parent,
                        apk,
                        blockLegacyApi,
                    )
                } else {
                    VectorModuleClassLoader(
                        dexBuffers,
                        parent,
                        apk,
                        librarySearchPath,
                        blockLegacyApi,
                    )
                }
            }
        }
    }
}

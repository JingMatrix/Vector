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
import java.util.stream.Collectors
import java.util.zip.ZipEntry

/**
 * Custom ClassLoader for module execution. Utilizes in-memory DEX loading to prevent the need to
 * extract module code to the disk, enhancing both security and performance during the module
 * lifecycle.
 */
class VectorModuleClassLoader : ByteBufferDexClassLoader {

    private val apkPath: String
    private val nativeLibraryDirs = mutableListOf<File>()

    @RequiresApi(Build.VERSION_CODES.Q)
    private constructor(
        dexBuffers: Array<ByteBuffer>,
        librarySearchPath: String?,
        parent: ClassLoader?,
        apkPath: String,
    ) : super(dexBuffers, librarySearchPath, parent) {
        this.apkPath = apkPath
        initNativeDirs(librarySearchPath)
    }

    private constructor(
        dexBuffers: Array<ByteBuffer>,
        parent: ClassLoader?,
        apkPath: String,
        librarySearchPath: String?,
    ) : super(dexBuffers, parent) {
        this.apkPath = apkPath
        initNativeDirs(librarySearchPath)
    }

    private fun initNativeDirs(librarySearchPath: String?) {
        val searchPath = librarySearchPath ?: ""
        nativeLibraryDirs.addAll(splitPaths(searchPath))
        nativeLibraryDirs.addAll(SYSTEM_NATIVE_LIBRARY_DIRS)
    }

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
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
        fun loadApk(
            apk: String,
            dexes: List<SharedMemory>,
            librarySearchPath: String,
            parent: ClassLoader?,
        ): ClassLoader {
            val dexBuffers =
                dexes
                    .parallelStream()
                    .map { dex ->
                        try {
                            dex.mapReadOnly()
                        } catch (e: ErrnoException) {
                            Log.w(TAG, "Cannot map $dex", e)
                            null
                        }
                    }
                    .collect(Collectors.toList())
                    .filterNotNull()
                    .toTypedArray()

            val cl =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VectorModuleClassLoader(dexBuffers, librarySearchPath, parent, apk)
                } else {
                    VectorModuleClassLoader(dexBuffers, parent, apk, librarySearchPath)
                }

            dexBuffers.toList().parallelStream().forEach { SharedMemory.unmap(it) }
            dexes.parallelStream().forEach { it.close() }

            return cl
        }
    }
}

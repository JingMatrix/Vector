package org.matrix.vector.impl.utils

import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.JarURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLConnection
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import sun.net.www.protocol.jar.Handler

/**
 * Custom URLStreamHandler for loading resources directly from the module's APK. Optimized to handle
 * internal JAR/ZIP entries without extracting them to the filesystem.
 */
internal class VectorURLStreamHandler(jarFileName: String) : Handler() {
    private val fileUri: String = File(jarFileName).toURI().toString()
    private val jarFile: JarFile = JarFile(jarFileName)

    fun getEntryUrlOrNull(entryName: String): URL? {
        if (jarFile.getEntry(entryName) != null) {
            return try {
                val encodedName = Uri.encode(entryName, "/")
                URL("jar", null, -1, "$fileUri!/$encodedName", this)
            } catch (e: MalformedURLException) {
                throw RuntimeException("Invalid entry name: $entryName", e)
            }
        }
        return null
    }

    @Throws(IOException::class)
    override fun openConnection(url: URL): URLConnection {
        return ClassPathURLConnection(url)
    }

    @Suppress("deprecation")
    @Throws(IOException::class)
    protected fun finalize() {
        jarFile.close()
    }

    private inner class ClassPathURLConnection(url: URL) : JarURLConnection(url) {
        private var connectionJarFile: JarFile? = null
        private var jarEntry: ZipEntry? = null
        private var jarInput: InputStream? = null
        private var isClosed = false

        init {
            useCaches = false
        }

        override fun setUseCaches(usecaches: Boolean) {
            super.setUseCaches(false)
        }

        @Throws(IOException::class)
        override fun connect() {
            check(!isClosed) { "JarURLConnection has been closed" }
            if (!connected) {
                jarEntry =
                    this@VectorURLStreamHandler.jarFile.getEntry(entryName)
                        ?: throw FileNotFoundException(
                            "URL=$url, zipfile=${this@VectorURLStreamHandler.jarFile.name}"
                        )
                connected = true
            }
        }

        @Throws(IOException::class)
        override fun getJarFile(): JarFile {
            connect()
            return connectionJarFile
                ?: JarFile(this@VectorURLStreamHandler.jarFile.name).also { connectionJarFile = it }
        }

        @Throws(IOException::class)
        override fun getInputStream(): InputStream {
            connect()
            return jarInput
                ?: object :
                        FilterInputStream(
                            this@VectorURLStreamHandler.jarFile.getInputStream(jarEntry)
                        ) {
                        @Throws(IOException::class)
                        override fun close() {
                            super.close()
                            isClosed = true
                            this@VectorURLStreamHandler.jarFile.close()
                            connectionJarFile?.close()
                        }
                    }
                    .also { jarInput = it }
        }

        override fun getContentType(): String {
            return guessContentTypeFromName(entryName) ?: "content/unknown"
        }

        override fun getContentLength(): Int {
            return try {
                connect()
                jarEntry?.size?.toInt() ?: -1
            } catch (ignored: IOException) {
                -1
            }
        }
    }
}

package org.matrix.vector.impl.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.jar.JarFile
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlVisitor
import pxb.android.axml.NodeVisitor

/**
 * Utility for parsing metadata configuration strictly from AndroidManifest.xml. Utilizes AXML
 * reading to extract runtime constraints like minimum framework versions without requiring full APK
 * extraction.
 */
class VectorMetaDataReader private constructor(apk: File) {

    val metaData = mutableMapOf<String, Any>()

    init {
        JarFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
            zip.getInputStream(entry).use { inputStream ->
                val reader = AxmlReader(getBytesFromInputStream(inputStream))
                reader.accept(
                    object : AxmlVisitor() {
                        override fun child(ns: String?, name: String?): NodeVisitor? {
                            // We only care about the root <manifest> tag.
                            // Returning ManifestTagVisitor() tells the parser to start
                            // looking at things inside <manifest>.
                            return ManifestTagVisitor()
                        }
                    }
                )
            }
        }
    }

    private inner class ManifestTagVisitor : NodeVisitor() {
        override fun child(ns: String?, name: String?): NodeVisitor? {
            // If we see <application>, we return a visitor to go deeper.
            if (name == "application") return ApplicationTagVisitor()
            // If we see <permission> or <activity>, we return null to skip them entirely.
            return null
        }
    }

    // Handles the inside of <application>
    private inner class ApplicationTagVisitor : NodeVisitor() {
        override fun child(ns: String?, name: String?): NodeVisitor? {
            // We only care about <meta-data> tags.
            if (name == "meta-data") return MetaDataVisitor()
            return null
        }
    }

    private inner class MetaDataVisitor : NodeVisitor() {
        var attrName: String? = null
        var attrValue: Any? = null

        override fun attr(ns: String?, name: String?, resourceId: Int, type: Int, obj: Any?) {
            if (type == 3 && name == "name") {
                attrName = obj as? String
            }
            if (name == "value") {
                attrValue = obj
            }
            super.attr(ns, name, resourceId, type, obj)
        }

        override fun end() {
            if (attrName != null && attrValue != null) {
                metaData[attrName!!] = attrValue!!
            }
            super.end()
        }
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun getMetaData(apk: File): Map<String, Any> {
            return VectorMetaDataReader(apk).metaData
        }

        @Throws(IOException::class)
        private fun getBytesFromInputStream(inputStream: InputStream): ByteArray {
            ByteArrayOutputStream().use { bos ->
                val b = ByteArray(1024)
                var n: Int
                while (inputStream.read(b).also { n = it } != -1) {
                    bos.write(b, 0, n)
                }
                return bos.toByteArray()
            }
        }

        @JvmStatic
        fun extractIntPart(str: String): Int {
            var result = 0
            for (c in str) {
                if (c in '0'..'9') {
                    result = result * 10 + (c - '0')
                } else {
                    break
                }
            }
            return result
        }
    }
}

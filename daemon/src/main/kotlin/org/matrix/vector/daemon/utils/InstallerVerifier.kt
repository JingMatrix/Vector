package org.matrix.vector.daemon.utils

import com.android.apksig.ApkVerifier
import java.io.File
import java.io.IOException

object InstallerVerifier {

  @Throws(IOException::class)
  fun verifyInstallerSignature(path: String) {
    val verifier = ApkVerifier.Builder(File(path)).setMinCheckedPlatformVersion(27).build()

    try {
      val result = verifier.verify()
      if (!result.isVerified) {
        throw IOException("APK signature not verified")
      }

      val mainCert = result.signerCertificates[0]
      if (!mainCert.encoded.contentEquals(SignInfo.CERTIFICATE)) {
        val dname = mainCert.subjectX500Principal.name
        throw IOException("APK signature mismatch: $dname")
      }
    } catch (e: Exception) {
      throw IOException(e)
    }
  }
}

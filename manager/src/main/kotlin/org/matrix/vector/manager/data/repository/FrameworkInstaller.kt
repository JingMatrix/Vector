package org.matrix.vector.manager.data.repository

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.lsposed.lspd.IFrameworkInstallCallback
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.Constants
import org.matrix.vector.manager.ipc.DaemonClient

/** Where a framework flash has got to. */
sealed interface FlashStep {

    data object Idle : FlashStep

    data class Downloading(val bytes: Long, val total: Long) : FlashStep

    /** The daemon is running the installer; [FrameworkInstaller.lines] grows as it speaks. */
    data object Flashing : FlashStep

    /** The installer exited zero. A reboot is what makes it take effect. */
    data object Done : FlashStep

    /** [code] is the installer's exit status, or one of ILSPManagerService.INSTALL_*. */
    data class Failed(val code: Int) : FlashStep
}

/**
 * Downloads a framework zip and hands it to the daemon to flash.
 *
 * **Via a file, unlike the module installer.** That one streams an APK straight into a
 * `PackageInstaller` session with no temporary file, and the reasoning does not carry over: a root
 * implementation's installer is a program that takes a *path*, so there has to be a file for it to
 * open. It goes in the manager's own cache directory, which the daemon can read as root, and it is
 * deleted once the installer has exited.
 *
 * **The download is separate from the flash, and reported separately**, because they fail for
 * unrelated reasons and the reader needs to know which happened. A download that dies on a flaky
 * connection has changed nothing on the device; an installer that dies halfway has.
 */
class FrameworkInstaller(
    private val context: Context,
    private val client: OkHttpClient,
    private val daemon: DaemonClient,
) {

    private val _state = MutableStateFlow<FlashStep>(FlashStep.Idle)
    val state: StateFlow<FlashStep> = _state.asStateFlow()

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Everything the installer has said, in order. Cleared when a new flash starts. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun acknowledge() {
        _state.value = FlashStep.Idle
        _lines.value = emptyList()
    }

    /**
     * Fetches [url] and flashes it.
     *
     * Returns when the *installer* has exited, not when the download finishes — the caller is a
     * screen that stays open across both.
     */
    suspend fun flash(url: String, declaredSize: Long, fileName: String) {
        _lines.value = emptyList()
        val zip =
            try {
                download(url, declaredSize, fileName)
            } catch (e: Exception) {
                Log.w(Constants.TAG, "update: download failed", e)
                append("Download failed: ${e.message}")
                _state.value = FlashStep.Failed(ILSPManagerService.INSTALL_NO_SUCH_FILE)
                return
            }

        _state.value = FlashStep.Flashing
        try {
            awaitInstall(zip.absolutePath)
        } finally {
            // Deleted once this is no longer waiting on the install: a release zip left in the
            // cache costs tens of megabytes that nothing else will ever clean up.
            runCatching { zip.delete() }
        }
    }

    private suspend fun download(url: String, declaredSize: Long, fileName: String): File =
        withContext(Dispatchers.IO) {
            val target = File(context.cacheDir, fileName)
            _state.value = FlashStep.Downloading(0, declaredSize)

            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: declaredSize

                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER)
                        var written = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            written += read
                            _state.value = FlashStep.Downloading(written, total)
                        }
                    }
                }
            }
            target
        }

    /**
     * Runs the daemon-side install and suspends until it reports an exit code.
     *
     * The installer's output arrives on the callback as it is produced rather than with the result,
     * so the screen fills in during a flash that takes minutes. The exit code comes separately,
     * over a deferred whose await is cancellable — leaving the screen stops the wait, and the
     * daemon keeps flashing regardless, which is the only safe thing for it to do half way through
     * writing a module tree.
     */
    private suspend fun awaitInstall(path: String) {
        val done = kotlinx.coroutines.CompletableDeferred<Int>()
        val callback =
            object : IFrameworkInstallCallback.Stub() {
                override fun onLine(line: String?) {
                    line?.let(::append)
                }

                override fun onFinished(exitCode: Int) {
                    done.complete(exitCode)
                }
            }

        val started = daemon.installFrameworkZip(path, callback)
        if (started.isFailure) {
            val cause = started.exceptionOrNull()
            Log.e(Constants.TAG, "update: daemon did not start the install of $path", cause)
            append("The daemon refused the install: ${cause?.message}")
            _state.value = FlashStep.Failed(ILSPManagerService.INSTALL_NOT_EXECUTED)
            return
        }

        val exit = done.await()
        _state.value = if (exit == 0) FlashStep.Done else FlashStep.Failed(exit)
    }

    private fun append(line: String) {
        // Bounded: an installer that loops would otherwise grow this without limit, and the screen
        // follows the tail.
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }

    private companion object {
        const val DOWNLOAD_BUFFER = 64 * 1024
        const val MAX_LINES = 500
    }
}

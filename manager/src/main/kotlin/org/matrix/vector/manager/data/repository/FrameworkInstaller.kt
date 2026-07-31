package org.matrix.vector.manager.data.repository

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 *
 * **The flash belongs to this object, not to the screen that asked for it.** It is the daemon that
 * is doing the work, and the daemon does not stop for anything the manager does, so the only thing
 * a caller taken away mid-flash can achieve is losing the answer.
 */
class FrameworkInstaller(
    private val context: Context,
    private val client: OkHttpClient,
    private val daemon: DaemonClient,
) {

    /**
     * The flash's own scope, alive for as long as the process is.
     *
     * A flash takes minutes, and the screen that starts one is a single back gesture away from
     * being destroyed together with its view model scope. When the work ran there, that gesture
     * killed the one line that reads the installer's exit code and moves off [FlashStep.Flashing]:
     * the daemon finished the install regardless, and the manager went on reporting a flash that
     * was already over, with no button anywhere on the bar to say otherwise, until it was force
     * stopped. Supervised, so a run that ends in a throw does not take the scope down with it and
     * leave the next flash nowhere to run.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<FlashStep>(FlashStep.Idle)
    val state: StateFlow<FlashStep> = _state.asStateFlow()

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /**
     * Everything the installer has said, in order.
     *
     * Cleared when a new flash starts, and when a finished one is put away with [acknowledge].
     */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private var job: Job? = null

    /**
     * Starts fetching [url] and flashing it, and returns straight away.
     *
     * There is nothing to wait for: [state] and [lines] are where the answer arrives, and they
     * outlive whatever screen is watching them.
     *
     * One at a time. A second call while a flash is in flight is refused rather than queued behind
     * it — the only way to reach one is a button on a screen that is reporting a flash in progress,
     * so honouring it would mean acting on a decision made against a screen that had moved on. And
     * refused means untouched: clearing [lines] on a press that starts nothing would empty the log
     * of the flash that is actually running.
     */
    fun start(url: String, declaredSize: Long, fileName: String) {
        if (job?.isActive == true) return
        _lines.value = emptyList()
        // Here rather than when the first byte lands: opening the connection can take seconds, and
        // a press that leaves the Install button sitting where it was reads as a press that missed.
        _state.value = FlashStep.Downloading(0, declaredSize)
        job = scope.launch { flash(url, declaredSize, fileName) }
    }

    /**
     * Puts a finished flash away, so the bar goes back to offering one.
     *
     * Only a finished one. A flash still running owns what [state] says about it, and clearing it
     * would leave the download and the installer going with nothing on screen admitting it — which
     * is the reset this class used to do to itself.
     *
     * That refusal is only safe because [FlashStep.Flashing] cannot outlive the thing it is waiting
     * for. The one way the exit code never arrives is the daemon dying with the install started,
     * and `Constants.setBinder` links to that death and exits the manager process — so the state
     * this declines to clear goes with it, rather than becoming a spinner nothing can reach.
     */
    fun acknowledge() {
        val step = _state.value
        if (step !is FlashStep.Done && step !is FlashStep.Failed) return
        _state.value = FlashStep.Idle
        _lines.value = emptyList()
    }

    /**
     * Fetches [url] and flashes it, reporting where it has got to through [state].
     *
     * Runs to the end on [scope] whatever the screen that asked for it does. Nothing cancels it:
     * an installer half way through writing a module tree cannot be recalled, so stopping the wait
     * would throw away the exit code and change nothing else.
     */
    private suspend fun flash(url: String, declaredSize: Long, fileName: String) {
        val zip =
            try {
                download(url, declaredSize, fileName)
            } catch (e: Exception) {
                // Nothing cancels a run any more, so this is only ever the process going away —
                // but a cancellation is a coroutine ending, not a file that could not be fetched,
                // and it must never be recorded as one.
                if (e is CancellationException) throw e
                Log.w(Constants.TAG, "update: download failed", e)
                append("Download failed: ${e.message}")
                _state.value = FlashStep.Failed(ILSPManagerService.INSTALL_NO_SUCH_FILE)
                return
            }

        _state.value = FlashStep.Flashing
        try {
            awaitInstall(zip.absolutePath)
        } finally {
            // Deleted once the installer has exited: a release zip left in the cache costs tens of
            // megabytes that nothing else will ever clean up.
            runCatching { zip.delete() }
        }
    }

    private suspend fun download(url: String, declaredSize: Long, fileName: String): File =
        withContext(Dispatchers.IO) {
            val target = File(context.cacheDir, fileName)

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
     * so the screen fills in during a flash that takes minutes. The exit code comes separately, on
     * a deferred nobody here abandons: it is the one moment the flash can be called finished, and a
     * wait that ended early left the bar spinning over an install that had long since succeeded.
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

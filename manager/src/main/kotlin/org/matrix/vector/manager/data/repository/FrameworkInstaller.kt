package org.matrix.vector.manager.data.repository

import android.content.Context
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
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.matrix.vector.ipc.IFrameworkInstallReceiver
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW

/** Where a framework flash has got to. */
sealed interface FlashStep {

    data object Idle : FlashStep

    data class Downloading(val bytes: Long, val total: Long) : FlashStep

    /** The daemon is running the installer; [FrameworkInstaller.lines] grows as it speaks. */
    data object Flashing : FlashStep

    /** The installer exited zero. A reboot is what makes it take effect. */
    data object Done : FlashStep

    /** [code] is the installer's exit status, or one of IFrameworkInstallReceiver.INSTALL_*. */
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
 * a caller taken away mid-flash can achieve is losing the answer. The download in front of it is
 * the exception — it has changed nothing on the device yet, and [cancelDownload] is where the
 * difference is argued.
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
     * The transfer in flight, and the only part of a flash that can be called off.
     *
     * The HTTP call rather than the coroutine holding it. Cancelling the coroutine would also
     * cancel the wait for the installer's exit code if the press landed in the instant between the
     * last byte and the daemon starting, and losing that wait is the failure this class exists to
     * prevent; cancelling the call can only ever end a transfer. Written from whichever thread
     * pressed the button and read on the download's own, hence volatile.
     */
    @Volatile private var transfer: Call? = null

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
        // Built here rather than on the download's own thread so that [cancelDownload] has
        // something to cancel from the first frame the progress row is on screen: a press that
        // arrived before the coroutine had been dispatched would otherwise find nothing to stop
        // and be dropped in silence. A call cancelled before it is executed refuses to run at all,
        // which is the same outcome by a shorter route.
        //
        // Guarded because building it parses the url, and `HttpUrl` throws on one it cannot read.
        // That used to happen inside the download coroutine, where the surrounding catch turned it
        // into a failed step; here it would be an uncaught exception on the caller's thread, which
        // is the main one. A release with an unusable asset url would take the app down on a press
        // of Install rather than saying so on the bar.
        val call =
            runCatching { client.newCall(Request.Builder().url(url).build()) }
                .getOrElse { e ->
                    logW("update: unusable download url $url", e)
                    append("Download failed: ${e.message}")
                    _state.value = FlashStep.Failed(IFrameworkInstallReceiver.INSTALL_NO_SUCH_FILE)
                    return
                }
        transfer = call
        // Here rather than when the first byte lands: opening the connection can take seconds, and
        // a press that leaves the Install button sitting where it was reads as a press that missed.
        _state.value = FlashStep.Downloading(0, declaredSize)
        job = scope.launch { flash(call, declaredSize, fileName) }
    }

    /**
     * Calls off a download in progress, leaving nothing of it behind.
     *
     * A download and nothing else. [FlashStep.Flashing] has no equivalent and must not grow one:
     * an installer half way through writing a module tree cannot be recalled, so stopping the wait
     * would throw away the exit code and change nothing on the device. A transfer is the opposite
     * case — it has changed nothing yet, it can be tens of megabytes over mobile data, and letting
     * one the reader has abandoned run to the end would flash a build they had decided against.
     *
     * Nothing here touches [state]. The transfer's own unwinding puts the bar back to
     * [FlashStep.Idle], after it has deleted the part of the zip it had written and at the moment
     * it has actually stopped, which is the only moment at which saying so is true.
     *
     * A press from anywhere else is a no-op: [transfer] is cleared the moment the transfer returns,
     * and a press that beats it there by a hair finds a call that has already delivered every byte
     * it was asked for. Either way the flash goes ahead, which is what "too late" has to mean.
     */
    fun cancelDownload() {
        // OkHttp's cancel closes the socket from under the read, so the transfer returns at once
        // rather than when the connection's read timeout expires — the difference between a
        // button that works and a button that looks broken on a stalled download.
        transfer?.cancel()
    }

    /**
     * Puts a finished flash away, so the bar goes back to offering one.
     *
     * Only a finished one. A flash still running owns what [state] says about it, and clearing it
     * would leave the download and the installer going with nothing on screen admitting it — which
     * is the reset this class used to do to itself. A transfer the reader genuinely wants rid of
     * is ended by [cancelDownload], which stops it rather than hiding it.
     *
     * [FlashStep.Flashing] is the state this refusal can strand, and the residual risk is worth
     * stating rather than wishing away. Two things end that wait. The daemon dying with the
     * install started is one, and it needs nothing from here: `Constants.setBinder` links to that
     * death and exits the manager process, taking this state with it. The other is the exit code
     * arriving — over a `oneway` binder call that `ManagerService.installFrameworkZip` wraps in
     * `runCatching`, logging "Could not report install result" and carrying on when the transaction
     * fails. A `oneway` call fails when the *receiving* process's transaction buffer is full, which
     * a chatty installer's output can do to this one, and the daemon that then gives up is very
     * much alive — so no death recipient fires, [state] stays here, and the bar reports a flash
     * that is over for the life of the process. The install itself is unharmed and the device has
     * it; what was lost is only the report, and this object holds nothing across a restart, so
     * ending the manager process is the way out. That is a poor escape but a better one than
     * dismissing the row would be: from here a lost report and an installer still working look
     * exactly alike, so a dismissal offered for the first is a dismissal offered during every
     * flash — and this class was given a life of its own precisely to stop that press existing.
     */
    fun acknowledge() {
        val step = _state.value
        if (step !is FlashStep.Done && step !is FlashStep.Failed) return
        _state.value = FlashStep.Idle
        _lines.value = emptyList()
    }

    /**
     * Fetches what [call] asks for and flashes it, reporting where it has got to through [state].
     *
     * Runs to the end on [scope] whatever the screen that asked for it does. Only the download can
     * be called off, and only through the call it is made on: from [FlashStep.Flashing] onwards
     * there is nothing to stop, because an installer half way through writing a module tree cannot
     * be recalled and abandoning the wait would throw away the exit code and change nothing else.
     */
    private suspend fun flash(call: Call, declaredSize: Long, fileName: String) {
        val zip =
            try {
                download(call, declaredSize, fileName)
            } catch (_: DownloadAbandoned) {
                // Called off, not broken. Nothing reached the device and nothing is left in the
                // cache, so there is no failure to report and nothing for the reader to put away:
                // the bar goes back to offering the flash it was offering before the press. This
                // is the one place that can say so, because it is the moment the transfer has
                // actually stopped.
                _state.value = FlashStep.Idle
                return
            } catch (e: Exception) {
                // Only the process going away can cancel this coroutine — a download is called off
                // through its call, which arrives above — but a cancellation is a coroutine ending,
                // not a file that could not be fetched, and it must never be recorded as one.
                if (e is CancellationException) throw e
                logW("update: download failed", e)
                append("Download failed: ${e.message}")
                _state.value = FlashStep.Failed(IFrameworkInstallReceiver.INSTALL_NO_SUCH_FILE)
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

    private suspend fun download(call: Call, declaredSize: Long, fileName: String): File =
        withContext(Dispatchers.IO) {
            val target = File(context.cacheDir, fileName)

            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} for ${call.request().url}")
                    }
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
            } catch (e: Throwable) {
                // Nothing will ever finish what is on disk. A transfer that stopped in the middle
                // leaves a truncated zip — tens of megabytes of it for a release build — that only
                // another attempt at the same file name would overwrite, and nothing else in the
                // app would ever reclaim; a connection that never opened leaves no file at all,
                // and deleting that costs nothing.
                runCatching { target.delete() }
                // A cancelled call reaches this as the socket read failing, which is true of the
                // socket and false about what happened: the reader stopped it. Said in the type,
                // because the caller has two entirely different things to do about the two cases.
                if (call.isCanceled()) throw DownloadAbandoned()
                throw e
            } finally {
                // Cleared here rather than by the caller so that it is cleared on every way out,
                // including the successful one: from this point the flash is the daemon's and
                // there must be nothing left for a cancel to reach.
                transfer = null
            }
        }

    /**
     * Runs the daemon-side install and suspends until it reports an exit code.
     *
     * The installer's output arrives on the receiver as it is produced rather than with the result,
     * so the screen fills in during a flash that takes minutes. The exit code comes separately, on
     * a deferred nobody here abandons: it is the one moment the flash can be called finished, and a
     * wait that ended early left the bar spinning over an install that had long since succeeded.
     */
    private suspend fun awaitInstall(path: String) {
        val done = kotlinx.coroutines.CompletableDeferred<Int>()
        val receiver =
            object : IFrameworkInstallReceiver.Stub() {
                override fun onLine(line: String?) {
                    line?.let(::append)
                }

                override fun onFinished(exitCode: Int) {
                    done.complete(exitCode)
                }
            }

        val started = daemon.installFrameworkZip(path, receiver)
        if (started.isFailure) {
            val cause = started.exceptionOrNull()
            logE("update: daemon did not start the install of $path", cause)
            append("The daemon refused the install: ${cause?.message}")
            _state.value = FlashStep.Failed(IFrameworkInstallReceiver.INSTALL_NOT_EXECUTED)
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

    /**
     * The transfer was called off by [cancelDownload], rather than failing.
     *
     * An `IOException` because that is what it is thrown in place of, and its own type because the
     * two are the opposite kind of news: one is something to tell the reader about, and the other
     * is the reader telling this class something.
     */
    private class DownloadAbandoned : IOException("Download abandoned")

    private companion object {
        const val DOWNLOAD_BUFFER = 64 * 1024
        const val MAX_LINES = 500
    }
}

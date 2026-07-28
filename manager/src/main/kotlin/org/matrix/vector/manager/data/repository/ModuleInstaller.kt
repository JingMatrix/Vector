package org.matrix.vector.manager.data.repository

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.matrix.vector.manager.Constants
import org.matrix.vector.manager.data.model.ReleaseAsset

/** Where an install has got to. One at a time, because a user installs one module at a time. */
sealed interface InstallStep {

    data object Idle : InstallStep

    data class Downloading(val packageName: String, val bytes: Long, val total: Long) : InstallStep

    /** Handed to the package installer; nothing more to report until it answers. */
    data class Installing(val packageName: String) : InstallStep

    /** Standalone only: the system's own install prompt is up and waiting on the user. */
    data class Confirming(val packageName: String) : InstallStep

    data class Done(val packageName: String) : InstallStep

    data class Failed(val packageName: String, val reason: String?) : InstallStep
}

/**
 * Downloads a release asset straight into a `PackageInstaller` session.
 *
 * **Straight into**, with no temporary file, and not as an optimisation. Parasitically the
 * manager's manifest is never installed, so it has no `ContentProvider` and therefore no
 * `FileProvider`; `ACTION_INSTALL_PACKAGE` with a `content://` URI is not available at all.
 * `PackageInstaller.Session.openWrite` is the one path that works identically in both modes, and it
 * needs no storage permission.
 *
 * **The consent story differs sharply between the two modes, and that is why the caller's own
 * dialog matters.** Parasitically the manager runs inside `com.android.shell`, which holds
 * `android.permission.INSTALL_PACKAGES` — so the commit below installs a third-party APK with no
 * system confirmation whatsoever. Standalone, the same code produces the usual
 * `REQUEST_INSTALL_PACKAGES` prompt. In the mode most people run, Vector's own confirmation is the
 * *only* consent gate there is, so it must name what is about to happen before anything is
 * downloaded. See ConfirmInstall, which asks the platform which of the two modes it is in.
 *
 * The session's package name is pinned to the catalogue entry's, and the platform fails an install
 * whose staged APKs are inconsistent with it. A module page therefore cannot install a package
 * other than the one it advertises.
 */
class ModuleInstaller(private val context: Context, private val client: OkHttpClient) {

    private val _state = MutableStateFlow<InstallStep>(InstallStep.Idle)
    val state: StateFlow<InstallStep> = _state.asStateFlow()

    /** Clears a finished result so the button returns to its resting state. */
    fun acknowledge() {
        _state.value = InstallStep.Idle
    }

    /**
     * Fetches [asset] and installs it as [packageName].
     *
     * Returns true only when the platform reports the package installed. There is no resume: a
     * dropped connection costs the whole transfer, which is an acceptable trade for module APKs
     * (tens to a few hundred kilobytes) in exchange for never touching the filesystem.
     */
    suspend fun install(packageName: String, asset: ReleaseAsset): Boolean =
        withContext(Dispatchers.IO) {
            val url = asset.downloadUrl
            if (url == null || !asset.isApk) {
                _state.value = InstallStep.Failed(packageName, null)
                return@withContext false
            }

            val packageInstaller = context.packageManager.packageInstaller
            var sessionId = -1
            var succeeded = false
            try {
                _state.value = InstallStep.Downloading(packageName, 0, asset.size)

                val params =
                    PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL
                        )
                        .apply {
                            setAppPackageName(packageName)
                            if (asset.size > 0) setSize(asset.size)
                        }
                sessionId = packageInstaller.createSession(params)

                packageInstaller.openSession(sessionId).use { session ->
                    stream(session, packageName, url, asset.size)
                    _state.value = InstallStep.Installing(packageName)
                    val result = commit(session, sessionId, packageName)
                    succeeded = result.first == PackageInstaller.STATUS_SUCCESS
                    if (!succeeded) {
                        Log.w(
                            Constants.TAG,
                            "store: install of $packageName failed, status ${result.first}: " +
                                "${result.second}",
                        )
                    }
                    _state.value =
                        if (succeeded) InstallStep.Done(packageName)
                        else InstallStep.Failed(packageName, result.second)
                }
            } catch (e: Exception) {
                Log.w(Constants.TAG, "store: install of $packageName failed", e)
                _state.value = InstallStep.Failed(packageName, e.message)
            } finally {
                // Without this, a cancelled download leaves a staged session behind — and staged
                // sessions accumulate, each holding the bytes written so far.
                if (!succeeded && sessionId != -1) {
                    runCatching { packageInstaller.abandonSession(sessionId) }
                }
            }
            succeeded
        }

    private suspend fun stream(
        session: PackageInstaller.Session,
        packageName: String,
        url: String,
        declaredSize: Long,
    ) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: declaredSize

            session.openWrite(WRITE_NAME, 0, total).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var written = 0L
                    var reported = 0L
                    while (true) {
                        // The read below is blocking, so cancellation is only observed between
                        // chunks. Checking here is what lets leaving the screen stop the transfer.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read

                        // Progress is published per 256 KB, not per chunk: at 64 KB a small module
                        // would spend more time on binder calls to setStagingProgress and on
                        // recompositions than on the download itself.
                        if (written - reported >= PROGRESS_STEP_BYTES || read < buffer.size) {
                            reported = written
                            _state.value = InstallStep.Downloading(packageName, written, total)
                            if (total > 0) session.setStagingProgress(written.toFloat() / total)
                        }
                    }
                    out.flush()
                    session.fsync(out)
                }
            }
        }
    }

    /**
     * Commits the session and waits for the platform's verdict.
     *
     * The result arrives as a broadcast, and the receiver is registered at runtime rather than
     * declared: parasitically nothing in the manifest exists, so a declared receiver would simply
     * never fire. `STATUS_PENDING_USER_ACTION` is not terminal — it means the system is asking the
     * user, and the real status follows once they answer.
     */
    private suspend fun commit(
        session: PackageInstaller.Session,
        sessionId: Int,
        packageName: String,
    ): Pair<Int, String?> = suspendCancellableCoroutine { continuation ->
        val action = "$RESULT_ACTION.$sessionId"
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(received: Context, intent: Intent) {
                    val status =
                        intent.getIntExtra(
                            PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE,
                        )
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        _state.value = InstallStep.Confirming(packageName)
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                            ?.let { confirm ->
                                runCatching {
                                        context.startActivity(
                                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                    .onFailure { e ->
                                        Log.e(
                                            Constants.TAG,
                                            "store: install prompt for $packageName could not be started",
                                            e,
                                        )
                                    }
                            }
                        return
                    }
                    runCatching { context.unregisterReceiver(this) }
                    if (continuation.isActive) {
                        continuation.resumeWith(
                            Result.success(
                                status to
                                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            )
                        )
                    }
                }
            }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                else 0
        val pending =
            PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(action).setPackage(context.packageName),
                flags,
            )
        session.commit(pending.intentSender)
    }

    private companion object {
        const val WRITE_NAME = "module.apk"
        const val CHUNK_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024
        const val RESULT_ACTION = "org.matrix.vector.manager.INSTALL_RESULT"
    }
}

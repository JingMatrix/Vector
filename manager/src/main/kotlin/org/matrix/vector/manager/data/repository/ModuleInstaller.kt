package org.matrix.vector.manager.data.repository

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.matrix.vector.manager.ipc.commitForResult
import org.matrix.vector.manager.ipc.requestReplaceExisting
import org.matrix.vector.manager.logW
import org.matrix.vector.ui.R as UiR
import org.matrix.vector.ui.store.InstallStep
import org.matrix.vector.ui.store.ReleaseAsset

/**
 * Downloads a release asset and puts it on the device, by one of two routes.
 *
 * **From API 29 the hand-off route: the download is staged into `MediaStore.Downloads` and one
 * plain `ACTION_VIEW` intent hands the APK to whatever installer answers** — the user's chosen
 * default, a resolver the user picks from, or the platform's own. Committing a `PackageInstaller`
 * session directly never gives another installer a chance: parasitically the commit is a silent
 * install by `com.android.shell` that nothing observes, and standalone it pins the platform's own
 * confirmation UI no matter what the user would rather install with. An intent carries a
 * `content://` URI, the manager has no `FileProvider` to serve one (its manifest is never
 * installed parasitically), and `MediaStore` is the one provider every device already runs whose
 * entries can be written without a permission and read by another app through the grant flag
 * — which is why the staging below exists rather than a cache file.
 *
 * **The session route** — below API 29, or when the hand-off could not start — streams straight
 * into a `PackageInstaller` session with no temporary file at all. That is not an
 * optimisation either: it is the same storage story as above in reverse, because before Q there is
 * no permissionless way to share bytes with another app.
 *
 * **The consent story differs sharply between the two routes, and that is why the caller's own
 * dialog matters.** Parasitically the manager runs inside `com.android.shell`, which holds
 * `android.permission.INSTALL_PACKAGES` — so the session route installs a third-party APK with no
 * system confirmation whatsoever. The hand-off route delegates the verdict to the chosen installer,
 * which may be silent (Shizuku, root) or not; either way Vector's own dialog remains the gate that
 * names what is about to happen before anything is downloaded. See ConfirmInstall, which asks the
 * platform which of the two modes it is in.
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
     * Returns true only when the install is confirmed: the platform reports success on the session
     * route, and a package event for [packageName] arrives on the hand-off route within
     * [HANDOFF_TIMEOUT_MS]. There is no resume: a dropped connection costs the whole transfer,
     * which is an acceptable trade for module APKs (tens to a few hundred kilobytes).
     *
     * What became of it is recorded by the caller rather than here — see
     * RepoRepository.readInstalled and SettingsRepository.noteStoreInstall — because the version to
     * record has to be read the way the Store reads it, across every user.
     */
    suspend fun install(packageName: String, asset: ReleaseAsset): Boolean =
        withContext(Dispatchers.IO) {
            val url = asset.downloadUrl
            if (url == null || !asset.isApk) {
                _state.value = InstallStep.Failed(packageName, null)
                return@withContext false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val handoff = prepareHandoff(packageName, url, asset)
                // A hand-off that never started — MediaStore refused, the intent could not be
                // fired, the staged APK is not the one the page advertised — is not an install
                // failure: the session route still works, and a dead end on screen is worth less
                // than the old behaviour.
                if (handoff == null) {
                    logW("store: $packageName could not be handed to an installer")
                    installThroughSession(packageName, url, asset.size)
                } else {
                    awaitHandoff(packageName, handoff.first, handoff.second)
                }
            } else {
                installThroughSession(packageName, url, asset.size)
            }
        }

    /**
     * The first half of the hand-off route: stage the download in `MediaStore.Downloads`, check it
     * against the catalogue entry, watch for the verdict, and let the user's installer take it
     * from an `ACTION_VIEW` intent.
     *
     * Null means nothing left this process — no intent fired, so the caller falls back to the
     * session route rather than reporting failure. Cancellation propagates instead: a cancelled
     * download must not resurrect itself as a session install.
     *
     * The entry is inserted `IS_PENDING` and only published once complete, so nothing can read a
     * half-written APK mid-transfer. The receiver is registered before the intent fires: a
     * privileged installer working silently needs seconds at most, and the verdict must not outrun
     * its listener.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun prepareHandoff(
        packageName: String,
        url: String,
        asset: ReleaseAsset,
    ): Pair<Uri, ExternalInstallWatch>? {
        val uri =
            try {
                stageInDownloads(packageName, url, asset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("store: hand-off of $packageName failed before the intent could fire", e)
                return null
            }
        if (!matchesCatalogueEntry(uri, packageName)) {
            logW("store: staged copy of $packageName declares another package; not handing it off")
            deleteStagedCopy(uri)
            return null
        }
        val watch = ExternalInstallWatch(context).also { it.register(packageName) }
        try {
            startInstallIntent(uri)
        } catch (e: Exception) {
            watch.dispose()
            // The entry was published for an intent that never fired, and the session fallback
            // stages a copy of its own — so this one goes back out.
            deleteStagedCopy(uri)
            logW("store: hand-off of $packageName failed before the intent could fire", e)
            return null
        }
        return uri to watch
    }

    /**
     * The second half of the hand-off route: wait for the installer's verdict.
     *
     * A package event naming [packageName] reports done; a run cancelled while the external
     * installer is still showing withdraws the staged file with it. A timeout keeps the file
     * instead — the installer may simply be waiting for the user, and deleting the source under it
     * would turn "not yet confirmed" into "cannot ever be confirmed".
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun awaitHandoff(
        packageName: String,
        uri: Uri,
        watch: ExternalInstallWatch,
    ): Boolean {
        var keepStagedFile = false
        try {
            // The intent already went out; from here on it is the installer's prompt.
            _state.value = InstallStep.Confirming(packageName)
            val confirmed = withTimeoutOrNull(HANDOFF_TIMEOUT_MS) { watch.await() } != null
            if (!confirmed) {
                keepStagedFile = true
                logW(
                    "store: external install of $packageName unconfirmed after " +
                        "${HANDOFF_TIMEOUT_MS / 60_000} min; $uri stays for the installer"
                )
                _state.value =
                    InstallStep.Failed(
                        packageName,
                        context.getString(
                            UiR.string.store_install_unconfirmed,
                            HANDOFF_TIMEOUT_MS / 60_000,
                        ),
                    )
            } else {
                _state.value = InstallStep.Done(packageName)
            }
            return confirmed
        } finally {
            // Only a timeout leaves the file behind; every other exit — cancellation, confirmed
            // install — cleans it up.
            if (!keepStagedFile) {
                deleteStagedCopy(uri)
            }
        }
    }

    /**
     * Streams the download into a pending `MediaStore.Downloads` entry and publishes it.
     *
     * Progress is reported against whichever length is realer — the response's or the release's —
     * exactly as the session route reports against its own.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun stageInDownloads(
        packageName: String,
        url: String,
        asset: ReleaseAsset,
    ): Uri {
        val displayName = suggestedFileName(packageName, asset)
        _state.value = InstallStep.Downloading(packageName, 0, asset.size)

        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused a Downloads entry for $displayName")

        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                val out =
                    resolver.openOutputStream(uri)
                        ?: throw IOException("no output stream for $uri")
                out.use { transfer(body.byteStream(), packageName, total, it) {} }
            }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // A staging that never reaches its publish leaves a pending row behind, and a pending
            // row is invisible in Downloads — nobody but MediaStore's week-long purge would ever
            // collect it. The session route abandons its session for exactly this reason, and a
            // cancelled transfer takes the row with it for the same one.
            deleteStagedCopy(uri)
            throw e
        }
        return uri
    }

    /** Publishes [uri] to whatever installer resolves an APK view intent. */
    private fun startInstallIntent(uri: Uri) {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                // ClipData carries the grant through a resolver dialog, where the flags alone
                // would stop at ResolverActivity.
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    /** A file name for the staged copy that no provider will reject. */
    private fun suggestedFileName(packageName: String, asset: ReleaseAsset): String {
        val original =
            asset.name?.substringAfterLast('/')?.takeIf { it.endsWith(".apk", ignoreCase = true) }
        return (original ?: "$packageName.apk").replace(ILLEGAL_FILE_CHARS, "_")
    }

    /** Withdraws a staged copy; an entry nobody will hand off is worth less than its bytes. */
    private fun deleteStagedCopy(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { logW("store: staged copy $uri could not be removed", it) }
    }

    /**
     * Whether the staged copy still declares the package the page advertised.
     *
     * The session route gets this for nothing: its package name is pinned on the session and the
     * platform fails a mismatch. The hand-off route hands bytes to an intent, so the guarantee is
     * checked here or not at all — a repository entry could otherwise serve any package it liked.
     * The check is one-way on purpose: an APK that cannot be parsed is still handed off, because
     * no installer can install what it cannot parse either, and the prompt the user then sees
     * names what was found. Only a name that could be read *and* disagrees stops the hand-off.
     */
    private fun matchesCatalogueEntry(uri: Uri, packageName: String): Boolean {
        val path =
            runCatching {
                    context.contentResolver
                        .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                }
                .getOrNull()
        if (path == null) return true
        val declared =
            runCatching { context.packageManager.getPackageArchiveInfo(path, 0)?.packageName }
                .getOrNull()
        return declared == null || declared == packageName
    }

    /**
     * The session route, unchanged from when it was the only one: the download goes straight into a
     * `PackageInstaller.Session.openWrite` stream and the commit waits for the platform's verdict.
     *
     * The session's package name is pinned to the catalogue entry's, and the platform fails an
     * install whose staged APKs are inconsistent with it. A module page therefore cannot install a
     * package other than the one it advertises — a guarantee the hand-off route upholds by
     * checking the staged copy before it is handed over.
     */
    private suspend fun installThroughSession(
        packageName: String,
        url: String,
        declaredSize: Long,
    ): Boolean {
        val packageInstaller = context.packageManager.packageInstaller
        var sessionId = -1
        var succeeded = false
        try {
            _state.value = InstallStep.Downloading(packageName, 0, declaredSize)

            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply {
                        setAppPackageName(packageName)
                        if (declaredSize > 0) setSize(declaredSize)
                        requestReplaceExisting()
                    }
            sessionId = packageInstaller.createSession(params)

            packageInstaller.openSession(sessionId).use { session ->
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0 } ?: declaredSize
                    session.openWrite(WRITE_NAME, 0, total).use { out ->
                        transfer(body.byteStream(), packageName, total, out) { written ->
                            if (total > 0) session.setStagingProgress(written.toFloat() / total)
                        }
                        session.fsync(out)
                    }
                }
                _state.value = InstallStep.Installing(packageName)
                val result = commit(session, sessionId, packageName)
                succeeded = result.first == PackageInstaller.STATUS_SUCCESS
                if (!succeeded) {
                    logW(
                        "store: install of $packageName failed, status ${result.first}: " +
                            "${result.second}"
                    )
                }
                _state.value =
                    if (succeeded) InstallStep.Done(packageName)
                    else InstallStep.Failed(packageName, result.second)
            }
        } catch (e: Exception) {
            // The check in transfer() cancels by throwing, and a cancelled transfer is not a
            // failed install: reporting it as one would put an error on a screen the reader
            // has already left, and would race the acknowledge() that cancelled it.
            if (e is CancellationException) throw e
            logW("store: install of $packageName failed", e)
            _state.value = InstallStep.Failed(packageName, e.message)
        } finally {
            // Without this, a cancelled download leaves a staged session behind — and staged
            // sessions accumulate, each holding the bytes written so far.
            if (!succeeded && sessionId != -1) {
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
        }
        return succeeded
    }

    /**
     * Copies [input] into [out], publishing progress and honouring cancellation between chunks.
     *
     * The read below is blocking, so cancellation is only observed between chunks. Checking here is
     * what lets leaving the screen stop the transfer.
     *
     * Progress is published per 256 KB, not per chunk: at 64 KB a small module would spend more
     * time recomposing than downloading. [onProgress] carries the route-specific part of a tick —
     * the session route maps it onto `setStagingProgress`; the hand-off route needs nothing.
     */
    private suspend fun transfer(
        input: InputStream,
        packageName: String,
        total: Long,
        out: OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(CHUNK_BYTES)
        var written = 0L
        var reported = 0L
        input.use { reader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = reader.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                written += read

                if (written - reported >= PROGRESS_STEP_BYTES || read < buffer.size) {
                    reported = written
                    _state.value = InstallStep.Downloading(packageName, written, total)
                    onProgress(written)
                }
            }
            out.flush()
        }
    }

    /**
     * Commits the session and waits for the platform's verdict.
     *
     * @see commitForResult
     */
    private suspend fun commit(
        session: PackageInstaller.Session,
        sessionId: Int,
        packageName: String,
    ): Pair<Int, String?> =
        context.commitForResult(
            session,
            sessionId,
            promptFailure = "store: install prompt for $packageName could not be started",
        ) {
            _state.value = InstallStep.Confirming(packageName)
        }

    private companion object {
        const val WRITE_NAME = "module.apk"
        const val CHUNK_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024L

        /** How long an externally handed-off install may stay unconfirmed before giving up. */
        const val HANDOFF_TIMEOUT_MS = 5L * 60_000L

        const val APK_MIME = "application/vnd.android.package-archive"

        val ILLEGAL_FILE_CHARS = Regex("[\\\\/\u0000]")
    }
}

/**
 * One registration that turns package broadcasts into a single resumption.
 *
 * Split from a plain `suspendCancellableCoroutine` block because order matters: the signal has to
 * exist *before* the install intent fires, or a privileged installer working silently could deliver
 * the verdict to nobody. A `CompletableDeferred` is the signal because the verdict can also beat
 * [await] itself — the intent goes out in the caller, the waiting starts a suspension later — and a
 * completion is buffered where a continuation attached too late would simply have missed it.
 *
 * Both events are accepted, and `EXTRA_REPLACING` is how they are told apart: an update announces
 * itself as ADDED carrying the flag, then REPLACED follows; taking the flagged ADD would resume
 * before the replacement finished. Broadcasts for other packages are ignored — every install on the
 * device passes through here otherwise.
 */
private class ExternalInstallWatch(private val context: Context) {

    /** Completed where the verdict lands, whenever that is; later completions are no-ops. */
    private val verdict = CompletableDeferred<Unit>()

    private var receiver: BroadcastReceiver? = null

    fun register(targetPackage: String) {
        check(receiver == null) { "ExternalInstallWatch registered twice" }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(received: Context, intent: Intent) {
                    if (
                        intent.action == Intent.ACTION_PACKAGE_ADDED &&
                            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    ) {
                        return
                    }
                    if (intent.data?.schemeSpecificPart != targetPackage) return
                    verdict.complete(Unit)
                }
            }
        this.receiver = receiver
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System-sent broadcasts reach a NOT_EXPORTED receiver, but nothing a random app
            // forges could resume this — the same bar InstallResult.kt holds its own receiver to.
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /**
     * Suspends until the watched package event arrives or the caller is cancelled. Either exit takes
     * the registration with it, so the receiver never outlives its one waiter.
     */
    suspend fun await() {
        try {
            verdict.await()
        } finally {
            dispose()
        }
    }

    /**
     * Removes the registration — the exit for paths that never wait, such as an intent that failed
     * to start.
     */
    fun dispose() {
        runCatching { receiver?.let(context::unregisterReceiver) }
        receiver = null
    }
}

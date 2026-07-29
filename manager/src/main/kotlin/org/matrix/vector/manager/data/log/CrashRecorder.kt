package org.matrix.vector.manager.data.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.matrix.vector.manager.BuildConfig

/**
 * Keeps the manager's own crashes where the log export already looks for them.
 *
 * The daemon captures the manager's logcat output — `logcat.cpp` routes any tag beginning `Vector`
 * to the verbose stream, so `VectorManager` lines land in the Verbose tab beside the daemon's own.
 * It captures the manager's *crashes* too, since the same filter admits `LOG_ID_CRASH`. Both,
 * however, only while verbose logging is switched on and only while a daemon is alive to do the
 * capturing — and neither is true in the case that matters most, which is a manager crashing on a
 * device whose framework is not activated.
 *
 * So the trace is written to disk first, and the platform's own handler runs afterwards. The
 * system dialog still appears, the tombstone is still written, `logcat -b crash` still has it.
 *
 * **The directory is not a choice.** `FileSystem.getLogs`, which builds the zip the log screen
 * exports, already collects two of them:
 * ```
 * addDir("crash_shell",   File("/data/data/${'$'}{MANAGER_INJECTED_PKG_NAME}/cache/crash"))
 * addDir("crash_manager", File("/data/data/${'$'}{DEFAULT_MANAGER_PACKAGE_NAME}/cache/crash"))
 * ```
 * Those are this process's `cacheDir/crash` in each of the two ways the manager can run —
 * parasitically inside `com.android.shell`, or standalone. Writing here means a crash travels in
 * the export with no new binder call, no daemon change, and no second place for anyone to look.
 * One file per crash, named for the epoch millisecond it happened at: a crash loop produces several
 * within the same second, and a name coarser than that would have each one overwrite the last.
 *
 * Two properties matter more than anything this class does:
 *
 * - **It never throws.** It runs inside a process that is already dying, on a thread whose stack
 *   just unwound. An exception here would replace a diagnosable crash with an undiagnosable one,
 *   so every step is wrapped and failure is silent by design.
 * - **It always delegates.** The previous handler is captured and called even if the write fails.
 *   Parasitically this is not our process — it is `com.android.shell`, and quietly swallowing that
 *   process's crashes because the manager happened to be open would be far worse than losing a
 *   trace. For the same reason it records whatever crashes, ours or not, rather than trying to
 *   guess whose stack frame it is looking at.
 */
object CrashRecorder {

    private const val DIR_NAME = "crash"

    /** How many crashes are kept. Older ones are the least likely to still be true. */
    private const val MAX_FILES = 5

    @Volatile private var installed = false

    /**
     * Takes over the default handler, once per process, and discards what another build left.
     *
     * Called from [org.matrix.vector.manager.di.ServiceLocator.attach], early in the activity's
     * `onCreate` and before anything that could fail, so the handler is in place before any screen
     * exists.
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val application = context.applicationContext ?: context
        runCatching { discardOtherBuilds(application) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(application, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Where the daemon's export collects them from. */
    fun directory(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** The recorded crashes, newest first, or null when there have been none. */
    fun read(context: Context): String? =
        runCatching {
                files(context)
                    .joinToString("\n") { it.readText().trimEnd() }
                    .ifBlank { null }
            }
            .getOrNull()

    /** How many crashes are on file. Cheap enough to ask on every visit to a screen. */
    fun count(context: Context): Int = files(context).size

    fun clear(context: Context) {
        runCatching { files(context).forEach { it.delete() } }
    }

    /**
     * Drops the records that some other build of the manager wrote.
     *
     * A record outlives the build that made it — the directory is this process's cache, which an
     * update does not clear — so the status card goes on showing a crash that the running build
     * has already fixed, until somebody thinks to press clear. That is not a stale line on a
     * screen. It is what a reporter copies into the issue to show the fix did not work: #799 was
     * answered with five traces from the build before the one that fixed them.
     *
     * Only at [install], never at [record]: this is about what a *new* build inherits, and a crash
     * loop within one build must keep every record it makes.
     *
     * A file whose second line cannot be read is kept. Losing a trace is the worse of the two
     * failures, and a record this class cannot parse is exactly the one worth still having.
     */
    private fun discardOtherBuilds(context: Context) {
        val current = BUILD
        files(context).forEach { file ->
            val theirs = runCatching { file.useLines { it.drop(1).firstOrNull() } }.getOrNull()
            if (theirs != null && !theirs.startsWith(current)) runCatching { file.delete() }
        }
    }

    /** Newest first, which is the order both the card and the clipboard want. */
    private fun files(context: Context): List<File> =
        directory(context)
            .listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name.removeSuffix(SUFFIX).toLongOrNull() ?: 0L }
            .orEmpty()

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val dir = directory(context)
        dir.mkdirs()
        val text = buildString {
            append(header(context, thread, now))
            append('\n')
            append(android.util.Log.getStackTraceString(throwable))
            append('\n')
        }
        runCatching { File(dir, "$now$SUFFIX").writeText(text) }
        // After writing, so a failure to prune never costs us the record we just made.
        runCatching { files(context).drop(MAX_FILES).forEach { it.delete() } }
    }

    /**
     * The context a stack trace alone does not carry.
     *
     * Which build, which of the two ways the manager can be running, which thread, and which
     * platform — the four things that are always asked first and are never in the trace. Written
     * in [Locale.ROOT] on purpose: this text exists to be pasted into an issue, and a crash report
     * whose date is formatted for the reporter's locale is a crash report the reader has to parse.
     */
    private fun header(context: Context, thread: Thread, at: Long): String {
        val parasitic = context.packageName == BuildConfig.INJECTED_PACKAGE_NAME
        val host = if (parasitic) "parasitic in ${context.packageName}" else "standalone"
        return "${TIMESTAMP.format(Date(at))}\n" +
            "$BUILD · $host · thread ${thread.name} · " +
            "android ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})"
    }

    /**
     * How a record says which build wrote it, and so what [discardOtherBuilds] matches on.
     *
     * The whole line rather than the hash alone: a build from a dirty tree carries the hash of the
     * commit it was built from, so two of them can share it while differing by everything that was
     * uncommitted at the time.
     */
    private val BUILD: String
        get() =
            "manager ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                BuildConfig.VERSION_HASH

    private const val SUFFIX = ".log"

    private val TIMESTAMP
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
}

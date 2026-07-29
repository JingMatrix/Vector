package org.matrix.vector.manager

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The manager's logging, which exists because the platform's own quietly loses stack traces.
 *
 * `android.util.Log.e(tag, msg, tr)` does not print `tr` when anything in its cause chain is an
 * [java.net.UnknownHostException]. This is deliberate upstream — `printlns` in AOSP's `Log.java`
 * breaks out of the cause walk with the comment "this is to reduce the amount of log spew that
 * apps do in the non-error condition of the network being unavailable" — and
 * [Log.getStackTraceString] returns `""` for the same reason. The message still prints, so nothing
 * looks wrong; only the trace is gone.
 *
 * For an app whose network use is a DoH resolver, a module store, and a GitHub feed, that filter
 * removes traces from precisely the failures worth reporting. Its worst case was `dns: DoH lookup
 * of $host failed` — OkHttp's `DnsOverHttps` signals failure by throwing `UnknownHostException`, so
 * the one log line whose entire purpose is explaining a DNS failure was the one guaranteed to
 * arrive without a trace.
 *
 * So the trace is formatted here and appended to the message, and only [Log.println] — which has no
 * filter — is called. Nothing in the manager calls `android.util.Log` directly; [Constants.TAG]
 * belongs to these three functions.
 *
 * The conventions the tag used to carry:
 *
 * `logcat.cpp` routes any tag beginning `Vector` into the daemon's **verbose** stream, so with
 * verbose logging on everything logged here appears in the Verbose tab beside the daemon's own
 * lines and travels in the zip export — the place a reader already looks. A file-local tag would be
 * ordinary Android practice and would land nowhere; there are none in this app.
 *
 * A message is `area: lowercase phrase naming the operation and its subject`, where the area is one
 * of ipc, dns, apps, modules, backup, restore, scope, store, update, feed, auth, status, framework,
 * logs, actions, report, splash. The subject matters: "modules: enable of $packageName failed" can
 * be acted on, "failed to enable module" cannot.
 *
 * The `Throwable` is always the last argument — never `e.message`, which discards the stack.
 * Nothing secret is ever interpolated: no OAuth token, no SAF `Uri` beyond its authority, no
 * third-party query string.
 *
 * Levels are [logE] when something the user asked for did not happen and nothing else will explain
 * it, [logW] for a degraded path recovered from, and [logI] for a one-off milestone worth having in
 * a bug report — counts, versions, the endpoint chosen. `d` and `v` are not offered, because
 * release builds ship them.
 *
 * A [kotlinx.coroutines.CancellationException] is never logged. Navigating away from a screen
 * cancels its scope, and a log that fires every time someone presses back is a log nobody reads;
 * any `runCatching` or broad `catch` that can see one rethrows or skips it first.
 */
fun logE(msg: String, tr: Throwable? = null) = emit(Log.ERROR, msg, tr)

/** A degraded path recovered from. See [logE] for the conventions. */
fun logW(msg: String, tr: Throwable? = null) = emit(Log.WARN, msg, tr)

/** A one-off milestone worth having in a bug report. See [logE] for the conventions. */
fun logI(msg: String, tr: Throwable? = null) = emit(Log.INFO, msg, tr)

/**
 * A throwable as text, cause chain and all, with none of [Log.getStackTraceString]'s filtering.
 *
 * Shared with [org.matrix.vector.manager.data.log.CrashRecorder], which writes the same text to
 * disk and had the same trace go missing for the same reason.
 */
fun stackTraceOf(tr: Throwable): String =
    StringWriter().also { tr.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

/**
 * One log entry per [PAYLOAD_LIMIT] characters, split on line boundaries.
 *
 * `Log.println` hands the whole string to liblog, which truncates it at the kernel logger's payload
 * size; the platform's `Log.e(tag, msg, tr)` avoids that by writing through a line-breaking writer.
 * Since we are no longer using it, we break the lines: a deep trace — and Compose produces very
 * deep ones — would otherwise lose its tail, which is where the frames that name our own code live.
 */
private fun emit(priority: Int, msg: String, tr: Throwable?) {
    val text = if (tr == null) msg else "$msg\n${stackTraceOf(tr)}"
    if (text.length <= PAYLOAD_LIMIT) {
        Log.println(priority, Constants.TAG, text)
        return
    }
    val entry = StringBuilder(PAYLOAD_LIMIT)
    for (line in text.lineSequence()) {
        if (entry.isNotEmpty() && entry.length + 1 + line.length > PAYLOAD_LIMIT) {
            Log.println(priority, Constants.TAG, entry.toString())
            entry.setLength(0)
        }
        if (entry.isNotEmpty()) entry.append('\n')
        // A single line over the limit is passed through and truncated by liblog. Stack frames are
        // never that long, and splitting mid-line would corrupt the one thing being preserved.
        entry.append(line)
    }
    if (entry.isNotEmpty()) Log.println(priority, Constants.TAG, entry.toString())
}

/** Under the ~4068-byte logger payload, with room for the tag and the two terminators. */
private const val PAYLOAD_LIMIT = 4000

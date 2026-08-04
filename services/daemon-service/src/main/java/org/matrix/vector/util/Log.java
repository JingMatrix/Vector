package org.matrix.vector.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * A drop-in replacement for {@code android.util.Log} that the user can silence.
 *
 * <p>Written to be substitutable by import alone for the ten overloads it covers: each takes the
 * arguments its {@code android.util.Log} counterpart takes, so a file switches over by changing
 * which {@code Log} it imports. That is why it keeps the platform's terse names. It is not a
 * complete stand-in — these return void where the platform returns the number of bytes written, and
 * {@code wtf}, {@code isLoggable} and the {@code (String, Throwable)} overloads are absent — so a
 * file that uses any of those has to keep reaching for the platform's.</p>
 *
 * <p>Lives here rather than as a member of {@link Utils}, where it began. It held only static
 * members while being a non-static inner class, which Java accepts only from release 16 and which
 * meant every call site had to write {@code Utils.Log} for a type that was never scoped to an
 * instance of anything.</p>
 */
public class Log {
    public static final int VERBOSE = android.util.Log.VERBOSE;
    public static final int DEBUG = android.util.Log.DEBUG;
    public static final int INFO = android.util.Log.INFO;
    public static final int WARN = android.util.Log.WARN;
    public static final int ERROR = android.util.Log.ERROR;
    public static final int ASSERT = android.util.Log.ASSERT;

    /**
     * Whether the user has asked the framework to keep quiet.
     *
     * <p>Set in an injected process from {@code IFrameworkService.isLogMuted}, and deliberately not
     * consulted for {@link #e} or for anything at {@code ERROR} and above: muting is a request for
     * less noise, not for a failure to go unrecorded. Which of the remaining overloads honour it is
     * uneven — the message-only forms and {@code w(String, String, Throwable)} do, the other
     * {@code Throwable} forms do not — and this is carried over unchanged.</p>
     */
    public static boolean muted = false;

    public static void println(int priority, String tag, String msg) {
        // Respect the muted flag for everything except ERROR/ASSERT
        if (muted && priority < android.util.Log.ERROR) return;
        android.util.Log.println(priority, tag, msg);
    }

    /**
     * A throwable as text, without the platform's filtering.
     *
     * {@code android.util.Log.getStackTraceString} returns an empty string when anything in
     * the cause chain is an {@link java.net.UnknownHostException} — deliberately upstream, to
     * cut log spew when the network is down, but here it silently turns a module's report of a
     * failed request into a message with nothing under it.
     */
    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        StringWriter sw = new StringWriter();
        tr.printStackTrace(new PrintWriter(sw));
        return sw.toString().stripTrailing();
    }

    public static void d(String tag, String msg) {
        if (muted) return;
        android.util.Log.d(tag, msg);
    }

    public static void d(String tag, String msg, Throwable tr) {
        android.util.Log.d(tag, msg, tr);
    }

    public static void v(String tag, String msg) {
        if (muted) return;
        android.util.Log.v(tag, msg);
    }

    public static void v(String tag, String msg, Throwable tr) {
        android.util.Log.v(tag, msg, tr);
    }

    public static void i(String tag, String msg) {
        if (muted) return;
        android.util.Log.i(tag, msg);
    }

    public static void i(String tag, String msg, Throwable tr) {
        android.util.Log.i(tag, msg, tr);
    }

    public static void w(String tag, String msg) {
        if (muted) return;
        android.util.Log.w(tag, msg);
    }

    public static void w(String tag, String msg, Throwable tr) {
        if (muted) return;
        android.util.Log.w(tag, msg, tr);
    }

    public static void e(String tag, String msg) {
        android.util.Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        android.util.Log.e(tag, msg, tr);
    }
}

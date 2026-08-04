package org.matrix.vector.util;

import android.os.SystemProperties;
import android.text.TextUtils;

/**
 * Logging under the framework's own tag, for the code that runs inside an injected process.
 *
 * <p>Use {@link Log} directly where a file has a tag of its own to log under; use the helpers here
 * where it does not, which is most of the framework.</p>
 */
public class Utils {

    /**
     * The tag every one of these helpers logs under, and it is not arbitrary.
     *
     * <p>The daemon's log reader routes any tag beginning {@code Vector} into its verbose stream —
     * see {@code kPrefixTags} in {@code daemon/src/main/jni/logcat.cpp} — so what is logged here
     * reaches the manager's Verbose tab and travels in an exported bug report. A tag invented here
     * that does not start with it is captured only if it is added to that reader's lists first.</p>
     */
    public static final String LOG_TAG = "Vector";

    /** Whether this is a MIUI/HyperOS build, which needs its own deopt workaround. */
    public static final boolean isMIUI =
            !TextUtils.isEmpty(SystemProperties.get("ro.miui.ui.version.name"));

    public static void logD(Object msg) {
        Log.d(LOG_TAG, msg.toString());
    }

    public static void logD(String msg, Throwable throwable) {
        Log.d(LOG_TAG, msg, throwable);
    }

    public static void logV(Object msg) {
        Log.v(LOG_TAG, msg.toString());
    }

    public static void logV(String msg, Throwable throwable) {
        Log.v(LOG_TAG, msg, throwable);
    }

    public static void logW(String msg) {
        Log.w(LOG_TAG, msg);
    }

    public static void logW(String msg, Throwable throwable) {
        Log.w(LOG_TAG, msg, throwable);
    }

    public static void logI(String msg) {
        Log.i(LOG_TAG, msg);
    }

    public static void logI(String msg, Throwable throwable) {
        Log.i(LOG_TAG, msg, throwable);
    }

    public static void logE(String msg) {
        Log.e(LOG_TAG, msg);
    }

    public static void logE(String msg, Throwable throwable) {
        Log.e(LOG_TAG, msg, throwable);
    }
}

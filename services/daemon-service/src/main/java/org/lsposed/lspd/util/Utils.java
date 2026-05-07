/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.util;

import android.os.SystemProperties;
import android.text.TextUtils;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesException;

public class Utils {

    public static final String LOG_TAG = "Vector";
    public static final boolean isMIUI = !TextUtils.isEmpty(SystemProperties.get("ro.miui.ui.version.name"));
    public static final boolean isLENOVO = !TextUtils.isEmpty(SystemProperties.get("ro.lenovo.region"));

    public class Log {
        public static final int VERBOSE = android.util.Log.VERBOSE;
        public static final int DEBUG = android.util.Log.DEBUG;
        public static final int INFO = android.util.Log.INFO;
        public static final int WARN = android.util.Log.WARN;
        public static final int ERROR = android.util.Log.ERROR;
        public static final int ASSERT = android.util.Log.ASSERT;

        public static boolean muted = false;

        public static void println(int priority, String tag, String msg) {
            // Respect the muted flag for everything except ERROR/ASSERT
            if (muted && priority < android.util.Log.ERROR) return;
            android.util.Log.println(priority, tag, msg);
        }

        public static String getStackTraceString(Throwable tr) {
            return android.util.Log.getStackTraceString(tr);
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

    public static ZoneId getZoneId() {
        var timezone = SystemProperties.get("persist.sys.timezone", "GMT");
        try {
            return ZoneId.of(timezone);
        } catch (ZoneRulesException e) {
            return ZoneOffset.UTC;
        }
    }
}

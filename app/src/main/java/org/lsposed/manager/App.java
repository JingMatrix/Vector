/*
 * This file is part of Vector.
 *
 * Vector is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vector is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Vector.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 * Copyright (C) 2026 Vector Contributors
 */

package org.lsposed.manager;

import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.provider.Settings;
import android.system.Os;
import android.text.TextUtils;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;
import org.lsposed.manager.util.AppHelper;
import org.lsposed.manager.receivers.LSPManagerServiceHolder;
import org.lsposed.manager.util.ModuleUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

//import rikka.core.os.FileUtils;

public class App extends Application {
    public static final int PER_USER_RANGE = 100000;
    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("");
        }
        Looper.myQueue().addIdleHandler(() -> {
            if (App.getInstance() == null || App.getExecutorService() == null) return true;
            App.getExecutorService().submit(() -> {
                var list = AppHelper.getAppList(false);
                var pm = App.getInstance().getPackageManager();
                list.parallelStream().forEach(i -> AppHelper.getAppLabel(i, pm));
                ModuleUtil.getInstance();
            });
            return false;
        });
    }

    public static final String TAG = "VectorManager";
    private static final String ACTION_USER_ADDED = "android.intent.action.USER_ADDED";
    private static final String ACTION_USER_REMOVED = "android.intent.action.USER_REMOVED";
    private static final String ACTION_USER_INFO_CHANGED = "android.intent.action.USER_INFO_CHANGED";
    private static final String EXTRA_REMOVED_FOR_ALL_USERS = "android.intent.extra.REMOVED_FOR_ALL_USERS";
    private static App instance = null;
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final Handler MainHandler = new Handler(Looper.getMainLooper());

    public static App getInstance() {
        return instance;
    }

    public static ExecutorService getExecutorService() {
        return executorService;
    }

    public static final boolean isParasitic = !Process.isApplicationUid(Process.myUid());

    
    public static Handler getMainHandler() {
        return MainHandler;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        var map = new HashMap<String, String>(1);
        map.put("isParasitic", String.valueOf(isParasitic));
        var am = getSystemService(ActivityManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            map.clear();
            var reasons = am.getHistoricalProcessExitReasons(null, 0, 1);
            if (reasons.size() == 1) {
                map.put("description", reasons.get(0).getDescription());
                map.put("importance", String.valueOf(reasons.get(0).getImportance()));
                map.put("process", reasons.get(0).getProcessName());
                map.put("reason", String.valueOf(reasons.get(0).getReason()));
                map.put("status", String.valueOf(reasons.get(0).getStatus()));
            }
        }
    }

    private void setCrashReport() {
        var handler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            var time = OffsetDateTime.now();
            var dir = new File(getCacheDir(), "crash");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdir();
            var file = new File(dir, time.toEpochSecond() + ".log");
            try (var pw = new PrintWriter(file)) {
                pw.println(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
                pw.println(time);
                pw.println("pid: " + Os.getpid() + " uid: " + Os.getuid());
                throwable.printStackTrace(pw);
            } catch (IOException ignored) {
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var table = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                var values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, "LSPosed_crash_report" + time.toEpochSecond() + ".zip");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);
                var cr = getContentResolver();
                var uri = cr.insert(table, values);
                if (uri == null) return;
                try (var zipFd = cr.openFileDescriptor(uri, "wt")) {
                    LSPManagerServiceHolder.getService().getLogs(zipFd);
                } catch (Exception ignored) {
                    cr.delete(uri, null, null);
                }
            }
            if (handler != null) {
                handler.uncaughtException(thread, throwable);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        setCrashReport();

        var res = getResources();
        var config = res.getConfiguration();
        //noinspection deprecation
        res.updateConfiguration(config, res.getDisplayMetrics());

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("org.lsposed.manager.NOTIFICATION");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent inIntent) {
                var intent = (Intent) inIntent.getParcelableExtra(Intent.EXTRA_INTENT);
                Log.d(TAG, "onReceive: " + intent);
                switch (intent.getAction()) {
                    case Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_CHANGED, Intent.ACTION_PACKAGE_FULLY_REMOVED, Intent.ACTION_UID_REMOVED -> {
                        var userId = intent.getIntExtra(Intent.EXTRA_USER, 0);
                        var packageName = intent.getStringExtra("android.intent.extra.PACKAGES");
                        var packageRemovedForAllUsers = intent.getBooleanExtra(EXTRA_REMOVED_FOR_ALL_USERS, false);
                        var isXposedModule = intent.getBooleanExtra("isXposedModule", false);
                        if (packageName != null) {
                            if (isXposedModule)
                                ModuleUtil.getInstance().reloadSingleModule(packageName, userId, packageRemovedForAllUsers);
                            else
                                App.getExecutorService().submit(() -> AppHelper.getAppList(true));
                        }
                    }
                    case ACTION_USER_ADDED, ACTION_USER_REMOVED, ACTION_USER_INFO_CHANGED -> App.getExecutorService().submit(() -> ModuleUtil.getInstance().reloadInstalledModules());
                }
            }
        }, intentFilter, Context.RECEIVER_NOT_EXPORTED);
    }
}

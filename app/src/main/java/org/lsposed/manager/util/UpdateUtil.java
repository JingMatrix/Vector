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
 * Copyright (C) 2022 LSPosed Contributors
 */

package org.lsposed.manager.util;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.lsposed.manager.App;
import org.lsposed.manager.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okio.Okio;

public class UpdateUtil {
    private static final Pattern VERSION_CODE_PATTERN = Pattern.compile("-(\\d+)(?=[-.])");

    public static void loadRemoteVersion() {
        var request = new Request.Builder()
                .url("https://api.github.com/repos/JingMatrix/Vector/releases/latest")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build();
        var callback = new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (!response.isSuccessful()) return;
                var body = response.body();
                if (body == null) return;
                try {
                    var info = JsonParser.parseReader(body.charStream()).getAsJsonObject();
                    var notes = info.get("body").getAsString();
                    var assetsArray = info.getAsJsonArray("assets");
                    for (var assets : assetsArray) {
                        checkAssets(assets.getAsJsonObject(), notes);
                    }
                } catch (Throwable t) {
                    Log.e(App.TAG, t.getMessage(), t);
                }
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(App.TAG, "loadRemoteVersion: " + e.getMessage());
                var pref = App.getPreferences();
                if (pref.getBoolean("checked", false)) return;
                pref.edit().putBoolean("checked", true).apply();
            }
        };
        App.getOkHttpClient().newCall(request).enqueue(callback);
    }

    private static void checkAssets(JsonObject assets, String releaseNotes) {
        var pref = App.getPreferences();
        var name = assets.get("name").getAsString();
        var versionCode = parseVersionCode(name);
        if (versionCode == null) return;
        var updatedAt = Instant.parse(assets.get("updated_at").getAsString());
        var downloadUrl = assets.get("browser_download_url").getAsString();
        var zipTime = pref.getLong("zip_time", 0);
        var hasNewAsset = !updatedAt.equals(Instant.ofEpochSecond(zipTime));
        pref.edit()
                .putInt("latest_version", versionCode)
                .putLong("latest_check", Instant.now().getEpochSecond())
                .putString("release_notes", releaseNotes)
                .putBoolean("checked", true)
                .apply();
        if (hasNewAsset) {
            pref.edit().putString("zip_file", null).apply();
            var zip = downloadNewZipSync(downloadUrl, name);
            var size = assets.get("size").getAsLong();
            if (zip != null && zip.length() == size) {
                pref.edit()
                        .putLong("zip_time", updatedAt.getEpochSecond())
                        .putString("zip_file", zip.getAbsolutePath())
                        .apply();
            }
        }
    }

    public static boolean needUpdate() {
        var pref = App.getPreferences();
        if (!pref.getBoolean("checked", false)) return false;
        var code = pref.getInt("latest_version", 0);
        return code > BuildConfig.VERSION_CODE;
    }

    @Nullable
    private static Integer parseVersionCode(String name) {
        var matcher = VERSION_CODE_PATTERN.matcher(name);
        Integer versionCode = null;
        while (matcher.find()) {
            versionCode = Integer.parseInt(matcher.group(1));
        }
        return versionCode;
    }

    @Nullable
    private static File downloadNewZipSync(String url, String name) {
        var request = new Request.Builder().url(url).build();
        var zip = new File(App.getInstance().getCacheDir(), name);
        try (Response response = App.getOkHttpClient().newCall(request).execute()) {
            var body = response.body();
            if (!response.isSuccessful() || body == null) return null;
            try (var source = body.source();
                 var sink = Okio.buffer(Okio.sink(zip))) {
                sink.writeAll(source);
            }
        } catch (IOException e) {
            Log.e(App.TAG, "downloadNewZipSync: " + e.getMessage());
            return null;
        }
        return zip;
    }
}

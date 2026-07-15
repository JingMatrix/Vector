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

package org.lsposed.manager.repo;

import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import org.lsposed.manager.App;
import org.lsposed.manager.R;
import org.lsposed.manager.repo.model.OnlineModule;
import org.lsposed.manager.repo.model.Release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RepoLoader {
    private static RepoLoader instance = null;
    private Map<String, OnlineModule> onlineModules = new HashMap<>();
    private Map<String, ModuleVersion> latestVersion = new ConcurrentHashMap<>();

    public static class ModuleVersion {
        public String versionName;
        public long versionCode;

        private ModuleVersion(long versionCode, String versionName) {
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        public boolean upgradable(long versionCode, String versionName) {
            return this.versionCode > versionCode || (this.versionCode == versionCode && !versionName.replace(' ', '_').equals(this.versionName));
        }

    }

    private final Path repoFile = Paths.get(App.getInstance().getFilesDir().getAbsolutePath(), "repo.json");
    private final Set<RepoListener> listeners = ConcurrentHashMap.newKeySet();
    private boolean repoLoaded = false;
    // The full module list is only served by the backup host; modules.lsposed.org
    // returns 403 for modules.json, and the blogcdn/cloudflare mirrors are dead.
    private static final String[] listRepoUrls = new String[]{
            "https://backup.modules.lsposed.org/"
    };
    // Per-module detail JSON is served by both the backup host and the public
    // site, so the latter acts as a real fallback for module/<package>.json.
    private static final String[] detailRepoUrls = new String[]{
            "https://backup.modules.lsposed.org/",
            "https://modules.lsposed.org/"
    };
    // Each module is mirrored as a GitHub repo under this org; used as a
    // last-resort README source when the JSON API omits it or is unreachable.
    private static final String moduleGithubReadmeUrl = "https://api.github.com/repos/Xposed-Modules-Repo/%s/readme";
    private final Resources resources = App.getInstance().getResources();
    private final String[] channels = resources.getStringArray(R.array.update_channel_values);

    public boolean isRepoLoaded() {
        return repoLoaded;
    }

    public static synchronized RepoLoader getInstance() {
        if (instance == null) {
            instance = new RepoLoader();
            App.getExecutorService().submit(() -> instance.loadLocalData(true));
        }
        return instance;
    }

    synchronized public void loadRemoteData() {
        repoLoaded = false;
        boolean loaded = false;
        Throwable lastError = null;
        try {
            for (String candidateRepoUrl : listRepoUrls) {
                try {
                    String bodyString = requestString(candidateRepoUrl + "modules.json");
                    OnlineModule[] repoModules = parseRepoModules(bodyString);
                    Files.write(repoFile, bodyString.getBytes(StandardCharsets.UTF_8));
                    Log.i(App.TAG, "repo: fetched module list from " + candidateRepoUrl + " (" + repoModules.length + " entries, " + bodyString.length() + " bytes)");
                    replaceRepoModules(repoModules);
                    loaded = true;
                    break;
                } catch (Throwable t) {
                    lastError = t;
                    Log.e(App.TAG, "load remote data from " + candidateRepoUrl, t);
                }
            }
            if (!loaded && lastError != null) {
                for (RepoListener listener : listeners) {
                    listener.onThrowable(lastError);
                }
            }
        } finally {
            if (!loaded) {
                Log.w(App.TAG, "repo: module list load failed on all mirrors, keeping cached data (" + onlineModules.size() + " modules)");
                repoLoaded = true;
                for (RepoListener listener : listeners) {
                    listener.onRepoLoaded();
                }
            }
        }
    }

    private OnlineModule[] parseRepoModules(String bodyString) throws IOException {
        Gson gson = new Gson();
        OnlineModule[] repoModules = gson.fromJson(bodyString, OnlineModule[].class);
        if (repoModules == null) {
            throw new IOException("Invalid repo response");
        }
        return repoModules;
    }

    private void replaceRepoModules(OnlineModule[] repoModules) {
        Map<String, OnlineModule> modules = new HashMap<>();
        Arrays.stream(repoModules).forEach(onlineModule -> modules.put(onlineModule.getName(), onlineModule));
        var channel = App.getPreferences().getString("update_channel", channels[0]);
        onlineModules = modules;
        Log.i(App.TAG, "repo: onlineModules replaced, now " + modules.size() + " modules (channel=" + channel + ")");
        updateLatestVersion(repoModules, channel);
    }

    private String requestString(String url) throws IOException {
        try (var response = App.getOkHttpClient().newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response " + response.code() + " from " + response.request().url());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response from " + response.request().url());
            }
            String bodyString = body.string();
            if (bodyString.trim().isEmpty()) {
                throw new IOException("Empty response from " + response.request().url());
            }
            return bodyString;
        }
    }

    synchronized public void loadLocalData(boolean updateRemoteRepo) {
        repoLoaded = false;
        Log.i(App.TAG, "repo: loadLocalData(updateRemoteRepo=" + updateRemoteRepo + "), cacheExists=" + Files.exists(repoFile));
        try {
            if (Files.notExists(repoFile)) {
                loadRemoteData();
                updateRemoteRepo = false;
            }
            byte[] encoded = Files.readAllBytes(repoFile);
            String bodyString = new String(encoded, StandardCharsets.UTF_8);
            OnlineModule[] repoModules = parseRepoModules(bodyString);
            Log.i(App.TAG, "repo: loadLocalData parsed " + repoModules.length + " modules from cache (" + encoded.length + " bytes)");
            replaceRepoModules(repoModules);
        } catch (Throwable t) {
            Log.e(App.TAG, "repo: loadLocalData failed", t);
            for (RepoListener listener : listeners) {
                listener.onThrowable(t);
            }
        } finally {
            repoLoaded = true;
            for (RepoListener listener : listeners) {
                listener.onRepoLoaded();
            }
            if (updateRemoteRepo) loadRemoteData();
        }
    }

    synchronized private void updateLatestVersion(OnlineModule[] onlineModules, String channel) {
        repoLoaded = false;
        Map<String, ModuleVersion> versions = new ConcurrentHashMap<>();
        for (var module : onlineModules) {
            String release = module.getLatestRelease();
            if (channel.equals(channels[1]) && module.getLatestBetaRelease() != null && !module.getLatestBetaRelease().isEmpty()) {
                release = module.getLatestBetaRelease();
            } else if (channel.equals(channels[2])) {
                if (module.getLatestSnapshotRelease() != null && !module.getLatestSnapshotRelease().isEmpty())
                    release = module.getLatestSnapshotRelease();
                else if (module.getLatestBetaRelease() != null && !module.getLatestBetaRelease().isEmpty())
                    release = module.getLatestBetaRelease();
            }
            if (release == null || release.isEmpty()) continue;
            var splits = release.split("-", 2);
            if (splits.length < 2) continue;
            long verCode;
            String verName;
            try {
                verCode = Long.parseLong(splits[0]);
                verName = splits[1];
            } catch (NumberFormatException ignored) {
                continue;
            }
            String pkgName = module.getName();
            versions.put(pkgName, new ModuleVersion(verCode, verName));
        }
        latestVersion = versions;
        repoLoaded = true;
        for (RepoListener listener : listeners) {
            listener.onRepoLoaded();
        }
    }

    public void updateLatestVersion(String channel) {
        if (repoLoaded)
            updateLatestVersion(onlineModules.keySet().parallelStream().map(onlineModules::get).toArray(OnlineModule[]::new), channel);
    }

    @Nullable
    public ModuleVersion getModuleLatestVersion(String packageName) {
        return repoLoaded ? latestVersion.getOrDefault(packageName, null) : null;
    }

    @Nullable
    public List<Release> getReleases(String packageName) {
        var channel = App.getPreferences().getString("update_channel", channels[0]);
        List<Release> releases = new ArrayList<>();
        if (repoLoaded) {
            var module = onlineModules.get(packageName);
            if (module != null) {
                releases = module.getReleases();
                if (!module.releasesLoaded) {
                    if (channel.equals(channels[1]) && !(module.getBetaReleases() != null && module.getBetaReleases().isEmpty())) {
                        releases = module.getBetaReleases();
                    } else if (channel.equals(channels[2]))
                        if (!(module.getSnapshotReleases() != null && module.getSnapshotReleases().isEmpty()))
                            releases = module.getSnapshotReleases();
                        else if (!(module.getBetaReleases() != null && module.getBetaReleases().isEmpty()))
                            releases = module.getBetaReleases();
                }
            }
        }
        return releases;
    }

    @Nullable
    public String getLatestReleaseTime(String packageName, String channel) {
        String releaseTime = null;
        if (repoLoaded) {
            var module = onlineModules.get(packageName);
            if (module != null) {
                releaseTime = module.getLatestReleaseTime();
                if (channel.equals(channels[1]) && module.getLatestBetaReleaseTime() != null) {
                    releaseTime = module.getLatestBetaReleaseTime();
                } else if (channel.equals(channels[2]))
                    if (module.getLatestSnapshotReleaseTime() != null)
                        releaseTime = module.getLatestSnapshotReleaseTime();
                    else if (module.getLatestBetaReleaseTime() != null)
                        releaseTime = module.getLatestBetaReleaseTime();
            }
        }
        return releaseTime;
    }

    public void loadRemoteReleases(String packageName) {
        loadRemoteReleases(packageName, 0);
    }

    private void loadRemoteReleases(String packageName, int attempt) {
        if (attempt >= detailRepoUrls.length) {
            // Every detail mirror failed; fall back to the module's GitHub repo
            // so we can at least recover the README instead of failing outright.
            Log.w(App.TAG, "repo: detail mirrors exhausted for " + packageName + ", falling back to GitHub README");
            loadReadmeFromGithub(packageName, null, new IOException("All module detail mirrors failed for " + packageName));
            return;
        }
        String candidateRepoUrl = detailRepoUrls[attempt];
        Log.i(App.TAG, "repo: loadRemoteReleases " + packageName + " attempt " + attempt + " -> " + candidateRepoUrl);
        App.getOkHttpClient().newCall(new Request.Builder().url(String.format(candidateRepoUrl + "module/%s.json", packageName)).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(App.TAG, "repo: detail fetch failed for " + packageName + " from " + call.request().url() + ": " + e.getMessage());
                loadRemoteReleases(packageName, attempt + 1);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (!response.isSuccessful()) {
                    Log.w(App.TAG, "repo: detail unexpected response " + response.code() + " for " + packageName + " from " + call.request().url());
                    response.close();
                    loadRemoteReleases(packageName, attempt + 1);
                    return;
                }
                OnlineModule module;
                try (response) {
                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new IOException("Empty response from " + call.request().url());
                    }
                    String bodyString = body.string();
                    if (bodyString.trim().isEmpty()) {
                        throw new IOException("Empty response from " + call.request().url());
                    }
                    Gson gson = new Gson();
                    module = gson.fromJson(bodyString, OnlineModule.class);
                    if (module == null) {
                        throw new IOException("Invalid response from " + call.request().url());
                    }
                    module.releasesLoaded = true;
                    onlineModules.replace(packageName, module);
                } catch (Throwable t) {
                    Log.e(App.TAG, "repo: detail parse failed for " + packageName, t);
                    loadRemoteReleases(packageName, attempt + 1);
                    return;
                }
                int releaseCount = module.getReleases() == null ? 0 : module.getReleases().size();
                Log.i(App.TAG, "repo: detail loaded for " + packageName + " from " + candidateRepoUrl + ", hasReadme=" + hasReadme(module) + ", releases=" + releaseCount);
                if (hasReadme(module)) {
                    for (RepoListener listener : listeners) {
                        listener.onModuleReleasesLoaded(module);
                    }
                } else {
                    // Detail loaded but carries no README; enrich it from GitHub
                    // before publishing so the README tab is not shown as empty.
                    Log.i(App.TAG, "repo: " + packageName + " detail has no README, fetching from GitHub");
                    loadReadmeFromGithub(packageName, module, null);
                }
            }
        });
    }

    private boolean hasReadme(@Nullable OnlineModule module) {
        return module != null && ((module.getReadmeHTML() != null && !module.getReadmeHTML().isEmpty())
                || (module.getReadme() != null && !module.getReadme().isEmpty()));
    }

    // Fetches the module's rendered README from its GitHub repo. When `loaded`
    // is non-null the detail JSON already succeeded (valid releases, README is a
    // bonus) and is always published; when null, the JSON mirrors were
    // unreachable, so we enrich the cached summary and surface `error` only if
    // even the GitHub fetch fails.
    private void loadReadmeFromGithub(String packageName, @Nullable OnlineModule loaded, @Nullable Throwable error) {
        OnlineModule target = loaded != null ? loaded : onlineModules.get(packageName);
        if (target == null) {
            Log.w(App.TAG, "repo: no cached module to enrich for " + packageName + ", giving up");
            if (error != null) {
                for (RepoListener listener : listeners) {
                    listener.onThrowable(error);
                }
            }
            return;
        }
        App.getOkHttpClient().newCall(new Request.Builder()
                .url(String.format(moduleGithubReadmeUrl, packageName))
                .header("Accept", "application/vnd.github.html+json")
                .build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(App.TAG, "repo: GitHub README fetch failed for " + packageName + ": " + e.getMessage());
                publishReadmeFallback(packageName, target, null, loaded != null, error);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                String html = null;
                try (response) {
                    if (response.isSuccessful()) {
                        ResponseBody body = response.body();
                        if (body != null) {
                            String bodyString = body.string();
                            if (!bodyString.trim().isEmpty()) {
                                html = bodyString;
                            }
                        }
                    } else {
                        Log.w(App.TAG, "repo: GitHub README unexpected response " + response.code() + " for " + packageName);
                    }
                } catch (Throwable t) {
                    Log.e(App.TAG, "repo: GitHub README read failed for " + packageName, t);
                }
                Log.i(App.TAG, "repo: GitHub README for " + packageName + " -> " + (html != null ? "recovered (" + html.length() + " bytes)" : "unavailable"));
                publishReadmeFallback(packageName, target, html, loaded != null, error);
            }
        });
    }

    private void publishReadmeFallback(String packageName, OnlineModule module, @Nullable String readmeHTML, boolean detailLoaded, @Nullable Throwable error) {
        if (readmeHTML != null) {
            module.setReadmeHTML(readmeHTML);
            onlineModules.replace(packageName, module);
        }
        if (detailLoaded || readmeHTML != null) {
            // The releases are already valid, or we recovered a README: publish
            // the (possibly enriched) module to the UI.
            Log.i(App.TAG, "repo: publishing " + packageName + " (detailLoaded=" + detailLoaded + ", readmeRecovered=" + (readmeHTML != null) + ")");
            for (RepoListener listener : listeners) {
                listener.onModuleReleasesLoaded(module);
            }
        } else if (error != null) {
            Log.w(App.TAG, "repo: nothing to publish for " + packageName + ", reporting failure");
            for (RepoListener listener : listeners) {
                listener.onThrowable(error);
            }
        }
    }

    public void addListener(RepoListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RepoListener listener) {
        listeners.remove(listener);
    }

    @Nullable
    public OnlineModule getOnlineModule(String packageName) {
        return repoLoaded && packageName != null ? onlineModules.get(packageName) : null;
    }

    @Nullable
    public Collection<OnlineModule> getOnlineModules() {
        return repoLoaded ? onlineModules.values() : null;
    }

    public interface RepoListener {
        default void onRepoLoaded() {
        }

        default void onModuleReleasesLoaded(OnlineModule module) {
        }

        default void onThrowable(Throwable t) {
            Log.e(App.TAG, "load repo failed", t);
        }
    }
}

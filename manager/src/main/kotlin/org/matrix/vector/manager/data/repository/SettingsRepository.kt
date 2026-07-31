package org.matrix.vector.manager.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.vector.manager.data.model.RepoVersion
import org.matrix.vector.manager.data.model.StoreInstall

/**
 * The manager's own preferences: how it looks, what it shows, and what it has been told to stop
 * mentioning.
 *
 * Nothing here belongs to the framework — which modules are on and what they may hook lives in the
 * daemon's database. This is the reader's opinion of the app, and it survives a process death,
 * which parasitically happens far more often than a user would expect since the host is
 * `com.android.shell`.
 */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_settings", Context.MODE_PRIVATE)

    // Theme Settings
    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamic_color", true))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _amoledBlack = MutableStateFlow(prefs.getBoolean("amoled_black", false))
    val amoledBlack: StateFlow<Boolean> = _amoledBlack.asStateFlow()

    /**
     * The colour every other colour is derived from, when dynamic colour is off.
     *
     * Stored as an ARGB int rather than a preset index so that a colour picked from the wheel
     * survives a reinstall and does not depend on the preset list staying the same order.
     */
    private val _seedColor = MutableStateFlow(prefs.getInt("seed_color", DEFAULT_SEED_COLOR))
    val seedColor: StateFlow<Int> = _seedColor.asStateFlow()

    fun setSeedColor(argb: Int) {
        prefs.edit().putInt("seed_color", argb).apply()
        _seedColor.value = argb
    }

    // Updates & Network

    /**
     * Which releases of a *module* the Store offers, "stable" or "beta". See StoreChannel.
     *
     * The framework's own channel is not here and is not a setting: it is derived from the build
     * that is actually running. See FrameworkUpdateRepository.
     */
    private val _updateChannel =
        MutableStateFlow(prefs.getString("update_channel", "stable") ?: "stable")
    val updateChannel: StateFlow<String> = _updateChannel.asStateFlow()

    /**
     * Resolve through Cloudflare rather than the network's own resolver.
     *
     * On by default. The mirrors this app depends on are the ones a network is most likely to
     * resolve wrongly or not at all, and someone whose Store is empty because of it has no reason
     * to suspect DNS. VectorDns only uses it when nothing is proxying the connection, and falls
     * back to the system resolver for the rest of the session the first time a lookup fails, so
     * the default costs nothing on a network where ordinary DNS already works.
     */
    private val _dohEnabled = MutableStateFlow(prefs.getBoolean("doh_enabled", true))
    val dohEnabled: StateFlow<Boolean> = _dohEnabled.asStateFlow()

    // --- Home activity feed ---

    /**
     * How far back the Home activity feed reaches, in months.
     *
     * Six is the default: long enough that a quiet stretch does not read as a dead project, short
     * enough that the contributor row still moves. A busy fork may want less, someone tracking a
     * slow-moving release may want more, so it is theirs to set.
     */
    private val _activityWindowMonths = MutableStateFlow(prefs.getInt("activity_window_months", 6))
    val activityWindowMonths: StateFlow<Int> = _activityWindowMonths.asStateFlow()

    /**
     * Whether GitHub links leave the app.
     *
     * Off by default: the built-in viewer keeps the user in Vector, which matters most in
     * parasitic mode where "the app" is really the shell process and handing off to a browser is a
     * jarring context switch out of something that does not look like an app to the system.
     */
    private val _openLinksExternally =
        MutableStateFlow(prefs.getBoolean("open_links_externally", false))
    val openLinksExternally: StateFlow<Boolean> = _openLinksExternally.asStateFlow()

    /**
     * How the scope list is filtered and ordered, remembered across visits.
     *
     * A scope is edited one module at a time, so these are settled a dozen times over in a single
     * sitting otherwise. They are ways of *reading* a list of several hundred apps rather than
     * anything about a particular module, which is the test this app applies everywhere else —
     * word wrap, header surface, activity window — and the reason it applies it is that the host
     * process is killed constantly, so anything held in a ViewModel is gone by the next visit.
     *
     * "Recommended only" is deliberately absent. It narrows the list to what one module asked for,
     * and a module that asks for nothing would then open to an empty screen — a filter that reads
     * as breakage. It stays per visit.
     */
    private val _scopeShowSystemApps = MutableStateFlow(prefs.getBoolean("scope_system_apps", false))
    val scopeShowSystemApps: StateFlow<Boolean> = _scopeShowSystemApps.asStateFlow()

    fun setScopeShowSystemApps(show: Boolean) {
        prefs.edit().putBoolean("scope_system_apps", show).apply()
        _scopeShowSystemApps.value = show
    }

    private val _scopeShowGames = MutableStateFlow(prefs.getBoolean("scope_games", true))
    val scopeShowGames: StateFlow<Boolean> = _scopeShowGames.asStateFlow()

    fun setScopeShowGames(show: Boolean) {
        prefs.edit().putBoolean("scope_games", show).apply()
        _scopeShowGames.value = show
    }

    private val _scopeShowModules = MutableStateFlow(prefs.getBoolean("scope_modules", false))
    val scopeShowModules: StateFlow<Boolean> = _scopeShowModules.asStateFlow()

    fun setScopeShowModules(show: Boolean) {
        prefs.edit().putBoolean("scope_modules", show).apply()
        _scopeShowModules.value = show
    }

    private val _scopeSort = MutableStateFlow(prefs.getString("scope_sort", "relevance") ?: "relevance")
    val scopeSort: StateFlow<String> = _scopeSort.asStateFlow()

    fun setScopeSort(key: String) {
        prefs.edit().putString("scope_sort", key).apply()
        _scopeSort.value = key
    }

    private val _scopeSortReversed = MutableStateFlow(prefs.getBoolean("scope_sort_reversed", false))
    val scopeSortReversed: StateFlow<Boolean> = _scopeSortReversed.asStateFlow()

    fun setScopeSortReversed(reversed: Boolean) {
        prefs.edit().putBoolean("scope_sort_reversed", reversed).apply()
        _scopeSortReversed.value = reversed
    }

    /**
     * How the contributor row is ordered: by how much someone has done, or by how recently.
     *
     * Both are honest and they honour different people. Volume puts the maintainer first forever,
     * which is accurate and unchanging; recency puts whoever last landed something at the front,
     * which is what makes a first contribution visible the day it happens.
     */
    private val _contributorOrder =
        MutableStateFlow(prefs.getString("contributor_order", "commits") ?: "commits")
    val contributorOrder: StateFlow<String> = _contributorOrder.asStateFlow()

    fun setContributorOrder(key: String) {
        prefs.edit().putString("contributor_order", key).apply()
        _contributorOrder.value = key
    }

    /**
     * The language the app is shown in, as a BCP-47 tag, or empty for whatever the system says.
     *
     * Not `setApplicationLocales`: that API is keyed on an installed package, and parasitically
     * this one is never installed. Asking the framework would change the host's language or
     * nothing at all. See LocalizedContent for how the override is applied instead.
     */
    private val _appLocale = MutableStateFlow(prefs.getString("app_locale", "") ?: "")
    val appLocale: StateFlow<String> = _appLocale.asStateFlow()

    fun setAppLocale(tag: String) {
        prefs.edit().putString("app_locale", tag).apply()
        _appLocale.value = tag
    }

    /**
     * Modules the reader has told us to stop nagging about.
     *
     * In the manager's own preferences rather than in the daemon's module database, because this is
     * a fact about *this reader's opinion of the catalogue*, not about the module: the daemon has
     * never heard of the catalogue, does not know a remote version exists, and would have to be
     * taught the whole notion to store one boolean. Muting also has to survive a module being
     * uninstalled and reinstalled, which a daemon-side per-module row would not.
     */
    private val _mutedUpdates =
        MutableStateFlow(prefs.getStringSet("muted_updates", emptySet())?.toSet() ?: emptySet())
    val mutedUpdates: StateFlow<Set<String>> = _mutedUpdates.asStateFlow()

    fun setUpdatesMuted(packageName: String, muted: Boolean) {
        val next =
            if (muted) _mutedUpdates.value + packageName else _mutedUpdates.value - packageName
        // A set of our own on the way in, and `toSet()` on the way out above: `getStringSet` hands
        // back the instance the preferences hold, which the platform documents as not ours to
        // modify.
        prefs.edit().putStringSet("muted_updates", HashSet(next)).apply()
        _mutedUpdates.value = next
    }

    /**
     * Which catalogue release the Store put on this device, per package. See [StoreInstall].
     *
     * Here rather than in the daemon for the reason the mute above is: the daemon has never heard
     * of the catalogue, and this is a fact about what *this* app did rather than about the module.
     * It has to survive a process death for the same reason too — parasitically the process is the
     * shell's, and it is killed constantly, so an in-memory note would forget by the next visit and
     * the offer it silenced would be back.
     *
     * A string set, like the mute, rather than a serialised map: three fields per row, joined by
     * newlines, which no package name or tag contains. A row that no longer parses is dropped,
     * which is the right answer for a note whose only job is to suppress an offer — the worst a
     * lost row can do is offer an update again. Rows are never pruned either, for the same reason:
     * one is a few dozen bytes, a device carries tens of modules, and a note left behind by a
     * module that has since been uninstalled says nothing until that module is back at that exact
     * version.
     */
    private val _storeInstalls = MutableStateFlow(readStoreInstalls())
    val storeInstalls: StateFlow<Map<String, StoreInstall>> = _storeInstalls.asStateFlow()

    /** Records what the Store installed for [packageName], replacing any earlier note of it. */
    fun noteStoreInstall(packageName: String, install: StoreInstall) {
        val next = _storeInstalls.value + (packageName to install)
        val rows = next.mapTo(HashSet()) { (name, noted) -> encode(name, noted) }
        prefs.edit().putStringSet("store_installs", rows).apply()
        _storeInstalls.value = next
    }

    private fun encode(packageName: String, install: StoreInstall): String =
        "$packageName\n${install.release.tag}\n${install.installed.tag}"

    private fun readStoreInstalls(): Map<String, StoreInstall> =
        prefs
            .getStringSet("store_installs", emptySet())
            .orEmpty()
            .mapNotNull { row ->
                val parts = row.split('\n')
                if (parts.size != 3) return@mapNotNull null
                val release = RepoVersion.parse(parts[1]) ?: return@mapNotNull null
                val installed = RepoVersion.parse(parts[2]) ?: return@mapNotNull null
                parts[0] to StoreInstall(release, installed)
            }
            .toMap()

    /** Which living surface the status header draws. See AmbienceKind. */
    private val _headerAmbience =
        MutableStateFlow(prefs.getString("header_ambience", DEFAULT_AMBIENCE) ?: DEFAULT_AMBIENCE)
    val headerAmbience: StateFlow<String> = _headerAmbience.asStateFlow()

    private val _updateVariant =
        MutableStateFlow(prefs.getString("update_variant", "release") ?: "release")

    /**
     * Which build of the framework to install, "release" or "debug".
     *
     * Remembered because someone who wants debug builds wants them every time — a maintainer
     * chasing a bug report is not making a fresh decision on each update — and because the choice
     * is otherwise invisible until the download size appears.
     */
    val updateVariant: StateFlow<String> = _updateVariant.asStateFlow()

    fun setUpdateVariant(key: String) {
        prefs.edit().putString("update_variant", key).apply()
        _updateVariant.value = key
    }

    /**
     * How big, how varied and how fast each ambience draws itself.
     *
     * Per kind rather than global: a comfortable glyph size for the code rain says nothing about
     * how large a maze cell should be, and someone who has tuned one and switches away should find
     * it as they left it. Written straight through on every gesture — these are a handful of bytes,
     * and the alternative is losing the adjustment to the next process death.
     */
    fun ambienceScale(kind: String): Float = prefs.getFloat("ambience_scale_$kind", 1f)

    fun setAmbienceScale(kind: String, value: Float) {
        prefs.edit().putFloat("ambience_scale_$kind", value).apply()
    }

    fun ambienceVariant(kind: String): Int = prefs.getInt("ambience_variant_$kind", 0)

    fun setAmbienceVariant(kind: String, value: Int) {
        prefs.edit().putInt("ambience_variant_$kind", value).apply()
    }

    fun ambienceSpeed(kind: String): Float = prefs.getFloat("ambience_speed_$kind", 1f)

    fun setAmbienceSpeed(kind: String, value: Float) {
        prefs.edit().putFloat("ambience_speed_$kind", value).apply()
    }

    fun setHeaderAmbience(key: String) {
        prefs.edit().putString("header_ambience", key).apply()
        _headerAmbience.value = key
    }

    fun setActivityWindowMonths(months: Int) {
        prefs.edit().putInt("activity_window_months", months).apply()
        _activityWindowMonths.value = months
    }

    fun setOpenLinksExternally(enabled: Boolean) {
        prefs.edit().putBoolean("open_links_externally", enabled).apply()
        _openLinksExternally.value = enabled
    }

    /**
     * Whether Home has been told to stop offering a launcher icon.
     *
     * Set by the "don't ask again" on the prompt that appears on first launch. Kept separate from
     * "a shortcut is pinned", which is the launcher's fact and is asked of the launcher: someone
     * who dismisses the prompt and later pins the shortcut by hand should not be asked again, and
     * someone who removes the shortcut should not be nagged about it once they have said no.
     */
    private val _launcherPromptDismissed =
        MutableStateFlow(prefs.getBoolean("launcher_prompt_dismissed", false))
    val launcherPromptDismissed: StateFlow<Boolean> = _launcherPromptDismissed.asStateFlow()

    fun dismissLauncherPrompt() {
        prefs.edit().putBoolean("launcher_prompt_dismissed", true).apply()
        _launcherPromptDismissed.value = true
    }

    // --- Logs ---

    /**
     * Whether log lines wrap rather than pan sideways.
     *
     * Persisted because it is a reading preference, not a transient view state: parasitically the
     * manager lives inside `com.android.shell`, whose process is killed routinely, so anything held
     * only in a ViewModel resets far more often than a user would expect.
     */
    private val _logWordWrap = MutableStateFlow(prefs.getBoolean("log_word_wrap", true))
    val logWordWrap: StateFlow<Boolean> = _logWordWrap.asStateFlow()

    fun setLogWordWrap(enabled: Boolean) {
        prefs.edit().putBoolean("log_word_wrap", enabled).apply()
        _logWordWrap.value = enabled
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _dynamicColor.value = enabled
    }

    fun setAmoledBlack(enabled: Boolean) {
        prefs.edit().putBoolean("amoled_black", enabled).apply()
        _amoledBlack.value = enabled
    }

    fun setUpdateChannel(channel: String) {
        prefs.edit().putString("update_channel", channel).apply()
        _updateChannel.value = channel
    }

    fun setDohEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("doh_enabled", enabled).apply()
        _dohEnabled.value = enabled
    }

    private companion object {
        /** The Winged Victory's patina. Kept as a literal so this file needs no UI imports. */
        const val DEFAULT_SEED_COLOR = 0xFF6ABFCF.toInt()

        /**
         * Must match an `AmbienceKind` key. An unknown one falls back harmlessly, but a stored
         * default that names no surface misleads whoever reads the preferences next.
         */
        const val DEFAULT_AMBIENCE = "maze"
    }
}

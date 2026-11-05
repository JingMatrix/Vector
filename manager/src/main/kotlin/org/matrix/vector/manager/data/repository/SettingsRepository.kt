package org.matrix.vector.manager.data.repository

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.vector.ui.net.NetworkSettings
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreInstall
import org.matrix.vector.ui.store.StoreSettings

/**
 * The manager's own preferences: how it looks, what it shows, and what it has been told to stop
 * mentioning.
 *
 * Nothing here belongs to the framework — which modules are on and what they may hook lives in the
 * daemon's database. This is the reader's opinion of the app, and it survives a process death,
 * which parasitically happens far more often than a user would expect since the host is
 * `com.android.shell`.
 */
class SettingsRepository(context: Context) : StoreSettings, NetworkSettings {
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
    override val updateChannel: StateFlow<String> = _updateChannel.asStateFlow()

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
    override val dohEnabled: StateFlow<Boolean> = _dohEnabled.asStateFlow()

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
    override val mutedUpdates: StateFlow<Set<String>> = _mutedUpdates.asStateFlow()

    override fun setUpdatesMuted(packageName: String, muted: Boolean) {
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
    override val storeInstalls: StateFlow<Map<String, StoreInstall>> = _storeInstalls.asStateFlow()

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

    /**
     * Which launchers are known to be holding a pinned Vector shortcut.
     *
     * The platform will not say. `ShortcutManager.getPinnedShortcuts` answers for *any* launcher at
     * once — the pin flag lives on the shortcut, not on the pair — and the per-launcher sets are
     * only readable by a caller that is itself the active launcher. So a device that pinned the
     * shortcut, then installed a different launcher, is told it already has one while its home
     * screen has nothing on it, which is what #883 reported.
     *
     * A set rather than a single package because pinning on a second launcher does not unpin the
     * first, and someone who keeps two and switches between them should not be offered a shortcut
     * they already have on both. What the set cannot represent is a shortcut *removed* from one of
     * several launchers holding it: nothing tells us which one lost it, and the platform still
     * reports the shortcut pinned. That row will read as done until the last copy is gone.
     */
    fun shortcutLaunchers(): Set<String> =
        prefs.getStringSet("shortcut_launchers", emptySet()).orEmpty().toSet()

    fun noteShortcutLauncher(packageName: String) {
        val known = shortcutLaunchers()
        if (packageName in known) return
        // A set of our own: `getStringSet` hands back the instance the preferences hold, which the
        // platform documents as not ours to modify.
        prefs.edit().putStringSet("shortcut_launchers", HashSet(known + packageName)).apply()
    }

    // --- the status badge's own hint ----------------------------------------------------------

    /**
     * How many times *today* the status badge was used to open System status.
     *
     * The badge is the only way to those settings, and nothing about a tick says so — #856. The
     * header answers that by having the tick turn into a gear now and then, and this is what stops
     * it: a reader who has opened the page several times today plainly knows where it is, and a gear
     * that keeps appearing after that is noise on the one part of the header whose job is to report
     * a state. How many is several is HomeViewModel's to say — this only counts.
     *
     * Counted per day rather than for good because the hint costs nothing to offer again and the
     * knowledge does fade — and because a count that only ever grows would retire the hint on the
     * strength of an afternoon spent on that page months ago. The day is stored beside the count and
     * a stale one reads as zero, so no reset has to run at midnight.
     */
    private val _statusBadgeOpens = MutableStateFlow(statusBadgeOpensToday())
    val statusBadgeOpens: StateFlow<Int> = _statusBadgeOpens.asStateFlow()

    fun noteStatusBadgeOpened() {
        val today = LocalDate.now().toEpochDay()
        // Against the stored day, not against the flow: a session left open across midnight holds
        // yesterday's count in memory, and adding to it would carry it into today.
        val next =
            if (prefs.getLong("status_badge_day", 0L) == today) _statusBadgeOpens.value + 1 else 1
        prefs.edit().putLong("status_badge_day", today).putInt("status_badge_opens", next).apply()
        _statusBadgeOpens.value = next
    }

    /**
     * Re-reads the count against today's date.
     *
     * Called when Home is opened, which is the only moment the hint can start running again, and is
     * what lets a session that has crossed midnight — parasitically rare, since the host process is
     * killed constantly, but free to handle — offer it afresh.
     */
    fun refreshStatusBadgeOpens() {
        _statusBadgeOpens.value = statusBadgeOpensToday()
    }

    private fun statusBadgeOpensToday(): Int =
        if (prefs.getLong("status_badge_day", 0L) == LocalDate.now().toEpochDay())
            prefs.getInt("status_badge_opens", 0)
        else 0

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

    /**
     * Whether a stack trace in the log opens where it sits, or on a screen of its own.
     *
     * Inline by default. The log is read with a filter applied and a scroll position worth keeping,
     * and pushing a route for one entry costs both — which matters most when the reason you are
     * reading the log is to compare one trace against another. The screen is the better answer for
     * a trace long enough that having it inside the list is the thing in the way, so which one is
     * right depends on the reader, and that is what makes it a setting rather than a decision.
     */
    private val _logTracesInline = MutableStateFlow(prefs.getBoolean("log_traces_inline", true))
    val logTracesInline: StateFlow<Boolean> = _logTracesInline.asStateFlow()

    fun setLogTracesInline(inline: Boolean) {
        prefs.edit().putBoolean("log_traces_inline", inline).apply()
        _logTracesInline.value = inline
    }

    // --- Navigation panels ---

    /**
     * Which panels the navigation container shows, in which order, and which are hidden.
     *
     * One delimited string rather than a set: `putStringSet` does not preserve order — the same
     * fact `muted_updates` above relies on being harmless — and here the order is the whole point.
     * Route keys rather than ordinals or class names, because R8 rewrites class names in a release
     * build and an ordinal would silently mean a different panel the day a fifth one is added.
     * Empty means "the catalogue as declared", which is what a fresh install has and what anyone
     * who has never opened edit mode keeps. See NavPanels for the format.
     */
    private val _navPanels = MutableStateFlow(prefs.getString("nav_panels", "") ?: "")
    val navPanels: StateFlow<String> = _navPanels.asStateFlow()

    fun setNavPanels(encoded: String) {
        prefs.edit().putString("nav_panels", encoded).apply()
        _navPanels.value = encoded
    }

    /**
     * Whether the panels live on a draggable ball over the content instead of in a bar or a rail.
     *
     * Off by default: the bar is what every other app on the device puts there, and a reader who
     * has not asked for anything else should not have to work out where their panels went. It is
     * offered at all because the bar costs a strip of every screen for four items that are rarely
     * touched, and on a small phone reading a log that strip is the expensive part.
     */
    private val _floatingNav = MutableStateFlow(prefs.getBoolean("floating_nav", false))
    val floatingNav: StateFlow<Boolean> = _floatingNav.asStateFlow()

    fun setFloatingNav(enabled: Boolean) {
        prefs.edit().putBoolean("floating_nav", enabled).apply()
        _floatingNav.value = enabled
    }

    /**
     * Where the floating ball was left: which side it snapped to, and how far down it sits as a
     * fraction of the window height.
     *
     * No flow, for the same reason the ambience adjustments have none: written straight through
     * from a gesture and read once when the ball is composed, so a StateFlow would recompose the
     * very thing being dragged on every frame of the drag. Persisted rather than remembered because
     * somebody who moved the ball out of the way of what they were reading has made a decision
     * about their thumb, and the host process is killed often enough that anything held in memory
     * would put the ball back over the content within the hour.
     *
     * The side is stored, not the x position: the ball always snaps to an edge, so a coordinate
     * would be a lie the moment the window is a different width — which, unfoldable and in
     * landscape, it routinely is.
     */
    fun floatingNavAtEnd(): Boolean = prefs.getBoolean("floating_nav_at_end", true)

    fun setFloatingNavAtEnd(atEnd: Boolean) {
        prefs.edit().putBoolean("floating_nav_at_end", atEnd).apply()
    }

    fun floatingNavY(): Float = prefs.getFloat("floating_nav_y", 0.72f)

    fun setFloatingNavY(fraction: Float) {
        prefs.edit().putFloat("floating_nav_y", fraction).apply()
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

    override fun setUpdateChannel(channel: String) {
        prefs.edit().putString("update_channel", channel).apply()
        _updateChannel.value = channel
    }

    override fun setDohEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("doh_enabled", enabled).apply()
        _dohEnabled.value = enabled
    }

    /** Bumped on every write, so a sheet already open re-reads the order it is drawing from. */
    private val _actionOrderRevision = MutableStateFlow(0)
    val actionOrderRevision: StateFlow<Int> = _actionOrderRevision.asStateFlow()

    /**
     * The order the long-press sheet lists its actions in, per kind of package.
     *
     * Ids rather than indices, so an action added or retired in a later build does not shuffle the
     * rest: one the stored order does not name keeps its place in declaration order, and a name the
     * build no longer has is ignored. Empty until someone moves a row, which is the usual case.
     */
    fun actionOrder(kind: String): List<String> =
        prefs.getString("action_order_$kind", "").orEmpty().split(',').filter { it.isNotEmpty() }

    fun setActionOrder(kind: String, ids: List<String>) {
        prefs.edit().putString("action_order_$kind", ids.joinToString(",")).apply()
        _actionOrderRevision.value++
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

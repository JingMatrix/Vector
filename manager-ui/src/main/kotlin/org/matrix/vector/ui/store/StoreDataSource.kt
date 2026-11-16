package org.matrix.vector.ui.store

import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the shared Store screen needs from its host to reach the catalogue and the device.
 * [StoreRepository] implements it for every app that hosts the Store, since how the repository is
 * read is a fact about the repository; what an app supplies is how the device is asked what is
 * installed and where a log line goes.
 */
interface StoreDataSource {
    /** The online module catalogue, updated by [refresh]. */
    val catalog: StateFlow<StoreCatalog>

    /** Whether a network refresh is in flight (drives pull-to-refresh). */
    val isRefreshing: StateFlow<Boolean>

    /** Installed module versions on this device, keyed by package name. */
    val installedVersions: StateFlow<Map<String, RepoVersion>>

    /** Reload the catalogue from the network; [force] bypasses any cache. */
    suspend fun refresh(force: Boolean = false)

    /** The full record for one module (README, all releases); null when unreachable. */
    suspend fun details(packageName: String): OnlineModule?

    /** Re-read installed versions (e.g. after an install/uninstall). */
    fun refreshInstalled()
}

/**
 * The store's few persisted preferences, injected so the shared view model does not depend on any
 * app's settings store. Hosts that do not persist these can back them with in-memory defaults.
 */
interface StoreSettings {
    /** The release channel preference, as a raw token ("stable" / "beta"). */
    val updateChannel: StateFlow<String>

    /** Package names whose available update the user has muted. */
    val mutedUpdates: StateFlow<Set<String>>

    /** Store-tracked installs, keyed by package name. */
    val storeInstalls: StateFlow<Map<String, StoreInstall>>

    fun setUpdateChannel(token: String)

    /** Mute or un-mute the available update for [packageName] (the Details screen's options sheet). */
    fun setUpdatesMuted(packageName: String, muted: Boolean)

    /** Whether foreign modules in the Store are automatically translated. */
    val autoTranslate: StateFlow<Boolean>
        get() = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun setAutoTranslate(enabled: Boolean) {}

    /** Effective target language tag for module translation (never empty when resolved). */
    val translationTargetLanguage: StateFlow<String>
        get() = kotlinx.coroutines.flow.MutableStateFlow("")

    fun setTranslationTargetLanguage(tag: String) {}

    /** Explicit override language tag if set, or empty to follow app locale. */
    val translationOverrideTag: StateFlow<String>
        get() = kotlinx.coroutines.flow.MutableStateFlow("")
}

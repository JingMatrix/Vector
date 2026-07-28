package org.matrix.vector.manager.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.vector.manager.data.github.FrameworkRelease
import org.matrix.vector.manager.data.github.GitHubRepository

/**
 * Whether a newer build of the framework exists, and which one this reader may be offered.
 *
 * **The channel is derived, not configured.** A preference would be a second source of truth about
 * something the device can simply be asked: someone who flashed a canary once and never changed a
 * setting would keep being offered canaries, and someone on a release build who toggled the setting
 * out of curiosity would be offered nightlies. Neither is what they are running.
 *
 * A build is a canary if either is true:
 *
 * - a `canary-<versionCode>` release exists for exactly this version code, which is the direct
 *   evidence; or
 * - this version code is *higher than the newest stable release*, which catches the canary that has
 *   aged out of the rolling five prereleases and would otherwise look like a release build. It also
 *   correctly classifies a locally built development copy, which is ahead of everything published.
 *
 * A reader on a release build is only ever offered releases. That is the whole point of the
 * distinction: a nightly is not something to be nudged towards.
 */
class FrameworkUpdateRepository(private val github: GitHubRepository) {

    private val _state = MutableStateFlow(FrameworkUpdateState())
    val state: StateFlow<FrameworkUpdateState> = _state.asStateFlow()

    suspend fun refresh(
        installedVersionCode: Long,
        installedCommit: String? = null,
        freshness: GitHubRepository.Freshness = GitHubRepository.Freshness.Revalidate,
    ) {
        if (installedVersionCode <= 0) return
        val releases = github.frameworkReleases(freshness)
        if (releases.isEmpty()) return

        val newestStable = releases.firstOrNull { !it.isCanary }
        val onCanary =
            releases.any { it.isCanary && it.versionCode == installedVersionCode } ||
                (newestStable != null && installedVersionCode > newestStable.versionCode)

        // A canary reader sees whichever is newer; a release reader never sees a canary at all.
        val candidates = if (onCanary) releases else releases.filterNot { it.isCanary }
        val newest = candidates.maxByOrNull { it.versionCode }

        _state.value =
            FrameworkUpdateState(
                loaded = true,
                onCanaryChannel = onCanary,
                installedVersionCode = installedVersionCode,
                installedCommit = installedCommit,
                available = newest?.takeIf { it.versionCode > installedVersionCode },
                // Every release on the channel, not only the newest: the same list that answers
                // "is there anything newer" also answers "what could I go back to" — a question
                // people ask after a build breaks something for them.
                history = candidates.sortedByDescending { it.versionCode },
            )
    }
}

/**
 * What is known about framework updates right now.
 *
 * [available] is null both when nothing newer exists and before anything has been fetched, which a
 * screen must not confuse: [loaded] is what separates "there is nothing newer" from "nobody has
 * asked yet".
 */
data class FrameworkUpdateState(
    val loaded: Boolean = false,
    val onCanaryChannel: Boolean = false,
    val installedVersionCode: Long = 0,
    /** The commit the running daemon was built from, when it recorded one. */
    val installedCommit: String? = null,
    val available: FrameworkRelease? = null,
    /** Every release on this channel, newest first — including ones older than the installed one. */
    val history: List<FrameworkRelease> = emptyList(),
) {
    val hasUpdate: Boolean
        get() = available != null
}

/** Where a release sits relative to what is installed. */
enum class ReleaseDirection {
    Newer,
    Installed,
    Older,
}

/**
 * Whether the running build is something other than the one this release published.
 *
 * Only askable when both sides recorded a commit: the canaries carry a SHA, a hand-made release
 * carries a branch name, and a build made before this existed carries nothing. "I cannot tell" is a
 * third answer and is reported as false rather than as divergence.
 */
fun FrameworkUpdateState.divergesFrom(release: FrameworkRelease?): Boolean {
    if (release == null || release.versionCode != installedVersionCode) return false
    val mine = installedCommit ?: return false
    val theirs = release.commit ?: return false
    // A dirty build matches nothing by definition — it was not built from any commit.
    if (mine.endsWith("-dirty")) return true
    return !theirs.startsWith(mine)
}

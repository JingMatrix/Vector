package org.matrix.vector.manager.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.vector.manager.data.github.FrameworkRelease
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.model.buildStamp

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
 * A reader on a release build is never *offered* a canary. That is the whole point of the
 * distinction: a nightly is not something to be nudged towards. It is not a ban on installing one —
 * the canary list exists to be acted on, and [FrameworkUpdateState.catalog] keeps both channels so
 * a build asked for by name can still be found. Only the unasked-for offer is filtered.
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

        // A canary reader is offered whichever is newer; a release reader is offered no canary.
        val newest = releases.filter { onCanary || !it.isCanary }.maxByOrNull { it.versionCode }

        _state.value =
            FrameworkUpdateState(
                installedVersionCode = installedVersionCode,
                installedCommit = installedCommit,
                available = newest?.takeIf { it.versionCode > installedVersionCode },
                // Every published build, not only the newest and not only this channel's: the same
                // list that answers "is there anything newer" also answers "what could I go back
                // to" — a question people ask after a build breaks something for them — and "which
                // build was that row on the canary page", which is a question only the other
                // channel can answer.
                catalog = releases.sortedByDescending { it.versionCode },
                onCanary = onCanary,
            )
    }
}

/**
 * What is known about framework updates right now.
 *
 * [available] is null both when nothing newer exists and before anything has been fetched. Nothing
 * asks the two apart: the screens that read this show an update when there is one and say nothing
 * when there is not, which is the same answer either way.
 */
data class FrameworkUpdateState(
    val installedVersionCode: Long = 0,
    /**
     * The build stamp the running daemon reports, when it recorded one.
     *
     * Not a bare hash: it names where the build came from as well as what commit it was made from,
     * in one of the shapes [buildStamp] reads. Nothing here compares it as a string.
     */
    val installedCommit: String? = null,
    val available: FrameworkRelease? = null,
    /**
     * Every published build, both channels, newest first — including ones older than the installed
     * one, and, for a reader on a release build, the canaries they are not being offered.
     *
     * Kept whole because a canary the reader picked off the canary page has to be resolvable by
     * version code. Filtering it out here is what made that tap land on the newest *release*
     * instead: the number named a build the screen had thrown away, so the selection fell through
     * to the channel's default and a reader who asked for a nightly was shown the stable release
     * they were already running.
     */
    val catalog: List<FrameworkRelease> = emptyList(),
    /** Whether the running build is itself a canary, by the rule the repository documents. */
    val onCanary: Boolean = false,
) {
    /** What this reader is offered unasked: their own channel, newest first. */
    val history: List<FrameworkRelease>
        get() = catalog.filter { onCanary || !it.isCanary }

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
 *
 * What the framework reports is a *build stamp*, not a bare hash, so the commit is read out of it
 * before anything is compared. Comparing the whole stamp is what #809 left behind: a CI stamp
 * carries the repository as well, no release SHA matches that, and so every canary reader was told
 * they were running "same number, other build" against the very release they had flashed, with no
 * row anywhere marked as installed.
 */
fun FrameworkUpdateState.divergesFrom(release: FrameworkRelease?): Boolean {
    if (release == null || release.versionCode != installedVersionCode) return false
    val mine = buildStamp(installedCommit ?: return false)
    if (mine.commit == null || release.commit == null) return false
    return !mine.isCommit(release.commit)
}

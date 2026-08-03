package org.matrix.vector.manager.ui.screens.canary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.manager.data.github.ClosedIssue
import org.matrix.vector.manager.data.github.CommunityFeed
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.repository.CanaryBoard
import org.matrix.vector.manager.data.repository.CanaryLayout
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.logW

/**
 * The canary list, joined to the history it comes from.
 *
 * **Nothing here fetches anything the app was not already holding.** The builds are the release
 * list the update page reads, filtered to the canaries; the commits are the feed the home screen
 * loads on launch, served from disk. The screen this feeds used to make a request of its own for a
 * second view of the first of those, and could still say nothing about the second.
 */
class CanaryViewModel : ViewModel() {

    private val daemon = ServiceLocator.daemon
    private val github = ServiceLocator.github
    private val updates = ServiceLocator.frameworkUpdates

    private val feed = MutableStateFlow(CommunityFeed())
    private val closed = MutableStateFlow<List<ClosedIssue>>(emptyList())

    /**
     * True once the release list has answered, however it answered.
     *
     * Without it an unreachable GitHub is indistinguishable from a fetch in flight, and the screen
     * spins forever on the devices least able to reach it.
     */
    private val attempted = MutableStateFlow(false)

    val board: StateFlow<CanaryBoard> =
        combine(feed, updates.state, closed, attempted) { commits, state, issues, asked ->
                CanaryLayout.build(commits, state, issues, asked)
            }
            // Off the main thread for the same reason the rail is: laying this out is a pass over
            // an archive that runs to thousands of commits, once per canary shown.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CanaryBoard())

    init {
        viewModelScope.launch {
            // The framework's version when the daemon is up, otherwise this manager's own. Both
            // are `git rev-list --count origin/master` on the same repository, so either locates a
            // build among the canaries correctly — and the fallback matters more here than
            // anywhere else, because a reader whose framework is not answering is exactly the
            // reader who has come looking for a build that works.
            val installed =
                daemon
                    .getXposedVersionCode()
                    .getOrElse { e ->
                        logW("canary: framework version unavailable, using the manager's own", e)
                        0L
                    }
                    .takeIf { it > 0 } ?: BuildConfig.VERSION_CODE.toLong()
            updates.refresh(installed, daemon.getFrameworkCommit().getOrNull())
            attempted.value = true
        }
        viewModelScope.launch {
            // The one request this screen adds, and the only way to know what has actually been
            // fixed: see `GitHubRepository.closedIssues`. Revalidated rather than forced, so
            // coming back to the screen inside the half-hour window costs nothing.
            closed.value = github.closedIssues()
        }
        viewModelScope.launch {
            // Disk first, which is where the home screen's launch-time load has already put it, so
            // arriving here costs no request and no wait. Only a reader who reached this screen
            // before that finished — or on the first run of a fresh install — pays for a fetch.
            val cached = github.load(GitHubRepository.Freshness.Cached)
            feed.value = cached
            if (cached.commits.isEmpty()) {
                feed.value = github.load(GitHubRepository.Freshness.Revalidate)
            }
        }
    }
}

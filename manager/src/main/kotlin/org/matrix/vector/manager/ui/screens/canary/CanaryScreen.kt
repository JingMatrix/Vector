package org.matrix.vector.manager.ui.screens.canary

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.github.TimelineCommit
import org.matrix.vector.manager.data.repository.CanaryItem
import org.matrix.vector.manager.data.repository.CanaryOverview
import org.matrix.vector.manager.data.repository.CanarySpan
import org.matrix.vector.manager.ui.components.InstalledMarkerRow
import org.matrix.vector.manager.ui.components.exactTime
import org.matrix.vector.manager.ui.theme.VectorMono

/**
 * Canary builds: what has landed since the reader's own build, and how to go and run it.
 *
 * **Nobody signs in here, and that is what decides where the zips come from.** GitHub gates artifact
 * downloads behind an account even on a public repository — `actions/artifacts/<id>/zip` answers 401
 * to an anonymous caller where a release asset answers 206 — so listing Actions artifacts would mean
 * asking every would-be tester for an OAuth grant to work around where the zips happen to live, and
 * would lose the people who cannot reach GitHub's login page at all. CI attaches the same zips to a
 * rolling `canary-<versionCode>` prerelease, and this lists those.
 *
 * **This page chooses; it does not install.** It used to do both, badly: each row carried the zip
 * names, their sizes and an install button, all of which the build page does better — it states the
 * sizes, remembers which variant was last taken, checks the root implementation, shows the download
 * and the installer's own output. Duplicating that left no room for the one thing this screen is
 * for, which is deciding whether tonight's build is worth an evening.
 *
 * So each row answers that instead. A version code is `git rev-list --count`, and the commit feed
 * counts the same way, so the commits between two builds are exact — the rows name them, and say
 * how many were fixes. That number is the honest argument for testing: not "please help", but "four
 * of the nine commits since your build are fixes".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanaryScreen(
    onNavigateBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onInstall: (Long) -> Unit,
    onOpenReport: () -> Unit,
    viewModel: CanaryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val board by viewModel.board.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_test_canary)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    // The only route out to GitHub on the page. It used to be three — this, a
                    // button under the list and a third in the empty state — which is two more
                    // than a screen has reasons to leave itself.
                    IconButton(onClick = { onOpenUrl(GitHubRepository.CANARY_URL) }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.canary_open_actions),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !board.loaded ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                board.items.isEmpty() -> CanaryEmpty(onOpenUrl = onOpenUrl)
                else ->
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        item { Preamble(board.overview) }
                        items(
                            items = board.items,
                            key = { item: CanaryItem -> itemKey(item) },
                        ) { item ->
                            when (item) {
                                is CanaryItem.Build ->
                                    BuildCard(
                                        span = item.span,
                                        onInstall = onInstall,
                                        onOpenUrl = onOpenUrl,
                                    )
                                is CanaryItem.Installed ->
                                    InstalledMarkerRow(
                                        versionCode = item.versionCode,
                                        commitsAhead = item.commitsAhead,
                                        aheadOfMaster = item.ahead,
                                        modifier = Modifier.padding(horizontal = 20.dp),
                                    )
                            }
                        }
                        item { ReportFoot(onOpenReport = onOpenReport) }
                    }
            }
        }
    }
}

private fun itemKey(item: CanaryItem): Any =
    when (item) {
        is CanaryItem.Build -> item.span.release.tag
        is CanaryItem.Installed -> "installed"
    }

/**
 * What a canary is, and what taking one would get *this* reader.
 *
 * The second half is the part that matters, and it is the part the screen never had. "Try a canary"
 * asks for a favour; naming three issues that have been fixed since their build states a reason.
 *
 * The two halves fail independently, on purpose. The commit count is version-code arithmetic and
 * needs nothing but the release list, so it survives a cold cache; the issues come from the tracker
 * and simply do not appear when that request fails or when the running build cannot be dated. What
 * is never shown is a zero — an empty answer here means "not known", and printing it as "0 issues
 * fixed" would turn a missing request into a discouraging fact.
 */
@Composable
private fun Preamble(overview: CanaryOverview) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Science,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.canary_what_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.canary_what_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text =
                when {
                    // Past every canary and not on one: a release cut after the last nightly, which
                    // is the normal state for a day or two after every release. Ordinary news, and
                    // must not borrow the sentence written for a build of unknown provenance.
                    overview.ahead && !overview.onCanary ->
                        stringResource(R.string.canary_after_release)
                    // Past every canary while on the canary channel: built locally or from a
                    // branch. Not a position in this list at all, and worth saying rather than
                    // leaving the reader to wonder why nothing below is marked as theirs.
                    overview.ahead -> stringResource(R.string.canary_ahead)
                    // Wearing the newest canary's number without being it. Saying "you are running
                    // the newest canary" here would contradict the card below, which marks itself
                    // "same number, other build" from the same comparison.
                    overview.diverged -> stringResource(R.string.canary_diverged)
                    !overview.behind -> stringResource(R.string.canary_current)
                    else ->
                        pluralStringResource(
                            R.plurals.canary_since_commits,
                            overview.commitsAhead,
                            overview.commitsAhead,
                        )
                },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            // Caution only for the two that say the running build is not what its number claims.
            color =
                if (overview.diverged || (overview.ahead && overview.onCanary)) colors.tertiary
                else colors.primary,
        )

        // The strongest argument the page has, and the only one that is about the reader's own
        // complaints rather than the project's activity. Named rather than counted: someone who
        // filed one of these recognises it, and a count never gives them that.
        if (overview.fixed.isNotEmpty() && overview.behind) {
            Spacer(Modifier.height(12.dp))
            Text(
                text =
                    pluralStringResource(
                        R.plurals.canary_fixed_since,
                        overview.fixed.size,
                        overview.fixed.size,
                    ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            overview.fixed.take(ISSUES_SHOWN).forEach { issue ->
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    Text("#${issue.number}", style = VectorMono, color = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = issue.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // The fear that stops people is not that a nightly might break; it is that they would be
        // stuck with it. Saying otherwise costs one line and is the difference between a page that
        // asks and a page that reassures.
        Text(
            stringResource(R.string.canary_keep_body, GitHubRepository.CANARY_KEEP),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.4f))
    }
}

/**
 * One canary: when it was built, what it brought, and one tap to the page that installs it.
 *
 * The whole card is the target rather than a button on it. There is one thing to do with a build,
 * the row is already about that build, and a button would only repeat what the row means while
 * shrinking the area that means it.
 */
@Composable
private fun BuildCard(span: CanarySpan, onInstall: (Long) -> Unit, onOpenUrl: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val release = span.release

    Column(
        Modifier.fillMaxWidth()
            .clickable { onInstall(release.versionCode) }
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.canary_build, release.versionCode),
                style = VectorMono,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            // Which of these is running, by the same rule the version picker uses: the number
            // alone is not enough, because a build made from another branch wears it too.
            when {
                span.diverged ->
                    StatusChip(stringResource(R.string.update_same_number), colors.tertiary)
                span.installed ->
                    StatusChip(stringResource(R.string.update_installed), colors.primary)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.SystemUpdateAlt,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = exactTime(release.epochSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            // Who wrote it, in place of a count of how many commits went by. The same credit line
            // the rail uses, and the same recognition: a contributor's name in the accent colour,
            // on the screen the project uses to ask for testers.
            span.head?.let { head ->
                Text(
                    text = "  ·  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.outlineVariant,
                )
                Text(
                    text = credit(head),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (head.isCommunity) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (head.isCommunity) colors.primary else colors.onSurfaceVariant,
                )
            }
        }

        span.subject?.let { subject ->
            Spacer(Modifier.height(8.dp))
            // Bottom-aligned, because the pull-request slot is a fixed corner of the card and the
            // subject grows upward from it: a title that wraps to three lines still ends level
            // with its own number.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    // Wrapped, never truncated. A commit subject is a sentence written to be read,
                    // and the half of it that an ellipsis eats is usually the half that says what
                    // the change actually does.
                    text = subject,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                PullRequestSlot(number = span.head?.pullRequest, onOpenUrl = onOpenUrl)
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/**
 * Everyone credited on the commit, written the way the rail writes it.
 *
 * The same three shapes and the same two strings as `CommitRow`, so a name reads identically
 * wherever the reader meets it.
 */
@Composable
private fun credit(commit: TimelineCommit): String =
    when (commit.coAuthors.size) {
        0 -> commit.authorLogin
        1 ->
            stringResource(
                R.string.home_with_coauthor,
                commit.authorLogin,
                commit.coAuthors.first().login,
            )
        else ->
            stringResource(
                R.string.home_with_coauthors,
                commit.authorLogin,
                commit.coAuthors.size,
            )
    }

/**
 * The bottom-right corner of a card, where the build's pull request lives.
 *
 * **The space is held whether or not there is a number in it.** The subject beside it wraps into
 * whatever room is left, so a slot that appeared and disappeared would re-wrap the titles from one
 * card to the next and the column would look ragged for a reason the reader cannot see.
 *
 * Its width is measured from the widest number the tracker could plausibly reach rather than
 * written down as a dp, so it is still correct at a large font scale — where a guessed width clips
 * the digits it exists to show.
 *
 * Tapping it opens the pull request rather than the build, which is the one place on this screen
 * where a reader can read the discussion, see the review and answer it.
 */
@Composable
private fun PullRequestSlot(number: Int?, onOpenUrl: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width =
        remember(measurer, density) {
            with(density) { measurer.measure(WIDEST_PR, VectorMono).size.width.toDp() } +
                PR_CHIP_PADDING * 2 +
                PR_CHIP_BORDER * 2
        }

    Box(Modifier.width(width), contentAlignment = Alignment.CenterEnd) {
        if (number != null) {
            Text(
                text = "#$number",
                style = VectorMono,
                color = colors.primary,
                maxLines = 1,
                modifier =
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .border(
                            PR_CHIP_BORDER,
                            colors.primary.copy(alpha = 0.4f),
                            RoundedCornerShape(4.dp),
                        )
                        .clickable {
                            onOpenUrl("${GitHubRepository.REPO_URL}/pull/$number")
                        }
                        .padding(horizontal = PR_CHIP_PADDING, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier.clip(CircleShape)
                .border(1.dp, color.copy(alpha = 0.5f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * The other half of testing.
 *
 * A canary that misbehaves is only useful to the project if somebody says so, and the reader most
 * likely to hit one is on this screen. The debug-build advice sits here rather than on the build
 * page because this is where it is still actionable — by the time the variant picker is on screen
 * the reader has already decided what they are installing and why.
 */
@Composable
private fun ReportFoot(onOpenReport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.BugReport,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.canary_report_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.canary_report_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onOpenReport) {
            Text(stringResource(R.string.home_open_issue))
        }
    }
}

/**
 * Nothing published yet.
 *
 * Says what to do about it rather than only reporting the absence: before CI has pushed its first
 * prerelease this is the normal state, not a fault.
 */
@Composable
private fun CanaryEmpty(onOpenUrl: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Science,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.canary_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { onOpenUrl(GitHubRepository.CANARY_URL) }) {
            Text(stringResource(R.string.canary_open_actions))
        }
    }
}

/**
 * The number the pull-request slot is sized to hold.
 *
 * Five digits: this repository is in the eight hundreds, and a slot that has to be widened later is
 * a slot that re-wraps every subject on the screen when it is.
 */
private const val WIDEST_PR = "#99999"

private val PR_CHIP_PADDING = 6.dp
private val PR_CHIP_BORDER = 1.dp

/**
 * How many closed issues the header names before it stops.
 *
 * Three, for the same reason: enough that a reader waiting on one has a fair chance of seeing it,
 * short enough that the list still reads as evidence rather than as a changelog.
 */
private const val ISSUES_SHOWN = 3

package org.matrix.vector.manager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.github.GitHubRepository

/**
 * Where the page turns a reader into a participant.
 *
 * Four doors into the project, none of which needs an account to walk through: pull requests to
 * review, discussions to join, a canary build to test, and an issue to report. The first two open
 * GitHub in the built-in viewer; the last two open screens of their own.
 *
 * The canary door is the one that matters most for a project like this. Testing a CI build needs
 * no account, no Git and no code, so it is the lowest-friction way for an ordinary user to help —
 * and it is what actually catches regressions on the long tail of devices and ROMs before they
 * reach a release.
 */
@Composable
fun TakePartSection(
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
    onCanary: () -> Unit,
    onReport: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_contribute),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        // IntrinsicSize.Min, so the two doors in a row settle on the height of the taller one and
        // each can then fill it. Without it a card is only as tall as its own label, and a language
        // where one label wraps and its neighbour does not — "Relire une modification" beside
        // "Discussions" — leaves a short card floating in a tall row.
        //
        // Deliberately not a fixed two lines: that would pay for the worst case in every language,
        // and English, where all four fit on one line, would carry a blank line in each card. It
        // also only postpones the problem to the first label that needs three.
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Door(
                Icons.Rounded.RateReview,
                stringResource(R.string.home_review_prs),
                Modifier.weight(1f).fillMaxHeight(),
            ) {
                onOpen(GitHubRepository.PULLS_URL)
            }
            Door(
                Icons.Rounded.Forum,
                stringResource(R.string.home_discussions),
                Modifier.weight(1f).fillMaxHeight(),
            ) {
                onOpen(GitHubRepository.DISCUSSIONS_URL)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // A screen rather than a link to the Actions page, which shows an anonymous visitor
            // that a build exists and then refuses to hand it over. The screen lists the builds
            // without a sign-in and explains what signing in would buy.
            Door(
                Icons.Rounded.Science,
                stringResource(R.string.home_test_canary),
                Modifier.weight(1f).fillMaxHeight(),
                onClick = onCanary,
            )
            // Also a screen rather than a link. The maintainer's own first reply to a bug report
            // is a checklist, and a screen can do most of it instead of describing it.
            Door(
                Icons.Rounded.BugReport,
                stringResource(R.string.home_open_issue),
                Modifier.weight(1f).fillMaxHeight(),
                onClick = onReport,
            )
        }
    }
}

@Composable
private fun Door(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick = onClick, modifier = modifier) {
        // fillMaxHeight so the content is centred in whatever height the row settled on, rather
        // than sitting at the top of a card that was stretched to match its neighbour.
        Row(
            modifier = Modifier.fillMaxHeight().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

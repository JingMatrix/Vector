package org.matrix.vector.manager.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.matrix.vector.manager.R
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.github.SignInState
import org.matrix.vector.manager.ui.theme.VectorMono

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

/**
 * Optional sign-in, rendered only when it can actually do something.
 *
 * The card is hidden entirely when no OAuth client id was compiled in, and when GitHub turns out
 * to be unreachable it collapses to a single quiet line rather than an error. A large share of this
 * project's users cannot reach github.com at all, and for them the rest of Home must still be a
 * complete, working screen — so sign-in is never a gate, only an upgrade.
 */
@Composable
fun GitHubSignInCard(
    state: SignInState,
    isConfigured: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isConfigured) return
    val context = LocalContext.current

    when (state) {
        is SignInState.SignedOut ->
            OutlinedCard(modifier = modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.github_sign_in),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.github_sign_in_why),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onSignIn) {
                        Text(stringResource(R.string.github_sign_in))
                    }
                }
            }

        is SignInState.AwaitingUser ->
            Card(
                modifier = modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.github_sign_in_code, state.verificationUri),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    // The code is the whole point of this state, so it is set large and
                    // monospaced — it is meant to be read off a screen and typed on another.
                    Text(
                        text = state.userCode,
                        style = VectorMono.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onOpen(state.verificationUri) }) {
                            Text(stringResource(R.string.github_open_browser))
                        }
                        TextButton(onClick = { copyCode(context, state.userCode) }) {
                            Text(stringResource(R.string.github_copy_code))
                        }
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.github_cancel))
                        }
                    }
                }
            }

        is SignInState.SignedIn ->
            Row(
                modifier = modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.MergeType,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.github_signed_in_as, state.login ?: "GitHub"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSignOut) {
                    Text(stringResource(R.string.github_sign_out))
                }
            }

        is SignInState.Unavailable ->
            Text(
                text = stringResource(R.string.github_unreachable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.fillMaxWidth(),
            )
    }
}

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(
        // Android 13 and later show this label in the clipboard preview, so it is user-visible.
        ClipData.newPlainText(context.getString(R.string.github_device_code), code)
    )
}

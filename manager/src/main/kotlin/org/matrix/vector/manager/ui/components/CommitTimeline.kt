package org.matrix.vector.manager.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import org.matrix.vector.manager.R
import org.matrix.vector.manager.ui.theme.currentLocale
import org.matrix.vector.manager.data.github.TimelineCommit
import org.matrix.vector.manager.ui.theme.VectorMono

/**
 * One row of the commit rail.
 *
 * The rail is a real vertical line with a node on it, not a list of cards, because the thing being
 * shown is a history — continuity is the point.
 *
 * **Node fill marks authorship.** A commit written by someone other than the repository owner gets
 * a filled node and its author's name in the emphasis colour; the maintainer's own commits get a
 * hollow one. No badge and no label saying "community", which would read as a category rather than
 * a thank-you — the contribution simply stands out on the rail. This is the design's answer to
 * "encourage participation": the recognition is visible in the screen every user opens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommitRow(
    commit: TimelineCommit,
    isFirst: Boolean,
    isLast: Boolean,
    onOpenCommit: (TimelineCommit) -> Unit,
    onOpenPullRequest: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onFilterAuthor: (String) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val nodeColor = commit.railColor()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable { onOpenCommit(commit) }
    ) {
        Rail(isFirst = isFirst, isLast = isLast, nodeColor = nodeColor, filled = commit.isCommunity)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(bottom = 18.dp)) {
            // The badges flow with the title text rather than sitting in a reserved column: a
            // fixed trailing column narrows every line of the subject, and the subject is the
            // thing worth reading. They are one inline slot rather than two, so the hash and the
            // pull-request badge can never be split across a line break — a wrap between them
            // reads as though the number belongs to the next commit.
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val prLabel = commit.pullRequest?.let { "#$it" }

            val badgeSize =
                remember(commit.shortSha, prLabel, density) {
                    val sha = measurer.measure(commit.shortSha, VectorMono).size
                    val pr = prLabel?.let { measurer.measure(it, VectorMono).size }
                    // Slack over the measured text: one chip's horizontal padding and border,
                    // per chip, plus the gap between them.
                    val pad = with(density) { CHIP_PADDING.roundToPx() }
                    val chip = (pad + with(density) { CHIP_BORDER.roundToPx() }) * 2
                    val gap = with(density) { CHIP_GAP.roundToPx() }
                    val width = sha.width + chip + (pr?.let { it.width + chip + gap } ?: 0)
                    // The chips fill the placeholder's height, so the same padding again is what
                    // keeps their background off the text above and below it.
                    val height = maxOf(sha.height, pr?.height ?: 0) + pad * 2
                    width to height
                }

            val title = buildAnnotatedString {
                append(commit.subject)
                append("  ")
                appendInlineContent(BADGE_SLOT, commit.shortSha)
            }

            val inline =
                mapOf(
                    BADGE_SLOT to
                        InlineTextContent(
                            Placeholder(
                                width = with(density) { badgeSize.first.toDp().toSp() },
                                height = with(density) { badgeSize.second.toDp().toSp() },
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            )
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(
                                    modifier =
                                        Modifier.fillMaxHeight()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(colors.surfaceContainerHigh)
                                            .padding(horizontal = CHIP_PADDING),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        commit.shortSha,
                                        style = VectorMono,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                if (prLabel != null) {
                                    // Opens the discussion where participation happens, not just
                                    // a diff.
                                    Box(
                                        modifier =
                                            Modifier.fillMaxHeight()
                                                .clip(RoundedCornerShape(4.dp))
                                                .border(
                                                    CHIP_BORDER,
                                                    colors.primary.copy(alpha = 0.4f),
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .clickable {
                                                    onOpenPullRequest(commit.pullRequest)
                                                }
                                                .padding(horizontal = CHIP_PADDING),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(prLabel, style = VectorMono, color = colors.primary)
                                    }
                                }
                            }
                        }
                )

            Text(
                text = title,
                inlineContent = inline,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            // The subject and its attribution are separate thoughts, and read as one dense block
            // when they are crowded.
            Spacer(Modifier.height(7.dp))
            val credit =
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val haptics = LocalHapticFeedback.current
                Text(
                    text = credit,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight =
                        if (commit.isCommunity) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (commit.isCommunity) colors.primary else colors.onSurfaceVariant,
                    // Holding the name narrows the rail to that person's work. The gesture is put
                    // on the name itself rather than on the whole row because the row already
                    // means "open this commit", and a long press on a subject line has no obvious
                    // subject; a long press on a name plainly means *that name*.
                    modifier =
                        Modifier.combinedClickable(
                            onClick = { onOpenCommit(commit) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onFilterAuthor(commit.authorLogin)
                            },
                        )
                )
                Text(
                    text = exactTime(commit.epochSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Consecutive bot commits collapse into one expandable row.
 *
 * About one in five of the last 300 commits on this repository is a dependabot `Bump …`. Left
 * inline they bury the human work the section exists to celebrate.
 */
@Composable
fun BotBundleRow(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    children: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().clickable { onToggle() }) {
            Rail(
                isFirst = false,
                isLast = isLast && !expanded,
                nodeColor = colors.outline,
                filled = false,
                nodeSize = 8.dp,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(R.string.home_bumps, count),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(bottom = 18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) { Column { children() } }
    }
}

@Composable
private fun Rail(
    isFirst: Boolean,
    isLast: Boolean,
    nodeColor: Color,
    filled: Boolean,
    nodeSize: androidx.compose.ui.unit.Dp = 11.dp,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    // The line fills the row's whole height rather than a fixed length, so it still reaches the
    // next node when a commit's subject runs to two lines.
    Column(
        modifier = Modifier.width(22.dp).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(2.dp)
                .height(7.dp)
                .background(if (isFirst) Color.Transparent else line)
        )
        Box(
            modifier =
                Modifier.size(nodeSize)
                    .clip(CircleShape)
                    .background(if (filled) nodeColor else MaterialTheme.colorScheme.surface)
                    .border(2.dp, nodeColor, CircleShape)
        )
        if (!isLast) {
            Box(Modifier.width(2.dp).weight(1f).background(line))
        }
    }
}

/**
 * The elapsed time between two commits, drawn as rail.
 *
 * This is where the timeline stops being a list. Two commits on the same day sit almost touching;
 * a fortnight apart and the line visibly stretches, so the project's rhythm — bursts of work,
 * stretches of quiet — is legible without reading a single date. A long silence is named, because
 * empty rail on its own is ambiguous and could read as a layout gap.
 */
@Composable
fun GapRow(days: Int, heightDp: Float, showLabel: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().height(heightDp.dp)) {
        Column(
            modifier = Modifier.width(22.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.width(2.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
        if (showLabel) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = pluralStringResource(R.plurals.home_quiet_days, days, days),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/**
 * The rail encodes **authorship only**, not commit type.
 *
 * Not by type. About one subject in five begins with "Fix", so an error-coloured node would make a
 * perfectly healthy history read as a wall of alarm — and it would be redundant, because this
 * project writes plain imperative subjects and the first word of the line the reader is already on
 * *is* the type.
 *
 * [CommitKind] is still parsed and kept on the model, for filtering later.
 */
@Composable
private fun TimelineCommit.railColor(): Color =
    if (isCommunity) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline


/**
 * The line between the build the reader is running and the commits they are not.
 *
 * This is the one thing on the page that is about *them*. `versionCode` is `git rev-list --count`,
 * so a build's position in history is exact — everything above this marker is precisely what an
 * update would bring, named commit by commit rather than summarised as "a new version".
 */
@Composable
fun InstalledMarkerRow(
    versionCode: Long,
    commitsAhead: Int,
    aheadOfMaster: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // A build past the head of master is not a position on this timeline, it is a warning: it was
    // built locally or from another branch, so the history below is not the history of what is
    // running. Drawn in the caution colour rather than the accent for exactly that reason.
    val accent = if (aheadOfMaster) colors.tertiary else colors.primary
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(22.dp).height(2.dp).background(accent),
            contentAlignment = Alignment.Center,
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            text =
                if (aheadOfMaster) stringResource(R.string.home_custom_build)
                else pluralStringResource(R.plurals.home_commits_ahead, commitsAhead, commitsAhead),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.home_your_build, versionCode),
            style = VectorMono,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.45f)))
    }
}

/**
 * A month boundary, carrying what that month amounted to.
 *
 * A bare month name is only a scroll landmark. With its own totals the separator becomes the
 * timeline's summary layer — the project's shape is readable by skimming the separators alone,
 * without reading a single commit subject.
 */
@Composable
fun MonthMarkerRow(month: Int, year: Int?, commits: Int, people: Int, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val label =
        remember(month, year, locale) {
            val cal = Calendar.getInstance(locale).apply { set(Calendar.MONTH, month) }
            val name =
                cal.getDisplayName(Calendar.MONTH, Calendar.LONG_STANDALONE, locale)
                    ?: month.toString()
            if (year == null) name else "$name $year"
        }
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.width(28.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                stringResource(
                    R.string.home_month_stats,
                    pluralStringResource(R.plurals.home_commit_count, commits, commits),
                    pluralStringResource(R.plurals.home_people_count_plain, people, people),
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

private const val BADGE_SLOT = "badges"

/**
 * The chips' own metrics, which the placeholder standing in for them has to leave room for.
 *
 * The chips are laid out from these same values rather than from their own literals, so the
 * placeholder cannot drift from the thing it is sizing. Held as dp and converted at the reader's
 * density rather than written down as the pixels they came to on one screen, where the placeholder
 * ends up a little tight or a little loose everywhere else — and a tight one clips the chip.
 */
private val CHIP_PADDING = 5.dp
private val CHIP_BORDER = 1.dp
private val CHIP_GAP = 4.dp

/**
 * The foot of the rail: where the history runs out, or where it is still being fetched.
 *
 * The timeline has to end somehow, and a list that simply stops is ambiguous — it reads equally as
 * "that is everything" and as "something failed". So the rail always terminates in a statement.
 * While there is more to fetch it says so and fetches it; when the project's first commit is on
 * screen it says that instead, and the line stops in a ring rather than being cut off mid-stroke.
 *
 * It is also the trigger. Being composed means the reader has scrolled to the end of what is held,
 * which is the clearest signal available that they want more — clearer than a scroll-offset
 * threshold, and it costs no per-frame observation to detect. [onReachEnd] fires once per time this
 * row enters composition, so the walk resumes each time the reader arrives here and not while they
 * are somewhere further up.
 */
@Composable
fun HistoryFootRow(
    loading: Boolean,
    hasMore: Boolean,
    stalled: Boolean,
    beginningDate: String?,
    /** True when the window is bounded and already full: nothing more can arrive inside it. */
    windowCovered: Boolean,
    onReachEnd: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether arriving here should fetch on its own.
     *
     * False while the rail is filtered to a few people. One person's commits are a short list, so
     * the foot is on screen the moment the filter is applied — and firing there would spend three
     * requests on every experiment with the chips, out of the sixty an hour an anonymous client
     * gets. The row stays tappable, so the reader can still ask; they are simply not asked for.
     */
    autoFetch: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val tappable = hasMore && !loading

    if (hasMore && !stalled && autoFetch) {
        LaunchedEffect(Unit) { onReachEnd() }
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (tappable) Modifier.clickable(onClick = onRetry) else Modifier)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            when {
                loading ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = colors.outline,
                    )
                // An open ring, drawn the way the oldest commit's node is drawn. The history does
                // not stop here because we stopped looking; it stops because there is nothing
                // before it.
                !hasMore ->
                    Box(
                        Modifier.size(9.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, colors.outlineVariant, CircleShape)
                    )
                else -> Box(Modifier.size(5.dp).clip(CircleShape).background(colors.outlineVariant))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text =
                when {
                    loading -> stringResource(R.string.home_history_loading)
                    hasMore -> stringResource(R.string.home_history_more)
                    // The strongest statement first: this is the first commit of the project, and
                    // there is nothing before it for any window to reach.
                    beginningDate != null ->
                        stringResource(R.string.home_history_beginning, beginningDate)
                    // Weaker and more common: the window is full. Older commits exist and are
                    // held; this range simply does not include them, so fetching is not what the
                    // reader wants — a wider range is.
                    windowCovered -> stringResource(R.string.home_history_window_end)
                    else -> ""
                },
            style = MaterialTheme.typography.labelSmall,
            color = colors.outline,
        )
    }
}

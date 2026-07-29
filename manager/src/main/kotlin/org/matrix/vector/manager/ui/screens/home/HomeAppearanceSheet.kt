package org.matrix.vector.manager.ui.screens.home

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.R
import org.matrix.vector.manager.ui.components.ChoiceRow
import org.matrix.vector.manager.ui.components.SheetHeading
import org.matrix.vector.manager.ui.components.StatusNote
import org.matrix.vector.manager.ui.components.ToggleRow
import org.matrix.vector.manager.net.DohStatus
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.components.ColorWheel
import org.matrix.vector.manager.ui.components.ambience.AmbienceKind
import org.matrix.vector.manager.ui.theme.SeedScheme
import org.matrix.vector.manager.ui.theme.ThemeMode

/**
 * How this screen looks, edited from this screen.
 *
 * Vector deliberately does not gather every switch into one Settings screen. A preference is easier
 * to find, and far easier to understand, next to the thing it changes — so what governs Home lives
 * behind Home's own button, backup lives on the module list it backs up, and so on. There is no
 * catch-all Settings screen at all: a screen that collects unrelated switches is where preferences
 * go to be forgotten, and every one of them has a place it actually belongs.
 *
 * Everything here takes effect immediately behind the sheet, which is the point of a sheet rather
 * than a screen: change the surface or the theme and you can watch it happen without losing your
 * place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAppearanceSheet(onDismiss: () -> Unit) {
    val settings = ServiceLocator.settings
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle()
    val amoled by settings.amoledBlack.collectAsStateWithLifecycle()
    val seed by settings.seedColor.collectAsStateWithLifecycle()
    val ambience by settings.headerAmbience.collectAsStateWithLifecycle()
    val contributorOrder by settings.contributorOrder.collectAsStateWithLifecycle()
    val resolvedDark =
        when (ThemeMode.from(themeMode)) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val windowMonths by settings.activityWindowMonths.collectAsStateWithLifecycle()
    val openExternally by settings.openLinksExternally.collectAsStateWithLifecycle()
    val doh by settings.dohEnabled.collectAsStateWithLifecycle()
    val dohStatus by ServiceLocator.dns.status.collectAsStateWithLifecycle()

    // The default state, deliberately. `skipPartiallyExpanded` removes the half-height stop, which
    // is the only thing a drag on a sheet can *do* other than dismiss it, so a sheet taller than
    // half the screen would open at full height and could not be made smaller. Left alone, Material
    // adds the stop only when the content is actually taller than half the screen, so short sheets
    // still open at their own height and nothing gains a useless drag.
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
LocalizedOverlay {

        Column(
            // Scrollable, so the sheet is usable at the half-height stop rather than only when
            // dragged to full — and so nested scroll can hand the drag to the sheet at the top
            // of the content, which is what makes pulling it up feel like one gesture.
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
        ) {
            SheetHeading(stringResource(R.string.appearance_theme), Icons.Rounded.Palette)
            BrightnessSelector(
                selected = ThemeMode.from(themeMode),
                onSelect = { settings.setThemeMode(it.key) },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SheetHeading(stringResource(R.string.appearance_color), Icons.Rounded.Colorize)
            ColorSection(
                dynamicColor = dynamicColor,
                seed = seed,
                dark = resolvedDark,
                onDynamic = settings::setDynamicColor,
                onSeed = settings::setSeedColor,
            )
            ToggleRow(
                title = stringResource(R.string.appearance_amoled),
                icon = Icons.Rounded.DarkMode,
                subtitle = stringResource(R.string.appearance_amoled_summary),
                checked = amoled,
                onCheckedChange = settings::setAmoledBlack,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SheetHeading(stringResource(R.string.settings_ambience), Icons.Rounded.Waves)
            ChoiceRow {
                AmbienceKind.entries.forEach { kind ->
                    FilterChip(
                        selected = AmbienceKind.from(ambience) == kind,
                        onClick = { settings.setHeaderAmbience(kind.key) },
                        label = { Text(stringResource(kind.labelRes)) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SheetHeading(stringResource(R.string.settings_activity), Icons.Rounded.History)
            ChoiceRow {
                // Zero is "as far back as there is", last because it is the widest.
                listOf(1, 3, 6, 12, 0).forEach { months ->
                    FilterChip(
                        selected = windowMonths == months,
                        onClick = { settings.setActivityWindowMonths(months) },
                        label = {
                            Text(
                                if (months == 0) {
                                    stringResource(R.string.settings_window_all)
                                } else {
                                    pluralStringResource(
                                        R.plurals.settings_window_months,
                                        months,
                                        months,
                                    )
                                }
                            )
                        },
                    )
                }
            }
            ChoiceRow {
                ContributorOrder.entries.forEach { option ->
                    FilterChip(
                        selected = ContributorOrder.from(contributorOrder) == option,
                        onClick = { settings.setContributorOrder(option.key) },
                        label = { Text(stringResource(option.labelRes)) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // Here rather than under the Store's filters, where it used to sit. It was never a
            // filter on that list: VectorDns applies it to the one OkHttp client, so it governs
            // the activity feed and the framework update check as much as the module mirrors —
            // and a reader whose network breaks GitHub has no reason to look for it under Store.
            SheetHeading(stringResource(R.string.settings_network), Icons.Rounded.Dns)
            ToggleRow(
                title = stringResource(R.string.settings_doh),
                icon = Icons.Rounded.Dns,
                subtitle = stringResource(R.string.settings_doh_summary),
                checked = doh,
                onCheckedChange = settings::setDohEnabled,
            )
            // The switch says what was asked for; this says what happened. They come apart more
            // often than the switch admits — a proxy takes the decision away entirely, and one
            // unreachable lookup latches the fallback for the rest of the session — and until now
            // all three cases looked identical from here.
            //
            // Only while the switch is on. Off, the switch has already said so, and a second line
            // repeating it would be the one piece of this that carries no information.
            if (doh) {
                when (val state = dohStatus) {
                    is DohStatus.Untested ->
                        StatusNote(stringResource(R.string.settings_doh_untested))

                    is DohStatus.Bypassed ->
                        StatusNote(stringResource(R.string.settings_doh_bypassed))

                    is DohStatus.Working ->
                        StatusNote(
                            stringResource(R.string.settings_doh_working, state.host),
                            tone = MaterialTheme.colorScheme.primary,
                        )

                    // The one state with something to offer. Not an error colour: falling back is
                    // the designed behaviour and the app is working, so this is the shade the rest
                    // of the app uses for "worth knowing", not for "something is broken".
                    is DohStatus.FellBack ->
                        StatusNote(
                            stringResource(R.string.settings_doh_fell_back, state.reason),
                            tone = MaterialTheme.colorScheme.tertiary,
                            actionLabel = stringResource(R.string.settings_doh_retry),
                            onAction = ServiceLocator.dns::retry,
                        )

                    // Reachable only in the gap between flipping the switch on and the next lookup
                    // recording something newer.
                    is DohStatus.Disabled ->
                        StatusNote(stringResource(R.string.settings_doh_untested))
                }
            }

            ToggleRow(
                title = stringResource(R.string.settings_open_externally),
                icon = Icons.Rounded.OpenInBrowser,
                subtitle = stringResource(R.string.settings_open_externally_summary),
                checked = openExternally,
                onCheckedChange = settings::setOpenLinksExternally,
            )

        }
    }
}
}

/**
 * Light, dark, or whatever the system says.
 *
 * A segmented row rather than three chips because these three are exclusive and cover the whole
 * choice — a chip row says "pick any of these", a segmented row says "it is one of these", and the
 * shared outline makes the third state visibly part of the same decision rather than an extra.
 */
@Composable
private fun BrightnessSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape =
                    SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                // Icon only. A sun, a moon and an auto-brightness glyph are already unambiguous,
                // and three words beside them would push the row past the width of the screen for
                // no information — the content description still carries the name for screen
                // readers.
                icon = {},
                label = {
                    Icon(
                        mode.icon(),
                        contentDescription = stringResource(mode.labelRes()),
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
        }
    }
}

/**
 * Where the accent comes from.
 *
 * One row of sources — the wallpaper first, then a set of seeds, then the wheel — because they are
 * alternatives to each other, and a switch labelled "dynamic colour" sitting above an unrelated
 * list of swatches hides that. Choosing any swatch turns the wallpaper off; choosing the wallpaper
 * turns the swatches off. The strip underneath shows the tones the choice actually produces, which
 * is the part that ends up on real surfaces: a seed that looks lovely as a dot can still make a
 * muddy container, and this shows that before it is applied.
 */
@Composable
private fun ColorSection(
    dynamicColor: Boolean,
    seed: Int,
    dark: Boolean,
    onDynamic: (Boolean) -> Unit,
    onSeed: (Int) -> Unit,
) {
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var wheelOpen by remember { mutableStateOf(false) }
    val custom = !dynamicColor && seed !in SeedScheme.PRESETS

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (supportsDynamic) {
            SourceSwatch(
                selected = dynamicColor,
                onClick = {
                    onDynamic(true)
                    wheelOpen = false
                },
            ) {
                // The wallpaper source cannot show its own colour — it does not have one until
                // the system resolves it — so it shows what it means instead.
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = stringResource(R.string.appearance_dynamic_color),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        SeedScheme.PRESETS.forEach { preset ->
            val presetScheme = remember(preset, dark) { SeedScheme.of(preset, dark) }
            SourceSwatch(
                selected = !dynamicColor && seed == preset,
                fill = presetScheme.primary,
                onClick = {
                    onDynamic(false)
                    onSeed(preset)
                    wheelOpen = false
                },
            ) {}
        }

        SourceSwatch(
            selected = custom,
            fill = if (custom) MaterialTheme.colorScheme.primary else null,
            onClick = {
                onDynamic(false)
                wheelOpen = !wheelOpen
            },
        ) {
            if (!custom) {
                Icon(
                    Icons.Rounded.Colorize,
                    contentDescription = stringResource(R.string.appearance_custom_color),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    AnimatedVisibility(visible = wheelOpen && !dynamicColor) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (chroma, hue) =
                remember(seed) {
                    with(SeedScheme) { Color(seed).toWheel() }
                }
            ColorWheel(
                hue = hue,
                chroma = chroma,
                dark = dark,
                onChange = { h, c -> onSeed(SeedScheme.wheelColor(h, c).toArgb()) },
                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(0.72f),
            )
            Text(
                text = with(SeedScheme) { Color(seed).toHex() },
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }

    TonalPreview(dynamicColor = dynamicColor, seed = seed, dark = dark)
}

/** One choosable colour source: a filled circle that gains a ring when it is the live one. */
@Composable
private fun SourceSwatch(
    selected: Boolean,
    onClick: () -> Unit,
    fill: Color? = null,
    content: @Composable () -> Unit,
) {
    val ring by
        animateColorAsState(
            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            label = "swatch ring",
        )
    Box(
        modifier =
            Modifier.size(44.dp)
                .border(width = 2.dp, color = ring, shape = CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(fill ?: MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint =
                    if (fill != null) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The tones the current source produces, light to dark.
 *
 * Not a decoration: these are the values the scheme hands to containers, outlines and text, so a
 * choice that will not have enough contrast at either end is visible here first.
 */
@Composable
private fun TonalPreview(dynamicColor: Boolean, seed: Int, dark: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val swatches =
        if (dynamicColor) {
            // A dynamic scheme keeps its tones private, so the preview shows the roles that are
            // reachable — which is what the user sees on screen anyway.
            listOf(
                scheme.primary,
                scheme.onPrimaryContainer,
                scheme.primaryContainer,
                scheme.secondary,
                scheme.secondaryContainer,
                scheme.tertiary,
                scheme.tertiaryContainer,
                scheme.surfaceContainerHighest,
                scheme.surfaceContainer,
                scheme.surface,
            )
        } else {
            remember(seed, dark) {
                val ramp = SeedScheme.Ramp(with(SeedScheme) { Color(seed).toWheel().second }, 48f)
                SeedScheme.PREVIEW_TONES.map { ramp[it] }
            }
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        swatches.forEachIndexed { index, colour ->
            Box(
                modifier =
                    Modifier.weight(1f)
                        .height(28.dp)
                        .clip(
                            when (index) {
                                0 -> RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
                                swatches.lastIndex ->
                                    RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
                                else -> RectangleShape
                            }
                        )
                        .background(colour)
            )
        }
    }
}

private fun ThemeMode.icon() =
    when (this) {
        ThemeMode.System -> Icons.Rounded.BrightnessAuto
        ThemeMode.Light -> Icons.Rounded.LightMode
        ThemeMode.Dark -> Icons.Rounded.DarkMode
    }

private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.System -> R.string.appearance_theme_system
        ThemeMode.Light -> R.string.appearance_theme_light
        ThemeMode.Dark -> R.string.appearance_theme_dark
    }

package org.matrix.vector.ui.locale

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Translate
import org.matrix.vector.ui.ToggleRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import org.matrix.vector.ui.LocalDialogLocalizer
import org.matrix.vector.ui.R
import org.matrix.vector.ui.SharedAlertDialog

/**
 * The language, in the languages themselves — every row written in its own script, so a reader
 * looking for a language they can read can find it. Choosing changes the strings behind the sheet
 * immediately, with no restart. Shared: the choice and the list come from the host's
 * [LocaleController].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSheet(
    controller: LocaleController,
    onDismiss: () -> Unit,
    onHelpTranslate: (() -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val current by controller.appLocale.collectAsStateWithLifecycle()
    val autoTranslate by controller.autoTranslate.collectAsStateWithLifecycle()
    val translationTarget by controller.translationTargetLanguage.collectAsStateWithLifecycle()
    val locales = remember(controller) { availableLocales(controller) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var showTargetPicker by remember { mutableStateOf(false) }

    if (showTargetPicker) {
        SharedAlertDialog(
            onDismissRequest = { showTargetPicker = false },
            confirmButton = {},
            title = { Text(stringResource(R.string.store_translate_target_title)) },
            text = {
                LazyColumn {
                    item {
                        LanguageRow(
                            native = stringResource(R.string.store_translate_target_follow_app),
                            english = stringResource(R.string.language_system_summary),
                            selected = translationTarget.isBlank(),
                            onClick = {
                                controller.setTranslationTargetLanguage("")
                                showTargetPicker = false
                            },
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                    items(locales, key = { it.toLanguageTag() }) { locale ->
                        LanguageRow(
                            native = locale.nativeName(),
                            english = locale.getDisplayName(Locale.ENGLISH),
                            selected = translationTarget == locale.toLanguageTag(),
                            onClick = {
                                controller.setTranslationTargetLanguage(locale.toLanguageTag())
                                showTargetPicker = false
                            },
                        )
                    }
                }
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LocalDialogLocalizer.current {
            Column {
                // Title and the Crowdin invitation share one row, so the link is visible at any
                // sheet height instead of being buried under a long list of languages.
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.language_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    onHelpTranslate?.let { help ->
                        val invitation = stringResource(R.string.language_help)
                        AssistChip(
                            onClick = help,
                            label = { Text(stringResource(R.string.language_help_short)) },
                            trailingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            modifier = Modifier.semantics { contentDescription = invitation },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

                LazyColumn(Modifier.padding(bottom = 24.dp)) {
                    item {
                        ToggleRow(
                            title = stringResource(R.string.store_translate_modules_title),
                            subtitle = stringResource(R.string.store_translate_modules_summary),
                            icon = Icons.Rounded.AutoAwesome,
                            checked = autoTranslate,
                            onCheckedChange = controller::setAutoTranslate,
                        )
                        if (autoTranslate) {
                            val targetLabel =
                                if (translationTarget.isBlank()) {
                                    stringResource(R.string.store_translate_target_follow_app)
                                } else {
                                    locales.firstOrNull { it.toLanguageTag() == translationTarget }?.nativeName()
                                        ?: translationTarget
                                }
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .clickable { showTargetPicker = true }
                                        .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.store_translate_target_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = targetLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Icon(
                                    Icons.Rounded.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    }
                    item {
                        LanguageRow(
                            native = stringResource(R.string.language_system),
                            english = stringResource(R.string.language_system_summary),
                            selected = current.isBlank(),
                            onClick = { controller.setAppLocale("") },
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    }
                    items(locales, key = { it.toLanguageTag() }) { locale ->
                        LanguageRow(
                            native = locale.nativeName(),
                            english = locale.getDisplayName(Locale.ENGLISH),
                            selected = current == locale.toLanguageTag(),
                            onClick = { controller.setAppLocale(locale.toLanguageTag()) },
                            credits = controller.translators.forLocale(locale),
                            onOpenUrl = onOpenUrl,
                        )
                    }
                }
            }
        }
    }
}

/** One language. Selected rows lift onto the primary container and spring a marker out. */
@Composable
private fun LanguageRow(
    native: String,
    english: String,
    selected: Boolean,
    onClick: () -> Unit,
    credits: List<Translator> = emptyList(),
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val container by
        animateColorAsState(
            if (selected) colors.primaryContainer else Color.Transparent,
            label = "language container",
        )
    val markScale by
        animateFloatAsState(
            if (selected) 1f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "language mark",
        )

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(container)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = native,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = english,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (selected) colors.onPrimaryContainer.copy(alpha = 0.7f)
                    else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only for languages a person put their name to, so the common row keeps its two
            // lines. A chip rather than plain text because it is a target: tapping it opens the
            // translator's page while a tap anywhere else on the row still picks the language.
            if (credits.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    credits.forEach { person ->
                        AssistChip(
                            onClick = { person.url?.let { url -> onOpenUrl?.invoke(url) } },
                            enabled = person.url != null && onOpenUrl != null,
                            label = {
                                Text(
                                    stringResource(R.string.language_translated_by, person.name),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }
        Box(
            modifier =
                Modifier.size(26.dp)
                    .scale(markScale)
                    .clip(CircleShape)
                    .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

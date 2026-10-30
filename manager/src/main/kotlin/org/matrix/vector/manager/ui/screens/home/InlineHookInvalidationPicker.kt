package org.matrix.vector.manager.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.matrix.vector.manager.R
import org.matrix.vector.ui.AppIcon
import org.matrix.vector.ui.SearchField
import org.matrix.vector.ui.theme.Mono

/** The global selector entry placed immediately above the status-notification switch. */
@Composable
fun InlineHookInvalidationRow(
    state: InlineHookInvalidationState,
    enabled: Boolean,
    onClick: () -> Unit,
    titleRes: Int = R.string.action_invalidate_art_inline_hooks,
    summaryRes: Int = R.string.action_invalidate_art_inline_hooks_summary,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(vertical = 10.dp)
                .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(summaryRes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (state.loadFailed) {
                Text(
                    stringResource(R.string.modules_no_daemon),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.error,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.scope_search_hint),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/** A searchable, package-wide list. Every checkbox is persisted immediately through the daemon. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InlineHookInvalidationPicker(
    state: InlineHookInvalidationState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    titleRes: Int = R.string.action_invalidate_art_inline_hooks,
    summaryRes: Int = R.string.action_invalidate_art_inline_hooks_summary,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim().lowercase()
    val visibleApps =
        remember(state.apps, needle) {
            if (needle.isEmpty()) {
                state.apps
            } else {
                state.apps.filter { app ->
                    val label = app.appName.ifBlank { app.packageName }
                    label.lowercase().contains(needle) ||
                        app.packageName.lowercase().contains(needle)
                }
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.scope_search_hint),
                    clearContentDescription = stringResource(R.string.modules_clear_search),
                )
                Text(
                    "(${state.selectedPackages.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            HorizontalDivider()

            when {
                state.loading && !state.loaded -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.loadFailed -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.modules_no_daemon),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetry) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }

                visibleApps.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.scope_no_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(visibleApps, key = { it.packageName }) { app ->
                            val checked = app.packageName in state.selectedPackages
                            val saving = app.packageName in state.savingPackages
                            val label = app.appName.ifBlank { app.packageName }
                            ListItem(
                                modifier =
                                    Modifier.toggleable(
                                            value = checked,
                                            enabled = !saving,
                                            role = Role.Checkbox,
                                            onValueChange = { onToggle(app.packageName, it) },
                                        )
                                        .alpha(if (saving) 0.6f else 1f),
                                supportingContent = {
                                    Text(app.packageName, style = Mono)
                                },
                                leadingContent = {
                                    val info = app.applicationInfo
                                    if (info != null) {
                                        AppIcon(
                                            applicationInfo = info,
                                            contentDescription = null,
                                            size = 36.dp,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Rounded.Apps,
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                trailingContent = {
                                    if (saving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = null,
                                        )
                                    }
                                },
                                colors =
                                    ListItemDefaults.colors(containerColor = Color.Transparent),
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
    }
}

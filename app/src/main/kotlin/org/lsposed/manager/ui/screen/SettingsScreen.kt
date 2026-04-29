/*
 * This file is part of Vector.
 *
 * Vector is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vector is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Vector.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2026 Vector Contributors
 */

package org.lsposed.manager.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.lsposed.manager.App
import org.lsposed.manager.ConfigManager
import org.lsposed.manager.R
import org.lsposed.manager.util.BackupUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class SettingsScreen(val dummy: Int = 0) : AbstractScreen() {
    @Composable
    override fun Display(
        padding: PaddingValues,
        onNavigate: (AbstractScreen) -> Unit,
        onBack: () -> Unit
    ) {
        SettingsContent(padding)
    }

    override fun getNeedDestroyAfterBack(): Boolean = false
}

@Composable
private fun SettingsContent(
    padding: PaddingValues
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()

    val isBinderAlive = ConfigManager.isBinderAlive()

    // Verbose log setting (note: the preference is "disable" so we invert the logic)
    var verboseLogDisabled by remember {
        mutableStateOf(if (isBinderAlive) !ConfigManager.isVerboseLogEnabled() else true)
    }

    // Status notification setting
    var statusNotificationEnabled by remember {
        mutableStateOf(if (isBinderAlive) ConfigManager.enableStatusNotification() else false)
    }

    // Show hidden icon apps setting (Android 10+)
    var showHiddenIconApps by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Global.getInt(context.contentResolver, "show_hidden_icon_apps_enabled", 1) != 0
            } else {
                false
            }
        )
    }

    // Backup launcher
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    BackupUtils.backup(it)
                    // TODO: Show success message
                } catch (e: Exception) {
                    // TODO: Show error message
                }
            }
        }
    }

    // Restore launcher
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    BackupUtils.restore(it)
                    // TODO: Show success message
                } catch (e: Exception) {
                    // TODO: Show error message
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.Settings),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 12.dp,
                start = 12.dp,
                end = 12.dp
            )
        ) {
            // Backup & Restore section
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.settings_backup),
                        summary = stringResource(R.string.settings_backup_summery),
                        enabled = isBinderAlive,
                        onClick = {
                            val now = LocalDateTime.now()
                            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                            val filename = "LSPosed_${now.format(formatter)}.lsp"
                            backupLauncher.launch(filename)
                        }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_restore),
                        summary = stringResource(R.string.settings_restore_summery),
                        enabled = isBinderAlive,
                        onClick = {
                            restoreLauncher.launch(arrayOf("*/*"))
                        }
                    )
                }
            }

            // Framework settings section
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Card {
                    SwitchPreference(
                        title = stringResource(R.string.settings_disable_verbose_log),
                        summary = stringResource(R.string.settings_disable_verbose_log_summary),
                        checked = verboseLogDisabled,
                        enabled = isBinderAlive,
                        onCheckedChange = { checked ->
                            verboseLogDisabled = checked
                            ConfigManager.setVerboseLogEnabled(!checked)
                        }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.settings_enable_status_notification),
                        summary = stringResource(R.string.settings_enable_status_notification_summary),
                        checked = statusNotificationEnabled,
                        enabled = isBinderAlive,
                        onCheckedChange = { checked ->
                            statusNotificationEnabled = checked
                            ConfigManager.setEnableStatusNotification(checked)
                        }
                    )
                }
            }

            // System settings section
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card {
                        SwitchPreference(
                            title = stringResource(R.string.settings_show_hidden_icon_apps_enabled),
                            summary = stringResource(R.string.settings_show_hidden_icon_apps_enabled_summary),
                            checked = showHiddenIconApps,
                            enabled = isBinderAlive,
                            onCheckedChange = { checked ->
                                showHiddenIconApps = checked
                                ConfigManager.setHiddenIcon(!checked)
                            }
                        )
                    }
                }
            }

            // Create shortcut section (for parasitic mode)
            if (App.isParasitic) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card {
                        ArrowPreference(
                            title = stringResource(R.string.create_shortcut),
                            summary = stringResource(R.string.settings_create_shortcut_summary),
                            onClick = {
                                try {
                                    if (org.lsposed.manager.util.ShortcutUtil.isRequestPinShortcutSupported(context)) {
                                        val success = org.lsposed.manager.util.ShortcutUtil.requestPinLaunchShortcut {
                                            // Callback after shortcut is pinned
                                            android.widget.Toast.makeText(
                                                context,
                                                R.string.settings_shortcut_pinned_hint,
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        if (!success) {
                                            android.widget.Toast.makeText(
                                                context,
                                                R.string.settings_unsupported_pin_shortcut_summary,
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            R.string.settings_unsupported_pin_shortcut_summary,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        e.message ?: "Error creating shortcut",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }

            // Translation section
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.settings_translation),
                        summary = stringResource(R.string.settings_translation_summary, stringResource(R.string.app_name)),
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://crowdin.com/project/lsposed_jingmatrix"))
                            context.startActivity(intent)
                        }
                    )

                    // Translation contributors - only show if translators string is not "null"
                    val translatorsText = stringResource(R.string.translators)
                    if (translatorsText != "null") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_translation_contributors),
                            summary = translatorsText,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://lsposed.org/translators.html"))
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

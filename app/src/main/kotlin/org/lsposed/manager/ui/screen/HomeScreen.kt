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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.lsposed.manager.BuildConfig
import org.lsposed.manager.ConfigManager
import org.lsposed.manager.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Button

@Composable
fun HomeScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    var binderAlive by remember { mutableStateOf(false) }
    var needUpdate by remember { mutableStateOf(false) }
    var statusTitle by remember { mutableStateOf("") }
    var statusSummary by remember { mutableStateOf("") }
    var apiVersion by remember { mutableStateOf("") }
    var frameworkVersion by remember { mutableStateOf("") }
    var systemVersion by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var systemAbi by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        binderAlive = ConfigManager.isBinderAlive()

        if (binderAlive) {
            statusTitle = context.getString(R.string.activated)
            statusSummary = String.format(
                "%s (%d)",
                ConfigManager.getXposedVersionName(),
                ConfigManager.getXposedVersionCode()
            )

            apiVersion = ConfigManager.getXposedApiVersion().toString()
            frameworkVersion = String.format(
                "%s (%d)",
                ConfigManager.getXposedVersionName(),
                ConfigManager.getXposedVersionCode()
            )
        } else {
            statusTitle = context.getString(R.string.not_installed)
            statusSummary = context.getString(R.string.not_install_summary)
            apiVersion = context.getString(R.string.not_installed)
            frameworkVersion = context.getString(R.string.not_installed)
        }

        systemVersion = if (Build.VERSION.PREVIEW_SDK_INT != 0) {
            String.format(
                "%s Preview (API %d)",
                Build.VERSION.CODENAME,
                Build.VERSION.SDK_INT
            )
        } else {
            String.format(
                "%s (API %d)",
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT
            )
        }

        device = getDeviceInfo()
        systemAbi = Build.SUPPORTED_ABIS[0]
    }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = { showMoreMenu = true }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = "More"
                        )
                    }

                    OverlayListPopup(
                        show = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        alignment = PopupPositionProvider.Align.BottomEnd
                    ) {
                        ListPopupColumn {
                            DropdownImpl(
                                text = stringResource(R.string.feedback_or_suggestion),
                                optionSize = 2,
                                isSelected = false,
                                index = 0,
                                onSelectedIndexChange = { index ->
                                    showMoreMenu = false
                                    if (index == 0) {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/JingMatrix/LSPosed/issues/new/choose"))
                                        context.startActivity(intent)
                                    }
                                }
                            )
                            DropdownImpl(
                                text = stringResource(R.string.About),
                                optionSize = 2,
                                isSelected = false,
                                index = 1,
                                onSelectedIndexChange = { index ->
                                    showMoreMenu = false
                                    if (index == 1) {
                                        showAboutDialog = true
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding()
            )
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Status Card
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = statusTitle,
                                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = statusSummary,
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }

                    // Update Warning Card
                    if (needUpdate && binderAlive) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.need_update),
                                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.please_update_summary),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }

                    // Info Card
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            InfoItem(stringResource(R.string.info_api_version), apiVersion)
                            InfoItem(stringResource(R.string.info_framework_version), frameworkVersion)
                            InfoItem(stringResource(R.string.info_system_version), systemVersion)
                            InfoItem(stringResource(R.string.info_device), device)
                            InfoItem(stringResource(R.string.info_system_abi), systemAbi, bottomPadding = 0.dp)
                        }
                    }
                }
            }
        }
    }

    // About Dialog
    OverlayDialog(
        show = showAboutDialog,
        title = stringResource(R.string.app_name),
        summary = stringResource(
            R.string.about_view_source_code,
            "GitHub: https://github.com/JingMatrix/LSPosed",
            "Telegram: https://t.me/LSPosed"
        ) + "\n\n" + stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
        onDismissRequest = { showAboutDialog = false }
    ) {
        Button(
            onClick = { showAboutDialog = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(android.R.string.ok))
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String, bottomPadding: androidx.compose.ui.unit.Dp = 24.dp) {
    Text(
        text = label,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface
    )
    Text(
        text = value,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding)
    )
}

private fun getDeviceInfo(): String {
    var manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    if (Build.BRAND != Build.MANUFACTURER) {
        manufacturer += " " + Build.BRAND.replaceFirstChar { it.uppercase() }
    }
    manufacturer += " " + Build.MODEL
    return manufacturer
}

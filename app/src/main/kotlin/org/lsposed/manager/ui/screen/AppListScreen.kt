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

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.lspd.models.UserInfo
import org.lsposed.manager.ConfigManager
import org.lsposed.manager.R
import org.lsposed.manager.util.AppHelper
import org.lsposed.manager.util.ApplicationWithEquals
import org.lsposed.manager.util.ModuleUtil
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppListScreen(
    packageName: String,
    userId: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moduleUtil = remember { ModuleUtil.getInstance() }
    val pm = remember { context.packageManager }

    var apps by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var scopeStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var recommendedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var moduleName by remember { mutableStateOf("") }
    var showForceStopDialog by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var pendingToggle by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    // Handle back press to prevent app exit
    BackHandler(onBack = onBack)

    // 加载应用列表和作用域状态
    LaunchedEffect(packageName, userId) {
        scope.launch(Dispatchers.IO) {
            try {
                val module = moduleUtil.getModule(packageName, userId)
                if (module == null) {
                    Log.e("AppListScreen", "Module not found: $packageName for user $userId")
                    withContext(Dispatchers.Main) {
                        onBack()
                    }
                    return@launch
                }

                val name = module.appName

                // 获取推荐作用域列表
                val scopeList = module.scopeList ?: emptyList()
                val recommended = scopeList.toSet()

                // 获取所有应用
                val allApps = AppHelper.getAppList(false)
                val comparator = AppHelper.getAppListComparator(0, pm)

                // 过滤：只显示与模块相同 userId 的应用，并排除特殊应用
                val filteredApps = allApps.filter { app ->
                    val appInfo = app.applicationInfo ?: return@filter false
                    val appUserId = appInfo.uid / 100000
                    val appPackageName = app.packageName

                    // 排除：非用户0的system、模块自己、LSPosed Manager
                    if (appPackageName == "system" && appUserId != 0) return@filter false
                    if (appPackageName == packageName) return@filter false
                    if (appPackageName == "org.lsposed.manager") return@filter false

                    // 只显示与模块相同 userId 的应用
                    appUserId == userId
                }.sortedWith(comparator)

                // 获取当前作用域
                val scopeListSet = ConfigManager.getModuleScope(packageName)
                val scopes = scopeListSet.associate {
                    "${it.packageName}_${it.userId}" to true
                }

                withContext(Dispatchers.Main) {
                    moduleName = name
                    apps = filteredApps
                    scopeStates = scopes
                    recommendedApps = recommended
                    isLoading = false
                }
            } catch (e: Exception) {
                Log.e("AppListScreen", "Failed to load apps", e)
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = moduleName,
                subtitle = packageName,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        // 强制停止对话框
        if (showForceStopDialog) {
            OverlayDialog(
                show = showForceStopDialog,
                title = stringResource(R.string.force_stop_dlg_title),
                summary = stringResource(R.string.force_stop_dlg_text),
                onDismissRequest = {
                    showForceStopDialog = false
                    pendingToggle = null
                },
                content = {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            text = stringResource(android.R.string.cancel),
                            onClick = {
                                showForceStopDialog = false
                                pendingToggle = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(android.R.string.ok),
                            onClick = {
                                pendingToggle?.let { (pkgName, _) ->
                                    scope.launch(Dispatchers.IO) {
                                        ConfigManager.forceStopPackage(pkgName, userId)
                                    }
                                }
                                showForceStopDialog = false
                                pendingToggle = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            )
        }

        // 重启对话框
        if (showRebootDialog) {
            OverlayDialog(
                show = showRebootDialog,
                title = stringResource(R.string.reboot),
                summary = stringResource(R.string.reboot_required),
                onDismissRequest = { showRebootDialog = false },
                content = {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            text = stringResource(android.R.string.cancel),
                            onClick = { showRebootDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.reboot),
                            onClick = {
                                ConfigManager.reboot()
                                showRebootDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
            if (isLoading) {
                item {
                    Card(modifier = Modifier.padding(vertical = 6.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.loading),
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            } else {
                // 推荐应用列表（如果有）
                if (recommendedApps.isNotEmpty()) {
                    item {
                        Card(modifier = Modifier.padding(vertical = 6.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.requested_by_module),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    items(
                        apps.filter { recommendedApps.contains(it.packageName) },
                        key = { "${it.packageName}_recommended" }
                    ) { app ->
                        AppItem(
                            app = app,
                            pm = pm,
                            userId = userId,
                            isEnabled = scopeStates["${app.packageName}_${userId}"] ?: false,
                            isRecommended = true,
                            onToggle = { enabled ->
                                scope.launch(Dispatchers.IO) {
                                    updateScope(
                                        packageName = packageName,
                                        appPackageName = app.packageName,
                                        userId = userId,
                                        enabled = enabled,
                                        moduleUtil = moduleUtil,
                                        onSuccess = { newStates ->
                                            scopeStates = newStates
                                        },
                                        onNeedReboot = {
                                            showRebootDialog = true
                                        }
                                    )
                                }
                            },
                            onClick = {
                                val currentState = scopeStates["${app.packageName}_${userId}"] ?: false
                                scope.launch(Dispatchers.IO) {
                                    updateScope(
                                        packageName = packageName,
                                        appPackageName = app.packageName,
                                        userId = userId,
                                        enabled = !currentState,
                                        moduleUtil = moduleUtil,
                                        onSuccess = { newStates ->
                                            scopeStates = newStates
                                        },
                                        onNeedReboot = {
                                            showRebootDialog = true
                                        }
                                    )
                                }
                            },
                            onLongClick = {
                                if (app.packageName != "system") {
                                    pendingToggle = app.packageName to scopeStates["${app.packageName}_${userId}"]!!
                                    showForceStopDialog = true
                                }
                            }
                        )
                    }
                }

                // 所有应用列表
                items(
                    apps.filter { !recommendedApps.contains(it.packageName) },
                    key = { "${it.packageName}_${it.applicationInfo?.uid ?: 0}" }
                ) { app ->
                    val appInfo = app.applicationInfo
                    if (appInfo != null) {
                        AppItem(
                            app = app,
                            pm = pm,
                            userId = userId,
                            isEnabled = scopeStates["${app.packageName}_${userId}"] ?: false,
                            isRecommended = false,
                            onToggle = { enabled ->
                                scope.launch(Dispatchers.IO) {
                                    updateScope(
                                        packageName = packageName,
                                        appPackageName = app.packageName,
                                        userId = userId,
                                        enabled = enabled,
                                        moduleUtil = moduleUtil,
                                        onSuccess = { newStates ->
                                            scopeStates = newStates
                                        },
                                        onNeedReboot = {
                                            showRebootDialog = true
                                        }
                                    )
                                }
                            },
                            onClick = {
                                val currentState = scopeStates["${app.packageName}_${userId}"] ?: false
                                scope.launch(Dispatchers.IO) {
                                    updateScope(
                                        packageName = packageName,
                                        appPackageName = app.packageName,
                                        userId = userId,
                                        enabled = !currentState,
                                        moduleUtil = moduleUtil,
                                        onSuccess = { newStates ->
                                            scopeStates = newStates
                                        },
                                        onNeedReboot = {
                                            showRebootDialog = true
                                        }
                                    )
                                }
                            },
                            onLongClick = {
                                if (app.packageName != "system") {
                                    pendingToggle = app.packageName to scopeStates["${app.packageName}_${userId}"]!!
                                    showForceStopDialog = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private suspend fun updateScope(
    packageName: String,
    appPackageName: String,
    userId: Int,
    enabled: Boolean,
    moduleUtil: ModuleUtil,
    onSuccess: (Map<String, Boolean>) -> Unit,
    onNeedReboot: () -> Unit
) {
    val key = "${appPackageName}_${userId}"

    // 获取当前所有作用域
    val currentScope = ConfigManager.getModuleScope(packageName).toMutableSet()

    if (enabled) {
        // 添加到作用域
        val newApp = ApplicationWithEquals(appPackageName, userId)
        currentScope.add(newApp)
    } else {
        // 从作用域移除
        currentScope.removeIf { it.packageName == appPackageName && it.userId == userId }
    }

    // 保存作用域
    val module = moduleUtil.getModule(packageName, userId)
    val success = ConfigManager.setModuleScope(packageName, module?.legacy ?: false, currentScope)

    if (success) {
        withContext(Dispatchers.Main) {
            val newStates = ConfigManager.getModuleScope(packageName).associate {
                "${it.packageName}_${it.userId}" to true
            }
            onSuccess(newStates)

            // 只有 system 包需要提示重启
            if (appPackageName == "system") {
                onNeedReboot()
            }
            // 其他应用不自动弹窗，用户需要时可以手动重启应用
        }
    }
}

@Composable
fun AppItem(
    app: PackageInfo,
    pm: PackageManager,
    userId: Int,
    isEnabled: Boolean,
    isRecommended: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var icon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var label by remember { mutableStateOf("") }

    LaunchedEffect(app.packageName) {
        withContext(Dispatchers.IO) {
            try {
                app.applicationInfo?.let { appInfo ->
                    icon = appInfo.loadIcon(pm).toBitmap()
                }
                label = AppHelper.getAppLabel(app, pm)?.toString() ?: app.packageName
            } catch (e: Exception) {
                label = app.packageName
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
        onClick = onClick,
        onLongPress = onLongClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            icon?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 应用信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "★",
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 开关
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

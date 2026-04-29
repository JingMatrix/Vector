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

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.lspd.models.UserInfo
import org.lsposed.manager.ConfigManager
import org.lsposed.manager.R
import org.lsposed.manager.util.ModuleUtil
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ModulesScreen(
    padding: PaddingValues,
    initialSelectedUserId: Int = 0,
    onModuleClick: (String, Int, Int) -> Unit  // packageName, userId, selectedUserId
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moduleUtil = remember { ModuleUtil.getInstance() }
    val scrollBehavior = MiuixScrollBehavior()

    var users by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var selectedUserIndex by remember { mutableIntStateOf(0) }
    var enabledCount by remember { mutableIntStateOf(0) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showUserSelectDialog by remember { mutableStateOf(false) }
    var selectedModule by remember { mutableStateOf<ModuleUtil.InstalledModule?>(null) }
    var selectedUser by remember { mutableStateOf<UserInfo?>(null) }
    var availableUsers by remember { mutableStateOf<List<UserInfo>>(emptyList()) }

    // 加载用户列表
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val userList = ConfigManager.getUsers() ?: listOf()
            withContext(Dispatchers.Main) {
                users = userList
                // 根据 initialSelectedUserId 设置初始选中的用户索引
                selectedUserIndex = userList.indexOfFirst { it.id == initialSelectedUserId }.takeIf { it >= 0 } ?: 0
            }
        }
    }

    // 加载启用的模块数量
    LaunchedEffect(refreshTrigger) {
        scope.launch(Dispatchers.IO) {
            val count = moduleUtil.enabledModulesCount
            withContext(Dispatchers.Main) {
                enabledCount = count
            }
        }
    }

    val selectedUserId = if (users.isNotEmpty() && selectedUserIndex < users.size) {
        users[selectedUserIndex].id
    } else {
        0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = context.getString(R.string.Modules),
                subtitle = if (enabledCount >= 0) {
                    context.resources.getQuantityString(R.plurals.modules_enabled_count, enabledCount, enabledCount)
                } else {
                    context.getString(R.string.loading)
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding()
                )
        ) {
            // 只有多个用户时才显示标签页
            if (users.size > 1) {
                TabRow(
                    tabs = users.map { user ->
                        if (user.name.isEmpty()) "User ${user.id}" else user.name
                    },
                    selectedTabIndex = selectedUserIndex,
                    onTabSelected = { selectedUserIndex = it }
                )
            }

            // 显示当前选中用户的模块列表
            ModuleListForUser(
                userId = selectedUserId,
                users = users,
                onModuleClick = onModuleClick,
                onRefresh = { refreshTrigger++ },
                onShowInstallDialog = { module, users ->
                    selectedModule = module
                    availableUsers = users
                    if (users.size == 1) {
                        // 只有一个可用用户，直接显示安装对话框
                        selectedUser = users.first()
                        showInstallDialog = true
                    } else {
                        // 多个可用用户，显示用户选择对话框
                        showUserSelectDialog = true
                    }
                }
            )
        }

        // 安装对话框 - 必须在 Scaffold 内部
        if (showInstallDialog && selectedModule != null && selectedUser != null) {
            OverlayDialog(
                show = showInstallDialog,
                title = context.getString(R.string.install_to_user, selectedUser!!.name.ifEmpty { selectedUser!!.id.toString() }),
                summary = context.getString(R.string.install_to_user_message, selectedModule!!.appName, selectedUser!!.name.ifEmpty { selectedUser!!.id.toString() }),
                onDismissRequest = {
                    showInstallDialog = false
                },
                content = {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            text = context.getString(android.R.string.ok),
                            onClick = {
                                val module = selectedModule!!
                                val user = selectedUser!!
                                scope.launch(Dispatchers.IO) {
                                    val success = ConfigManager.installExistingPackageAsUser(module.packageName, user.id)
                                    val message = if (success) {
                                        context.getString(R.string.module_installed, module.appName, user.name.ifEmpty { user.id.toString() })
                                    } else {
                                        context.getString(R.string.module_install_failed)
                                    }
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                                        if (success) {
                                            moduleUtil.reloadSingleModule(module.packageName, user.id)
                                            refreshTrigger++
                                        }
                                    }
                                }
                                showInstallDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = context.getString(android.R.string.cancel),
                            onClick = {
                                showInstallDialog = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            )
        }

        // 用户选择对话框 - 当有多个可用用户时显示
        if (showUserSelectDialog && selectedModule != null && availableUsers.isNotEmpty()) {
            OverlayDialog(
                show = showUserSelectDialog,
                title = context.getString(R.string.add_module_to_user),
                summary = selectedModule!!.appName,
                onDismissRequest = {
                    showUserSelectDialog = false
                },
                content = {
                    Column {
                        availableUsers.forEach { user ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        selectedUser = user
                                        showUserSelectDialog = false
                                        showInstallDialog = true
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = user.name.ifEmpty { user.id.toString() },
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun ModuleListForUser(
    userId: Int,
    users: List<UserInfo>,
    onModuleClick: (String, Int, Int) -> Unit,  // packageName, userId, selectedUserId
    onRefresh: () -> Unit,
    onShowInstallDialog: (ModuleUtil.InstalledModule, List<UserInfo>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moduleUtil = remember { ModuleUtil.getInstance() }

    var modules by remember { mutableStateOf<List<ModuleUtil.InstalledModule>>(emptyList()) }
    var moduleStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }

    // 加载指定用户的模块列表
    LaunchedEffect(userId) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val allModules = moduleUtil.modules?.values?.toList() ?: emptyList()
            // 只显示当前用户的模块
            val userModules = allModules.filter { it.userId == userId }
            val states = userModules.associate { it.packageName to moduleUtil.isModuleEnabled(it.packageName) }
            val sortedModules = userModules.sortedWith(compareByDescending<ModuleUtil.InstalledModule> {
                states[it.packageName] ?: false
            }.thenBy {
                it.appName
            })

            withContext(Dispatchers.Main) {
                modules = sortedModules
                moduleStates = states
                isLoading = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            bottom = 12.dp
        )
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
                            text = context.getString(R.string.loading),
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else if (modules.isEmpty()) {
            item {
                Card(modifier = Modifier.padding(vertical = 6.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No modules found",
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else {
            items(modules, key = { "${it.packageName}_${it.userId}" }) { module ->
                ModuleItem(
                    module = module,
                    isEnabled = moduleStates[module.packageName] ?: false,
                    onToggle = { enabled ->
                        scope.launch(Dispatchers.IO) {
                            moduleUtil.setModuleEnabled(module.packageName, enabled)
                            withContext(Dispatchers.Main) {
                                moduleStates = moduleStates + (module.packageName to enabled)
                                onRefresh()
                            }
                        }
                    },
                    onClick = {
                        onModuleClick(module.packageName, module.userId, userId)
                    },
                    onLongClick = {
                        if (users.size > 1) {
                            // 找到所有未安装该模块的其他用户
                            val targetUsers = users.filter { user ->
                                user.id != 0 && moduleUtil.getModule(module.packageName, user.id) == null
                            }
                            if (targetUsers.isNotEmpty()) {
                                onShowInstallDialog(module, targetUsers)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ModuleItem(
    module: ModuleUtil.InstalledModule,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var icon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var cardWidth by remember { mutableStateOf(0) }

    LaunchedEffect(module.packageName) {
        withContext(Dispatchers.IO) {
            try {
                val drawable = module.app.loadIcon(context.packageManager)
                icon = drawable.toBitmap()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .onSizeChanged { size ->
                cardWidth = size.width
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        // 计算开关中心位置：卡片宽度 - 右边距(16dp) - 开关宽度的一半(约25dp)
                        val switchCenterX = with(density) {
                            cardWidth - 16.dp.toPx() - 25.dp.toPx()
                        }
                        // 不可点击区域的左边界：开关中心 - 50dp
                        val noClickBoundary = with(density) {
                            switchCenterX - 50.dp.toPx()
                        }

                        // 只有点击位置在不可点击区域左侧时才触发 onClick
                        if (offset.x < noClickBoundary) {
                            onClick()
                        }
                    },
                    onLongPress = {
                        onLongClick()
                    }
                )
            }
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

            // 模块信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = module.appName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )

                if (module.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = module.description,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = module.versionName,
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

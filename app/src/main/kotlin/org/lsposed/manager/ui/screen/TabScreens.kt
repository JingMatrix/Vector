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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.Serializable
import org.lsposed.manager.R
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Settings

@Serializable
data class TabScreens(
    val isBinderAlive: Boolean = true
) : AbstractScreen() {

    // 当前选中的标签页索引
    var currentTabIndex by mutableIntStateOf(1) // 默认 Home

    // 各个标签页的 Screen 实例（单例）
    val modulesScreen = ModulesScreen()
    val homeScreen = HomeScreen()
    val logsScreen = LogsScreen()
    val settingsScreen = SettingsScreen()

    @Composable
    override fun Display(
        padding: PaddingValues,
        onNavigate: (AbstractScreen) -> Unit,
        onBack: () -> Unit
    ) {
        val context = LocalContext.current

        // 处理返回键：非 Home 页面返回到 Home，Home 页面调用 onBack（退出应用）
        BackHandler(enabled = currentTabIndex != 1) {
            currentTabIndex = 1 // 返回到 Home
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTabIndex == 0,
                        onClick = { currentTabIndex = 0 },
                        icon = MiuixIcons.All,
                        label = context.getString(R.string.Modules)
                    )

                    NavigationBarItem(
                        selected = currentTabIndex == 1,
                        onClick = { currentTabIndex = 1 },
                        icon = MiuixIcons.Album,
                        label = context.getString(R.string.overview)
                    )

                    if (isBinderAlive) {
                        NavigationBarItem(
                            selected = currentTabIndex == 2,
                            onClick = { currentTabIndex = 2 },
                            icon = MiuixIcons.File,
                            label = context.getString(R.string.Logs)
                        )
                    }

                    NavigationBarItem(
                        selected = currentTabIndex == (if (isBinderAlive) 3 else 2),
                        onClick = { currentTabIndex = if (isBinderAlive) 3 else 2 },
                        icon = MiuixIcons.Settings,
                        label = context.getString(R.string.Settings)
                    )
                }
            }
        ) { innerPadding ->
            // 根据 currentTabIndex 显示对应的 Screen
            when (currentTabIndex) {
                0 -> modulesScreen.Display(innerPadding, onNavigate, onBack)
                1 -> homeScreen.Display(innerPadding, onNavigate, onBack)
                2 -> if (isBinderAlive) {
                    logsScreen.Display(innerPadding, onNavigate, onBack)
                } else {
                    settingsScreen.Display(innerPadding, onNavigate, onBack)
                }
                3 -> settingsScreen.Display(innerPadding, onNavigate, onBack)
            }
        }
    }

    override fun getNeedDestroyAfterBack(): Boolean = false
}

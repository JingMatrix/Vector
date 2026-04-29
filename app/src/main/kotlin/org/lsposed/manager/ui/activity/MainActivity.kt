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

package org.lsposed.manager.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import org.lsposed.manager.App
import org.lsposed.manager.ConfigManager
import org.lsposed.manager.R
import org.lsposed.manager.ui.screen.*
import org.lsposed.manager.ui.theme.VectorTheme
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Settings

class MainActivity : FragmentActivity() {

    private var restarting = false

    private fun handleIntent(intent: Intent): Int {
        if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            return 3
        }

        if (intent.categories?.contains("org.lsposed.manager.LAUNCH_MANAGER") == true) {
            return 1
        }

        if (ConfigManager.isBinderAlive() && !intent.dataString.isNullOrEmpty()) {
            return when (intent.dataString) {
                "modules" -> 0
                "logs" -> 2
                "settings" -> 3
                else -> 1
            }
        }

        return 1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restart()
    }

    companion object {
        @JvmStatic
        fun newIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
        }
    }

    fun restart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || App.isParasitic) {
            recreate()
        } else {
            try {
                val savedInstanceState = Bundle()
                onSaveInstanceState(savedInstanceState)
                finish()
                startActivity(newIntent(this))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                restarting = true
            } catch (e: Throwable) {
                recreate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        actionBar?.hide()

        val initialPage = handleIntent(intent)
        val isBinderAlive = ConfigManager.isBinderAlive()

        setContent {
            VectorTheme {
                MainScreen(initialPage = initialPage, isBinderAlive = isBinderAlive)
            }
        }
    }

    @Composable
    private fun MainScreen(initialPage: Int, isBinderAlive: Boolean) {
        // TabScreens 数组：保存每个标签页的单例 Screen 实例
        val tabScreens = remember {
            arrayOf(
                ModulesScreen(),
                HomeScreen(),
                if (isBinderAlive) LogsScreen() else SettingsScreen(),
                SettingsScreen()
            )
        }

        // 全局导航栈：底部永远是 Home，中间是当前选中的非 Home 标签页，顶部是二级页面
        val backStack = remember {
            // 构造栈：底部永远是 Home
            if (initialPage == 1) {
                // 初始页面是 Home，只放 Home
                mutableStateListOf<AbstractScreen>(tabScreens[1])
            } else {
                // 初始页面不是 Home，底部放 Home，上面放初始页面
                mutableStateListOf<AbstractScreen>(tabScreens[1], tabScreens[initialPage])
            }
        }

        // 当前选中的标签页索引
        var currentTabIndex by remember {
            mutableIntStateOf(initialPage)
        }

        val entryProvider = remember(backStack) {
            entryProvider<AbstractScreen> {
                entry<HomeScreen> { screen ->
                    screen.Display(
                        padding = androidx.compose.foundation.layout.PaddingValues(),
                        onNavigate = { newScreen ->
                            backStack.add(newScreen)
                        },
                        onBack = {}
                    )
                }
                entry<ModulesScreen> { screen ->
                    screen.Display(
                        padding = androidx.compose.foundation.layout.PaddingValues(),
                        onNavigate = { newScreen ->
                            backStack.add(newScreen)
                        },
                        onBack = {}
                    )
                }
                entry<LogsScreen> { screen ->
                    screen.Display(
                        padding = androidx.compose.foundation.layout.PaddingValues(),
                        onNavigate = { newScreen ->
                            backStack.add(newScreen)
                        },
                        onBack = {}
                    )
                }
                entry<SettingsScreen> { screen ->
                    screen.Display(
                        padding = androidx.compose.foundation.layout.PaddingValues(),
                        onNavigate = { newScreen ->
                            backStack.add(newScreen)
                        },
                        onBack = {}
                    )
                }
                entry<AppListScreen> { screen ->
                    screen.Display(
                        padding = androidx.compose.foundation.layout.PaddingValues(),
                        onNavigate = { newScreen ->
                            backStack.add(newScreen)
                        },
                        onBack = {
                            if (backStack.size > 1) {
                                backStack.removeLast()
                            }
                        }
                    )
                }
            }
        }

        val entries = rememberDecoratedNavEntries(
            backStack = backStack,
            entryProvider = entryProvider
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                BottomNavigationBar(
                    currentTab = currentTabIndex,
                    isBinderAlive = isBinderAlive,
                    onTabSelected = { newTabIndex ->
                        val newScreen = tabScreens[newTabIndex]

                        // 切换逻辑
                        when {
                            // 非 Home → Home：弹栈到只剩 Home
                            currentTabIndex != 1 && newTabIndex == 1 -> {
                                while (backStack.size > 1) {
                                    backStack.removeLast()
                                }
                            }
                            // Home → 非 Home：压入新的 Tab Screen
                            currentTabIndex == 1 && newTabIndex != 1 -> {
                                backStack.add(newScreen)
                            }
                            // 非 Home → 非 Home：先弹栈到 Home，再压入新的 Tab Screen
                            currentTabIndex != 1 && newTabIndex != 1 -> {
                                while (backStack.size > 1) {
                                    backStack.removeLast()
                                }
                                backStack.add(newScreen)
                            }
                        }

                        currentTabIndex = newTabIndex
                    }
                )
            }
        ) { padding ->
            NavDisplay(
                entries = entries,
                onBack = {
                    if (backStack.size > 1) {
                        val removedScreen = backStack.removeLast()
                        if (removedScreen.getNeedDestroyAfterBack()) {
                            // 需要销毁
                        }
                        val topScreen = backStack.lastOrNull()
                        val newTabIndex = tabScreens.indexOf(topScreen)
                        if (newTabIndex != -1) {
                            currentTabIndex = newTabIndex
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun BottomNavigationBar(
        currentTab: Int,
        isBinderAlive: Boolean,
        onTabSelected: (Int) -> Unit
    ) {
        NavigationBar {
            NavigationBarItem(
                selected = currentTab == 0,
                onClick = { onTabSelected(0) },
                icon = MiuixIcons.All,
                label = getString(R.string.Modules)
            )

            NavigationBarItem(
                selected = currentTab == 1,
                onClick = { onTabSelected(1) },
                icon = MiuixIcons.Album,
                label = getString(R.string.overview)
            )

            if (isBinderAlive) {
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { onTabSelected(2) },
                    icon = MiuixIcons.File,
                    label = getString(R.string.Logs)
                )
            }

            NavigationBarItem(
                selected = currentTab == (if (isBinderAlive) 3 else 2),
                onClick = { onTabSelected(if (isBinderAlive) 3 else 2) },
                icon = MiuixIcons.Settings,
                label = getString(R.string.Settings)
            )
        }
    }
}

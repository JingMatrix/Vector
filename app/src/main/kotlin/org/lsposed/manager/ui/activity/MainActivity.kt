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
import androidx.activity.compose.BackHandler
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.lsposed.manager.App
import org.lsposed.manager.ConfigManager
import org.lsposed.manager.R
import org.lsposed.manager.ui.screen.AppListScreen
import org.lsposed.manager.ui.screen.HomeScreen
import org.lsposed.manager.ui.screen.LogsScreen
import org.lsposed.manager.ui.screen.ModulesScreen
import org.lsposed.manager.ui.screen.RepoScreen
import org.lsposed.manager.ui.screen.SettingsScreen
import org.lsposed.manager.ui.theme.VectorTheme
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Settings
import kotlin.math.abs

sealed class Screen {
    data class Main(val targetPage: Int = 1, val selectedUserId: Int = 0) : Screen()
    data class AppList(val packageName: String, val userId: Int, val fromPage: Int = 1, val fromUserId: Int = 0) : Screen()
}

class MainActivity : FragmentActivity() {

    private var restarting = false

    private fun handleIntent(intent: Intent): Int {
        // Handle APPLICATION_PREFERENCES action
        if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            return 3 // Settings page
        }

        // Handle shortcut with special category (for parasitic mode shortcut)
        if (intent.categories?.contains("org.lsposed.manager.LAUNCH_MANAGER") == true) {
            return 1 // Home page for launch shortcut
        }

        // Handle shortcut data
        if (ConfigManager.isBinderAlive() && !intent.dataString.isNullOrEmpty()) {
            return when (intent.dataString) {
                "modules" -> 0  // Modules page
                "logs" -> 2     // Logs page
                "settings" -> 3 // Settings page
                else -> 1       // Default to Home
            }
        }

        return 1 // Default to Home
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Restart activity to handle new intent
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

        // Hide system ActionBar
        actionBar?.hide()

        // Handle intent to determine initial page
        val initialPage = handleIntent(intent)

        setContent {
            VectorTheme {
                val isBinderAlive = remember { ConfigManager.isBinderAlive() }
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Main(initialPage)) }

                // 页面切换动画
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        when {
                            // 进入子页面：从右滑入
                            targetState is Screen.AppList && initialState is Screen.Main -> {
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(300, easing = EaseInOut)
                                ) togetherWith slideOutHorizontally(
                                    targetOffsetX = { -it / 3 },
                                    animationSpec = tween(300, easing = EaseInOut)
                                )
                            }
                            // 返回主页面：从左滑入
                            targetState is Screen.Main && initialState is Screen.AppList -> {
                                slideInHorizontally(
                                    initialOffsetX = { -it / 3 },
                                    animationSpec = tween(300, easing = EaseInOut)
                                ) togetherWith slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(300, easing = EaseInOut)
                                )
                            }
                            else -> {
                                fadeIn() togetherWith fadeOut()
                            }
                        }
                    },
                    label = "ScreenTransition"
                ) { screen ->
                    when (screen) {
                        is Screen.Main -> {
                            MainPagerScreen(
                                isBinderAlive = isBinderAlive,
                                initialPage = screen.targetPage,
                                initialSelectedUserId = screen.selectedUserId,
                                onNavigateToAppList = { packageName, userId, fromPage, fromUserId ->
                                    currentScreen = Screen.AppList(packageName, userId, fromPage, fromUserId)
                                }
                            )
                        }
                        is Screen.AppList -> {
                            AppListScreen(
                                packageName = screen.packageName,
                                userId = screen.userId,
                                onBack = {
                                    currentScreen = Screen.Main(targetPage = screen.fromPage, selectedUserId = screen.fromUserId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MainPagerScreen(
        isBinderAlive: Boolean,
        initialPage: Int = 1,
        initialSelectedUserId: Int = 0,
        onNavigateToAppList: (String, Int, Int, Int) -> Unit
    ) {
        val scope = rememberCoroutineScope()

        // 计算页面数量：如果 LSPosed 未安装，隐藏日志页
        val pageCount = if (isBinderAlive) 4 else 3
        val pagerState = rememberPagerState(
            initialPage = initialPage,
            pageCount = { pageCount }
        )

        // 记录从哪个页面进入AppList，用于返回
        var lastMainPage by remember { mutableIntStateOf(0) }

        // 处理返回键：如果不在首页，返回首页
        BackHandler(enabled = pagerState.currentPage != 1) {
            scope.launch {
                animateToPage(pagerState, 1)
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                BottomNavigationBar(
                    pagerState = pagerState,
                    isBinderAlive = isBinderAlive
                )
            }
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 2,
                userScrollEnabled = true,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when {
                    // Modules
                    page == 0 -> {
                        LaunchedEffect(pagerState.currentPage) {
                            if (pagerState.currentPage == 0) {
                                lastMainPage = 0
                            }
                        }
                        ModulesScreen(
                            padding = padding,
                            initialSelectedUserId = initialSelectedUserId,
                            onModuleClick = { packageName, userId, selectedUserId ->
                                onNavigateToAppList(packageName, userId, 0, selectedUserId)
                            }
                        )
                    }
                    // Home
                    page == 1 -> HomeScreen(padding)
                    // Logs (只在 LSPosed 安装时显示)
                    page == 2 && isBinderAlive -> LogsScreen(padding)
                    // Settings
                    page == (if (isBinderAlive) 3 else 2) -> SettingsScreen(padding)
                }
            }
        }
    }

    @Composable
    private fun BottomNavigationBar(
        pagerState: PagerState,
        isBinderAlive: Boolean
    ) {
        val scope = rememberCoroutineScope()
        val currentPage = pagerState.currentPage

        NavigationBar {
            NavigationBarItem(
                selected = currentPage == 0,
                onClick = {
                    scope.launch {
                        animateToPage(pagerState, 0)
                    }
                },
                icon = MiuixIcons.All,
                label = getString(R.string.Modules)
            )

            NavigationBarItem(
                selected = currentPage == 1,
                onClick = {
                    scope.launch {
                        animateToPage(pagerState, 1)
                    }
                },
                icon = MiuixIcons.Album,
                label = getString(R.string.overview)
            )

            // Only show Logs tab if LSPosed is installed
            if (isBinderAlive) {
                NavigationBarItem(
                    selected = currentPage == 2,
                    onClick = {
                        scope.launch {
                            animateToPage(pagerState, 2)
                        }
                    },
                    icon = MiuixIcons.File,
                    label = getString(R.string.Logs)
                )
            }

            NavigationBarItem(
                selected = currentPage == (if (isBinderAlive) 3 else 2),
                onClick = {
                    scope.launch {
                        animateToPage(pagerState, if (isBinderAlive) 3 else 2)
                    }
                },
                icon = MiuixIcons.Settings,
                label = getString(R.string.Settings)
            )
        }
    }

    // 平滑滚动到指定页面，动画时长根据距离动态计算
    private suspend fun animateToPage(pagerState: PagerState, targetPage: Int) {
        val distance = abs(targetPage - pagerState.currentPage).coerceAtLeast(1)
        val duration = 100 * distance + 100 // 200-400ms

        pagerState.animateScrollToPage(
            page = targetPage,
            animationSpec = tween(
                durationMillis = duration,
                easing = EaseInOut
            )
        )
    }
}

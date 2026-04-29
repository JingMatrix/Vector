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
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
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
import kotlin.math.abs

@Serializable
data class TabScreens(
    val isBinderAlive: Boolean = true
) : AbstractScreen() {

    var currentTabIndex by mutableIntStateOf(1)

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
        val scope = rememberCoroutineScope()

        val pageCount = if (isBinderAlive) 4 else 3
        val pagerState = rememberPagerState(
            initialPage = currentTabIndex,
            pageCount = { pageCount }
        )

        LaunchedEffect(pagerState.currentPage) {
            currentTabIndex = pagerState.currentPage
        }

        BackHandler(enabled = pagerState.currentPage != 1) {
            scope.launch {
                val distance = abs(1 - pagerState.currentPage).coerceAtLeast(1)
                val duration = 100 * distance + 100
                pagerState.animateScrollToPage(
                    page = 1,
                    animationSpec = tween(durationMillis = duration, easing = EaseInOut)
                )
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            scope.launch {
                                val distance = abs(0 - pagerState.currentPage).coerceAtLeast(1)
                                val duration = 100 * distance + 100
                                pagerState.animateScrollToPage(
                                    page = 0,
                                    animationSpec = tween(durationMillis = duration, easing = EaseInOut)
                                )
                            }
                        },
                        icon = MiuixIcons.All,
                        label = context.getString(R.string.Modules)
                    )

                    NavigationBarItem(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            scope.launch {
                                val distance = abs(1 - pagerState.currentPage).coerceAtLeast(1)
                                val duration = 100 * distance + 100
                                pagerState.animateScrollToPage(
                                    page = 1,
                                    animationSpec = tween(durationMillis = duration, easing = EaseInOut)
                                )
                            }
                        },
                        icon = MiuixIcons.Album,
                        label = context.getString(R.string.overview)
                    )

                    if (isBinderAlive) {
                        NavigationBarItem(
                            selected = pagerState.currentPage == 2,
                            onClick = {
                                scope.launch {
                                    val distance = abs(2 - pagerState.currentPage).coerceAtLeast(1)
                                    val duration = 100 * distance + 100
                                    pagerState.animateScrollToPage(
                                        page = 2,
                                        animationSpec = tween(durationMillis = duration, easing = EaseInOut)
                                    )
                                }
                            },
                            icon = MiuixIcons.File,
                            label = context.getString(R.string.Logs)
                        )
                    }

                    NavigationBarItem(
                        selected = pagerState.currentPage == (if (isBinderAlive) 3 else 2),
                        onClick = {
                            scope.launch {
                                val targetPage = if (isBinderAlive) 3 else 2
                                val distance = abs(targetPage - pagerState.currentPage).coerceAtLeast(1)
                                val duration = 100 * distance + 100
                                pagerState.animateScrollToPage(
                                    page = targetPage,
                                    animationSpec = tween(durationMillis = duration, easing = EaseInOut)
                                )
                            }
                        },
                        icon = MiuixIcons.Settings,
                        label = context.getString(R.string.Settings)
                    )
                }
            }
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 2,
                userScrollEnabled = true,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
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
    }

    override fun getNeedDestroyAfterBack(): Boolean = false
}

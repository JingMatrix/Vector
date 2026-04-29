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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import org.lsposed.manager.App
import org.lsposed.manager.ConfigManager
import org.lsposed.manager.ui.screen.*
import org.lsposed.manager.ui.theme.VectorTheme

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
                Initialize(initialPage = initialPage, isBinderAlive = isBinderAlive)
            }
        }
    }

    @Composable
    private fun Initialize(initialPage: Int, isBinderAlive: Boolean) {
        // TabScreens：包含所有标签页的容器，作为栈底元素
        val tabScreens = remember {
            TabScreens(isBinderAlive = isBinderAlive).apply {
                currentTabIndex = initialPage
            }
        }

        // 全局导航栈：栈底是 TabScreens，上面是二级页面
        val backStack = remember {
            mutableStateListOf<AbstractScreen>(tabScreens)
        }

        val entryProvider = remember(backStack) {
            entryProvider<AbstractScreen> {
                entry<TabScreens> { screen ->
                    screen.Display(
                        padding = PaddingValues(),
                        onNavigate = { newScreen ->
                            backStack.add(newScreen)
                        },
                        onBack = {}
                    )
                }
                entry<AppListScreen> { screen ->
                    screen.Display(
                        padding = PaddingValues(),
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

        NavDisplay(
            entries = entries,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLast()
                }
            }
        )
    }
}

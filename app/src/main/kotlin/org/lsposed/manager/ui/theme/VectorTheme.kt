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


package org.lsposed.manager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@Composable
fun VectorTheme(
    darkMode: Boolean = isSystemInDarkTheme(),//MIUIX 没有动态颜色
    content: @Composable () -> Unit
) {
    val colorSchemeMode = if (darkMode) {
        ColorSchemeMode.Dark
    } else {
        ColorSchemeMode.Light
    }

    val controller = remember(colorSchemeMode) {
        ThemeController(colorSchemeMode)
    }

    MiuixTheme(
        controller = controller,
        content = content
    )
}

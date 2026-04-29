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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
abstract class AbstractScreen : NavKey {
    /**
     * 显示界面
     */
    @Composable
    abstract fun Display(
        padding: PaddingValues,
        onNavigate: (AbstractScreen) -> Unit,
        onBack: () -> Unit
    )

    /**
     * 是否需要在返回时从栈中销毁
     * TabScreen 返回 false（保留在栈中）
     * SecondaryScreen 返回 true（返回后销毁）
     */
    abstract fun getNeedDestroyAfterBack(): Boolean//global page 不需要销毁，二级页面需要销毁
}

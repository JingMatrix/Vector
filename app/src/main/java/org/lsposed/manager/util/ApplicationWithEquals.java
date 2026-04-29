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
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 * Copyright (C) 2026 Vector Contributors
 */

package org.lsposed.manager.util;

import org.lsposed.lspd.models.Application;
import java.util.Objects;

public final class ApplicationWithEquals extends Application {

    public ApplicationWithEquals(String packageName, int userId) {
        Objects.requireNonNull(packageName, "packageName");
        this.packageName = packageName;
        this.userId = userId;
    }

    public ApplicationWithEquals(Application application) {
        this(application.packageName, application.userId);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Application)) {
            return false;
        }
        Application app = (Application) other;
        return Objects.equals(this.packageName, app.packageName)
                && this.userId == app.userId;
    }

    @Override
    public int hashCode() {
        return 31 * packageName.hashCode() + userId;
    }
}
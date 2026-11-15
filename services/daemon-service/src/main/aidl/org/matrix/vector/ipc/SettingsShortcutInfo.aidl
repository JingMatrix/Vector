package org.matrix.vector.ipc;

import android.content.Intent;

/** Launch data for the shortcut owned by the Android Settings app. */
parcelable SettingsShortcutInfo {
    boolean enabled;
    @nullable Intent launchIntent;
    @nullable byte[] iconPng;
}

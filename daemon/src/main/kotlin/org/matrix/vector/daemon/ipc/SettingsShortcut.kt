package org.matrix.vector.daemon.ipc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Process
import java.io.ByteArrayOutputStream
import org.matrix.vector.daemon.R
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.ipc.SettingsShortcutInfo

internal object SettingsShortcut {
  fun isPublisher(uid: Int, process: String): Boolean =
    uid == Process.SYSTEM_UID && process == "com.android.settings"

  // Keep the icon inline. A Bitmap parcel can carry an FD that Settings cannot receive.
  private val iconPng: ByteArray by lazy {
    val icon = FileSystem.resources.getDrawable(R.drawable.ic_statue_monochrome, null)
    val size = 96
    val inset = size / 4
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    try {
      bitmap.eraseColor(Color.rgb(18, 18, 18))
      icon.setBounds(inset, inset, size - inset, size - inset)
      icon.draw(Canvas(bitmap))
      ByteArrayOutputStream().use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        out.toByteArray()
      }
    } finally {
      bitmap.recycle()
    }
  }

  fun get(): SettingsShortcutInfo {
    if (!PreferenceStore.isSettingsShortcutEnabled()) return SettingsShortcutInfo()
    val intent =
      checkNotNull(ManagerService.managerShortcutIntent()) { "Manager intent unavailable" }
    return SettingsShortcutInfo().apply {
      enabled = true
      launchIntent = intent
      iconPng = this@SettingsShortcut.iconPng
    }
  }
}

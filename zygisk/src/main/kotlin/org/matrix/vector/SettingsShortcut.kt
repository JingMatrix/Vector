package org.matrix.vector

import android.app.Activity
import android.app.ActivityThread
import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.matrix.vector.impl.core.VectorServiceClient
import org.matrix.vector.impl.hooks.VectorHookBuilder
import org.matrix.vector.util.Utils

/** Publishes in the process that owns the Settings launcher icon. */
internal object SettingsShortcut {
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val ID = "org.matrix.vector.settings.shortcut"
    private var publisher: Publisher? = null

    fun install(processName: String) {
        if (processName != SETTINGS_PACKAGE || Process.myUid() != Process.SYSTEM_UID) return
        runCatching {
            val application = ActivityThread.currentApplication()
            if (application != null) {
                start(application)
            } else {
                val bindData = Class.forName("android.app.ActivityThread\$AppBindData")
                val bind =
                    ActivityThread::class.java.getDeclaredMethod("handleBindApplication", bindData)
                VectorHookBuilder(bind).intercept { chain ->
                    chain.proceed().also {
                        runCatching { ActivityThread.currentApplication()?.let(::start) }
                            .onFailure {
                                Utils.logW("Could not observe Settings activity launches", it)
                            }
                    }
                }
            }
        }
            .onFailure { Utils.logW("Could not register the Settings shortcut publisher", it) }
    }

    private fun start(application: Application) {
        if (application.packageName != SETTINGS_PACKAGE || publisher != null) return
        val entry = Publisher(application)
        application.registerActivityLifecycleCallbacks(entry)
        publisher = entry
        entry.schedule()
    }

    private class Publisher(private val application: Application) :
        Application.ActivityLifecycleCallbacks {
        private val worker =
            ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, LinkedBlockingQueue()) {
                Thread(it, "vector-settings-shortcut")
            }
        private val queued = AtomicBoolean()

        // Accessed only on the worker.
        private var publishedIntent: Intent? = null

        fun schedule() {
            if (!queued.compareAndSet(false, true)) return
            worker.execute {
                queued.set(false)
                runCatching { updateShortcut() }
                    .onFailure {
                        Utils.logW("Could not update the Vector shortcut in Settings", it)
                    }
            }
        }

        private fun updateShortcut() {
            if (application.getSystemService(UserManager::class.java)?.isUserUnlocked != true)
                return
            val shortcutManager =
                application.getSystemService(ShortcutManager::class.java) ?: return
            val data = VectorServiceClient.settingsShortcut ?: return
            val existing = shortcutManager.dynamicShortcuts.any { it.id == ID && it.isEnabled }
            if (!data.enabled) {
                val pinned = shortcutManager.pinnedShortcuts.any { it.id == ID && it.isEnabled }
                if (existing || pinned) shortcutManager.disableShortcuts(listOf(ID))
                publishedIntent = null
                return
            }

            val intent = checkNotNull(data.launchIntent) { "Manager intent unavailable" }
            if (existing && intent.filterEquals(publishedIntent)) return
            val png = checkNotNull(data.iconPng) { "Manager icon unavailable" }
            check(png.size in 1..(64 * 1024)) { "Invalid Settings shortcut icon size" }
            val bitmap =
                BitmapFactory.decodeByteArray(png, 0, png.size)
                    ?: error("Invalid Settings shortcut icon")
            val shortcut =
                ShortcutInfo.Builder(application, ID)
                    .setShortLabel("Vector")
                    .setIcon(Icon.createWithAdaptiveBitmap(bitmap))
                    .setIntent(intent)
                    .build()
            // Merge this ID into Settings' shortcuts without replacing any other publisher's ID.
            if (shortcutManager.addDynamicShortcuts(listOf(shortcut))) {
                publishedIntent = intent
            }
        }

        override fun onActivityResumed(activity: Activity) = schedule()

        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

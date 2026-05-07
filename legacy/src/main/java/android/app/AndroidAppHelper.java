package android.app;

import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findFieldIfExists;
import static de.robv.android.xposed.XposedHelpers.findMethodExactIfExists;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.newInstance;
import static de.robv.android.xposed.XposedHelpers.setFloatField;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.Display;

import java.lang.ref.WeakReference;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * Contains various methods for information about the current app.
 *
 * <p>For historical reasons, this class is in the {@code android.app} package. It can't be moved
 * without breaking compatibility with existing modules.
 */
public final class AndroidAppHelper {
	private AndroidAppHelper() {}

	private static final Class<?> CLASS_RESOURCES_KEY;
	private static final boolean HAS_IS_THEMEABLE;
	private static final boolean HAS_THEME_CONFIG_PARAMETER;

	static {
		CLASS_RESOURCES_KEY = findClass("android.content.res.ResourcesKey", null);

		HAS_IS_THEMEABLE = findFieldIfExists(CLASS_RESOURCES_KEY, "mIsThemeable") != null;
		HAS_THEME_CONFIG_PARAMETER = HAS_IS_THEMEABLE && findMethodExactIfExists("android.app.ResourcesManager", null, "getThemeConfig") != null;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Map<Object, WeakReference> getResourcesMap(ActivityThread activityThread) {
		Object resourcesManager = getObjectField(activityThread, "mResourcesManager");
		return (Map) getObjectField(resourcesManager, "mResourceImpls");
	}

	/* For SDK 24+ */
	private static Object createResourcesKey(String resDir, String[] splitResDirs, String[] overlayDirs, String[] libDirs, int displayId, Configuration overrideConfiguration, CompatibilityInfo compatInfo) {
		try {
			return newInstance(CLASS_RESOURCES_KEY, resDir, splitResDirs, overlayDirs, libDirs, displayId, overrideConfiguration, compatInfo);
		} catch (Throwable t) {
			XposedBridge.log(t);
			return null;
		}
	}

	/** @hide */
	public static void addActiveResource(String resDir, float scale, boolean isThemeable, Resources resources) {
		addActiveResource(resDir, resources);
	}

	/** @hide */
	public static void addActiveResource(String resDir, Resources resources) {
		ActivityThread thread = ActivityThread.currentActivityThread();
		if (thread == null) {
			return;
		}

		Object resourcesKey;
		CompatibilityInfo compatInfo = (CompatibilityInfo) newInstance(CompatibilityInfo.class);
		setFloatField(compatInfo, "applicationScale", resources.hashCode());
		resourcesKey = createResourcesKey(resDir, null, null, null, Display.DEFAULT_DISPLAY, null, compatInfo);

		if (resourcesKey != null) {
			Object resImpl = getObjectField(resources, "mResourcesImpl");
			getResourcesMap(thread).put(resourcesKey, new WeakReference<>(resImpl));
		}
	}

	/**
	 * Returns the name of the current process. It's usually the same as the main package name.
	 */
	public static String currentProcessName() {
		String processName = ActivityThread.currentPackageName();
		if (processName == null)
			return "android";
		return processName;
	}

	/**
	 * Returns information about the main application in the current process.
	 *
	 * <p>In a few cases, multiple apps might run in the same process, e.g. the SystemUI and the
	 * Keyguard which both have {@code android:process="com.android.systemui"} set in their
	 * manifest. In those cases, the first application that was initialized will be returned.
	 */
	public static ApplicationInfo currentApplicationInfo() {
		ActivityThread am = ActivityThread.currentActivityThread();
		if (am == null)
			return null;

		Object boundApplication = getObjectField(am, "mBoundApplication");
		if (boundApplication == null)
			return null;

		return (ApplicationInfo) getObjectField(boundApplication, "appInfo");
	}

	/**
	 * Returns the Android package name of the main application in the current process.
	 *
	 * <p>In a few cases, multiple apps might run in the same process, e.g. the SystemUI and the
	 * Keyguard which both have {@code android:process="com.android.systemui"} set in their
	 * manifest. In those cases, the first application that was initialized will be returned.
	 */
	public static String currentPackageName() {
		ApplicationInfo ai = currentApplicationInfo();
		return (ai != null) ? ai.packageName : "android";
	}

	/**
	 * Returns the main {@link android.app.Application} object in the current process.
	 *
	 * <p>In a few cases, multiple apps might run in the same process, e.g. the SystemUI and the
	 * Keyguard which both have {@code android:process="com.android.systemui"} set in their
	 * manifest. In those cases, the first application that was initialized will be returned.
	 */
	public static Application currentApplication() {
		return ActivityThread.currentApplication();
	}

	/** @deprecated Use {@link XSharedPreferences} instead. */
	@SuppressWarnings("UnusedParameters")
	@Deprecated
	public static SharedPreferences getSharedPreferencesForPackage(String packageName, String prefFileName, int mode) {
		return new XSharedPreferences(packageName, prefFileName);
	}

	/** @deprecated Use {@link XSharedPreferences} instead. */
	@Deprecated
	public static SharedPreferences getDefaultSharedPreferencesForPackage(String packageName) {
		return new XSharedPreferences(packageName);
	}

	/** @deprecated Use {@link XSharedPreferences#reload} instead. */
	@Deprecated
	public static void reloadSharedPreferencesIfNeeded(SharedPreferences pref) {
		if (pref instanceof XSharedPreferences) {
			((XSharedPreferences) pref).reload();
		}
	}
}

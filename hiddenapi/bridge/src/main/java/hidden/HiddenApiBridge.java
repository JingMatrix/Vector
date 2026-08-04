package hidden;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Resources;
import android.content.res.ResourcesImpl;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Int32Ref;
import android.system.Os;
import android.util.MutableInt;

import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;

public class HiddenApiBridge {
    public static int AssetManager_addAssetPath(AssetManager am, String path) {
        return am.addAssetPath(path);
    }

    public static IBinder Binder_allowBlocking(IBinder binder) {
        return Binder.allowBlocking(binder);
    }

    public static void Resources_setImpl(Resources resources, ResourcesImpl impl) {
        resources.setImpl(impl);
    }

    public static IBinder Context_getActivityToken(Context ctx) {
        return ctx.getActivityToken();
    }

    public static UserHandle UserHandle(int h) {
        return new UserHandle(h);
    }

    public static String ApplicationInfo_credentialProtectedDataDir(ApplicationInfo applicationInfo) {
        return applicationInfo.credentialProtectedDataDir;
    }

    public static void ApplicationInfo_credentialProtectedDataDir(ApplicationInfo applicationInfo, String dir) {
        applicationInfo.credentialProtectedDataDir = dir;
    }

    public static String[] ApplicationInfo_resourceDirs(ApplicationInfo applicationInfo) {
        return applicationInfo.resourceDirs;
    }

    public static void ApplicationInfo_resourceDirs(ApplicationInfo applicationInfo, String[] resourceDirs) {
        applicationInfo.resourceDirs = resourceDirs;
    }

    @RequiresApi(31)
    public static String[] ApplicationInfo_overlayPaths(ApplicationInfo applicationInfo) {
        return applicationInfo.overlayPaths;
    }

    @RequiresApi(31)
    public static void ApplicationInfo_overlayPaths(ApplicationInfo applicationInfo, String[] overlayPaths) {
        applicationInfo.overlayPaths = overlayPaths;
    }

    public static CompatibilityInfo Resources_getCompatibilityInfo(Resources res) {
        return res.getCompatibilityInfo();
    }

    public static int Os_ioctlInt(FileDescriptor fd, int cmd, int arg) throws ErrnoException {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1) {
            return Os.ioctlInt(fd, cmd, new MutableInt(arg));
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Os.ioctlInt(fd, cmd, new Int32Ref(arg));
        } else {
            return Os.ioctlInt(fd, cmd);
        }
    }

    public static int ActivityManager_UID_OBSERVER_GONE() {
        return ActivityManager.UID_OBSERVER_GONE;
    }

    public static int ActivityManager_UID_OBSERVER_ACTIVE() {
        return ActivityManager.UID_OBSERVER_ACTIVE;
    }

    public static int ActivityManager_UID_OBSERVER_IDLE() {
        return ActivityManager.UID_OBSERVER_IDLE;
    }

    public static int ActivityManager_UID_OBSERVER_CACHED() {
        return ActivityManager.UID_OBSERVER_CACHED;
    }

    public static int ActivityManager_PROCESS_STATE_UNKNOWN() {
        return ActivityManager.PROCESS_STATE_UNKNOWN;
    }
}

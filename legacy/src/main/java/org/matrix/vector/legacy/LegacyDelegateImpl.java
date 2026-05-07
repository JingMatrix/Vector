package org.matrix.vector.legacy;

import android.content.res.XResources;

import org.lsposed.lspd.util.Utils;
import org.matrix.vector.impl.core.VectorServiceClient;
import org.matrix.vector.impl.di.LegacyFrameworkDelegate;
import org.matrix.vector.impl.di.LegacyPackageInfo;
import org.matrix.vector.impl.di.OriginalInvoker;
import org.matrix.vector.impl.hooks.VectorLegacyCallback;
import org.matrix.vector.impl.utils.VectorMetaDataReader;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Implementation of the explicit dependency injection contract.
 * Translates modern lifecycle events and hooks into legacy Xposed API operations.
 */
public class LegacyDelegateImpl implements LegacyFrameworkDelegate {

    @Override
    public void loadModules(Object activityThread) {
        XposedInit.loadModules((android.app.ActivityThread) activityThread);
    }

    @Override
    public void onPackageLoaded(LegacyPackageInfo info) {
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
        lpparam.packageName = info.getPackageName();
        lpparam.processName = info.getProcessName();
        lpparam.classLoader = info.getClassLoader();
        lpparam.appInfo = info.getAppInfo();
        lpparam.isFirstApplication = info.isFirstApplication();

        if (info.isFirstApplication() && hasLegacyModule(info.getPackageName())) {
            hookNewXSP(lpparam);
        }

        XC_LoadPackage.callAll(lpparam);
    }

    @Override
    public void onSystemServerLoaded(ClassLoader classLoader) {
        XposedInit.loadedPackagesInProcess.add("android");
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
        lpparam.packageName = "android";
        // For comptibility, we set the process name of `system_server` as `android`.
        // https://github.com/rovo89/XposedBridge/blob/art/app/src/main/java/de/robv/android/xposed/XposedInit.java
        lpparam.processName = "android";
        lpparam.classLoader = classLoader;
        lpparam.isFirstApplication = true;
        XC_LoadPackage.callAll(lpparam);
    }

    @Override
    public Object processLegacyHook(Executable executable, Object thisObject, Object[] args, Object[] legacyHooks, OriginalInvoker invokeOriginal) {
        VectorLegacyCallback<Executable> callback = new VectorLegacyCallback<>(executable, thisObject, args);
        XposedBridge.LegacyApiSupport<Executable> legacy = new XposedBridge.LegacyApiSupport<>(callback, legacyHooks);

        legacy.handleBefore();

        if (!callback.isSkipped()) {
            try {
                Object result = invokeOriginal.invoke();
                callback.setResult(result);
            } catch (Throwable t) {
                callback.setThrowable(t);
            }
        }

        legacy.handleAfter();

        if (callback.getThrowable() != null) {
            sneakyThrow(callback.getThrowable());
        }
        return callback.getResult();
    }

    @Override
    public boolean isResourceHookingDisabled() {
        return XposedInit.disableResources;
    }

    @Override
    public boolean hasLegacyModule(String packageName) {
        return XposedInit.getLoadedModules().containsKey(packageName);
    }

    @Override
    public void setPackageNameForResDir(String packageName, String resDir) {
        // Call a separate static inner class to prevent the verifier
        // from looking at XResources when LegacyDelegateImpl is loaded.
        ResourceProxy.set(packageName, resDir);
    }

    // This class is only verified the FIRST time 'set' is called.
    private static class ResourceProxy {
        static void set(String p, String r) {
            XResources.setPackageNameForResDir(p, r);
        }
    }

    private void hookNewXSP(XC_LoadPackage.LoadPackageParam lpparam) {
        int xposedminversion = -1;
        boolean xposedsharedprefs = false;
        try {
            Map<String, Object> metaData = VectorMetaDataReader.getMetaData(new File(lpparam.appInfo.sourceDir));
            Object minVersionRaw = metaData.get("xposedminversion");
            if (minVersionRaw instanceof Integer) {
                xposedminversion = (Integer) minVersionRaw;
            } else if (minVersionRaw instanceof String) {
                xposedminversion = VectorMetaDataReader.extractIntPart((String) minVersionRaw);
            }
            xposedsharedprefs = metaData.containsKey("xposedsharedprefs");
        } catch (NumberFormatException | IOException ignored) {
        }

        if (xposedminversion > 92 || xposedsharedprefs) {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "checkMode", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (((int) param.args[0] & 1) != 0) {
                        param.setThrowable(null);
                    }
                }
            });
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "getPreferencesDir", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return new File(VectorServiceClient.INSTANCE.getPrefsPath(lpparam.packageName));
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}

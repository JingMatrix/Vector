import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.*;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import java.io.Serializable;
import java.lang.reflect.*;
import java.util.*;
import org.matrix.vector.daemon.data.ConfigCache;
import org.matrix.vector.daemon.data.ModuleDatabase;
import org.matrix.vector.daemon.ipc.FrameworkService;
import org.matrix.vector.daemon.ipc.SystemServerService;
import org.matrix.vector.impl.VectorContext;
import org.matrix.vector.impl.VectorRemotePreferences;
import org.matrix.vector.impl.core.VectorModuleManager;
import org.matrix.vector.impl.core.VectorServiceClient;
import org.matrix.vector.ipc.*;
import org.matrix.vector.service.BridgeService;

public class ReattachTest {
    private static int passed;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message, android.util.Log.lastFailure);
        passed++;
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) || method.getName().startsWith(name + "$")) {
                method.setAccessible(true);
                try { return method.invoke(target, args); }
                catch (InvocationTargetException e) {
                    if (e.getCause() instanceof Exception ex) throw ex;
                    throw e;
                }
            }
        }
        throw new AssertionError("Missing production method " + name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    private static Bundle bundle(String key, Map<String, Object> values) {
        Bundle bundle = new Bundle();
        bundle.putSerializable(key, new HashMap<>(values));
        return bundle;
    }

    private static class ModuleService extends IModuleService.Stub {
        Map<String, Object> values = new HashMap<>();
        IRemotePreferenceCallback callback;
        int requests;
        boolean fail;
        Bundle updateInFlight;
        public long getFrameworkProperties() { return 0; }
        public String[] getRemoteFileNames() { return new String[]{"current"}; }
        public ParcelFileDescriptor openRemoteFile(String path) { return null; }
        public Bundle requestRemotePreferences(String group, IRemotePreferenceCallback next)
                throws RemoteException {
            requests++;
            if (fail) throw new RemoteException("temporary failure");
            callback = next;
            Bundle snapshot = bundle("map", values);
            if (updateInFlight != null) next.onRemotePreferencesChanged(updateInFlight);
            return snapshot;
        }
    }

    private static class Framework extends IFrameworkService.Stub {
        int channels;
        boolean rejectChannel;
        public void attachProcessChannel(IProcessChannel channel) throws RemoteException {
            if (rejectChannel) throw new RemoteException("retry channel");
            channels++;
        }
        public List<LoadedModule> getModules() { throw new AssertionError("must not reload modules"); }
        public List<LoadedModule> getLegacyModules() { throw new AssertionError("must not reload legacy modules"); }
        public boolean isLogMuted() { return false; }
        public String getPrefsPath(String module) { return null; }
        public ParcelFileDescriptor openManagerApk() { return null; }
        public IBinder requestManagerService() { return null; }
    }

    private static class Daemon extends IVectorDaemon.Stub {
        final Framework framework = new Framework();
        final ModuleService module = new ModuleService();
        int attaches, contexts, modules;
        long reportedVersion = -1;
        public IFrameworkService attachProcess(int uid, int pid, String name, IBinder life) {
            check(Binder.getCallingUid() == 1000 && Binder.getCallingPid() == 123,
                  "nested calls must use system_server identity");
            check(uid == 1000 && pid == 123 && name.equals("system") && life != null,
                  "reattach identifies the same live process");
            attaches++;
            return framework;
        }
        public void dispatchSystemServerContext(IBinder thread, IBinder token) { contexts++; }
        public boolean preStartManager() { return true; }
        public IModuleService reconnectSystemModule(String name, long version, boolean reloadable) {
            check(name.equals("test.module"), "only loaded system modules reconnect");
            check(reloadable, "actual generation capability retained");
            modules++;
            reportedVersion = version;
            return module;
        }
    }

    private static boolean send(Daemon daemon, int uid) {
        Binder.callerUid = uid;
        Binder.callerPid = 999;
        Parcel data = Parcel.obtain();
        data.writeInt(1);
        data.writeStrongBinder(daemon);
        boolean result = BridgeService.onTransact(data, Parcel.obtain(), 0);
        check(Binder.callerUid == uid && Binder.callerPid == 999, "calling identity restored");
        return result;
    }

    private static void registry() throws Exception {
        FrameworkService registry = FrameworkService.INSTANCE;
        SystemServerService system = SystemServerService.INSTANCE;
        Binder.callerUid = 1000; Binder.callerPid = 123;
        Binder life = new Binder();
        check(!system.getSystemServerRequested(), "status starts disconnected");
        check(invoke(system, "attachProcess", 1001, 123, "system", life) == null, "wrong UID denied");
        check(invoke(system, "attachProcess", 1000, 124, "system", life) == null, "wrong PID denied");
        check(invoke(system, "attachProcess", 1000, 123, "other", life) == null, "wrong process denied");
        Binder broken = new Binder(); broken.rejectLink = true;
        check(invoke(system, "attachProcess", 1000, 123, "system", broken) == null,
              "failed heartbeat denied");
        check(!system.getSystemServerRequested(), "failed registration does not latch injected");
        check(invoke(system, "attachProcess", 1000, 123, "system", life) == registry,
              "initial native handshake accepted");
        check(system.getSystemServerRequested(), "live registration drives status");

        ModuleService moduleService = new ModuleService();
        LoadedModule module = new LoadedModule();
        module.packageName = "test.module"; module.versionCode = 22;
        module.service = moduleService; module.code = new ModuleCode();
        module.code.legacy = false;
        ConfigCache.INSTANCE.modules.put(module.packageName, module);
        ModuleDatabase.INSTANCE.scopes.add(module.packageName);
        check(registry.reconnectSystemModule(module.packageName, 11, true) == moduleService,
              "cache-owned module service reused");
        var target = registry.getHotReloadTargets(module.packageName, 0).get(0);
        check(target.loadedVersionCode == 11, "in-memory version retained instead of installed version");
        check(target.state == io.github.libxposed.service.HookedProcess.TARGET_STATE_STALE,
              "older generation reported stale");
        IProcessChannel channel = new IProcessChannel.Default();
        registry.attachProcessChannel(channel);
        check(invoke(system, "attachProcess", 1000, 123, "system", new Binder()) == registry,
              "duplicate handshake accepted");
        var actualTarget = registry.getHotReloadTarget(target.targetId, module.packageName, 0);
        check(registry.getHotReloadBinder(actualTarget) == channel, "duplicate preserves process channel");
        registry.reconnectSystemModule(module.packageName, 22, false);
        check(registry.getHotReloadTargets(module.packageName, 0).get(0).loadedVersionCode == 11,
              "retry does not replace registered generation");

        List<IBinder.DeathRecipient> oldDeaths = List.copyOf(life.deaths);
        life.alive = false;
        check(!system.getSystemServerRequested(), "death visible before queued callback");
        Binder replacement = new Binder();
        check(registry.registerHeartBeat(1000, 123, "system", replacement), "dead PID record may be replaced");
        oldDeaths.forEach(IBinder.DeathRecipient::binderDied);
        check(system.getSystemServerRequested(), "late old death preserves replacement");
        check(registry.getHotReloadTargets(module.packageName, 0).isEmpty(), "dead targets removed");
        registry.reconnectSystemModule(module.packageName, 0, true);
        registry.backfillLoadedVersions();
        check(registry.getHotReloadTargets(module.packageName, 0).get(0).loadedVersionCode == 0,
              "unknown restored version not invented from installed APK");
        ModuleDatabase.INSTANCE.scopes.clear();
        check(registry.reconnectSystemModule(module.packageName, 11, true) == null, "revoked scope denied");
        replacement.die();
        check(!system.getSystemServerRequested(), "process death clears injected status");
        check(registry.getHotReloadTargets(module.packageName, 0).isEmpty(), "process death clears targets");
    }

    private static void preferences() throws Exception {
        ModuleService first = new ModuleService();
        first.values.put("removed", 1); first.values.put("changed", 1);
        VectorRemotePreferences prefs = new VectorRemotePreferences(first, "settings");
        IRemotePreferenceCallback old = first.callback;
        Set<String> changed = new HashSet<>();
        prefs.registerOnSharedPreferenceChangeListener((p, key) -> changed.add(key));
        ModuleService second = new ModuleService();
        second.values.put("changed", 2);
        second.updateInFlight = bundle("put", Map.of("racing", 3));
        invoke(prefs, "reconnect", second);
        check(!prefs.contains("removed") && prefs.getInt("changed", 0) == 2,
              "reconnect replaces old preference snapshot including deletions");
        check(prefs.getInt("racing", 0) == 3, "in-flight callback applied after snapshot");
        check(changed.equals(Set.of("removed", "changed", "racing")), "listener receives snapshot differences");
        old.onRemotePreferencesChanged(bundle("put", Map.of("changed", -1)));
        check(prefs.getInt("changed", 0) == 2, "old subscription cannot overwrite new values");
        invoke(prefs, "reconnect", second);
        check(second.requests == 1, "successful subscription not duplicated on retry");
        ModuleService failed = new ModuleService(); failed.fail = true;
        try { invoke(prefs, "reconnect", failed); throw new AssertionError("expected IPC error"); }
        catch (RemoteException expected) {}
        second.callback.onRemotePreferencesChanged(bundle("put", Map.of("changed", 4)));
        check(prefs.getInt("changed", 0) == 4, "failed rebind retains previous subscription");
        prefs.registerOnSharedPreferenceChangeListener((p, key) -> { throw new RuntimeException("module listener"); });
        ModuleService third = new ModuleService(); third.values.put("changed", 5);
        invoke(prefs, "reconnect", third);
        invoke(prefs, "reconnect", third);
        check(third.requests == 1 && prefs.getInt("changed", 0) == 5,
              "listener failure cannot undo successful binding");
    }

    private static void bridge() throws Exception {
        Daemon first = new Daemon();
        first.module.values.put("value", 1);
        VectorServiceClient.INSTANCE.init(first.framework, "system");
        VectorContext context = new VectorContext("test.module", new ApplicationInfo(), first.module,
                                                   ExceptionMode.PROTECTIVE);
        SharedPreferences prefs = context.getRemotePreferences("settings");
        Class<?> generationType = Class.forName("org.matrix.vector.impl.core.VectorModuleManager$Generation");
        Constructor<?> constructor = generationType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object generation = constructor.newInstance(ReattachTest.class.getClassLoader(), context,
                                                     List.of(), true, "system", 11L, true);
        Map<String, Object> generations = field(VectorModuleManager.class, "generations");
        generations.put("test.module", generation);
        Object appGeneration = constructor.newInstance(ReattachTest.class.getClassLoader(), context,
                                                        List.of(), false, "test.app", 11L, true);
        generations.put("test.app.module", appGeneration);
        check(!send(first, 10001) && first.attaches == 0, "application cannot push a daemon binder");
        check(send(first, 0), "initial bridge delivery works");
        check(first.modules == 0 && first.framework.channels == 1, "initial delivery does not replay bootstrap");
        List<IBinder.DeathRecipient> oldBridgeDeaths = List.copyOf(first.deaths);
        List<IBinder.DeathRecipient> oldFrameworkDeaths = List.copyOf(first.framework.deaths);
        first.alive = false; first.framework.alive = false;
        Daemon second = new Daemon(); second.module.values.put("value", 2);
        check(send(second, 0), "new daemon reattaches to existing process");
        check(second.reportedVersion == 11 && second.framework.channels == 1,
              "actual loaded version and process channel restored");
        check(context.getRemotePreferences("settings") == prefs && prefs.getInt("value", 0) == 2,
              "existing module context and preference object survive");
        check(generations.get("test.module") == generation && generations.get("test.app.module") == appGeneration,
              "module generations and class loaders unchanged");
        oldBridgeDeaths.forEach(IBinder.DeathRecipient::binderDied);
        oldFrameworkDeaths.forEach(IBinder.DeathRecipient::binderDied);
        check(BridgeService.getService() == second && VectorServiceClient.INSTANCE.asBinder() == second.framework,
              "delayed old daemon death cannot clear new connections");
        int attaches = second.attaches;
        check(send(second, 0) && second.attaches == attaches && second.module.requests == 1,
              "duplicate delivery has no subscription or channel side effects");

        Daemon third = new Daemon(); third.module.fail = true;
        check(!send(third, 0), "partial restoration does not report successful bridge");
        third.module.fail = false; third.module.values.put("value", 3);
        third.framework.rejectChannel = true;
        check(!send(third, 0), "failed process channel is retried");
        int requests = third.module.requests;
        third.framework.rejectChannel = false;
        check(send(third, 0), "next delivery completes partial restore");
        check(third.module.requests == requests && prefs.getInt("value", 0) == 3,
              "channel retry keeps completed preference subscription");
        third.die(); third.framework.die();
        check(BridgeService.getService() == null && VectorServiceClient.INSTANCE.asBinder() == null,
              "current daemon death disconnects both clients");
    }

    public static void main(String[] args) throws Exception {
        Build.VERSION.SDK_INT = Integer.parseInt(args[0]);
        registry(); preferences(); bridge();
        System.out.println("PASS API branch " + Build.VERSION.SDK_INT + ": " + passed + " lifecycle assertions");
    }
}

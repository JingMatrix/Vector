import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.error.HookFailedError;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.atomic.*;
import kotlin.jvm.internal.Ref;
import org.matrix.vector.impl.hooks.*;
import org.matrix.vector.nativebridge.HookBridge;

/** Exercises compiled production handles/parameters and production native callback operations.
 * GC is diagnostic in this host test only; it is never added to Vector's runtime. */
public final class HookCleanupTest {
    private static final String MODULE = "test.cleanup.module";
    private static final Method TARGET;
    private static final List<Object> retained = new ArrayList<>();
    private static int checks, failures;
    static {
        try { TARGET = HookCleanupTest.class.getMethod("original", int.class); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    public static String original(int value) {
        if (value < 0) throw new IllegalArgumentException("original failure");
        return Integer.toString(value);
    }
    private static native void install(Method method, Object record, boolean modern, int priority);
    private static native int snapshotAllocations(Class<?> recordType, Method method);
    private static native void releaseFixture();
    private static void require(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(message);
    }
    private interface Case { void run() throws Throwable; }
    private static void test(String name, Case body) {
        try { body.run(); System.out.println("PASS " + name); }
        catch (Throwable t) { ++failures; System.out.println("FAIL " + name + ": " + t); }
        finally {
            for (HookHandle h : VectorHookRegistry.INSTANCE.liveHandles(MODULE)) h.unhook();
            releaseFixture();
            retained.clear();
        }
    }
    private static Hooker hooker(Object value) { return chain -> value; }
    private static VectorHookHandle register(Hooker hooker, String owner) {
        VectorHookRecord record = new VectorHookRecord(hooker, 71, ExceptionMode.PASSTHROUGH, "stable-id");
        install(TARGET, record, true, 71);
        VectorHookHandle handle = new VectorHookHandle(TARGET, owner, record);
        if (owner != null) {
            VectorHookRegistry.INSTANCE.track(owner, handle);
            VectorHookRegistry.INSTANCE.claimId(owner, TARGET, "stable-id", handle);
        }
        return handle;
    }
    private static Object[][] snapshot() { return HookBridge.callbackSnapshot(VectorHookRecord.class, TARGET); }
    private static VectorHookRecord current() {
        Object[][] records = snapshot();
        require(records != null && records[0].length == 1 && records[1].length == 0,
            "snapshot must contain exactly one modern callback");
        return (VectorHookRecord) records[0][0];
    }
    private static final class Generation {
        final Hooker hooker;
        final WeakReference<ClassLoader> loader;
        Generation(String value) throws Exception {
            URLClassLoader child = new URLClassLoader(new URL[0], Hooker.class.getClassLoader());
            loader = new WeakReference<>(child);
            hooker = (Hooker) Proxy.newProxyInstance(child, new Class<?>[]{Hooker.class},
                (proxy, method, args) -> value);
            child.close();
        }
    }
    private static void collected(WeakReference<?> reference, String message) throws Exception {
        for (int i = 0; i < 30 && reference.get() != null; ++i) {
            System.gc();
            Thread.sleep(10);
        }
        require(reference.get() == null, message);
    }
    private static void emptySnapshot() throws Throwable {
        VectorHookHandle h = register(hooker("unused"), MODULE);
        h.unhook();
        require(snapshotAllocations(VectorHookRecord.class, TARGET) == 0,
            "empty callbackSnapshot allocated JNI arrays");
        require(snapshot() == null, "empty site should use the existing null fast path");
    }
    private static void originalSemantics() throws Throwable {
        register(hooker("unused"), MODULE).unhook();
        VectorNativeHooker<Method> callback = new VectorNativeHooker<>(TARGET);
        require(callback.callback(new Object[]{5}).equals("5"), "callback original result changed");
        try { callback.callback(new Object[]{-1}); throw new AssertionError("expected original exception"); }
        catch (IllegalArgumentException expected) { require(expected.getMessage().equals("original failure"), "wrong cause"); }
        VectorMethodInvoker invoker = new VectorMethodInvoker(TARGET);
        require(invoker.invokeWith(null, new Object[]{9}).equals("9"), "invoker result changed");
        try { invoker.invokeWith(null, new Object[]{-1}); throw new AssertionError("expected reflection wrapper"); }
        catch (Throwable expected) {
            require(expected instanceof InvocationTargetException && expected.getCause() instanceof IllegalArgumentException,
                "invoker exception contract changed");
        }
    }
    private static WeakReference<ClassLoader> retireByUnhook(String owner) throws Throwable {
        Generation generation = new Generation("old");
        VectorHookHandle h = register(generation.hooker, owner);
        retained.add(h);
        h.unhook(); h.unhook();
        require(h.getId().equals("stable-id") && h.getExecutable().equals(TARGET), "retired identity changed");
        return generation.loader;
    }
    private static final class InFlight {
        Object[][] records;
        WeakReference<ClassLoader> loader;
        HookHandle replacement;
    }
    private static InFlight replaceWithSnapshot() throws Throwable {
        Generation generation = new Generation("old");
        VectorHookHandle h = register(generation.hooker, MODULE);
        retained.add(h);
        InFlight flight = new InFlight();
        flight.records = snapshot();
        flight.loader = generation.loader;
        flight.replacement = h.replaceHook(hooker("new"));
        h.unhook(); // An invalid handle must not remove the replacement.
        require(current().getHooker().intercept(null).equals("new"), "replacement was removed");
        require(current().getPriority() == 71 && current().getExceptionMode() == ExceptionMode.PASSTHROUGH,
            "replacement did not inherit priority/mode");
        require(h.getId().equals(flight.replacement.getId()), "replacement identity changed");
        try { h.replaceHook(hooker("invalid")); throw new AssertionError("retired handle replaced twice"); }
        catch (IllegalStateException expected) { ++checks; }
        return flight;
    }
    private static void inFlight() throws Throwable {
        InFlight flight = replaceWithSnapshot();
        System.gc();
        require(flight.loader.get() != null, "in-flight callback was prematurely collected");
        require(((VectorHookRecord) flight.records[0][0]).getHooker().intercept(null).equals("old"),
            "in-flight callback changed generation");
        flight.records = null;
        collected(flight.loader, "retained invalid handle pins old callback/classloader after snapshot finishes");
        require(current().getHooker().intercept(null).equals("new"), "live replacement stopped working");
        flight.replacement.unhook();
    }
    private static void failedSwap() throws Throwable {
        VectorHookHandle h = register(hooker("old"), MODULE);
        // Simulate the native registry no longer containing the record.
        HookBridge.unhookMethod(true, TARGET, current());
        try { h.replaceHook(hooker("new")); throw new AssertionError("missing record replaced"); }
        catch (HookFailedError expected) { ++checks; }
        require(VectorHookRegistry.INSTANCE.liveHandles(MODULE).contains(h), "failed replacement invalidated owner");
        h.unhook();
        require(VectorHookRegistry.INSTANCE.liveHandles(MODULE).isEmpty(), "failed-swap cleanup lost owner");
    }
    private static void concurrentSnapshots() throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        HookHandle h = register(hooker(0), MODULE);
        Thread reader = new Thread(() -> {
            try {
                do {
                    Object[][] records = snapshot();
                    if (records == null || records[0].length != 1 || records[1].length != 0)
                        throw new AssertionError("replacement exposed zero or two callbacks");
                    Object value = ((VectorHookRecord) records[0][0]).getHooker().intercept(null);
                    if (!(value instanceof Integer)) throw new AssertionError("invalid snapshot payload");
                    reads.incrementAndGet();
                } while (running.get());
            } catch (Throwable t) { failure.set(t); }
        });
        reader.start();
        try { for (int i = 1; i <= 3000; ++i) h = h.replaceHook(hooker(i)); }
        finally { running.set(false); reader.join(); }
        require(failure.get() == null, "concurrent reader failed: " + failure.get());
        require(reads.get() > 0 && current().getHooker().intercept(null).equals(3000), "replacement race failed");
        h.unhook();
    }
    private static void legacySnapshot() {
        VectorHookHandle modern = register(hooker("modern"), MODULE);
        Object legacy = new Object();
        install(TARGET, legacy, false, 99);
        Object[][] both = snapshot();
        require(both[0].length == 1 && both[1].length == 1 && both[1][0] == legacy, "mixed callbacks lost");
        modern.unhook();
        Object[][] onlyLegacy = snapshot();
        require(onlyLegacy != null && onlyLegacy[0].length == 0 && onlyLegacy[1][0] == legacy,
            "empty modern callbacks bypassed legacy callbacks");
        HookBridge.unhookMethod(false, TARGET, legacy);
    }
    private static Object makeParam(Class<?> type, Object... args) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }
    private static WeakReference<ClassLoader> retainedParams() throws Throwable {
        String prefix = "org.matrix.vector.impl.core.VectorModuleManager$runHotReload$";
        Class<?> beforeType = Class.forName(prefix + "reloadingParam$1");
        Class<?> afterType = Class.forName(prefix + "reloadedParam$1");
        for (Class<?> type : List.of(beforeType, afterType)) {
            for (Field field : type.getDeclaredFields()) {
                require(!field.getType().getName().endsWith("$Generation"), "callback parameter pins Generation");
            }
        }
        Generation old = new Generation("old");
        Ref.ObjectRef<Object> state = new Ref.ObjectRef<>();
        HotReloadingParam before = (HotReloadingParam) makeParam(beforeType, null, old.loader, state);
        try { before.setSavedInstanceState(old.hooker); throw new AssertionError("old loader state accepted"); }
        catch (IllegalArgumentException expected) { ++checks; }
        before.setSavedInstanceState(List.of("neutral", 7));
        List<HookHandle> handles = List.of();
        HotReloadedParam after = (HotReloadedParam) makeParam(afterType, false, "test.process", null, state, handles);
        require(!after.isSystemServer() && after.getProcessName().equals("test.process"), "process info changed");
        require(after.getSavedInstanceState() == state.element && after.getOldHookHandles() == handles,
            "parameter values changed");
        retained.add(before); retained.add(after);
        return old.loader;
    }
    public static void main(String[] args) throws Throwable {
        System.load(args[0]);
        test("empty-snapshot-allocations", HookCleanupTest::emptySnapshot);
        test("empty-site-original-and-exceptions", HookCleanupTest::originalSemantics);
        test("unhook-releases-module-loader", () -> collected(retireByUnhook(MODULE), "unhooked handle pins loader"));
        test("unhook-releases-framework-callback", () -> collected(retireByUnhook(null), "framework handle pins loader"));
        test("replacement-preserves-in-flight-and-releases-retired", HookCleanupTest::inFlight);
        test("failed-replacement-keeps-ownership", HookCleanupTest::failedSwap);
        test("atomic-replacement-3000", HookCleanupTest::concurrentSnapshots);
        test("legacy-and-modern-snapshots", HookCleanupTest::legacySnapshot);
        test("retained-hotload-parameters", () -> {
            collected(retainedParams(), "retained hotload parameter pins loader");
            // Retaining a parameter does not introduce a new failure once its loader is gone.
            ((HotReloadingParam) retained.get(0)).setSavedInstanceState("later-neutral-state");
            require(((HotReloadedParam) retained.get(1)).getSavedInstanceState().equals("later-neutral-state"),
                "saved-state contract changed");
        });
        System.out.println("Checks: " + checks + ", failures: " + failures);
        if (failures != 0) throw new AssertionError(failures + " failed cleanup cases");
    }
}

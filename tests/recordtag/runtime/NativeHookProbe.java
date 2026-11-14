import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Run only in a disposable app_process, never inside system_server. */
public final class NativeHookProbe {
    private static native boolean init(ClassLoader loader);

    private static ClassLoader loader;
    private static Class<?> invokerClass;
    private static Class<?> typeClass;
    private static Method invoke;
    private static Object full;
    private static int hits;

    public static int target(int value) {
        return value * 3;
    }

    private static Object invoker(Method target, Object type) throws Exception {
        Constructor<?> constructor = invokerClass.getDeclaredConstructor(Method.class);
        constructor.setAccessible(true);
        Object instance = constructor.newInstance(target);
        loader.loadClass("io.github.libxposed.api.XposedInterface$Invoker")
                .getMethod("setType", typeClass).invoke(instance, type);
        return instance;
    }

    /** LSPlant's trampoline delegates to the production invoker and interceptor chain. */
    public static final class Hooker {
        public Hooker(Executable target) {}

        public Object callback(Object[] args) throws Exception {
            return invoke.invoke(full, null, args);
        }
    }

    private static void check(Object actual, int expected) {
        if (!Integer.valueOf(expected).equals(actual)) {
            throw new AssertionError(actual + " != " + expected);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected DEX, JNI library, invoker and hook-record class names");
        }
        loader = (ClassLoader) Class.forName("dalvik.system.InMemoryDexClassLoader")
                .getConstructor(ByteBuffer.class, ClassLoader.class)
                .newInstance(ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[0]))),
                        NativeHookProbe.class.getClassLoader());
        System.load(args[1]);
        if (!init(loader)) throw new AssertionError("Native initialization failed");
        Class<?> bridge = loader.loadClass("org.matrix.vector.nativebridge.HookBridge");
        invokerClass = loader.loadClass(args[2]);
        typeClass = loader.loadClass("io.github.libxposed.api.XposedInterface$Invoker$Type");
        Class<?> chain = loader.loadClass(typeClass.getName() + "$Chain");
        Object all = chain.getField("FULL").get(null);
        Object origin = loader.loadClass(typeClass.getName() + "$Origin").getConstructor().newInstance();
        Method target = NativeHookProbe.class.getMethod("target", int.class);
        invoke = loader.loadClass("io.github.libxposed.api.XposedInterface$Invoker")
                .getMethod("invoke", Object.class, Object[].class);
        full = invoker(target, all);
        Object original = invoker(target, origin);
        Class<?> hookInterface = loader.loadClass("io.github.libxposed.api.XposedInterface$Hooker");
        Class<?> chainInterface = loader.loadClass("io.github.libxposed.api.XposedInterface$Chain");
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{hookInterface}, (proxy, method, arguments) -> {
            if (!method.getName().equals("intercept")) throw new AssertionError(method);
            hits++;
            return ((Integer) chainInterface.getMethod("proceed").invoke(arguments[0])) + 100;
        });
        Class<?> mode = loader.loadClass("io.github.libxposed.api.XposedInterface$ExceptionMode");
        Object record = loader.loadClass(args[3]).getConstructors()[0]
                .newInstance(callback, 50, mode.getEnumConstants()[0], "recordtag-probe");
        Method hook = bridge.getMethod("hookMethod", boolean.class, Executable.class, Class.class, int.class, Object.class);
        check(target.invoke(null, 7), 21);
        if (!Boolean.TRUE.equals(hook.invoke(null, true, target, Hooker.class, 50, record))) {
            throw new AssertionError("Hook failed");
        }
        check(target.invoke(null, 7), 121);
        check(invoke.invoke(original, null, new Object[]{7}), 21);
        Object filtered = invoker(target, chain.getConstructor(int.class).newInstance(49));
        check(invoke.invoke(filtered, null, new Object[]{7}), 21);
        check(invoke.invoke(full, null, new Object[]{7}), 121);
        if (hits != 2) throw new AssertionError("Hook hits=" + hits);
        if (!Boolean.TRUE.equals(bridge.getMethod("unhookMethod", boolean.class, Executable.class, Object.class)
                .invoke(null, true, target, record))) {
            throw new AssertionError("Unhook failed");
        }
        check(target.invoke(null, 7), 21);
        System.out.println("PASS: real native hook dispatch, Chain.FULL, priority filtering, Origin bypass, original return values and unhook");
    }
}

import dalvik.system.InMemoryDexClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Disposable process: load API record types without initializing Vector's hooks. */
public final class RecordTagRuntimeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Expected DEX path and optional --fixture");
        ClassLoader loader = new InMemoryDexClassLoader(
                ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[0]))),
                ClassLoader.getSystemClassLoader());
        if (args.length == 2) {
            if (!"--fixture".equals(args[1])) throw new IllegalArgumentException("Unknown mode");
            Class<?> record = Class.forName("org.matrix.vector.recordtest.RecordProbe$Value", true, loader);
            Object first = record.getConstructor(int.class).newInstance(42);
            Object equal = record.getConstructor(int.class).newInstance(42);
            Object different = record.getConstructor(int.class).newInstance(43);
            if (!first.equals(equal) || first.equals(different)
                    || first.hashCode() != equal.hashCode()
                    || !Integer.valueOf(42).equals(record.getMethod("value").invoke(first))
                    || !first.toString().contains("42"))
                throw new AssertionError("Fixture record contract failed");
            System.out.println("PASS: packaged fixture record loads and preserves value semantics");
            return;
        }
        String prefix = "io.github.libxposed.api.XposedInterface$Invoker$Type";
        Class<?> chain = Class.forName(prefix + "$Chain", true, loader);
        Object first = chain.getConstructor(int.class).newInstance(42);
        Object equal = chain.getConstructor(int.class).newInstance(42);
        Object different = chain.getConstructor(int.class).newInstance(43);
        if (!first.equals(equal) || first.equals(different)
                || first.hashCode() != equal.hashCode()
                || !Integer.valueOf(42).equals(chain.getMethod("maxPriority").invoke(first))
                || !first.toString().contains("42") || chain.getField("FULL").get(null) == null)
            throw new AssertionError("Chain record contract failed");
        Class<?> origin = Class.forName(prefix + "$Origin", true, loader);
        Object original = origin.getConstructor().newInstance();
        if (!original.equals(origin.getConstructor().newInstance()))
            throw new AssertionError("Origin record contract failed");
        Class<?> invoker = Class.forName("org.matrix.vector.impl.hooks.VectorMethodInvoker", true, loader);
        java.lang.reflect.Constructor<?> constructor = invoker.getDeclaredConstructor(java.lang.reflect.Method.class);
        constructor.setAccessible(true);
        Object instance = constructor.newInstance(String.class.getMethod("length"));
        java.lang.reflect.Method setType = invoker.getMethod("setType", Class.forName(prefix, true, loader));
        setType.setAccessible(true);
        if (setType.invoke(instance, original) != instance)
            throw new AssertionError("Invoker type selection failed");
        System.out.println("PASS: Chain/Origin semantics, BaseInvoker construction and original-type selection");
    }
}

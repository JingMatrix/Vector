# Preserve the libxposed public API surface for module developers
-keep class io.github.libxposed.** { *; }

# Preserve all native methods (HookBridge, ResourcesHook, NativeAPI, etc.)
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Preserve the JNI Hook Trampoline
-keepclassmembers class org.matrix.vector.impl.hooks.VectorNativeHooker {
    public <init>(java.lang.reflect.Executable);
    public java.lang.Object callback(java.lang.Object[]);
}

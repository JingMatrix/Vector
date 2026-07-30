# Preserve the libxposed public API surface for module developers
-keep class io.github.libxposed.** { *; }

# Preserve all native methods (HookBridge, ResourcesHook, NativeAPI, etc.)
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Preserve the JNI Hook Trampoline. hook_bridge.cpp resolves every one of these by name off the
# hooker class the first time a hook is installed: callback is the native entry point it registers,
# dispatch is what that entry point calls once the re-entrancy guard is up, and the two fields are
# what the guarded path reads to reach the original without entering Java at all.
-keepclassmembers class org.matrix.vector.impl.hooks.VectorNativeHooker {
    public <init>(java.lang.reflect.Executable);
    public java.lang.Object callback(java.lang.Object[]);
    public java.lang.Object dispatch(java.lang.Object[]);
    public java.lang.reflect.Executable method;
    public boolean isStatic;
}

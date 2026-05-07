# Legacy Xposed API Implementation

This document details the architecture and implementation of the `legacy` module within the Vector framework. The `legacy` subsystem provides a backward-compatibility layer, implementing the classic `de.robv.android.xposed` API namespace while routing execution to the modern native ART hooking engine.

## Module Topology and Boundaries

The legacy compatibility subsystem spans multiple compilation boundaries to enforce strict separation between the API surface, the Dalvik/ART runtime, and the native execution environment.

- [legacy](.): Contains the Java API surface (`de.robv.android.xposed.*`), state translation handlers (`LegacyDelegateImpl`), reflection caching mechanisms, and resource/shared preference overrides.
- [xposed](../xposed): Manages the isolated classloaders (`VectorModuleClassLoader`), the Ahead-Of-Time (AOT) compiler deoptimizer (`VectorDeopter`), and the Dependency Injection (DI) framework.
- [native](../native): Houses the JNI bridges (`hook_bridge.cpp`, `resources_hook.cpp`), concurrent hook registries, stack-allocated invocation logic, in-memory DEX generation, and binary XML mutation routines.
- [daemon](../daemon): Operates out-of-process with elevated privileges to provision SELinux-permissive directories and manage filesystem contexts for cross-process file sharing.

### Dependency Injection and Bootstrap

During process startup, `Startup.initXposed` is invoked in the `zygisk` module. This routine establishes the DI (Dependency Injection) contract by instantiating `LegacyDelegateImpl` and injecting it into the `xposed` module via `VectorBootstrap.INSTANCE.init()`.

The `LegacyDelegateImpl` satisfies the `LegacyFrameworkDelegate` interface, acting as the sole translation boundary for:

- Application package load events (`onPackageLoaded`).
- System server initialization (`onSystemServerLoaded`).
- Native hook execution routing (`processLegacyHook`).
- Resource directory tracking (`setPackageNameForResDir`).

## Module Initialization

Legacy modules are loaded during the initialization phase via `XposedInit.loadLegacyModules()`. The framework queries the daemon (`VectorServiceClient.INSTANCE.getLegacyModulesList()`) to retrieve the list of enabled APK paths.

Modules are not loaded using standard Android mechanism. To prevent detection via `ClassLoader.getParent()` chain-walking and to eliminate residual file descriptors, `XposedInit.loadModule` utilizes `VectorModuleClassLoader`. This classloader loads the module APK directly into memory, isolating the module's execution environment from the host application's classpath.

Once mapped into memory, the framework parses two specific manifest files within the APK to initialize Java and native execution hooks:

1. `assets/xposed_init`: Defines the Java entrypoint class, which is loaded via `VectorModuleClassLoader` and inspected for `IXposedMod` implementations to be registered with the internal callback arrays:
    - `IXposedHookZygoteInit`: Invoked immediately with a `StartupParam` structure containing the module path and system server status.
    - `IXposedHookLoadPackage`: Wrapped in `IXposedHookLoadPackage.Wrapper` and appended to the `XposedBridge.sLoadedPackageCallbacks` array.
    - `IXposedHookInitPackageResources`: Appended to `XposedBridge.sInitPackageResourcesCallbacks` array, which subsequently triggers the native resource hooking subsystem via `XposedInit.hookResources()`.

2. `assets/native_init`: Defines native library filenames that are treated as [native hooking modules](https://github.com/LSPosed/LSPosed/wiki/Native-Hook). These names are registered via` NativeAPI::recordNativeEntrypoint` to be intercepted during the dynamic linking process. The `native` module provides an infrastructure for native modules to perform inline hooking via [native_api.cpp](../native/src/jni/native_api_bridge.cpp) without direct access to the framework's core symbols.

### Lifecycle Event Translation

The `xposed` module manages Android lifecycle events (e.g., `LoadedApk.createOrUpdateClassLoaderLocked`), and dispatches a `LegacyPackageInfo` payload to `LegacyDelegateImpl`.

`LegacyDelegateImpl.onPackageLoaded` translates the modern payload into the classic Xposed format. It constructs an `XC_LoadPackage.LoadPackageParam` object, mapping the following fields: `packageName`, `processName`, `classLoader`, `appInfo`, and `isFirstApplication`. The populated parameter object is then passed to `XC_LoadPackage.callAll`, which iterates over the `sLoadedPackageCallbacks` array and executes the registered module callbacks.

The system server represents a unique lifecycle edge case. `LegacyDelegateImpl.onSystemServerLoaded` manually registers `android` into the `loadedPackagesInProcess` set and constructs a hardcoded `LoadPackageParam` with the process name `system_server`.

## Execution Routing and Method Hooking

The method hooking lifecycle involves identifying target executables via reflection, ensuring ART compliance by deoptimizing compiled code, registering JNI trampolines, and managing the execution state during invocation.

### Structural Reflection Caching

Legacy modules rely heavily on reflection to locate target methods and fields (e.g., via `XposedHelpers.findAndHookMethod`). The `legacy` module implements a structural caching mechanism within `XposedHelpers`. Queries are encapsulated into `MemberCacheKey` subclasses (`Method`, `Constructor`, `Field`). These keys compute their hashes based on object identity and structural properties (class references, parameter array contents, and exactness flags) rather than strings. The keys are stored in `ConcurrentHashMap` instances (`fieldCache`, `methodCache`, `constructorCache`), enabling zero-allocation cache hits for repeated reflective lookups.

### AOT Deoptimization

Modern Android Runtime (ART) environments utilize Ahead-Of-Time (AOT) compilation, which frequently inlines short methods into their callers. If an inlined method is hooked, the JNI trampoline will be bypassed because the caller executes the inlined machine code directly.

To mitigate this, the `xposed` module implements `VectorDeopter`. During initialization or application load, `VectorDeopter.deoptMethods()` queries a registry of known inlined methods [VectorInlinedCallers](../xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorInlinedCallers.kt). It iterates through these targets and issues a native command (`HookBridge.deoptimizeMethod`) to force ART to discard the compiled machine code for the target. This forces the method execution back into the ART interpreter (via `ClassLinker::SetEntryPointsToInterpreter` in `lsplant`), which strictly respects method boundaries and guarantees execution of the installed JNI trampolines.

### Native Hook Registry and Execution State Translation

Hook registration is routed from `XposedBridge` to the native layer in [hook_bridge.cpp](../native/src/jni/hook_bridge.cpp). The native environment manages a concurrent global registry to track hooked executables and their associated callbacks.
When a hooked method executes, the native engine pauses standard execution and routes control back to `xposed`, which invokes the `LegacyFrameworkDelegate.processLegacyHook` interface.

The `LegacyDelegateImpl` translates the modern execution state (`OriginalInvoker`) into the legacy specification. It wraps the invocation state within `LegacyApiSupport` and performs the following execution loop:

1.  Iterates forward through all registered `XC_MethodHook` instances, invoking `beforeHookedMethod`.
2.  Checks if any module invoked `setResult()` or `setThrowable()`. If the execution was not skipped, it commands the `OriginalInvoker` to proceed with the native method execution.
3.  Iterates backward through the callbacks, invoking `afterHookedMethod`. If a downstream module threw an exception during execution, `LegacyApiSupport` catches the exception and restores the original cached result or throwable. This protects the host process from unhandled module exceptions.

## Resource Hooking Subsystem

The resource hooking implementation allows legacy modules to replace application assets, layout definitions, and string values at runtime. Because Android optimizes resource retrieval and XML parsing heavily in native C++ code, this subsystem requires a combination of framework-level injection, dynamic class generation, and direct memory manipulation of binary XML structures.

To intercept resource queries, the system replaces the default OS-provided `Resources` instance with a custom `XResources` subclass. During framework startup, `XposedInit.hookResources()` intercepts the Android `android.app.ResourcesManager` component, by applying hooks to the resource factory methods (`createResources`, `createResourcesForActivity` on Android 12+, and `getOrCreateResources` on older versions). When an application requests a new resource object, the hook callback executes `cloneToXResources()`. This method instantiates a new `XResources` object and copies the underlying OS implementation by extracting `mResourcesImpl` via `HiddenApiBridge.Resources_setImpl`. The newly constructed `XResources` instance is then injected back into the operating system's internal tracking arrays (e.g., `mResourceReferences` or the `ActivityResource` struct), encapsulated within a `WeakReference` to prevent memory leaks.

### Dynamic Class Hierarchy Generation

To intercept resource queries effectively, the framework must replace the OS-provided resource instances with `XResources` and `XTypedArray`. However, hardcoding `XResources` to inherit directly from the base AOSP `Resources` class would result in fatal `ClassCastException` aborts when the heavily modified OEM framework attempts to cast the injected object back to its expected proprietary type. To resolve this runtime polymorphism requirement without triggering `ClassNotFoundException` during initial loading, the framework dynamically generates an intermediate class hierarchy.

During initialization, `XposedBridge.initXResources` within the `legacy` module inspects the exact runtime class of the system resources and typed arrays. It then invokes the `native` bridge `ResourcesHook.makeInheritable` to strip the final modifier from these OEM classes at the ART level, ensuring they can be subclassed. Subsequently, it invokes `ResourcesHook.buildDummyClassLoader`. The `native` implementation utilizes the `dex_builder` library to construct a DEX file directly within a memory buffer. It generates dummy classes named `xposed.dummy.XResourcesSuperClass` and `xposed.dummy.XTypedArraySuperClass`, dynamically setting their superclasses to the exact OEM classes detected earlier. This memory buffer is then loaded into the runtime via `dalvik.system.InMemoryDexClassLoader`. Finally, the `legacy` module manipulates the parent chain of its own classloader by overriding the parent field to point to the in-memory dummy classloader. At compile time, `XResources` is declared as extending the `XResourcesSuperClass` stub. At runtime, when Dalvik/ART resolves `XResources`, the manipulated classloader chain provides the dynamically generated dummy class. This mechanism guarantees that `XResources` safely inherits all OEM-specific methods and fields, successfully passing any internal type checks performed by the vendor framework.

On Lenovo ZUI devices, the OEM modified the `obtainTypedArray` implementation to query the device configuration from `android.app.ActivityThread.sCurrentActivityThread`. Accessing this field during Zygote startup returns null, resulting in a fatal `NullPointerException` that crashes the boot process. The legacy module circumvents this by reflecting an empty, uninitialized `ActivityThread` object, injecting it into the static `sCurrentActivityThread` field, invoking `obtainTypedArray`, and immediately nullifying the field in a finally block.

### Replacement Caching and Native Binary XML Mutation

Intercepting high-frequency rendering paths (such as `getDrawable` or `getColor`) and querying a standard hash map for module replacements causes severe lock contention and degrades UI thread performance. To prevent this, `XResources` utilizes a lock-free bitmask cache to validate the existence of a replacement in O(1) time before querying the main `sReplacements` hash map.
- `sSystemReplacementsCache`: A static 256-byte array tracking framework resource IDs (values below `0x7f000000`).
- `mReplacementsCache`: A 128-byte array tracking application-specific resource IDs (values greater than or equal to `0x7f000000`).

The bitmask acts as a high-performance fast-path rejection filter to prevent synchronized map lookups for unmodified resources. During registration, the framework maps the resource ID to an index in a byte array—harvesting entropy from the Type and Entry Index fields—and sets a specific bit using the ID's lowest three bits. Upon resource retrieval, `getReplacement` performs a bitwise check; if the bit is zero, the framework immediately returns `null` without acquiring a monitor. This O(1) check ensures that the majority of resource requests bypass the global `sReplacements` lock, maintaining UI thread performance.

When an application inflates a layout via `LayoutInflater`, the Android OS parses compiled AAPT binary XML files utilizing the native `android::ResXMLParser` C++ class. Standard Java hooks cannot intercept the internal ID resolution performed by this parser. To inject custom module layouts, the framework mutates the binary XML tree in memory.

1. In [resources_hook.cpp](../native/src/jni/resources_hook.cpp), the `PrepareSymbols` function utilizes the `ElfImage` utility to parse `libandroidfw.so` in memory. It resolves unexported, mangled C++ symbols for `android::ResXMLParser::next`, `restart`, and `getAttributeNameID`, caching their memory addresses in global function pointers.

2. If a requested layout is not already cached, `XResources` extracts the native pointer (`mParseState`) and passes it to the JNI bridge `rewriteXmlReferencesNative`. The native code casts the `jlong` back to an `android::ResXMLParser*` and executes a loop, manually invoking `ResXMLParser_next`.

3. When the parser encounters an `android::ResXMLParser::START_TAG` token, it extracts the attribute count and iterates over the tag's attributes. For each attribute, it resolves the `attrNameID` via the cached `getAttributeNameID` pointer. If the ID belongs to the application package namespace (`0x7f000000`), it queries the Java layer via JNI (`XResources.translateAttrId`) to check if a module provided a replacement. If a replacement ID is returned, the native code performs an in-place mutation of the binary XML tree by directly overwriting the integer in the parser's memory allocation (`mResIds[attrNameID] = attrResID`). It repeats this evaluation and mutation logic for the attribute's value reference via `XResources.translateResId`.

4. Upon reaching the `END_DOCUMENT` token, the native loop exits and invokes `ResXMLParser_restart`. When the native bridge returns and the Android framework resumes the inflation process, it unknowingly parses the mutated binary XML tree, correctly resolving the module-provided layout IDs.

## SharedPreferences and SELinux Boundaries

The classic Xposed API relied on the `Context.MODE_WORLD_READABLE` flag, allowing target applications to read configuration files directly from the module's `/data/data/<package>/shared_prefs/` directory. Starting with Android 7.0, the operating system throws a `SecurityException` when this flag is used. Furthermore, modern SELinux policies enforce strict application data isolation, preventing cross-process directory traversal regardless of Unix file permissions.

To restore `XSharedPreferences` functionality without compromising system stability, the framework implements a coordinated bypass utilizing the out-of-process daemon and runtime path redirection.

The `daemon` module operates with elevated privileges and provisions a specialized safe-zone directory for module configuration sharing. When resolving the module directory, the daemon executes `setSelinuxContextRecursive` to apply the [u:object_r:xposed_data:s0](../zygisk/module/sepolicy.rule) SELinux context. This specific context is universally readable across standard application domains. The daemon subsequently invokes `Os.chmod` to enforce `755` Unix permissions and adjusts directory ownership. This creates a filesystem bridge that both the module and target applications can legally access without violating SELinux isolation.

### Interception and Redirection

To utilize the safe-zone transparently, the `legacy` module intercepts the configuration saving mechanics within the module's own user interface process. During application load, `LegacyDelegateImpl` parses the module's APK metadata using `VectorMetaDataReader`. If the module declares an `xposedminversion` greater than 92 or contains the `xposedsharedprefs` flag, the framework triggers `hookNewXSP`. This routine applies two critical hooks to `android.app.ContextImpl`:

1.  Flag Stripping: It hooks `checkMode` to intercept the mode integer. If the `MODE_WORLD_READABLE` bit is present, it suppresses the resulting `SecurityException` by setting the hook throwable to null.
2.  Path Redirection: It hooks `getPreferencesDir` using an `XC_MethodReplacement`. Instead of returning the standard isolated data directory, it returns the daemon-provisioned safe-zone path obtained via `VectorServiceClient.INSTANCE.getPrefsPath`.

When the module attempts to save its standard `SharedPreferences`, the Android framework transparently writes the XML file into the SELinux-permissive bridge.

### File I/O and IPC Bypass

When a target application is hooked and instantiates `XSharedPreferences`, the framework determines the path based on the target API level. For modern modules, it bypasses the legacy `/data/data` path entirely and maps directly to the safe-zone.

In the original [Xposed framework](https://github.com/rovo89/XposedBridge), circumventing SELinux at read-time required synchronous IPC via BinderService or native root access via ZygoteService. In the Vector framework, these IPC mechanisms have been removed. Because the daemon pre-emptively assigns a permissive SELinux context to the safe-zone, the target application process possesses the necessary permissions to read the file directly. The SELinuxHelper component unconditionally returns DirectAccessService, an implementation of BaseService. This service acts purely as a structural API shim to maintain compatibility with the internal caching logic of XSharedPreferences, performing raw reads utilizing standard `FileInputStream` and `BufferedInputStream` operations without IPC overhead.

Since standard Android inter-process communication mechanisms (such as broadcast intents or content providers) are overly visible for cross-process preference tracking, `XSharedPreferences` implements an in-process filesystem watcher to handle live updates. When an `OnSharedPreferenceChangeListener` is registered, the framework spawns an internal daemon thread (`sWatcherDaemon`). This thread utilizes `java.nio.file.WatchService` (an abstraction over the Linux `inotify` subsystem) to monitor the safe-zone directory. The thread blocks on `sWatcher.take()`, and upon receiving an `ENTRY_MODIFY` or `ENTRY_DELETE` event for the target XML file, it validates the file hash and natively dispatches the legacy preference change callbacks to the registered listeners.

## Developer References

For module developers building against or debugging the legacy Xposed API, the following external documentation provides historical context and usage guidelines:

- [Xposed Development Tutorial (rovo89)](https://github.com/rovo89/XposedBridge/wiki/Development-tutorial)
- [LSPosed New XSharedPreferences Mechanism](https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences)

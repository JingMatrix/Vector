# Xposed API implementation of the Vector framework

This module implements the [libxposed](https://github.com/libxposed/api) API for the Vector framework. It serves as the primary bridge between the native ART hooking engine (`lsplant`) and module developers, providing a type-safe, OkHttp-style interceptor chain architecture.

## Architectural Overview

The `xposed` module is designed with strict boundaries to ensure stability during the Android boot process and application lifecycles. It is written entirely in Kotlin and operates independently of the legacy Xposed API (`de.robv.android.xposed`). 
It defines a Dependency Injection (DI) contract (`LegacyFrameworkDelegate`) which the `legacy` module must implement and inject during startup.

## Core Components

### 1. The Hooking Engine

*   **`VectorHookBuilder`**: Implements the `HookBuilder` API. It validates the target `Executable`, bundles the module's `Hooker`, `priority`, and `ExceptionMode` into a `VectorHookRecord`, and registers it natively via JNI.
*   **`VectorNativeHooker`**: The JNI trampoline target. When a hooked method is executed, the C++ layer invokes `callback(Array<Any?>)` on this class. It fetches the active hooks (both modern and legacy) from the native registry as global `jobject` references, constructs the root `VectorChain`, and initiates execution.
*   **`VectorChain`**: Implements the recursive `proceed()` state machine.
    *   **Exception Handling**: It implements the logic for `ExceptionMode`. In `PROTECTIVE` mode, if an interceptor throws an exception *before* calling `proceed()`, the chain skips the interceptor. If it throws *after* calling `proceed()`, the chain catches the exception and restores the cached downstream result/throwable to protect the host process.

### 2. The Invocation System

The `Invoker` system allows modules to execute methods while bypassing standard JVM access checks, with granular control over hook execution.

*   **`Type.Origin`**: Dispatches directly to JNI (`HookBridge.invokeOriginalMethod`), bypassing all active hooks.
*   **`Type.Chain`**: Constructs a localized `VectorChain` containing only hooks with a priority less than or equal to the requested `maxPriority`, allowing modules to execute partial hook chains.
*   **`VectorCtorInvoker`**: Handles constructor invocation. It separates memory allocation (`HookBridge.allocateObject`) from initialization (`invokeOriginalMethod` / `invokeSpecialMethod`) to support safe `newInstanceSpecial` logic.

### 3. Dependency Injection Contract

To maintain the separation of concerns, the `xposed` module communicates with the legacy Xposed ecosystem via `VectorBootstrap` and `LegacyFrameworkDelegate`.

When `xposed` intercepts an Android lifecycle event (e.g., `LoadedApk.createClassLoader`), it dispatches the event internally via `VectorLifecycleManager` and then delegates the raw parameters to `LegacyFrameworkDelegate` so the `legacy` module can construct and dispatch the legacy `XC_LoadPackage` callbacks.

### 4. In-Memory ClassLoading & Isolation

Modules are executed strictly from memory using an isolated ClassLoader, ensuring zero disk footprint and maximum stealth against anti-cheat mechanisms.
* The module APK is loaded into `SharedMemory` (ashmem) to bypass Java heap limitations. Once the Android Runtime (ART) ingests the DEX buffers, the ashmem is instantly unmapped, preventing memory leaks and leaving no residual file descriptors.
* The `VectorModuleClassLoader` is attached exclusively to the Xposed Framework's classloader branch, preventing the target app from discovering the module via reflection or `ClassLoader.getParent()` chain-walking.
* `VectorURLStreamHandler` intercepts standard `jar:` requests, reading assets and resources natively from the module path without triggering Android's global `JarFile` cache, preventing OS-level file locks.

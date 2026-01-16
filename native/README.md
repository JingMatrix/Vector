# Vector Native Library (`native`)

## Purpose and Design Philosophy

This library is a C++ toolkit that provides low-level hooking and modification capabilities for the Android OS. It is not a standalone application but a collection of components designed to be integrated into a larger loading mechanism, such as a Zygisk module.

The core design philosophy is **Inversion of Control**. The library provides a powerful set of tools and defines an abstract injection lifecycle, but it intentionally leaves the high-level orchestration (e.g., deciding *which* processes to inject into and *when*) to its consumer. This is primarily achieved through the `core::Context` abstract class, which the consumer must inherit from and implement.

In essence, this library provides the "what" and the "how," while the consumer provides the "when" and "where."

## Architectural Breakdown

The library is organized into distinct modules, each with a clear responsibility.

### `core` - The Abstract Engine

This module defines the central abstractions and manages the runtime state. It's the conceptual heart of the library.

-   **`Context`**: An abstract base class that defines the injection lifecycle. It contains pure virtual methods like `LoadDex` and `SetupEntryClass`. The consumer of this library (e.g., the Zygisk module) must inherit from `Context` and provide the concrete implementations for these steps. This decouples the low-level mechanics from the high-level process management logic.
-   **`ConfigBridge`**: A simple, native-side singleton that acts as a cache for configuration data (specifically, the obfuscation map) that is fetched and provided by the consumer.
-   **`native_api`**: Implements the native module support system. It works by hooking the system's `do_dlopen` function. When it detects a registered module library being loaded, it calls that library's `native_init` entry point, providing it with a stable ABI for creating its own native hooks.

### `elf` - Symbol Resolution

This module is responsible for runtime symbol lookups in shared libraries, a critical function for native hooking.

-   **`ElfImage`**: A parser for ELF files mapped into the current process's memory. Its key feature is the ability to resolve symbols even in stripped binaries by locating, decompressing (using xz-embedded), and parsing the `.gnu_debugdata` section. It uses a cascading lookup strategy: GNU hash -> ELF hash -> linear scan of the symbol table.
-   **`ElfSymbolCache`**: A thread-safe, lazy-initialized cache for `ElfImage` instances. To prevent race conditions during first-time access from multiple threads, initialization is protected by a mutex using a double-checked locking pattern. This provides a performant and safe way to access common libraries like `libart.so` and the `linker`.

### `jni` - The Business Logic Interface

This is the most significant module and represents the library's primary "service layer." It contains a set of JNI bridges that expose the core features to the injected Java framework code. The functionality here is the main product of the native library.

-   **`jni_bridge.h`**: Provides a set of helper macros (`VECTOR_NATIVE_METHOD`, `REGISTER_VECTOR_NATIVE_METHODS`, etc.) to standardize and simplify the tedious process of writing JNI boilerplate.
-   **`HookBridge`**: The engine for ART method hooking. It maintains a thread-safe map of all active hooks. It also includes some stability controls, such as using atomic operations to set the backup method trampoline and throwing a Java exception instead of causing a native crash if a user tries to invoke the original method of a failed hook.
-   **`ResourcesHook`**: Provides the functionality to intercept and rewrite Android's binary XML resources on the fly. It relies on non-public structures from `libandroidfw.so` and uses the `elf` module to find the necessary function symbols at runtime.
-   **`DexParserBridge`**: Exposes a high-performance native DEX parser to the Java layer using a visitor pattern. This allows for efficient analysis of an app's bytecode without the overhead of instantiating the entire DEX structure as Java objects.
-   **`NativeApiBridge`**: The JNI counterpart to the `core/native_api`. It exposes a method for the Java framework to register the filenames of third-party native modules.

### `common` & `framework`

-   **`common`**: A collection of basic utilities, including a `fmt`-based logging system, global constants, and helper functions.
-   **`framework`**: Contains minimal, reverse-engineered C++ structure definitions that mirror those inside Android's internal `libandroidfw.so`. These are necessary to correctly interpret resource data pointers.

## 3. Build System

The library is configured with CMake to be built as a **static library (`libnative.a`)**. This allows it to be easily linked into a final shared object (like a Zygisk module) without requiring the end-user device to have a separate `.so` file. All external dependencies are also linked statically for maximum portability.

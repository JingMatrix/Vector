# VectorDex2Oat

VectorDex2Oat is a specialized wrapper and instrumentation suite for the Android `dex2oat` (Ahead-of-Time compiler) binary. It is designed to intercept the compilation process, force specific compiler behaviors (specifically disabling method inlining), and transparently spoof the resulting OAT metadata to hide the presence of the wrapper.

## Overview

In the Android Runtime (ART), `dex2oat` compiles DEX files into OAT files. Modern ART optimizations often inline methods, making it difficult for instrumentation tools to hook specific function calls. 

This project consists of two primary components:
1.  **dex2oat (Wrapper):** A replacement binary that intercepts the execution, communicates via Unix Domain Sockets to obtain the original compiler binary, and executes it with forced flags.
2.  **liboat_hook.so (Hooker):** A shared library injected into the `dex2oat` process via `LD_PRELOAD` that utilizes PLT hooking to sanitize the OAT header's command-line metadata.

## Key Features

*   **Inlining Suppression:** Appends `--inline-max-code-units=0` to the compiler arguments, ensuring all methods remain discrete and hookable.
*   **FD-Based Execution:** Executes the original `dex2oat` via the system linker using `/proc/self/fd/` paths, avoiding direct execution of files on the disk.
*   **Metadata Spoofing:** Intercepts `art::OatHeader::ComputeChecksum` or `art::OatHeader::GetKeyValueStore` to remove traces of the wrapper and its injected flags from the final `.oat` file.
*   **Abstract Socket Communication:** Uses the Linux Abstract Namespace for Unix sockets to coordinate file descriptor passing between the controller and the wrapper.

## Architecture

### The Wrapper [dex2oat.cpp](src/main/cpp/dex2oat.cpp)
The wrapper acts as a "man-in-the-middle" for the compiler. When called by the system, it
1.  connects to a predefined Unix socket (the stub name `5291374ceda0...` will be replaced during installation of `Vector`);
2.  identifies the target architecture (32-bit vs 64-bit) and debug status;
3.  receives File Descriptors (FDs) for both the original `dex2oat` binary and the `oat_hook` library;
4.  reconstructs the command line, replacing the wrapper path with the original binary path and appending the "no-inline" flags;
5.  clears `LD_LIBRARY_PATH` and sets `LD_PRELOAD` to the hooker library's FD;
6.  invokes the dynamic linker (`linker64`) to execute the compiler.

### The Hooker [oat_hook.cpp](src/main/cpp/oat_hook.cpp)
The hooker library is preloaded into the compiler's address space. It uses the [LSPlt](https://github.com/JingMatrix/LSPlt) library to:
1.  Scan the memory map to find the `dex2oat` binary.
2.  Locate and hook internal ART functions:
    *   [art::OatHeader::GetKeyValueStore](https://cs.android.com/android/platform/superproject/+/android-latest-release:art/runtime/oat/oat.cc;l=366)
    *   [art::OatHeader::ComputeChecksum](https://cs.android.com/android/platform/superproject/+/android-latest-release:art/runtime/oat/oat.cc;l=366)
3.  When the compiler attempts to write the "dex2oat-cmdline" key into the OAT header, the hooker intercepts the call, parses the key-value store, and removes the wrapper-specific flags and paths.

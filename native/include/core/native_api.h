#pragma once

#include <dlfcn.h>
#include <dobby.h>

#include <string>
#include <utils/hook_helper.hpp>

#include "common/config.h"
#include "common/logging.h"

/**
 * @file native_api.h
 * @brief Manages the native module ecosystem and provides a stable API for them.
 *
 * This component is responsible for hooking the dynamic library loader (`dlopen`) to
 * detect when registered native modules are loaded.
 * It then provides these modules with a set of function pointers for
 * interacting with the Vector core, primarily for creating native hooks.
 */

// NOTE: The following type definitions form a public ABI for native modules.
// Do not change them without careful consideration for backward compatibility.

/*
 * =========================================================================================
 *  Vector Native API Interface
 * =========================================================================================
 *
 * This following function types and data structures allow a native library (your module) to
 * interface with the Vector framework.
 * The core idea is that Vector provides a set of powerful tools (like function hooking),
 * and your module consumes these tools through a well-defined entry point.
 *
 * The interaction flow is as follows:
 *
 *   1. Vector intercepts the loading of your native library (e.g., libnative.so).
 *   2. Vector looks for and calls the `native_init` function within your library.
 *   3. Vector passes a `NativeAPIEntries` struct to your `native_init`,
 *      which contains function pointers to Vector's hooking
 *      and unhooking implementations (powered by Dobby).
 *   4. Your `native_init` function saves these function pointers for later use
 *      and returns a callback function (`NativeOnModuleLoaded`).
 *   5. Vector will then invoke your returned callback every time
 *      a new native library is loaded into the target process,
 *      allowing you to perform "late" hooks on specific libraries.
 *
 *
 * Initialization Flow
 *
 *   Vector Framework                    Your Native Module (e.g., libnative.so)
 *   -----------------                    -------------------------------------
 *
 *        |                                            |
 * [ Intercepts dlopen("libnative.so") ]               |
 *        |                                            |
 *        |----------> [ Finds & Calls native_init() ] |
 *        |                                            |
 *   [ Passes NativeAPIEntries* ]  ---> [ Stores function pointers ]
 *   (Contains hook/unhook funcs)                      |
 *        |                                            |
 *        |                                            |
 *        |             <-----------[ Returns `NativeOnModuleLoaded` callback ]
 *        |                                            |
 *        |                                            |
 *   [ Stores your callback ]                          |
 *        |                                            |
 *
 */

// Function pointer type for a native hooking implementation.
using HookFunType = int (*)(void *func, void *replace, void **backup);

// Function pointer type for a native unhooking implementation.
using UnhookFunType = int (*)(void *func);

// Callback function pointer that modules receive, invoked when any library is loaded.
using NativeOnModuleLoaded = void (*)(const char *name, void *handle);

/**
 * @struct NativeAPIEntries
 * @brief A struct containing function pointers exposed to native modules.
 */
struct NativeAPIEntries {
    uint32_t version;          // The version of this API struct.
    HookFunType hookFunc;      // Pointer to the function for inline  hooking.
    UnhookFunType unhookFunc;  // Pointer to the function for unhooking.
};

// NOTE: Module developers should not include the following INTERNAL definitions.

namespace vector::native {

// The entry point function that native modules must export (`native_init`).
using NativeInit = NativeOnModuleLoaded (*)(const NativeAPIEntries *entries);

/**
 * @brief Installs the hooks required for the native API to function.
 * @param handler The LSPlant hook handler.
 * @return True on success, false on failure.
 */
bool InstallNativeAPI(const lsplant::HookHandler &handler);

/**
 * @brief Registers a native library by its filename for module initialization.
 *
 * When a library with a matching filename is loaded via `dlopen`, the runtime will attempt to
 * initialize it as a native module by calling its `native_init` function.
 *
 * @param library_name The filename of the native module's .so file (e.g., "libmymodule.so").
 */
void RegisterNativeLib(const std::string &library_name);

#if defined(__aarch64__)
inline void *ResolveOEMVeneer(void *target) {
    if (!target) return nullptr;

    auto *insn = static_cast<uint32_t *>(target);

    // Check for 'ldr x17, pc+8' (0x58000051) followed by 'br x17' (0xd61f0220)
    if (insn[0] == 0x58000051 && insn[1] == 0xd61f0220) {
        void *real_target = *reinterpret_cast<void **>(insn + 2);
        LOGW("OEM veneer (x17) detected at {}, resolving to {}", target, real_target);
        return real_target;
    }

    // Check for 'ldr x16, pc+8' (0x58000050) followed by 'br x16' (0xd61f0200)
    if (insn[0] == 0x58000050 && insn[1] == 0xd61f0200) {
        void *real_target = *reinterpret_cast<void **>(insn + 2);
        LOGW("OEM veneer (x16) detected at {}, resolving to {}", target, real_target);
        return real_target;
    }

    return target;
}
#else
inline void *ResolveOEMVeneer(void *target) { return target; }
#endif

/**
 * @brief A wrapper around DobbyHook.
 */
inline int HookInline(void *original, void *replace, void **backup) {
    original = ResolveOEMVeneer(original);

    if constexpr (kIsDebugBuild) {
        Dl_info info;
        if (dladdr(original, &info)) {
            LOGD("Dobby hooking {} ({}) from {} ({})",
                 info.dli_sname ? info.dli_sname : "(unknown symbol)",
                 info.dli_saddr ? info.dli_saddr : original,
                 info.dli_fname ? info.dli_fname : "(unknown file)", info.dli_fbase);
        }
    }
    return DobbyHook(original, reinterpret_cast<dobby_dummy_func_t>(replace),
                     reinterpret_cast<dobby_dummy_func_t *>(backup));
}

/**
 * @brief A wrapper around DobbyDestroy.
 */
inline int UnhookInline(void *original) {
    original = ResolveOEMVeneer(original);

    if constexpr (kIsDebugBuild) {
        Dl_info info;
        if (dladdr(original, &info)) {
            LOGD("Dobby unhooking {} ({}) from {} ({})",
                 info.dli_sname ? info.dli_sname : "(unknown symbol)",
                 info.dli_saddr ? info.dli_saddr : original,
                 info.dli_fname ? info.dli_fname : "(unknown file)", info.dli_fbase);
        }
    }
    return DobbyDestroy(original);
}

}  // namespace vector::native

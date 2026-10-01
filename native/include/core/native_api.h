#pragma once

#include <dlfcn.h>
#if defined(__aarch64__) || defined(__arm__)
#include <shadowhook.h>
#endif
#include <dobby.h>

#include <atomic>
#include <cstdint>
#include <map>
#include <mutex>
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

inline constexpr int kInlineHookBackendDobby = 0;
inline constexpr int kInlineHookBackendShadowHook = 1;

inline std::atomic<int> g_inline_hook_backend{kInlineHookBackendDobby};

inline void SetInlineHookBackend(int backend) {
#if defined(__aarch64__) || defined(__arm__)
    backend =
        backend == kInlineHookBackendShadowHook ? kInlineHookBackendShadowHook : kInlineHookBackendDobby;
#else
    backend = kInlineHookBackendDobby;
#endif
    g_inline_hook_backend.store(backend, std::memory_order_relaxed);
    LOGD("Inline hook backend set to {}",
         backend == kInlineHookBackendShadowHook ? "ShadowHook" : "Dobby");
}

#if defined(__aarch64__) || defined(__arm__)
inline std::once_flag g_shadowhook_init_once;
inline int g_shadowhook_init_result = -1;
inline std::mutex g_shadowhook_mutex;
inline std::map<uintptr_t, void *> g_shadowhook_stubs;

inline bool shadowhookInit() {
    std::call_once(g_shadowhook_init_once, [] {
        g_shadowhook_init_result = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
        if (g_shadowhook_init_result == 0) {
            LOGD("ShadowHook engine ready. (mode: unique)");
        } else {
            LOGE("ShadowHook init failed: %s",
                 shadowhook_to_errmsg(shadowhook_get_init_errno()));
        }
    });
    return g_shadowhook_init_result == 0;
}
#endif

inline void logHookedSymbol(int backend, void *address) {
    if constexpr (kIsDebugBuild) {
        Dl_info info;
        if (dladdr(address, &info)) {
            LOGD("{} hooking {} ({}) from {} ({})",
                 backend == kInlineHookBackendShadowHook ? "ShadowHook" : "Dobby",
                 info.dli_sname ? info.dli_sname : "(unknown symbol)",
                 info.dli_saddr ? info.dli_saddr : address,
                 info.dli_fname ? info.dli_fname : "(unknown file)", info.dli_fbase);
        }
    }
}

inline void logUnhookedSymbol(int backend, void *address) {
    if constexpr (kIsDebugBuild) {
        Dl_info info;
        if (dladdr(address, &info)) {
            LOGD("{} unhooking {} ({}) from {} ({})",
                 backend == kInlineHookBackendShadowHook ? "ShadowHook" : "Dobby",
                 info.dli_sname ? info.dli_sname : "(unknown symbol)",
                 info.dli_saddr ? info.dli_saddr : address,
                 info.dli_fname ? info.dli_fname : "(unknown file)", info.dli_fbase);
        }
    }
}

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

/**
 * @brief A wrapper around the configured inline hook backend.
 */
inline int HookInline(void *original, void *replace, void **backup) {
#if defined(__aarch64__) || defined(__arm__)
    if (g_inline_hook_backend.load(std::memory_order_relaxed) == kInlineHookBackendShadowHook) {
        if (shadowhookInit()) {
            logHookedSymbol(kInlineHookBackendShadowHook, original);
            void *stub = shadowhook_hook_func_addr(original, replace, backup);
            if (stub != nullptr) {
                std::lock_guard<std::mutex> lk(g_shadowhook_mutex);
                g_shadowhook_stubs[reinterpret_cast<uintptr_t>(original)] = stub;
                return 0;
            }
            LOGE("Inline Hook with ShadowHook failed: %s (%p), falling back to Dobby",
                 shadowhook_to_errmsg(shadowhook_get_errno()), original);
        }
    }
#endif
    logHookedSymbol(kInlineHookBackendDobby, original);
    return DobbyHook(original, reinterpret_cast<dobby_dummy_func_t>(replace),
                     reinterpret_cast<dobby_dummy_func_t *>(backup));
}

/**
 * @brief A wrapper around the configured inline hook backend.
 */
inline int UnhookInline(void *original) {
#if defined(__aarch64__) || defined(__arm__)
    if (g_inline_hook_backend.load(std::memory_order_relaxed) == kInlineHookBackendShadowHook) {
        void *stub = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_shadowhook_mutex);
            auto it = g_shadowhook_stubs.find(reinterpret_cast<uintptr_t>(original));
            if (it != g_shadowhook_stubs.end()) {
                stub = it->second;
                g_shadowhook_stubs.erase(it);
            }
        }
        if (stub != nullptr) {
            if (shadowhook_unhook(stub) == 0) {
                logUnhookedSymbol(kInlineHookBackendShadowHook, original);
                return 0;
            }
            LOGE("Inline Unhook with ShadowHook failed: %s (%p)",
                 shadowhook_to_errmsg(shadowhook_get_errno()), original);
            return -1;
        }
    }
#endif
    logUnhookedSymbol(kInlineHookBackendDobby, original);
    return DobbyDestroy(original);
}

}  // namespace vector::native

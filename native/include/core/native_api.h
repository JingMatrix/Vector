#pragma once

#include "common/config.h"
#include "common/logging.h"
#include <dlfcn.h>
#include <dobby.h>
#include <string>
#include <utils/hook_helper.hpp>

/**
 * @file native_api.h
 * @brief Manages the native module ecosystem and provides a stable API for
 * them.
 *
 * This component is responsible for hooking the dynamic library loader
 * (`dlopen`) to detect when registered native modules are loaded. It then
 * provides these modules with a set of function pointers for interacting with
 * the Vector core, primarily for creating native hooks.
 */

namespace vector::native {

// NOTE: The following type definitions form a stable ABI for native modules.
// Do not change them without careful consideration for backward compatibility.

/// Function pointer type for a native hooking implementation.
using HookFunType = int (*)(void *func, void *replace, void **backup);

/// Function pointer type for a native unhooking implementation.
using UnhookFunType = int (*)(void *func);

/// Callback function pointer that modules receive, invoked when any library is
/// loaded.
using NativeOnModuleLoaded = void (*)(const char *name, void *handle);

/**
 * @struct NativeAPIEntries
 * @brief A struct containing function pointers exposed to native modules.
 */
struct NativeAPIEntries {
  uint32_t version;         ///< The version of this API struct.
  HookFunType hookFunc;     ///< Pointer to the function for inline hooking.
  UnhookFunType unhookFunc; ///< Pointer to the function for unhooking.
};

/// The entry point function that native modules must export (`native_init`).
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
 * When a library with a matching filename is loaded via `dlopen`, the runtime
 * will attempt to initialize it as a native module by calling its `native_init`
 * function.
 *
 * @param library_name The filename of the native module's .so file (e.g.,
 * "libmymodule.so").
 */
void RegisterNativeLib(const std::string &library_name);

/**
 * @brief A wrapper around DobbyHook to provide a consistent hooking interface.
 */
inline int HookInline(void *original, void *replace, void **backup) {
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
 * @brief A wrapper around DobbyDestroy to provide a consistent unhooking
 * interface.
 */
inline int UnhookInline(void *original) {
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

} // namespace vector::native

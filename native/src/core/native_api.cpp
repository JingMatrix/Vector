#include "core/native_api.h"

#include <sys/mman.h>

#include <functional>
#include <list>
#include <memory>
#include <mutex>

#include "common/logging.h"
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"

/**
 * @file native_api.cpp
 * @brief Implementation of the native module loading and API provisioning system.
 */

using lsplant::operator""_sym;
/*
 * ===========================================================================================
 * LSPLANT HOOKING DSL (DOMAIN SPECIFIC LANGUAGE) DOCUMENTATION
 * ===========================================================================================
 *
 * This source file utilizes the 'lsplant' library, which implements a C++20 Hooking DSL.
 * Unlike traditional C-style hooking (which relies on void* casting, manual trampolines,
 * and global function pointers), this DSL uses compile-time metaprogramming to ensure
 * type safety and encapsulate hooking logic.
 *
 * -------------------------------------------------------------------------------------------
 * 1. SYNTAX ANATOMY
 * -------------------------------------------------------------------------------------------
 * The hooking syntax follows this pattern:
 *    "SYMBOL_NAME"_sym .hook ->* [] <auto backup> (args...) { ...body... };
 *
 *  A. "SYMBOL_NAME"_sym
 *     - This is a C++ User-Defined Literal (UDL). It converts the string literal into a
 *       compile-time 'Symbol' type.
 *     - For C++ mangled names (common in Android system libs), you must provide the full
 *       mangled signature (e.g., "__dl__Z9do_dlopen...").
 *
 *  B. Multi-Architecture Support (| Operator)
 *     - Android often requires different symbol names for 32-bit (ARM) and 64-bit (ARM64).
 *     - The DSL supports the pipe operator '|' to select the correct symbol at compile time:
 *       ("Sym32"_sym | "Sym64"_sym)
 *
 *  C. .hook ->*
 *     - '.hook' accesses the hook injection mechanism.
 *     - '->*' (Member Pointer Operator) is overloaded to bind the symbol to the lambda.
 *
 *  D. The Template Lambda (The Replacement)
 *     - Syntax: [] <lsplant::Backup auto backup> (Type arg1, Type arg2...) { ... }
 *     - This is a C++20 Template Lambda.
 *     - 'backup': Represents the ORIGINAL function (trampoline).
 *                 You call this to execute the original system logic.
 *     - 'args...': Must match the signature of the target function exactly.
 *
 * -------------------------------------------------------------------------------------------
 * 2. EXAMPLE USAGE
 * -------------------------------------------------------------------------------------------
 * inline static auto my_hook =
 *     "__open"_sym.hook ->* []<auto backup>(const char* path, int flags) {
 *         // 1. Pre-processing (Before original)
 *         LOGD("Opening file: %s", path);
 *
 *         // 2. Call Original (The "Backup")
 *         int result = backup(path, flags);
 *
 *         // 3. Post-processing (After original)
 *         return result;
 *     };
 *
 * -------------------------------------------------------------------------------------------
 * 3. REGISTRATION
 * -------------------------------------------------------------------------------------------
 * Defining the hook variable does not apply it.
 * You must pass the variable to the HookHandler to modify memory: handler(my_hook).
 * ===========================================================================================
 */

namespace vector::native {

namespace {
// Mutex to protect access to the global module lists.
std::mutex g_module_registry_mutex;
// List of callback functions provided by loaded native modules.
std::list<NativeOnModuleLoaded> g_module_loaded_callbacks;
// List of native library filenames that are registered as modules.
std::list<std::string> g_module_native_libs;

// A smart pointer to a memory page that will hold the NativeAPIEntries struct.
std::unique_ptr<void, std::function<void(void *)>> g_api_page(
    mmap(nullptr, 4096, PROT_READ | PROT_WRITE, MAP_ANONYMOUS | MAP_PRIVATE, -1, 0), [](void *ptr) {
        if (ptr != MAP_FAILED) {
            munmap(ptr, 4096);
        }
    });
}  // namespace

// The read-only, statically available Native API entry points for modules.
const NativeAPIEntries *g_native_api_entries = nullptr;

/**
 * @brief Initializes the Native API entries struct and makes it read-only.
 */
void InitializeApiEntries() {
    if (g_api_page.get() == MAP_FAILED) {
        LOGF("Failed to allocate memory for native API entries.");
        LOGD("Release the memory page pointer %p", g_api_page.release());
        return;
    }
    auto *entries = new (g_api_page.get()) NativeAPIEntries{
        .version = 2,
        .hookFunc = &HookInline,
        .unhookFunc = &UnhookInline,
    };
    if (mprotect(g_api_page.get(), 4096, PROT_READ) != 0) {
        PLOGE("Failed to mprotect API page to read-only");
    }
    g_native_api_entries = entries;
    LOGI("Native API entries initialized and protected.");
}

void RegisterNativeLib(const std::string &library_name) {
    static bool is_initialized = []() {
        InitializeApiEntries();
        return InstallNativeAPI(lsplant::InitInfo{
            .inline_hooker =
                [](void *target, void *replacement) {
                    void *backup = nullptr;
                    return HookInline(target, replacement, &backup) == 0 ? backup : nullptr;
                },
            .art_symbol_resolver =
                [](auto symbol) { return ElfSymbolCache::GetLinker()->getSymbAddress(symbol); },
        });
    }();

    if (!is_initialized) {
        LOGE("Cannot register module '{}' because native API failed to initialize.",
             library_name.c_str());
        return;
    }

    std::lock_guard<std::mutex> lock(g_module_registry_mutex);
    g_module_native_libs.push_back(library_name);
    LOGD("Native module library '{}' has been registered.", library_name.c_str());
}

bool HasEnding(std::string_view fullString, std::string_view ending) {
    if (fullString.length() >= ending.length()) {
        return (fullString.compare(fullString.length() - ending.length(), std::string_view::npos,
                                   ending) == 0);
    }
    return false;
}

inline static auto do_dlopen_hook =
    "__dl__Z9do_dlopenPKciPK17android_dlextinfoPKv"_sym.hook->*
    []<lsplant::Backup auto backup>(const char *name, int flags, const void *extinfo,
                                    const void *caller_addr) static -> void * {
    void *handle = backup(name, flags, extinfo, caller_addr);
    const std::string lib_name = (name != nullptr) ? name : "null";
    LOGV("do_dlopen hook triggered for library: '{}'", lib_name.c_str());

    if (handle == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(g_module_registry_mutex);

    for (std::string_view module_lib : g_module_native_libs) {
        if (HasEnding(lib_name, module_lib)) {
            LOGI("Detected registered native module being loaded: '{}'", lib_name.c_str());
            void *init_sym = dlsym(handle, "native_init");
            if (init_sym == nullptr) {
                LOGW("Library '{}' matches a module name but does not export 'native_init'.",
                     lib_name.c_str());
                break;
            }
            auto native_init = reinterpret_cast<NativeInit>(init_sym);
            if (auto callback = native_init(g_native_api_entries)) {
                g_module_loaded_callbacks.push_back(callback);
                LOGI("Initialized native module '{}' and registered its callback.",
                     lib_name.c_str());
            }
            break;
        }
    }

    for (const auto &callback : g_module_loaded_callbacks) {
        callback(name, handle);
    }

    return handle;
};

bool InstallNativeAPI(const lsplant::HookHandler &handler) { return handler(do_dlopen_hook); }

}  // namespace vector::native

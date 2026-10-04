#include "core/native_api.h"

#include <sys/mman.h>

#include <algorithm>
#include <functional>
#include <list>
#include <memory>
#include <mutex>
#include <string_view>
#include <vector>

#include "common/logging.h"
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"

/**
 * @file native_api.cpp
 * @brief Implementation of the native module loading and API provisioning system.
 */

/*
 * ===========================================================================================
 * HOW A MODULE'S NATIVE PART GETS INITIALIZED
 * ===========================================================================================
 *
 * The dynamic loader is the one place every native library a process loads passes through, and it
 * is the same place whether the process runs ART or not. So the whole mechanism hangs off a single
 * inline hook on the linker's `do_dlopen`:
 *
 *   1. A module's declared library names are recorded by RegisterNativeLib.
 *   2. The first such call installs the `do_dlopen` hook, through whichever hook backend is in
 *      effect: Dobby by default, or the engine the runtime supplied through SetHookBackend.
 *   3. When a matching library is loaded, the hook resolves its `native_init`, calls it with the
 *      NativeAPIEntries, and keeps the callback it returns to replay on every later load.
 *
 * None of that needs a JVM, which is what lets the same code serve both an ART process, where the
 * names arrive over JNI, and a HyperOS Rust Runtime process, where they are read from disk by
 * zygisk/src/main/cpp/hyos_runtime.cpp.
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

// The hooking primitives the API hands out and uses for its own interception of the loader. Dobby
// unless something with a better claim on the process replaced it.
HookFunType g_hook_func = &HookInline;
UnhookFunType g_unhook_func = &UnhookInline;

// The trampoline the `do_dlopen` hook calls to reach the real loader. Written once, with the hook.
void *g_original_do_dlopen = nullptr;
bool g_native_api_installed = false;

// Both one-time builds below are reachable from more than one entry point, so each gets its own
// guard rather than a function-local static that only the first caller would share.
std::once_flag g_api_entries_once;
std::once_flag g_install_once;

using DoDlopenFn = void *(*)(const char *, int, const void *, const void *);

// `do_dlopen` under its exported, mangled linker name: the linker exports nothing unmangled, and
// this is the entry every load in the process funnels through - libc's `dlopen`, the Android
// extensions, and the loader's own `android_dlopen_ext`.
constexpr auto kDoDlopenSymbol = "__dl__Z9do_dlopenPKciPK17android_dlextinfoPKv";
}  // namespace

// The read-only, statically available Native API entry points for modules.
const NativeAPIEntries *g_native_api_entries = nullptr;

/**
 * @brief Initializes the Native API entries struct and makes it read-only.
 */
void InitializeApiEntries() {
    std::call_once(g_api_entries_once, []() {
        if (g_api_page.get() == MAP_FAILED) {
            LOGF("Failed to allocate memory for native API entries.");
            LOGD("Release the memory page pointer %p", g_api_page.release());
            return;
        }
        auto *entries = new (g_api_page.get())
            NativeAPIEntries{.version = 2, .hookFunc = g_hook_func, .unhookFunc = g_unhook_func};
        if (mprotect(g_api_page.get(), 4096, PROT_READ) != 0) {
            PLOGE("Failed to mprotect API page to read-only");
        }
        g_native_api_entries = entries;
        LOGI("Native API entries initialized and protected.");
    });
}

const NativeAPIEntries *GetNativeAPIEntries() {
    InitializeApiEntries();
    return g_native_api_entries;
}

void SetHookBackend(HookFunType hook, UnhookFunType unhook) {
    if (hook == nullptr || unhook == nullptr) {
        LOGE("Refusing a null hook backend; keeping the default.");
        return;
    }
    if (g_native_api_entries != nullptr) {
        // The entries page has been handed to modules and made read-only. Swapping the primitives
        // now would leave them holding the old pair with no way to notice.
        LOGE("The hook backend must be set before the native API is first used; ignoring.");
        return;
    }
    g_hook_func = hook;
    g_unhook_func = unhook;
    LOGD("Native API hook backend replaced.");
}

namespace {

bool HasEnding(std::string_view fullString, std::string_view ending) {
    if (fullString.length() >= ending.length()) {
        return (fullString.compare(fullString.length() - ending.length(), std::string_view::npos,
                                   ending) == 0);
    }
    return false;
}

/**
 * @brief Hands a newly loaded library to the modules that asked for it, then to every module that
 *        wants to see every load.
 *
 * The module code is called with no lock held. `native_init`, and the callbacks it returns, belong
 * to somebody else and are free to call `dlopen` -- which re-enters this function on the same
 * thread. Holding the registry mutex across those calls would turn that into a self-deadlock, and
 * it is not a hypothetical: a module whose whole purpose is native hooking reaches for the loaded
 * libraries by name.
 */
void OnLibraryLoaded(const char *name, void *handle) {
    const std::string lib_name = (name != nullptr) ? name : "null";
    LOGV("do_dlopen hook triggered for library: '{}'", lib_name.c_str());

    if (handle == nullptr) return;

    bool matches_a_module = false;
    std::vector<NativeOnModuleLoaded> callbacks;
    {
        std::lock_guard<std::mutex> lock(g_module_registry_mutex);
        for (std::string_view module_lib : g_module_native_libs) {
            if (HasEnding(lib_name, module_lib)) {
                matches_a_module = true;
                break;
            }
        }
        callbacks.assign(g_module_loaded_callbacks.begin(), g_module_loaded_callbacks.end());
    }

    if (matches_a_module) {
        LOGI("Detected registered native module being loaded: '{}'", lib_name.c_str());
        void *init_sym = dlsym(handle, "native_init");
        if (init_sym == nullptr) {
            LOGW("Library '{}' matches a module name but does not export 'native_init'.",
                 lib_name.c_str());
        } else {
            auto native_init = reinterpret_cast<NativeInit>(init_sym);
            if (auto callback = native_init(g_native_api_entries)) {
                std::lock_guard<std::mutex> lock(g_module_registry_mutex);
                g_module_loaded_callbacks.push_back(callback);
                LOGI("Initialized native module '{}' and registered its callback.",
                     lib_name.c_str());
            }
        }
    }

    for (const auto &callback : callbacks) {
        callback(name, handle);
    }
}

void *DoDlopenHook(const char *name, int flags, const void *extinfo, const void *caller_addr) {
    auto original = reinterpret_cast<DoDlopenFn>(g_original_do_dlopen);
    if (original == nullptr) {
        // Not reachable in practice: the trampoline is stored before the hook can ever be reached.
        LOGE("The do_dlopen hook ran without a trampoline; refusing to guess.");
        return nullptr;
    }
    void *handle = original(name, flags, extinfo, caller_addr);
    OnLibraryLoaded(name, handle);
    return handle;
}

}  // namespace

bool InstallNativeAPI() {
    std::call_once(g_install_once, []() {
        auto *linker = ElfSymbolCache::GetLinker();
        if (linker == nullptr) {
            LOGE("Cannot install the native API: the linker image could not be resolved.");
            return;
        }
        void *target = linker->getSymbAddress(kDoDlopenSymbol);
        if (target == nullptr) {
            LOGE("Cannot install the native API: {} is not exported by {}.", kDoDlopenSymbol,
                 linker->GetPath().c_str());
            return;
        }

        void *original = nullptr;
        if (g_hook_func(target, reinterpret_cast<void *>(&DoDlopenHook), &original) != 0 ||
            original == nullptr) {
            LOGE("Cannot install the native API: hooking {} at {} failed.", kDoDlopenSymbol,
                 target);
            return;
        }

        g_original_do_dlopen = original;
        g_native_api_installed = true;
        LOGI("Native API installed: {} hooked at {} (trampoline {}).", kDoDlopenSymbol, target,
             original);
    });
    return g_native_api_installed;
}

void RegisterNativeLib(const std::string &library_name) {
    static bool is_initialized = []() {
        InitializeApiEntries();
        return InstallNativeAPI();
    }();

    if (!is_initialized) {
        LOGE("Cannot register module '{}' because native API failed to initialize.",
             library_name.c_str());
        return;
    }

    std::lock_guard<std::mutex> lock(g_module_registry_mutex);
    // The list is walked on every dlopen in the process and never shrinks - there is no
    // unregistration, and hot reload records a module's names again for each new generation - so
    // without this it grows without bound and every dlopen pays for the duplicates.
    if (std::find(g_module_native_libs.begin(), g_module_native_libs.end(), library_name) !=
        g_module_native_libs.end()) {
        LOGD("Native module library '{}' is already registered.", library_name.c_str());
        return;
    }
    g_module_native_libs.push_back(library_name);
    LOGD("Native module library '{}' has been registered.", library_name.c_str());
}

}  // namespace vector::native

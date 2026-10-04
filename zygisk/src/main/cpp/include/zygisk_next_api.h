#pragma once

#include <inttypes.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ZYGISK_NEXT_API_VERSION 4
#define ZYGISK_NEXT_HYOS_API_VERSION 1

#define ZN_SUCCESS 0
#define ZN_FAILED 1

struct ZnSymbolResolver;
struct ZygiskNextRuntime;

struct ZygiskNextAPI {
    // Hook API

    // Do plt hook at symbol specified by the param `symbol` of library
    // specified by the param `base_addr` The plt address of `symbol` in the
    // library will be replaced with hook_handler, and its original value will
    // be put to the address specified by `original` (can be null). You can use
    // this api to do caller-oriented hook If you want to unhook, please call
    // this function with hook_handler = original If hook succeed, returns
    // ZN_SUCCESS, otherwise ZN_FAILED
    int (*pltHook)(void *base_addr, const char *symbol, void *hook_handler, void **original);

    // Do inline hook at the address specified by `target`, replace it with a
    // new function specified by `addr`, and the param `original` receives the
    // address of original function. You can use this api to achieve a global
    // hook in current process. In the current implementation , an address can
    // only hook once, so the module can't hook an address which is already
    // hooked by an another module, except that the module unhooked it. If
    // hooking succeed, returns ZN_SUCCESS, otherwise ZN_FAILED
    int (*inlineHook)(void *target, void *addr, void **original);

    // Unhook the address which is formerly hooked.
    // If hook succeed, returns ZN_SUCCESS, otherwise ZN_FAILED
    int (*inlineUnhook)(void *target);

    // Symbol Resolver API

    // Obtain a new ZnSymbolResolver object
    // `path` is required, which specifies the path of library to resolve. It
    // can be an absolute path or just the file name of library, e.g.
    // /system/lib64/libc.so or libc.so . If `base_addr` is non-zero, it will be
    // used as the base address of the library. Otherwise, Zygisk Next will try
    // to find out the base address of the specified library in this process. If
    // succeed, it returns a valid pointer to the symbol resolver, otherwise
    // nullptr is returned.
    struct ZnSymbolResolver *(*newSymbolResolver)(const char *path, void *base_addr);

    // Release the ZnSymbolResolver object pointed by `resolver`.
    void (*freeSymbolResolver)(struct ZnSymbolResolver *resolver);

    // Retrieve the base address of the library of the resolver image in the
    // process.
    void *(*getBaseAddress)(struct ZnSymbolResolver *resolver);

    // Lookup the address of symbol by name or prefix (if `prefix` is true)
    // If the symbol exists, the function returns its address, otherwise returns
    // nullptr. If `size` is not nullptr, the size of the symbol will be put to
    // *size . In the current implementation, gnu_debugdata resolution is
    // supported.
    void *(*symbolLookup)(struct ZnSymbolResolver *resolver, const char *name, bool prefix,
                          size_t *size);

    // Walk through the symbol table of the library, the callback will receive
    // the name, the address, and the size of each symbol. Returning false in
    // the callback means stop the walking.
    void (*forEachSymbols)(struct ZnSymbolResolver *resolver,
                           bool (*callback)(const char *name, void *addr, size_t size, void *data),
                           void *data);

    // Companion API

    // Create a unix sock stream connection to your declared companion process.
    // The value of `handle` is the `self_handle` which you've received from
    // onModuleLoaded. On success, it returns the file descriptor refer to the
    // socket, otherwise -1 is returned. Please close this file descriptor by
    // yourself.
    int (*connectCompanion)(void *handle);

    // Return the runtime-specific API for the current process, or null if the
    // process does not expose a supported runtime. The returned object remains
    // valid for the lifetime of the process. Inspect its type before passing a
    // runtime-specific module structure to registerModule.
    const struct ZygiskNextRuntime *(*getRuntime)(void);
};

// Callbacks of an injected library
struct ZygiskNextModule {
    // Please fill this with the target version of your module, e.g.
    // ZYGISK_NEXT_API_VERSION
    int target_api_version;

    // This callback will be called after all needed library of the main
    // executable are loaded, and before the entry (i.e. `main`) of the main
    // executable is called.
    void (*onModuleLoaded)(void *self_handle, const struct ZygiskNextAPI *api);
};

enum ZygiskNextRuntimeType {
    ZN_RUNTIME_HYOS = 1,
};

// Read-only process specialization information for applications forked by
// /system_ext/bin/hyos_spawner. The callback runs after the uid, gid,
// supplementary groups, and SELinux app context have been applied. The string
// pointers are non-null and only valid for the duration of the callback.
struct ZnHyosAppSpecializeArgs {
    const char *process_name;
    const char *package_name;
    const char *se_info;
};

// HyperOS Rust Runtime callbacks registered through
// ZygiskNextRuntime.registerModule.
struct ZygiskNextHyosModule {
    int target_api_version;
    void (*onAppSpecialized)(const struct ZnHyosAppSpecializeArgs *args);
};

// Runtime-specific API returned by getRuntime. The module structure accepted
// by registerModule is selected by type. The runtime copies the structure
// before registerModule returns.
struct ZygiskNextRuntime {
    enum ZygiskNextRuntimeType type;
    int api_version;
    int (*registerModule)(const void *module);
};

// Callbacks of a companion library
struct ZygiskNextCompanionModule {
    int target_api_version;

    void (*onCompanionLoaded)();

    // This callback will be called when your Zygisk Next module is trying to
    // establish a connection with your companion module, i.e.
    // `connectCompanion` is called. The `fd` param will be a unix sock stream
    // file descriptor. Please close this file descriptor after use by yourself.
    void (*onModuleConnected)(int fd);
};

// Please define your `zn_module` in your source file.
extern __attribute__((visibility("default"), unused)) struct ZygiskNextModule zn_module;
extern __attribute__((visibility("default"),
                      unused)) struct ZygiskNextCompanionModule zn_companion_module;

#ifdef __cplusplus
}
#endif


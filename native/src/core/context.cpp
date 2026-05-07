#include "core/context.h"

#include "core/config_bridge.h"
#include "jni/jni_hooks.h"

namespace vector::native {

// Instantiate the singleton pointers for Context and ConfigBridge.
std::unique_ptr<Context> Context::instance_;
std::unique_ptr<ConfigBridge> ConfigBridge::instance_;

Context *Context::GetInstance() { return instance_.get(); }

std::unique_ptr<Context> Context::ReleaseInstance() { return std::move(instance_); }

Context::PreloadedDex::PreloadedDex(int fd, size_t size) {
    LOGD("Mapping PreloadedDex: fd={}, size={}", fd, size);
    void *addr = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);

    if (addr != MAP_FAILED) {
        addr_ = addr;
        size_ = size;
    } else {
        addr_ = nullptr;
        size_ = 0;
        PLOGE("Failed to mmap dex file");
    }
}

Context::PreloadedDex::~PreloadedDex() {
    if (addr_ && size_ > 0) {
        munmap(addr_, size_);
    }
}

void Context::InitArtHooker(JNIEnv *env, const lsplant::InitInfo &initInfo) {
    if (!lsplant::Init(env, initInfo)) {
        LOGE("Failed to initialize LSPlant hooking framework.");
    }
}

void Context::InitHooks(JNIEnv *env) {
    // -------------------------------------------------------------------------
    // DEX Privilege Elevation
    // -------------------------------------------------------------------------
    // We traverse the DexPathList of the injected ClassLoader to access the
    // underlying 'mCookie' for every loaded DEX file.
    // The cookie provides a handle to the native C++ DexFile object in ART memory.

    // Retrieve the DexPathList object (holds the array of DEX elements).
    auto path_list = lsplant::JNI_GetObjectFieldOf(env, inject_class_loader_, "pathList",
                                                   "Ldalvik/system/DexPathList;");
    if (!path_list) {
        LOGE("InitHooks: Failed to retrieve 'pathList' from the injected class loader.");
        return;
    }

    // Retrieve the 'dexElements' array, which contains the actual DEX files and resources.
    auto elements = lsplant::JNI_Cast<jobjectArray>(lsplant::JNI_GetObjectFieldOf(
        env, path_list, "dexElements", "[Ldalvik/system/DexPathList$Element;"));
    if (!elements) {
        LOGE("InitHooks: Failed to retrieve 'dexElements' from DexPathList.");
        return;
    }

    // Iterate over every element in the DexPathList to process individual DEX files.
    for (auto &element : elements) {
        if (element.get() == nullptr) continue;

        // extract the DexFile Java object from the element.
        auto java_dex_file =
            lsplant::JNI_GetObjectFieldOf(env, element, "dexFile", "Ldalvik/system/DexFile;");
        if (!java_dex_file) {
            // Not all elements are guaranteed to have a valid DexFile
            // (e.g., resource-only elements).
            LOGW("InitHooks: Encountered a dexElement with no associated DexFile.");
            continue;
        }

        // Retrieve the 'mCookie'. In ART, this field stores the pointer (as a long or object)
        // to the internal native DexFile structure.
        auto cookie =
            lsplant::JNI_GetObjectFieldOf(env, java_dex_file, "mCookie", "Ljava/lang/Object;");
        if (!cookie) {
            LOGW("InitHooks: Could not retrieve 'mCookie' (native handle) from DexFile.");
            continue;
        }

        // Attempt to modify the internal ART flags for this DEX file.
        // This effectively whitelists the DEX file, treating it as if it were part of
        // the BootClassPath, thereby bypassing Hidden API enforcement policies.
        if (lsplant::MakeDexFileTrusted(env, cookie.get())) {
            LOGD("InitHooks: Successfully elevated trust privileges for DexFile.");
        } else {
            LOGW("InitHooks: Failed to elevate trust privileges for DexFile.");
        }
    }

    // -------------------------------------------------------------------------
    // JNI Bridge Registration
    // -------------------------------------------------------------------------
    jni::RegisterResourcesHook(env);
    jni::RegisterHookBridge(env);
    jni::RegisterNativeApiBridge(env);
}

lsplant::ScopedLocalRef<jclass> Context::FindClassFromLoader(JNIEnv *env, jobject class_loader,
                                                             std::string_view class_name) {
    if (class_loader == nullptr) {
        return {env, nullptr};
    }
    static const auto dex_class_loader_class =
        lsplant::JNI_NewGlobalRef(env, lsplant::JNI_FindClass(env, "dalvik/system/DexClassLoader"));
    static jmethodID load_class_mid = lsplant::JNI_GetMethodID(
        env, dex_class_loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!load_class_mid) {
        load_class_mid = lsplant::JNI_GetMethodID(env, dex_class_loader_class, "findClass",
                                                  "(Ljava/lang/String;)Ljava/lang/Class;");
    }

    if (load_class_mid) {
        auto name_str = lsplant::JNI_NewStringUTF(env, class_name.data());
        auto result = lsplant::JNI_CallObjectMethod(env, class_loader, load_class_mid, name_str);
        if (result) {
            return result;
        }
    } else {
        LOGE("Could not find DexClassLoader.loadClass / .findClass method ID.");
    }

    // Log clearly on failure.
    if (env->ExceptionCheck()) {
        env->ExceptionClear();  // Clear exception to prevent app crash
    }
    LOGE("Class '{}' not found using the provided class loader.", class_name.data());
    return {env, nullptr};
}

}  // namespace vector::native

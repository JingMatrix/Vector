#include "core/context.h"
#include "core/config_bridge.h"
#include "jni/jni_hooks.h"

namespace vector::native {

// Instantiate the singleton pointers for Context and ConfigBridge.
std::unique_ptr<Context> Context::instance_;
std::unique_ptr<ConfigBridge> ConfigBridge::instance_;

Context *Context::GetInstance() { return instance_.get(); }

std::unique_ptr<Context> Context::ReleaseInstance() {
  return std::move(instance_);
}

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
  // Makes the framework's own DEX files "trusted" by the ART runtime.
  auto path_list = lsplant::JNI_GetObjectFieldOf(
      env, inject_class_loader_, "pathList", "Ldalvik/system/DexPathList;");
  if (!path_list) {
    LOGE("Failed to get DexPathList from class loader.");
    return;
  }

  auto elements = lsplant::JNI_Cast<jobjectArray>(lsplant::JNI_GetObjectFieldOf(
      env, path_list, "dexElements", "[Ldalvik/system/DexPathList$Element;"));
  if (!elements) {
    LOGE("Failed to get dexElements from DexPathList.");
    return;
  }

  for (auto &element : elements) {
    if (element.get() == nullptr)
      continue;
    auto java_dex_file = lsplant::JNI_GetObjectFieldOf(
        env, element, "dexFile", "Ldalvik/system/DexFile;");
    if (!java_dex_file) {
      LOGW("Could not get DexFile from a dexElement.");
      continue;
    }

    auto cookie = lsplant::JNI_GetObjectFieldOf(env, java_dex_file, "mCookie",
                                                "Ljava/lang/Object;");
    if (!cookie) {
      LOGW("Could not get mCookie from a DexFile instance.");
      continue;
    }

    if (lsplant::MakeDexFileTrusted(env, cookie.get())) {
      LOGD("Successfully made a DexFile trusted.");
    } else {
      LOGW("Failed to make a DexFile trusted.");
    }
  }

  // Register all the JNI bridges that expose native functionality to Java.
  jni::RegisterResourcesHook(env);
  jni::RegisterHookBridge(env);
  jni::RegisterNativeApiBridge(env);
  jni::RegisterDexParserBridge(env);
}

lsplant::ScopedLocalRef<jclass>
Context::FindClassFromLoader(JNIEnv *env, jobject class_loader,
                             std::string_view class_name) {
  if (class_loader == nullptr) {
    return {env, nullptr};
  }
  static const auto dex_class_loader_class = lsplant::JNI_NewGlobalRef(
      env, lsplant::JNI_FindClass(env, "dalvik/system/DexClassLoader"));
  static jmethodID load_class_mid =
      lsplant::JNI_GetMethodID(env, dex_class_loader_class, "loadClass",
                               "(Ljava/lang/String;)Ljava/lang/Class;");
  if (!load_class_mid) {
    load_class_mid =
        lsplant::JNI_GetMethodID(env, dex_class_loader_class, "findClass",
                                 "(Ljava/lang/String;)Ljava/lang/Class;");
  }

  if (load_class_mid) {
    auto name_str = lsplant::JNI_NewStringUTF(env, class_name.data());
    auto result = lsplant::JNI_CallObjectMethod(env, class_loader,
                                                load_class_mid, name_str);
    if (result) {
      return result;
    }
  } else {
    LOGE("Could not find DexClassLoader.loadClass / .findClass method ID.");
  }

  // Log clearly on failure.
  if (env->ExceptionCheck()) {
    env->ExceptionClear(); // Clear exception to prevent app crash
  }
  LOGE("Class '{}' not found using the provided class loader.",
       class_name.data());
  return {env, nullptr};
}

} // namespace vector::native

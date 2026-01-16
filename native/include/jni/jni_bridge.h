#pragma once

#include "common/logging.h"
#include "common/utils.h"
#include "core/config_bridge.h"
#include "core/context.h"
#include <string>

/**
 * @file jni_bridge.h
 * @brief Provides essential macros and helper functions for creating JNI
 * bridges.
 *
 * This file standardizes the process of defining and registering JNI native
 * methods, making the bridge code cleaner and less error-prone.
 */

namespace vector::native::jni {

/**
 * @brief A helper function to get the obfuscated native bridge class signature
 * prefix.
 *
 * It reads the obfuscation map to find the correct, potentially obfuscated,
 * package name for the native bridge classes.
 *
 * @return The JNI signature prefix (e.g., "org/matrix/vector/nativebridge/").
 */
inline std::string GetNativeBridgeSignature() {
  auto *bridge = ConfigBridge::GetInstance();
  if (bridge) {
    const auto &obfs_map = bridge->obfuscation_map();
    // The key must match what the Java build script places in the map.
    auto it = obfs_map.find("org.matrix.vector.nativebridge.");
    if (it != obfs_map.end()) {
      return it->second;
    }
  }
  // Fallback or default value if not found.
  return "org/matrix/vector/nativebridge/";
}

/**
 * @brief Internal implementation for registering native methods.
 *
 * Finds the target class using the framework's class loader and calls JNI's
 * RegisterNatives.
 */
[[gnu::always_inline]]
inline bool RegisterNativeMethodsInternal(JNIEnv *env,
                                          std::string_view class_name,
                                          const JNINativeMethod *methods,
                                          jint method_count) {
  auto *context = Context::GetInstance();
  if (!context) {
    LOGF("Cannot register natives for '{}', Context is null.",
         class_name.data());
    return false;
  }
  auto clazz = context->FindClassFromCurrentLoader(env, class_name);
  if (clazz.get() == nullptr) {
    LOGF("JNI class not found: %s", class_name.data());
    return false;
  }
  return env->RegisterNatives(clazz.get(), methods, method_count) == JNI_OK;
}

// A helper cast for the native method function pointers.
#define VECTOR_JNI_CAST(to) reinterpret_cast<to>

/**
 * @def VECTOR_NATIVE_METHOD(className, functionName, signature)
 * @brief Defines a JNINativeMethod entry.
 *
 * This macro constructs a JNINativeMethod struct, automatically creating the
 * mangled C-style function name that JNI expects.
 *
 * @param className The simple name of the Java class (e.g., "HookBridge").
 * @param functionName The name of the Java method (e.g., "hookMethod").
 * @param signature The JNI signature of the method (e.g., "(I)V").
 */
#define VECTOR_NATIVE_METHOD(className, functionName, signature)               \
  {#functionName, signature,                                                   \
   VECTOR_JNI_CAST(void *)(                                                    \
       Java_org_matrix_vector_nativebridge_##className##_##functionName)}

/**
 * @def JNI_START
 * @brief Defines the standard first two arguments for any JNI native method
 * implementation.
 */
#define JNI_START [[maybe_unused]] JNIEnv *env, [[maybe_unused]] jclass clazz

/**
 * @def VECTOR_DEF_NATIVE_METHOD(ret, className, functionName, ...)
 * @brief Defines the function signature for a JNI native method implementation.
 *
 * This macro creates the full C++ function definition with the correct JNI
 * name-mangling convention.
 */
#define VECTOR_DEF_NATIVE_METHOD(ret, className, functionName, ...)            \
  extern "C" JNIEXPORT ret JNICALL                                             \
      Java_org_matrix_vector_nativebridge_##className##_##functionName(         \
          JNI_START, ##__VA_ARGS__)

/**
 * @def REGISTER_VECTOR_NATIVE_METHODS(class_name)
 * @brief Registers all methods defined in the `gMethods` array for a given
 * class.
 *
 * This is the final step in linking the C++ implementations to the Java native
 * methods.
 */
#define REGISTER_VECTOR_NATIVE_METHODS(class_name)                             \
  RegisterNativeMethodsInternal(env, GetNativeBridgeSignature() + #class_name, \
                                gMethods, ArraySize(gMethods))

} // namespace vector::native::jni

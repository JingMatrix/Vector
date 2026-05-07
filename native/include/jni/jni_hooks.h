#pragma once

#include <jni.h>

/**
 * @file jni_hooks.h
 * @brief Declares the registration functions for all JNI bridge modules.
 */

namespace vector::native::jni {

/// Registers the JNI methods for the HookBridge.
void RegisterHookBridge(JNIEnv *env);

/// Registers the JNI methods for the NativeApiBridge.
void RegisterNativeApiBridge(JNIEnv *env);

/// Registers the JNI methods for the ResourcesHook bridge.
void RegisterResourcesHook(JNIEnv *env);

}  // namespace vector::native::jni

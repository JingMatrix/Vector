#pragma once

#include <jni.h>

/**
 * @file jni_hooks.h
 * @brief Declares the registration functions for all JNI bridge modules.
 *
 * This provides a single header for the core Context to include, allowing it
 * to initialize all JNI functionality without needing to know about the
 * implementation details of each bridge.
 */

namespace vector::native::jni {

/// Registers the JNI methods for the DexParserBridge.
void RegisterDexParserBridge(JNIEnv *env);

/// Registers the JNI methods for the HookBridge.
void RegisterHookBridge(JNIEnv *env);

/// Registers the JNI methods for the NativeApiBridge.
void RegisterNativeApiBridge(JNIEnv *env);

/// Registers the JNI methods for the ResourcesHook bridge.
void RegisterResourcesHook(JNIEnv *env);

} // namespace vector::native::jni

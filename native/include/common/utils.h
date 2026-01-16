#pragma once

#include <algorithm>
#include <cstddef>
#include <string>
#include <sys/system_properties.h>

/**
 * @file utils.h
 * @brief Provides miscellaneous utility functions and templates for the native
 * library.
 */

namespace vector::native {

/**
 * @brief Retrieves the Android API level of the current device.
 *
 * This function reads system properties "ro.build.version.sdk" and
 * "ro.build.version.preview_sdk" to determine the API level. The result is
 * cached for subsequent calls.
 *
 * @return The integer Android API level.
 */
[[nodiscard]] inline int32_t GetAndroidApiLevel() {
  // Caches the API level in a static variable for efficiency.
  static const int32_t api_level = []() {
    char prop_value[PROP_VALUE_MAX];
    __system_property_get("ro.build.version.sdk", prop_value);
    int base = atoi(prop_value);
    if (base > 0) {
      __system_property_get("ro.build.version.preview_sdk", prop_value);
      int preview = atoi(prop_value);
      return base + preview;
    }
    return 0; // Should not happen on a real device.
  }();
  return api_level;
}

/**
 * @brief Converts a Java class name (dot-separated) to a JNI signature format.
 *
 * Example: "java.lang.String" -> "Ljava/lang/String;"
 * Note: This implementation only prepends 'L' and does not append ';'.
 * The JNI functions that consume this format are often flexible.
 *
 * @param className The dot-separated Java class name.
 * @return The class name in JNI format (e.g., "Ljava/lang/Object").
 */
[[nodiscard]] inline std::string JavaNameToSignature(std::string className) {
  std::replace(className.begin(), className.end(), '.', '/');
  return "L" + className;
}

/**
 * @brief Returns the number of elements in a statically-allocated C-style
 * array.
 *
 * This is a compile-time constant. Attempting to use this on a pointer will
 * result in a compilation error, preventing common mistakes.
 *
 * @tparam T The type of the array elements.
 * @tparam N The size of the array.
 * @param arr A reference to the array.
 * @return The number of elements in the array.
 */
template <typename T, size_t N>
[[nodiscard]] constexpr inline size_t ArraySize(T (&)[N]) {
  return N;
}

} // namespace vector::native

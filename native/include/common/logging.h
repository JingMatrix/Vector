#pragma once

#include <android/log.h>
#include <array>
#include <fmt/format.h>

/**
 * @file logging.h
 * @brief Provides a lightweight, high-performance logging framework for
 * Android.
 *
 * This file defines a set of macros (LOGV, LOGD, LOGI, LOGW, LOGE, LOGF) for
 * logging messages that will appear in Android's logcat. Debug and Verbose logs
 * are automatically compiled out in release builds (when NDEBUG is defined). It
 * uses the {fmt} library for safe and efficient message formatting.
 */

/// The tag used for all log messages from this library.
#ifndef LOG_TAG
#define LOG_TAG "VectorNative"
#endif

/**
 * @def LOGV(fmt, ...)
 * @brief Logs a verbose message. Compiled out in release builds.
 * Includes file, line, and function information.
 */

/**
 * @def LOGD(fmt, ...)
 * @brief Logs a debug message. Compiled out in release builds.
 * Includes file, line, and function information.
 */

/**
 * @def LOGI(fmt, ...)
 * @brief Logs an informational message.
 */

/**
 * @def LOGW(fmt, ...)
 * @brief Logs a warning message.
 */

/**
 * @def LOGE(fmt, ...)
 * @brief Logs an error message.
 */

/**
 * @def LOGF(fmt, ...)
 * @brief Logs a fatal error message.
 */

/**
 * @def PLOGE(fmt, ...)
 * @brief Logs an error message and appends the string representation of the
 * current `errno` value.
 */

#ifdef LOG_DISABLED
#define LOGV(...) ((void)0)
#define LOGD(...) ((void)0)
#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
#define LOGE(...) ((void)0)
#define LOGF(...) ((void)0)
#define PLOGE(...) ((void)0)
#else

namespace vector::native::detail {
template <typename... T>
inline void LogToAndroid(int prio, const char *tag,
                                   fmt::format_string<T...> fmt, T &&...args) {
  // Using a stack-allocated buffer for performance.
  std::array<char, 1024> buf{};
  // format_to_n is safe against buffer overflows.
  auto result = fmt::format_to_n(buf.data(), buf.size() - 1, fmt,
                                 std::forward<T>(args)...);
  buf[result.size] = '\0';
  __android_log_write(prio, tag, buf.data());
}
} // namespace vector::native::detail

#ifndef NDEBUG
#define LOGV(fmt, ...)                                                         \
  ::vector::native::detail::LogToAndroid(                                      \
      ANDROID_LOG_VERBOSE, LOG_TAG, "{}:{} ({}): " fmt, __FILE_NAME__,         \
      __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(, ) __VA_ARGS__)
#define LOGD(fmt, ...)                                                         \
  ::vector::native::detail::LogToAndroid(                                      \
      ANDROID_LOG_DEBUG, LOG_TAG, "{}:{} ({}): " fmt, __FILE_NAME__, __LINE__, \
      __PRETTY_FUNCTION__ __VA_OPT__(, ) __VA_ARGS__)
#else
#define LOGV(...) ((void)0)
#define LOGD(...) ((void)0)
#endif

#define LOGI(fmt, ...)                                                         \
  ::vector::native::detail::LogToAndroid(ANDROID_LOG_INFO, LOG_TAG,            \
                                         fmt __VA_OPT__(, ) __VA_ARGS__)
#define LOGW(fmt, ...)                                                         \
  ::vector::native::detail::LogToAndroid(ANDROID_LOG_WARN, LOG_TAG,            \
                                         fmt __VA_OPT__(, ) __VA_ARGS__)
#define LOGE(fmt, ...)                                                         \
  ::vector::native::detail::LogToAndroid(ANDROID_LOG_ERROR, LOG_TAG,           \
                                         fmt __VA_OPT__(, ) __VA_ARGS__)
#define LOGF(fmt, ...)                                                         \
  ::vector::native::detail::LogToAndroid(ANDROID_LOG_FATAL, LOG_TAG,           \
                                         fmt __VA_OPT__(, ) __VA_ARGS__)
#define PLOGE(fmt, ...)                                                        \
  LOGE(fmt " failed with error {}: {}", ##__VA_ARGS__, errno, strerror(errno))

#endif // LOG_DISABLED

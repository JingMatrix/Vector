#pragma once

/**
 * @file config.h
 * @brief Compile-time constants, version information, and platform-specific configurations.
 */

namespace vector::native {

[[nodiscard]] constexpr bool IsDebugBuild() {
#ifdef NDEBUG
    return false;
#else
    return true;
#endif
}

/// A compile-time constant indicating if this is a debug build.
inline constexpr bool kIsDebugBuild = IsDebugBuild();

/**
 * @def LP_SELECT(lp32, lp64)
 * @brief A preprocessor macro to select a value based on the architecture.
 * @param lp32 The value to use on 32-bit platforms.
 * @param lp64 The value to use on 64-bit platforms.
 */
#if defined(__LP64__)
#define LP_SELECT(lp32, lp64) lp64
#else
#define LP_SELECT(lp32, lp64) lp32
#endif

/// The filename of the core Android Runtime (ART) library.
inline constexpr auto kArtLibraryName = "libart.so";

/// The filename of the Android Binder library.
inline constexpr auto kBinderLibraryName = "libbinder.so";

/// The filename of the Android Framework library.
inline constexpr auto kFrameworkLibraryName = "libandroidfw.so";

/// The path to the dynamic linker.
inline constexpr auto kLinkerPath = "/linker";

/// The version code of the library, populated by the build system.
const int kVersionCode = VERSION_CODE;

// Stringize the VERSION_NAME token so the build system can pass it as a bare
// token (e.g. -DVERSION_NAME=2.0) without quoting, which is fragile across
// Windows / Unix toolchains. Macros are not namespace members, so these must
// live at file scope.
#define VECTOR_STRINGIZE(x) #x
#define VECTOR_STRINGIZE_TOKEN(x) VECTOR_STRINGIZE(x)

/// The version name of the library, populated by the build system.
const char *const kVersionName = VECTOR_STRINGIZE_TOKEN(VERSION_NAME);

}  // namespace vector::native

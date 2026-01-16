#pragma once

/**
 * @file symbol_cache.h
 * @brief Provides a thread-safe, lazy-initialized cache for commonly used
 * ElfImage objects.
 *
 * This avoids the cost of repeatedly parsing the ELF files for libart,
 * libbinder, and the linker during runtime.
 */

namespace vector::native {

// Forward declaration
class ElfImage;

/**
 * @class ElfSymbolCache
 * @brief A singleton cache for frequently accessed system library ELF images.
 *
 * All methods are static and guarantee thread-safe, one-time initialization
 * of the underlying ElfImage objects.
 */
class ElfSymbolCache {
public:
  /**
   * @brief Gets the cached ElfImage for the ART library (libart.so).
   * @return A const pointer to the ElfImage, or nullptr if it could not be
   * loaded.
   */
  static const ElfImage *GetArt();

  /**
   * @brief Gets the cached ElfImage for the Binder library (libbinder.so).
   * @return A const pointer to the ElfImage, or nullptr if it could not be
   * loaded.
   */
  static const ElfImage *GetLibBinder();

  /**
   * @brief Gets the cached ElfImage for the dynamic linker.
   * @return A const pointer to the ElfImage, or nullptr if it could not be
   * loaded.
   */
  static const ElfImage *GetLinker();

  /**
   * @brief Clears the cache for a specific ElfImage object.
   *
   * This function is thread-safe. If the provided pointer matches one of the
   * cached images, that specific cache entry will be cleared, forcing a reload
   * on the next `Get...()` call for that library. If the pointer does not
   * match any cached image, this function does nothing.
   *
   * @param image_to_clear A pointer to the cached ElfImage to be removed.
   */
  static bool ClearCache(const ElfImage *image_to_clear);

  /**
   * @brief Clears the cache, releasing all ElfImage objects.
   *
   * This is primarily for testing or specific shutdown scenarios. After this
   * call, the next call to a Get...() method will reload the library from
   * scratch.
   */
  static void ClearCache();
};

} // namespace vector::native

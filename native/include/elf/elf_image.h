#pragma once

#include "common/config.h"
#include <link.h>
#include <linux/elf.h>
#include <map>
#include <string>
#include <string_view>
#include <vector>

/**
 * @file elf_image.h
 * @brief Defines the ElfImage class for parsing ELF files from memory.
 *
 * This utility can find the base address of a loaded shared library, parse its
 * ELF headers, and look up symbol addresses using various methods (GNU hash,
 * ELF hash, and linear search of the symbol table). It is capable of handling
 * stripped ELF files by decompressing and parsing the `.gnu_debugdata` section.
 */

namespace vector::native {

/**
 * @class ElfImage
 * @brief Represents a loaded ELF shared library in the current process.
 *
 * An ElfImage instance is created with the filename of a library (e.g.,
 * "libart.so"). It automatically finds the library's base address in memory by
 * parsing `/proc/self/maps` and then memory-maps the ELF file from disk to
 * parse its headers.
 */
class ElfImage {
public:
  /**
   * @brief Constructs an ElfImage for a given shared library.
   * @param lib_name The filename of the library (e.g., "libart.so", "/linker").
   */
  explicit ElfImage(std::string_view lib_name);
  ~ElfImage();

  // Disable copy and assignment to prevent accidental slicing and resource
  // mismanagement.
  ElfImage(const ElfImage &) = delete;
  ElfImage &operator=(const ElfImage &) = delete;

  /**
   * @brief Finds the memory address of a symbol by its name.
   *
   * This method attempts to resolve a symbol's address using, in order:
   * 1. The GNU hash table (.gnu.hash) for fast lookups.
   * 2. The standard ELF hash table (.hash) as a fallback.
   * 3. A linear search through the full symbol table (.symtab), which is
   *    often present in unstripped or debug-data-containing binaries.
   *
   * @tparam T The desired pointer type (e.g., `void*`, `int (*)(...)`).
   * @param name The name of the symbol to find.
   * @return The absolute memory address of the symbol, or nullptr if not found.
   */
  template <typename T = void *>
    requires(std::is_pointer_v<T>)
  const T getSymbAddress(std::string_view name) const {
    // Pre-calculate hashes for efficiency.
    auto gnu_hash = GnuHash(name);
    auto elf_hash = ElfHash(name);
    auto offset = getSymbOffset(name, gnu_hash, elf_hash);
    if (offset > 0 && base_ != nullptr) {
      // The final address is: base_address + symbol_offset - load_bias
      return reinterpret_cast<T>(reinterpret_cast<uintptr_t>(base_) + offset -
                                 bias_);
    }
    return nullptr;
  }

  /**
   * @brief Finds the first symbol whose name starts with the given prefix.
   *
   * This is useful for finding symbols when the exact name is unknown, such
   * as mangled C++ symbols. This search is performed only on the full symbol
   * table (.symtab) and may be slow.
   *
   * @tparam T The desired pointer type.
   * @param prefix The prefix to search for.
   * @return The address of the first matching symbol, or nullptr if none is
   * found.
   */
  template <typename T = void *>
    requires(std::is_pointer_v<T>)
  const T getSymbPrefixFirstAddress(std::string_view prefix) const {
    auto offset = prefixLookupFirst(prefix);
    if (offset > 0 && base_ != nullptr) {
      return reinterpret_cast<T>(reinterpret_cast<uintptr_t>(base_) + offset -
                                 bias_);
    }
    return nullptr;
  }

  /**
   * @brief Finds all symbols matching a given name.
   *
   * This is typically used for weakly defined symbols where multiple
   * definitions can exist. This search is performed only on the full symbol
   * table (.symtab).
   *
   * @tparam T The desired pointer type for the returned addresses.
   * @param name The exact symbol name to look for.
   * @return A vector of absolute memory addresses for all matching symbols.
   */
  template <typename T = void *>
    requires(std::is_pointer_v<T>)
  const std::vector<T> getAllSymbAddress(std::string_view name) const {
    auto offsets = linearRangeLookup(name);
    std::vector<T> res;
    res.reserve(offsets.size());
    for (const auto &offset : offsets) {
      res.emplace_back(reinterpret_cast<T>(reinterpret_cast<uintptr_t>(base_) +
                                           offset - bias_));
    }
    return res;
  }

  /**
   * @brief Checks if the ELF image was successfully loaded and parsed.
   * @return True if the image is valid, false otherwise.
   */
  [[nodiscard]] bool IsValid() const { return base_ != nullptr; }

  /**
   * @brief Returns the canonical path of the loaded library, as found in
   * /proc/self/maps.
   */
  [[nodiscard]] const std::string &GetPath() const { return path_; }

private:
  /// Finds the base address of the library in the current process's memory map.
  bool findModuleBase();
  /// Parses the main ELF headers from a given header pointer.
  void parseHeaders(ElfW(Ehdr) * header);
  /// Decompresses the .gnu_debugdata section if it exists.
  bool decompressGnuDebugData();

  /// Looks up a symbol offset using the ELF hash table.
  ElfW(Addr) elfLookup(std::string_view name, uint32_t hash) const;
  /// Looks up a symbol offset using the GNU hash table.
  ElfW(Addr) gnuLookup(std::string_view name, uint32_t hash) const;
  /// Looks up a symbol offset via a linear scan of the .symtab section.
  ElfW(Addr) linearLookup(std::string_view name) const;
  /// Finds all symbol offsets with a given name via a linear scan.
  std::vector<ElfW(Addr)> linearRangeLookup(std::string_view name) const;
  /// Finds the first symbol offset whose name starts with the given prefix.
  ElfW(Addr) prefixLookupFirst(std::string_view prefix) const;

  /// Gets a symbol's offset from the start of the file.
  ElfW(Addr) getSymbOffset(std::string_view name, uint32_t gnu_hash,
                           uint32_t elf_hash) const;

  /// Lazily initializes the map for linear symbol lookups from the .symtab
  /// section.
  void ensureLinearMapInitialized() const;

  /// Calculates the standard ELF hash for a symbol name.
  [[nodiscard]] static constexpr uint32_t ElfHash(std::string_view name);
  /// Calculates the GNU hash for a symbol name.
  [[nodiscard]] static constexpr uint32_t GnuHash(std::string_view name);

  // --- Member Variables ---

  std::string path_;
  void *base_ = nullptr;
  void *file_map_ = nullptr;
  size_t file_size_ = 0;
  ElfW(Addr) bias_ = 0;

  // Pointers into the mapped ELF file data.
  ElfW(Ehdr) *header_ = nullptr;
  ElfW(Shdr) *dynsym_ = nullptr;
  ElfW(Sym) *dynsym_start_ = nullptr;
  const char *strtab_start_ = nullptr; // Note: const char* is safer.

  // ELF hash section fields
  uint32_t nbucket_ = 0;
  uint32_t *bucket_ = nullptr;
  uint32_t *chain_ = nullptr;

  // GNU hash section fields
  uint32_t gnu_nbucket_ = 0;
  uint32_t gnu_symndx_ = 0;
  uint32_t gnu_bloom_size_ = 0;
  uint32_t gnu_shift2_ = 0;
  uintptr_t *gnu_bloom_filter_ = nullptr;
  uint32_t *gnu_bucket_ = nullptr;
  uint32_t *gnu_chain_ = nullptr;

  // For stripped binaries with .gnu_debugdata
  std::string elf_debugdata_;
  ElfW(Ehdr) *header_debugdata_ = nullptr;
  ElfW(Sym) *symtab_start_ = nullptr;
  ElfW(Off) symtab_count_ = 0;
  const char *symtab_str_start_ = nullptr;

  // Lazily-initialized map for fast linear lookups.
  // `mutable` allows init in const methods.
  mutable std::map<std::string_view, ElfW(Sym) *> symtabs_;
};

// --- Inlined Hash Function Implementations ---

constexpr uint32_t ElfImage::ElfHash(std::string_view name) {
  uint32_t h = 0, g;
  for (unsigned char p : name) {
    h = (h << 4) + p;
    if ((g = h & 0xf0000000) != 0) {
      h ^= g >> 24;
    }
    h &= ~g;
  }
  return h;
}

constexpr uint32_t ElfImage::GnuHash(std::string_view name) {
  uint32_t h = 5381;
  for (unsigned char p : name) {
    h = (h << 5) + h + p; // h * 33 + p
  }
  return h;
}

} // namespace vector::native

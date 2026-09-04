#include "elf/elf_image.h"

#include <fcntl.h>
#include <linux/xz.h>  // For decompressing .gnu_debugdata
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>
#include <string>
#include <string_view>
#include <utility>  // For std::move
#include <vector>

#include "common/logging.h"

namespace vector::native {

namespace {
// Helper to safely cast an offset from a base pointer.
template <typename T>
inline T PtrOffset(void *base, ptrdiff_t offset) {
    return reinterpret_cast<T>(reinterpret_cast<uintptr_t>(base) + offset);
}
}  // namespace

ElfImage::ElfImage(std::string_view lib_name) : path_(lib_name) {
    // Ask the dynamic linker first; fall back to parsing /proc/self/maps only for objects it
    // does not enumerate.
    if (!findModuleBaseViaLinker() && !findModuleBase()) {
        base_ = nullptr;  // Ensure base_ is null on failure.
        return;
    }

    // From here on every failure clears base_: IsValid() must not report an image whose symbol
    // tables were never parsed, or callers get a null address for every symbol with no clue why.
    int fd = open(path_.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        PLOGE("Failed to open ELF file: {}", path_.c_str());
        base_ = nullptr;
        return;
    }

    struct stat file_info;
    if (fstat(fd, &file_info) < 0) {
        PLOGE("fstat failed for {}", path_.c_str());
        close(fd);
        base_ = nullptr;
        return;
    }
    file_size_ = file_info.st_size;

    file_map_ = mmap(nullptr, file_size_, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);

    if (file_map_ == MAP_FAILED) {
        PLOGE("mmap failed for {}", path_.c_str());
        file_map_ = nullptr;
        base_ = nullptr;
        return;
    }

    // The path is whatever the linker or the maps file named, which is not necessarily an ELF at
    // all (a library loaded straight out of an APK names the APK). parseHeaders() walks section
    // headers by offset and would read wild pointers, so check the magic before trusting it.
    if (file_size_ < sizeof(ElfW(Ehdr)) || memcmp(file_map_, ELFMAG, SELFMAG) != 0) {
        LOGE("{} is not an ELF file, refusing to parse it", path_.c_str());
        base_ = nullptr;
        return;
    }

    header_ = static_cast<ElfW(Ehdr) *>(file_map_);
    parseHeaders(header_);

    // Check for and handle compressed debug symbols.
    if (decompressGnuDebugData()) {
        header_debugdata_ = PtrOffset<ElfW(Ehdr) *>(elf_debugdata_.data(), 0);
        // Re-parse to find the .symtab and its .strtab from the debug data.
        parseHeaders(header_debugdata_);
    }
}

ElfImage::~ElfImage() {
    if (file_map_ != nullptr) {
        munmap(file_map_, file_size_);
    }
}

void ElfImage::parseHeaders(ElfW(Ehdr) * header) {
    if (!header) return;

    ElfW(Shdr) *section_headers = PtrOffset<ElfW(Shdr) *>(header, header->e_shoff);
    const char *section_str_table =
        PtrOffset<const char *>(header, section_headers[header->e_shstrndx].sh_offset);

    for (int i = 0; i < header->e_shnum; ++i) {
        ElfW(Shdr) *section_h = &section_headers[i];
        const char *sname = section_str_table + section_h->sh_name;

        switch (section_h->sh_type) {
        case SHT_DYNSYM:
            // We only care about the first .dynsym found in the original ELF file.
            if (dynsym_ == nullptr) {
                dynsym_ = section_h;
                dynsym_start_ = PtrOffset<ElfW(Sym) *>(header, section_h->sh_offset);
            }
            break;
        case SHT_SYMTAB:
            if (strcmp(sname, ".symtab") == 0) {
                symtab_start_ = PtrOffset<ElfW(Sym) *>(header, section_h->sh_offset);
                symtab_count_ = section_h->sh_size / section_h->sh_entsize;
            }
            break;
        case SHT_STRTAB:
            // The string table for .dynsym is usually the first SHT_STRTAB after .dynsym.
            // We identify it by checking if dynsym is found but its strtab is not.
            if (dynsym_ != nullptr && strtab_start_ == nullptr) {
                strtab_start_ = PtrOffset<const char *>(header, section_h->sh_offset);
            }
            // The string table for .symtab is explicitly named ".strtab".
            if (strcmp(sname, ".strtab") == 0) {
                symtab_str_start_ = PtrOffset<const char *>(header, section_h->sh_offset);
            }
            break;
        case SHT_PROGBITS:
            // The load bias is the difference between
            // the virtual address of a loaded segment and its offset in the file.

            // Ensure we skip early sections like .interp or .note
            // by waiting until after dynsym and strtab are found.
            if (dynsym_ == nullptr || strtab_start_ == nullptr) break;

            if (!bias_calculated_ && section_h->sh_flags & SHF_ALLOC && section_h->sh_addr > 0) {
                bias_ = section_h->sh_addr - section_h->sh_offset;
                bias_calculated_ = true;
            }
            break;
        case SHT_HASH:
            // Standard ELF hash table.
            if (nbucket_ == 0) {
                uint32_t *hash_data = PtrOffset<uint32_t *>(header, section_h->sh_offset);
                nbucket_ = hash_data[0];
                // nchain is hash_data[1]
                bucket_ = &hash_data[2];
                chain_ = bucket_ + nbucket_;
            }
            break;
        case SHT_GNU_HASH:
            // GNU-style hash table.
            if (gnu_nbucket_ == 0) {
                uint32_t *hash_data = PtrOffset<uint32_t *>(header, section_h->sh_offset);
                gnu_nbucket_ = hash_data[0];
                gnu_symndx_ = hash_data[1];
                gnu_bloom_size_ = hash_data[2];
                gnu_shift2_ = hash_data[3];
                gnu_bloom_filter_ = reinterpret_cast<uintptr_t *>(&hash_data[4]);
                gnu_bucket_ = reinterpret_cast<uint32_t *>(gnu_bloom_filter_ + gnu_bloom_size_);
                gnu_chain_ = gnu_bucket_ + gnu_nbucket_;
            }
            break;
        }
    }
}

bool ElfImage::decompressGnuDebugData() {
    ElfW(Shdr) *section_headers = PtrOffset<ElfW(Shdr) *>(header_, header_->e_shoff);
    const char *section_str_table =
        PtrOffset<const char *>(header_, section_headers[header_->e_shstrndx].sh_offset);
    ElfW(Off) debugdata_offset = 0;
    ElfW(Off) debugdata_size = 0;

    for (int i = 0; i < header_->e_shnum; ++i) {
        if (strcmp(section_str_table + section_headers[i].sh_name, ".gnu_debugdata") == 0) {
            debugdata_offset = section_headers[i].sh_offset;
            debugdata_size = section_headers[i].sh_size;
            break;
        }
    }

    if (debugdata_offset == 0 || debugdata_size == 0) {
        return false;  // Section not found.
    }
    LOGD("Found .gnu_debugdata section in {} ({} bytes). Decompressing...", path_.c_str(),
         debugdata_size);

    xz_crc32_init();
    struct xz_dec *dec = xz_dec_init(XZ_DYNALLOC, 1 << 26);
    if (!dec) return false;

    struct xz_buf buf;
    buf.in = PtrOffset<const uint8_t *>(header_, debugdata_offset);
    buf.in_pos = 0;
    buf.in_size = debugdata_size;

    elf_debugdata_.resize(debugdata_size * 8);  // Initial guess
    buf.out = reinterpret_cast<uint8_t *>(elf_debugdata_.data());
    buf.out_pos = 0;
    buf.out_size = elf_debugdata_.size();

    while (true) {
        enum xz_ret ret = xz_dec_run(dec, &buf);
        if (ret == XZ_STREAM_END) {
            elf_debugdata_.resize(buf.out_pos);
            xz_dec_end(dec);
            LOGD("Successfully decompressed .gnu_debugdata ({} bytes)", elf_debugdata_.size());
            return true;
        }
        if (ret != XZ_OK) {
            LOGE("XZ decompression failed with code {}", (int)ret);
            xz_dec_end(dec);
            return false;
        }
        if (buf.out_pos == buf.out_size) {
            elf_debugdata_.resize(elf_debugdata_.size() * 2);
            // Reset pointer to the potentially new base address
            buf.out = reinterpret_cast<uint8_t *>(elf_debugdata_.data());
            // Update the total capacity
            buf.out_size = elf_debugdata_.size();
        }
    }
}

ElfW(Addr) ElfImage::getSymbOffset(std::string_view name, uint32_t gnu_hash,
                                   uint32_t elf_hash) const {
    if (auto offset = gnuLookup(name, gnu_hash); offset > 0) {
        return offset;
    } else if (offset = elfLookup(name, elf_hash); offset > 0) {
        return offset;
    } else if (offset = linearLookup(name); offset > 0) {
        return offset;
    } else {
        return 0;
    }
}

ElfW(Addr) ElfImage::gnuLookup(std::string_view name, uint32_t hash) const {
    if (gnu_nbucket_ == 0) return 0;

    constexpr auto bloom_mask_bits = sizeof(ElfW(Addr)) * 8;
    auto bloom_word = gnu_bloom_filter_[(hash / bloom_mask_bits) % gnu_bloom_size_];
    uintptr_t mask =
        (1ULL << (hash % bloom_mask_bits)) | (1ULL << ((hash >> gnu_shift2_) % bloom_mask_bits));

    if ((bloom_word & mask) != mask) {
        return 0;  // Not in bloom filter, definitely not here.
    }

    uint32_t sym_idx = gnu_bucket_[hash % gnu_nbucket_];
    if (sym_idx < gnu_symndx_) return 0;

    do {
        ElfW(Sym) *sym = dynsym_start_ + sym_idx;
        if (((gnu_chain_[sym_idx - gnu_symndx_] ^ hash) >> 1) == 0) {
            if (std::string_view(strtab_start_ + sym->st_name) == name) {
                return sym->st_value;
            }
        }
    } while ((gnu_chain_[sym_idx++ - gnu_symndx_] & 1) == 0);

    return 0;
}

ElfW(Addr) ElfImage::elfLookup(std::string_view name, uint32_t hash) const {
    if (nbucket_ == 0) return 0;

    for (uint32_t n = bucket_[hash % nbucket_]; n != 0; n = chain_[n]) {
        ElfW(Sym) *sym = dynsym_start_ + n;
        if (std::string_view(strtab_start_ + sym->st_name) == name) {
            return sym->st_value;
        }
    }
    return 0;
}

void ElfImage::ensureLinearMapInitialized() const {
    // Lazily parse the .symtab section and build a map for faster lookups.
    if (!symtabs_.empty() || !symtab_start_ || !symtab_str_start_) {
        return;
    }

    for (ElfW(Off) i = 0; i < symtab_count_; ++i) {
        auto *sym = &symtab_start_[i];
        unsigned int st_type = ELF_ST_TYPE(sym->st_info);
        // We only care about function or object symbols that have a size.
        if ((st_type == STT_FUNC || st_type == STT_OBJECT) && sym->st_size > 0) {
            const char *st_name = symtab_str_start_ + sym->st_name;
            symtabs_.emplace(st_name, sym);
        }
    }
}

ElfW(Addr) ElfImage::linearLookup(std::string_view name) const {
    ensureLinearMapInitialized();
    auto it = symtabs_.find(name);
    if (it != symtabs_.end()) {
        return it->second->st_value;
    }
    return 0;
}

std::vector<ElfW(Addr)> ElfImage::linearRangeLookup(std::string_view name) const {
    ensureLinearMapInitialized();
    std::vector<ElfW(Addr)> res;
    for (auto [it, end] = symtabs_.equal_range(name); it != end; ++it) {
        res.emplace_back(it->second->st_value);
    }
    return res;
}

ElfW(Addr) ElfImage::prefixLookupFirst(std::string_view prefix) const {
    ensureLinearMapInitialized();
    // lower_bound finds the first element not less than the prefix.
    auto it = symtabs_.lower_bound(prefix);
    if (it != symtabs_.end() && it->first.starts_with(prefix)) {
        return it->second->st_value;
    }
    return 0;
}

namespace {

// Payload for the dl_iterate_phdr callback below.
struct LinkerQuery {
    std::string_view wanted;  // name or fragment the caller asked for
    std::string matched_path;
    uintptr_t base = 0;
    bool found = false;
    bool exact = false;  // basename == wanted, so a later inexact hit cannot displace it
};

std::string_view BasenameOf(std::string_view s) {
    const auto slash = s.find_last_of('/');
    return slash == std::string_view::npos ? s : s.substr(slash + 1);
}

int LinkerCallback(struct dl_phdr_info *info, size_t, void *data) {
    auto *q = static_cast<LinkerQuery *>(data);
    if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;

    const std::string_view name{info->dlpi_name};
    if (name.find(q->wanted) == std::string_view::npos) return 0;

    const bool exact = BasenameOf(name) == q->wanted;
    if (q->found && (q->exact || !exact)) return 0;  // keep the better match already held

    const ElfW(Phdr) *first_load = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        if (first_load == nullptr || ph->p_vaddr < first_load->p_vaddr) first_load = ph;
    }
    if (first_load == nullptr) return 0;

    // dlpi_addr is the load bias, which is the address of file offset 0 only when the first
    // PT_LOAD has p_vaddr == p_offset -- usual, but not guaranteed, so subtract them out.
    q->base = static_cast<uintptr_t>(info->dlpi_addr) + first_load->p_vaddr - first_load->p_offset;
    q->matched_path.assign(name);
    q->found = true;
    q->exact = exact;
    return exact ? 1 : 0;  // an exact basename match ends the walk
}

}  // namespace

/*
 * The linker already knows where every library it loaded lives, so ask it rather than reconstruct
 * the answer from /proc/self/maps.  It is the better source on three counts:
 *
 *   * no decoy can fool it.  Extra mappings of the file -- including this class's own MAP_SHARED
 *     view of it -- are invisible here, because the linker never loaded them.
 *   * a library that is genuinely loaded twice enumerates in the linker's own order -- solist is
 *     kept in load order -- so the first hit is the earliest load rather than whichever address
 *     happens to sort lowest.
 *   * it does not read /proc/self/maps, a read that is itself detectable.
 *
 * One module is not covered: the dynamic linker itself.  Bionic has put it at the head of solist
 * under its own path on every release we support, but it only fills that entry's program headers
 * in from API 29 on -- get_libdl_info() began copying linker_si.phdr/phnum in Android 10, and
 * before that dlpi_phnum is 0 there.  With no PT_LOAD to read the walk yields nothing, so on API
 * 27 and 28 kLinkerPath falls through to findModuleBase(), which remains the fallback both for
 * that and for anything else the linker does not list.
 */
bool ElfImage::findModuleBaseViaLinker() {
    LinkerQuery query;
    query.wanted = path_;
    dl_iterate_phdr(&LinkerCallback, &query);
    if (!query.found) return false;

    base_ = reinterpret_cast<void *>(query.base);
    path_ = std::move(query.matched_path);
    LOGD("Linker reports {} at {:#x}", path_.c_str(), query.base);
    return true;
}

bool ElfImage::findModuleBase() {
    // One mapping line of /proc/self/maps that belongs to the target file.
    struct MapEntry {
        uintptr_t start_addr = 0;
        uintptr_t file_offset = 0;
        char perms[5] = {0};
        std::string pathname;
    };

    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) {
        PLOGE("Failed to open /proc/self/maps");
        return false;
    }

    char line_buffer[512];
    std::vector<MapEntry> filtered_list;

    while (fgets(line_buffer, sizeof(line_buffer), maps)) {
        unsigned long long temp_start = 0;
        unsigned long long temp_offset = 0;
        char path_buffer[256] = {0};
        char p[5] = {0};

        // start-end perms offset dev inode pathname.  The offset is kept rather than discarded:
        // it is what makes the base exact (see below).  A trailing " (deleted)" is dropped for
        // free, because %255s stops at the space.
        int items_parsed = sscanf(line_buffer, "%llx-%*llx %4s %llx %*s %*u %255s", &temp_start, p,
                                  &temp_offset, path_buffer);
        if (items_parsed != 4) continue;  // anonymous mapping, or no pathname

        // Match against the pathname field only.  Matching the whole line would let the
        // address, permission or device columns satisfy the test.
        if (!strstr(path_buffer, path_.c_str())) continue;

        MapEntry entry;
        entry.start_addr = static_cast<uintptr_t>(temp_start);
        entry.file_offset = static_cast<uintptr_t>(temp_offset);
        strncpy(entry.perms, p, 4);
        entry.pathname = path_buffer;
        filtered_list.push_back(std::move(entry));
    }
    fclose(maps);

    if (filtered_list.empty()) {
        LOGE("Could not find any mappings for {}", path_.c_str());
        return false;
    }

    // The name we are given may be a bare soname ("libart.so") or a fragment ("/linker"), so
    // several distinct FILES can match it -- /system/lib64/libz.so and /vendor/lib64/libz.so both
    // contain "libz.so", at unrelated addresses.  Mixing their segments together is what makes a
    // positional heuristic settle on a base belonging to neither, so pick one file first.
    std::string chosen_path = filtered_list.front().pathname;
    {
        // Prefer an exact basename match, then the shorter path, then the lowest address (the
        // maps file is ordered by address).  Deterministic in every case, so two runs on one
        // device cannot disagree.
        const std::string_view needle{path_};
        bool best_exact = BasenameOf(chosen_path) == needle;
        for (const auto &entry : filtered_list) {
            if (entry.pathname == chosen_path) continue;
            const bool exact = BasenameOf(entry.pathname) == needle;
            if (exact != best_exact) {
                if (exact) {
                    chosen_path = entry.pathname;
                    best_exact = true;
                }
                continue;
            }
            if (entry.pathname.size() < chosen_path.size()) chosen_path = entry.pathname;
        }
    }

    // The base is where file offset 0 of the file is mapped.  The kernel states that outright, so
    // it does not have to be guessed from segment permissions -- and it cannot be recovered from
    // an arbitrary segment as start - file_offset, because modern ELFs are linked with a separate
    // code segment and padded between the two, leaving the executable segment a few pages above
    // where its file offset alone would put it.
    //
    // Two kinds of impostor also map the file at offset 0 without being the load:
    //
    //   * a shared mapping, which this class makes itself: the constructor mmaps the whole file
    //     MAP_SHARED and holds it for the object's lifetime, while SymbolCache keeps ElfImage
    //     instances alive.  The kernel can place it below the real load base, so it outranks the
    //     real base under any "lowest address" or "first entry" rule.  Requiring `p` drops it.
    //   * a private read-only mapping with no code behind it.
    //
    // A real load is told apart from both by the same evidence: it has an executable mapping of
    // the same file at candidate + file_offset, give or take the inter-segment padding.  So take
    // every private offset-0 mapping as a candidate and rank it by that corroboration.
    //
    // Getting this wrong is silent rather than a failed lookup: every symbol resolves to
    // base_ + offset - bias_, so a wrong base shifts every hook target by the same amount and the
    // write lands in whatever else is mapped there.
    // Segments are padded apart by whole alignment units, and AOSP links with
    // -Wl,-z,max-page-size=16384 by default (4096 on low-memory and pre-VSR-34 devices), so 64 KiB
    // of slack covers every layout while still being far tighter than the gap to another library.
    constexpr uintptr_t kMaxSegmentPadding = 0x10000;

    uintptr_t module_base = 0;
    int best_corroboration = -1;
    for (const auto &candidate : filtered_list) {
        if (candidate.pathname != chosen_path) continue;
        if (candidate.perms[3] != 'p') continue;   // private mappings only
        if (candidate.file_offset != 0) continue;  // the base is where file offset 0 lands

        int corroboration = 0;
        for (const auto &seg : filtered_list) {
            if (seg.pathname != chosen_path) continue;
            // Executable, but not necessarily readable: Android 10 maps libart.so's code as --xp.
            if (seg.perms[3] != 'p' || seg.perms[2] != 'x') continue;
            if (seg.start_addr < candidate.start_addr) continue;
            const uintptr_t distance = seg.start_addr - candidate.start_addr;
            if (distance < seg.file_offset) continue;  // below where its own file offset puts it
            if (distance - seg.file_offset <= kMaxSegmentPadding) corroboration++;
        }

        // Most corroborated wins; ties go to the lowest address, so a library that is genuinely
        // loaded twice resolves to the same base on every run.
        if (corroboration > best_corroboration) {
            best_corroboration = corroboration;
            module_base = candidate.start_addr;
        }
    }

    if (best_corroboration < 0) {
        LOGE("No private offset-0 mapping of {}, so its base cannot be established",
             chosen_path.c_str());
        return false;
    }
    if (best_corroboration == 0) {
        LOGW("Base {:#x} for {} is not corroborated by any executable segment", module_base,
             chosen_path.c_str());
    }

    base_ = reinterpret_cast<void *>(module_base);
    path_ = chosen_path;

    LOGD("Found base for {} at {:#x}", path_.c_str(), module_base);
    return true;
}

}  // namespace vector::native

#include "core/art_inline_hook_invalidation.h"

#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#include "common/logging.h"

namespace vector::native {
namespace {

std::mutex g_art_invalidation_mutex;
std::vector<void *> g_art_inline_hook_targets;
bool g_art_invalidation_enabled = false;
bool g_art_invalidation_completed = false;

struct LibArtRestoreResult {
    bool found = false;
    bool success = false;
    size_t executable_segments = 0;
    size_t modified_pages = 0;
    size_t restored_bytes = 0;
};

struct PreservedExecutablePage {
    uintptr_t address = 0;
    std::vector<uint8_t> bytes;
};

struct LibArtSnapshotResult {
    bool found = false;
    bool success = false;
    std::vector<PreservedExecutablePage> modified_pages;
};

std::vector<PreservedExecutablePage> g_preserved_art_pages;

bool IsLibArtPath(const char *path) {
    if (!path || *path == '\0') return false;
    const char *name = std::strrchr(path, '/');
    name = name ? name + 1 : path;
    return std::strcmp(name, "libart.so") == 0;
}

int ProtectionFromFlags(ElfW(Word) flags) {
    int protection = 0;
    if ((flags & PF_R) != 0) protection |= PROT_READ;
    if ((flags & PF_W) != 0) protection |= PROT_WRITE;
    if ((flags & PF_X) != 0) protection |= PROT_EXEC;
    return protection;
}

bool GetPageLayout(size_t &page_size, uintptr_t &page_mask) {
    const long value = sysconf(_SC_PAGESIZE);
    if (value <= 0 || (value & (value - 1)) != 0) {
        LOGE("Failed to determine a valid page size while processing libart.so");
        return false;
    }
    page_size = static_cast<size_t>(value);
    page_mask = static_cast<uintptr_t>(page_size - 1);
    return true;
}

bool SegmentContainsTrackedTarget(uintptr_t segment_start, uintptr_t segment_end) {
    return std::any_of(g_art_inline_hook_targets.begin(), g_art_inline_hook_targets.end(),
                       [segment_start, segment_end](const void *target) {
                           const auto address = reinterpret_cast<uintptr_t>(target);
                           return address >= segment_start && address < segment_end;
                       });
}

bool CaptureExecutablePages(const dl_phdr_info *info, LibArtSnapshotResult &result) {
    const char *path = info->dlpi_name;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        PLOGE("Failed to open libart backing file '{}' while capturing existing hooks", path);
        return false;
    }

    struct stat file_stat {};
    if (fstat(fd, &file_stat) != 0 || file_stat.st_size <= 0) {
        PLOGE("Failed to stat libart backing file '{}' while capturing existing hooks", path);
        close(fd);
        return false;
    }

    const size_t file_size = static_cast<size_t>(file_stat.st_size);
    void *file_map = mmap(nullptr, file_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (file_map == MAP_FAILED) {
        PLOGE("Failed to map libart backing file '{}' while capturing existing hooks", path);
        close(fd);
        return false;
    }

    size_t page_size = 0;
    uintptr_t page_mask = 0;
    if (!GetPageLayout(page_size, page_mask)) {
        munmap(file_map, file_size);
        close(fd);
        return false;
    }

    bool success = true;
    const auto *clean_file = static_cast<const uint8_t *>(file_map);
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_LOAD || (phdr.p_flags & PF_X) == 0 || phdr.p_filesz == 0) continue;

        const size_t file_offset = static_cast<size_t>(phdr.p_offset);
        const size_t segment_size = static_cast<size_t>(phdr.p_filesz);
        const uintptr_t image_base = static_cast<uintptr_t>(info->dlpi_addr);
        const uintptr_t virtual_address = static_cast<uintptr_t>(phdr.p_vaddr);
        if (virtual_address > UINTPTR_MAX - image_base) {
            LOGE("Executable libart segment {} snapshot address overflows", i);
            success = false;
            continue;
        }
        const uintptr_t segment_start = image_base + virtual_address;
        if (segment_size > UINTPTR_MAX - segment_start) {
            LOGE("Executable libart segment {} snapshot range overflows", i);
            success = false;
            continue;
        }
        const size_t first_page_prefix = static_cast<size_t>(segment_start & page_mask);
        if (file_offset < first_page_prefix || segment_size > SIZE_MAX - first_page_prefix) {
            LOGE("Executable libart segment {} has invalid aligned snapshot bounds", i);
            success = false;
            continue;
        }

        const size_t mapping_span = first_page_prefix + segment_size;
        if (mapping_span > SIZE_MAX - page_mask) {
            LOGE("Executable libart segment {} snapshot size overflows", i);
            success = false;
            continue;
        }
        const size_t mapping_size = (mapping_span + page_mask) & ~page_mask;
        const size_t first_page_file_offset = file_offset - first_page_prefix;
        if (first_page_file_offset > file_size || mapping_size > file_size - first_page_file_offset) {
            LOGE("Executable libart segment {} aligned snapshot exceeds backing file bounds", i);
            success = false;
            continue;
        }

        const uintptr_t mapping_start = segment_start & ~page_mask;
        for (size_t offset = 0; offset < mapping_size; offset += page_size) {
            auto *live = reinterpret_cast<const uint8_t *>(mapping_start + offset);
            const auto *clean = clean_file + first_page_file_offset + offset;
            if (std::memcmp(live, clean, page_size) == 0) continue;

            PreservedExecutablePage page;
            page.address = mapping_start + offset;
            page.bytes.assign(live, live + page_size);
            result.modified_pages.emplace_back(std::move(page));
        }
    }

    munmap(file_map, file_size);
    close(fd);
    return success;
}

int CaptureLibArtCallback(dl_phdr_info *info, size_t, void *data) {
    if (!IsLibArtPath(info->dlpi_name)) return 0;

    auto &result = *static_cast<LibArtSnapshotResult *>(data);
    result.found = true;
    result.success = CaptureExecutablePages(info, result);
    return 1;
}

LibArtSnapshotResult CaptureLibArtExecutableState() {
    LibArtSnapshotResult result;
    dl_iterate_phdr(CaptureLibArtCallback, &result);
    if (!result.found) LOGE("Unable to locate loaded libart.so before installing ART hooks");
    return result;
}

bool PreparePreservedPagesForRewrite(uintptr_t mapping_start, uintptr_t mapping_end,
                                     int original_protection, size_t page_size) {
    for (const auto &page : g_preserved_art_pages) {
        if (page.address < mapping_start || page.address >= mapping_end) continue;
        if (page.bytes.size() != page_size) {
            LOGE("Invalid preserved libart page size at {}", reinterpret_cast<void *>(page.address));
            return false;
        }

        const int writable_protection = original_protection | PROT_WRITE;
        if (mprotect(reinterpret_cast<void *>(page.address), page_size, writable_protection) != 0) {
            PLOGE("Cannot make preserved libart page at {} writable",
                  reinterpret_cast<void *>(page.address));
            return false;
        }
        if (mprotect(reinterpret_cast<void *>(page.address), page_size, original_protection) != 0) {
            PLOGE("Cannot restore protection for preserved libart page at {}",
                  reinterpret_cast<void *>(page.address));
            return false;
        }
    }
    return true;
}

bool RestorePreservedPages(uintptr_t mapping_start, uintptr_t mapping_end, int original_protection,
                           size_t page_size) {
    bool success = true;
    for (const auto &page : g_preserved_art_pages) {
        if (page.address < mapping_start || page.address >= mapping_end) continue;

        const int writable_protection = original_protection | PROT_WRITE;
        if (mprotect(reinterpret_cast<void *>(page.address), page_size, writable_protection) != 0) {
            PLOGE("Failed to make preserved libart page at {} writable",
                  reinterpret_cast<void *>(page.address));
            success = false;
            continue;
        }
        std::memcpy(reinterpret_cast<void *>(page.address), page.bytes.data(), page_size);
        __builtin___clear_cache(reinterpret_cast<char *>(page.address),
                                reinterpret_cast<char *>(page.address + page_size));
        if (mprotect(reinterpret_cast<void *>(page.address), page_size, original_protection) != 0) {
            PLOGE("Failed to restore protection for preserved libart page at {}",
                  reinterpret_cast<void *>(page.address));
            success = false;
        }
    }
    return success;
}

bool RestoreExecutableSegments(const dl_phdr_info *info, LibArtRestoreResult &result) {
    const char *path = info->dlpi_name;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        PLOGE("Failed to open libart backing file '{}'", path);
        return false;
    }

    struct stat file_stat {};
    if (fstat(fd, &file_stat) != 0 || file_stat.st_size <= 0) {
        PLOGE("Failed to stat libart backing file '{}'", path);
        close(fd);
        return false;
    }

    const size_t file_size = static_cast<size_t>(file_stat.st_size);
    void *file_map = mmap(nullptr, file_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (file_map == MAP_FAILED) {
        PLOGE("Failed to map libart backing file '{}'", path);
        close(fd);
        return false;
    }

    size_t page_size = 0;
    uintptr_t page_mask = 0;
    if (!GetPageLayout(page_size, page_mask)) {
        munmap(file_map, file_size);
        close(fd);
        return false;
    }

    bool success = true;
    const auto *clean_file = static_cast<const uint8_t *>(file_map);

    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_LOAD || (phdr.p_flags & PF_X) == 0 || phdr.p_filesz == 0) {
            continue;
        }

        const size_t file_offset = static_cast<size_t>(phdr.p_offset);
        const size_t segment_size = static_cast<size_t>(phdr.p_filesz);
        if (file_offset > file_size || segment_size > file_size - file_offset) {
            LOGE("Executable libart segment {} exceeds backing file bounds", i);
            success = false;
            continue;
        }

        const uintptr_t segment_start =
            static_cast<uintptr_t>(info->dlpi_addr) + static_cast<uintptr_t>(phdr.p_vaddr);
        if (segment_size > UINTPTR_MAX - segment_start) {
            LOGE("Executable libart segment {} address range overflows", i);
            success = false;
            continue;
        }
        const uintptr_t segment_end = segment_start + segment_size;
        if (!SegmentContainsTrackedTarget(segment_start, segment_end)) continue;
        ++result.executable_segments;
        const auto *clean_segment = clean_file + file_offset;
        const int original_protection = ProtectionFromFlags(phdr.p_flags);
        const size_t first_page_prefix = static_cast<size_t>(segment_start & page_mask);
        if (file_offset < first_page_prefix) {
            LOGE("Executable libart segment {} has an invalid page-aligned file offset", i);
            success = false;
            continue;
        }
        const size_t first_page_file_offset = file_offset - first_page_prefix;

        // Count dirty pages first, then replace the complete executable PT_LOAD in one mmap. Mapping
        // individual dirty pages leaves visible VMA boundaries around every former trampoline;
        // protection libraries can treat that non-standard libart layout as tampering even when all
        // instruction bytes have been restored. A segment-wide file mapping recreates the loader's
        // normal contiguous VMA shape. Only pages containing preserved pre-Vector modifications are
        // made transiently writable below, and only after writability has been preflighted.
        const uintptr_t mapping_start = segment_start & ~page_mask;
        uintptr_t page_start = mapping_start;
        size_t segment_modified_pages = 0;
        size_t segment_restored_bytes = 0;
        while (page_start < segment_end) {
            const uintptr_t next_page = page_start + page_size;
            if (next_page < page_start) {
                LOGE("Page range overflow while invalidating libart.so");
                success = false;
                break;
            }

            const uintptr_t copy_start = std::max(page_start, segment_start);
            const uintptr_t copy_end = std::min(next_page, segment_end);
            const size_t copy_size = static_cast<size_t>(copy_end - copy_start);
            const size_t segment_offset = static_cast<size_t>(copy_start - segment_start);
            auto *live = reinterpret_cast<uint8_t *>(copy_start);
            const auto *clean = clean_segment + segment_offset;

            if (std::memcmp(live, clean, copy_size) != 0) {
                ++segment_modified_pages;
                segment_restored_bytes += copy_size;
            }

            page_start = next_page;
        }

        if (segment_modified_pages == 0) continue;

        const size_t mapping_span = first_page_prefix + segment_size;
        if (mapping_span > SIZE_MAX - page_mask) {
            LOGE("Executable libart segment {} mapping size overflows", i);
            success = false;
            continue;
        }
        const size_t mapping_size = (mapping_span + page_mask) & ~page_mask;
        if (mapping_size > UINTPTR_MAX - mapping_start) {
            LOGE("Executable libart segment {} aligned address range overflows", i);
            success = false;
            continue;
        }
        const uintptr_t mapping_end = mapping_start + mapping_size;
        if (first_page_file_offset > file_size ||
            mapping_size > file_size - first_page_file_offset) {
            LOGE("Executable libart segment {} aligned mapping exceeds backing file bounds", i);
            success = false;
            continue;
        }

        // Verify that pre-Vector modifications in this segment can be rewritten before replacing
        // its mapping. If this fails, leave the original mapping and every external hook intact.
        if (!PreparePreservedPagesForRewrite(mapping_start, mapping_end, original_protection,
                                             page_size)) {
            success = false;
            continue;
        }

        void *mapped = mmap(reinterpret_cast<void *>(mapping_start), mapping_size,
                            original_protection, MAP_PRIVATE | MAP_FIXED, fd,
                            static_cast<off_t>(first_page_file_offset));
        if (mapped == MAP_FAILED) {
            PLOGE("Failed to invalidate executable libart segment {} at {} from backing offset {}",
                  i, reinterpret_cast<void *>(mapping_start), first_page_file_offset);
            success = false;
            continue;
        }
        if (!RestorePreservedPages(mapping_start, mapping_end, original_protection, page_size)) {
            success = false;
            continue;
        }
        __builtin___clear_cache(reinterpret_cast<char *>(mapping_start),
                                reinterpret_cast<char *>(mapping_end));
        result.modified_pages += segment_modified_pages;
        result.restored_bytes += segment_restored_bytes;
    }

    munmap(file_map, file_size);
    close(fd);
    return success && result.executable_segments > 0;
}

int RestoreLibArtCallback(dl_phdr_info *info, size_t, void *data) {
    if (!IsLibArtPath(info->dlpi_name)) return 0;

    auto &result = *static_cast<LibArtRestoreResult *>(data);
    result.found = true;
    result.success = RestoreExecutableSegments(info, result);
    return 1;  // libart.so is unique in an app process; stop after handling it.
}

LibArtRestoreResult RestoreLibArtExecutableBytes() {
    LibArtRestoreResult result;
    dl_iterate_phdr(RestoreLibArtCallback, &result);
    if (!result.found) {
        LOGE("Unable to locate loaded libart.so for executable-byte invalidation");
    }
    return result;
}

}  // namespace

bool ConfigureArtInlineHookInvalidation(bool enabled) {
    std::lock_guard<std::mutex> lock(g_art_invalidation_mutex);
    g_art_inline_hook_targets.clear();
    g_preserved_art_pages.clear();
    g_art_invalidation_enabled = false;
    g_art_invalidation_completed = false;

    if (!enabled) return false;

    auto snapshot = CaptureLibArtExecutableState();
    if (!snapshot.success) {
        LOGW("ART inline-hook invalidation was not armed because the pre-Vector libart.so state "
             "could not be captured safely.");
        return false;
    }

    g_preserved_art_pages = std::move(snapshot.modified_pages);
    g_art_invalidation_enabled = true;
    LOGI("Preserved {} pre-existing modified libart.so executable page(s) before installing "
         "Vector hooks.",
         g_preserved_art_pages.size());
    return true;
}

void RecordArtInlineHookInvalidationTarget(void *target) {
    if (!target) return;

    std::lock_guard<std::mutex> lock(g_art_invalidation_mutex);
    if (!g_art_invalidation_enabled || g_art_invalidation_completed) return;

    if (std::find(g_art_inline_hook_targets.begin(), g_art_inline_hook_targets.end(), target) ==
        g_art_inline_hook_targets.end()) {
        g_art_inline_hook_targets.push_back(target);
    }
}

void ForgetArtInlineHookInvalidationTarget(void *target) {
    if (!target) return;

    std::lock_guard<std::mutex> lock(g_art_invalidation_mutex);
    g_art_inline_hook_targets.erase(
        std::remove(g_art_inline_hook_targets.begin(), g_art_inline_hook_targets.end(), target),
        g_art_inline_hook_targets.end());
}

bool InvalidateArtInlineHooksIfEnabled() {
    std::lock_guard<std::mutex> lock(g_art_invalidation_mutex);
    if (!g_art_invalidation_enabled || g_art_invalidation_completed) return true;

    const size_t tracked_targets = g_art_inline_hook_targets.size();
    LOGI("Running libart.so executable-byte invalidation after framework bootstrap "
         "({} tracked LSPlant target(s)).",
         tracked_targets);

    if (tracked_targets == 0) {
        g_preserved_art_pages.clear();
        g_art_invalidation_completed = true;
        g_art_invalidation_enabled = false;
        LOGI("No Vector/LSPlant ART inline-hook targets were installed; invalidation is unnecessary.");
        return true;
    }

    // Deliberately do not call DobbyDestroy/UnhookInline here. LSPosed-style invalidation is a
    // compatibility operation, not normal hook teardown: restore libart.so's file-backed executable
    // image, reapply modifications captured before Vector initialized, and leave LSPlant/Dobby
    // trampoline and interceptor metadata intact. Apps opting into this mode accept that Vector's
    // ART maintenance hooks no longer execute afterwards.
    const LibArtRestoreResult result = RestoreLibArtExecutableBytes();
    if (!result.success) {
        g_art_inline_hook_targets.clear();
        g_preserved_art_pages.clear();
        g_art_invalidation_enabled = false;
        LOGW("libart.so executable-byte invalidation failed; ART inline-hook invalidation mode "
             "was not fully applied in this process.");
        return false;
    }

    g_art_inline_hook_targets.clear();
    g_preserved_art_pages.clear();
    g_art_invalidation_completed = true;
    g_art_invalidation_enabled = false;

    if (result.modified_pages == 0) {
        LOGI("libart.so executable segments already match the backing file ({} segment(s) checked).",
             result.executable_segments);
    } else {
        LOGI("Invalidated libart.so executable pages from backing file: {} modified page(s), {} "
             "file-backed byte(s) restored across {} executable segment(s).",
             result.modified_pages, result.restored_bytes, result.executable_segments);
    }
    return true;
}

}  // namespace vector::native

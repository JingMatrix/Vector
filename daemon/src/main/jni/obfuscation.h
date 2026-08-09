#pragma once

#include <android/sharedmem.h>
#include <slicer/writer.h>
#include <sys/mman.h>
#include <unistd.h>

#include "logging.h"

// Custom allocator for dex::Writer that creates an ashmem region.
// Manages the virtual memory mapping lifecycle to prevent memory leaks.
class DexAllocator : public dex::Writer::Allocator {
    void* mapped_mem_ = nullptr;
    size_t mapped_size_ = 0;
    int fd_ = -1;

public:
    inline void* Allocate(size_t size) override {
        LOGV("DexAllocator: attempting to allocate %zu bytes", size);

        fd_ = ASharedMemory_create("obfuscated_dex", size);
        if (fd_ < 0) {
            // Log the specific error
            PLOGE("DexAllocator: ASharedMemory_create");
            return nullptr;
        }

        mapped_size_ = size;
        // MAP_SHARED is required for the output buffer so that Slicer's writes
        // are immediately reflected in the underlying file descriptor.
        mapped_mem_ = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);

        if (mapped_mem_ == MAP_FAILED) {
            PLOGE("DexAllocator: mmap");
            close(fd_);
            fd_ = -1;
            mapped_mem_ = nullptr;
        }

        LOGV("DexAllocator: success, mapped at %p, fd=%d", mapped_mem_, fd_);
        return mapped_mem_;
    }

    inline void Free(void* ptr) override {
        if (ptr == mapped_mem_ && mapped_mem_ != nullptr) {
            munmap(mapped_mem_, mapped_size_);
            close(fd_);
            mapped_mem_ = nullptr;
            fd_ = -1;
            mapped_size_ = 0;
        }
    }

    inline int GetFd() const { return fd_; }

    inline ~DexAllocator() {
        // Unmap the virtual memory upon destruction to prevent memory leaks.
        if (mapped_mem_ != nullptr && mapped_mem_ != MAP_FAILED) {
            munmap(mapped_mem_, mapped_size_);
        }
        // Notice: We do NOT close(fd_) here!
        // The file descriptor is extracted via GetFd() and handed over to Java's SharedMemory,
        // which assumes lifecycle ownership of it.
    }
};

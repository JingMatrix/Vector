#pragma once

#include <android/sharedmem.h>
#include <slicer/writer.h>
#include <sys/mman.h>
#include <unistd.h>

#include "logging.h"

// 为 dex::Writer 提供自定义分配器，创建一个 ashmem 区域。
// 管理虚拟内存映射生命周期，避免泄露。
class DexAllocator : public dex::Writer::Allocator {
    void* mapped_mem_ = nullptr;
    size_t mapped_size_ = 0;
    int fd_ = -1;

public:
    inline void* Allocate(size_t size) override {
        LOGV("DexAllocator: attempting to allocate %zu bytes", size);

        // /proc/self/maps 中会暴露内存名，且在不同进程可见。这里将区域设置为匿名，
        // 避免注入应用获得稳定的 framework 指纹。
        fd_ = ASharedMemory_create(nullptr, size);
        if (fd_ < 0) {
            // 记录具体错误信息
            PLOGE("DexAllocator: ASharedMemory_create");
            return nullptr;
        }

        mapped_size_ = size;
        // 输出缓冲区必须使用 MAP_SHARED，这样 Slicer 的写入才能
        // 立即反映到底层文件描述符。
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
        // 析构时解除虚拟内存映射，避免泄露。
        if (mapped_mem_ != nullptr && mapped_mem_ != MAP_FAILED) {
            munmap(mapped_mem_, mapped_size_);
        }
        // 注意：这里不在此处 close(fd_)！
        // 文件描述符会通过 GetFd() 取出并交给 Java 的 SharedMemory 管理，
        // Java 侧承担其生命周期所有权。
    }
};

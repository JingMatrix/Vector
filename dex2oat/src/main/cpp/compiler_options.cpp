#include <dlfcn.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <cstdlib>

extern "C" __attribute__((visibility("default"))) void _ZN3art15CompilerOptionsC1Ev(void *self) {
    using Constructor = void (*)(void *);
    static Constructor original =
        reinterpret_cast<Constructor>(dlsym(RTLD_NEXT, "_ZN3art15CompilerOptionsC1Ev"));
    if (original == nullptr) return;

    original(self);

    constexpr size_t kMaxSlots = 10;
    for (size_t i = 0; i < kMaxSlots; i++) {
        auto *slot = reinterpret_cast<uintptr_t *>(static_cast<char *>(self) + i * sizeof(uintptr_t));
        if (*slot == static_cast<uintptr_t>(-1)) {
            *slot = 0;
            break;
        }
    }

    unsetenv("LD_PRELOAD");
}

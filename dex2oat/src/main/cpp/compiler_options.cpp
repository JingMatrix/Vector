#include <android/log.h>
#include <dlfcn.h>

#include <cstddef>

extern "C" __attribute__((visibility("default"))) void _ZN3art15CompilerOptionsC1Ev(void *self) {
    using Constructor = void (*)(void *);
    static Constructor original =
        reinterpret_cast<Constructor>(dlsym(RTLD_NEXT, "_ZN3art15CompilerOptionsC1Ev"));
    if (original == nullptr) return;

    original(self);

    constexpr size_t kInlineMaxCodeUnitsOffset = sizeof(void *) == 8 ? 16 : 8;
    auto *units = reinterpret_cast<size_t *>(static_cast<char *>(self) + kInlineMaxCodeUnitsOffset);
    *units = 0;
}

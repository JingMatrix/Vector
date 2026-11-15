#include "core/context.h"
#include "core/native_api.h"
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"
#include "jni/jni_hooks.h"

using namespace vector::native;

// This context belongs exclusively to the disposable test process.
class ProbeContext : public Context {
public:
    static void Setup(JNIEnv *env, jobject loader) {
        auto context = new ProbeContext;
        context->inject_class_loader_ = env->NewGlobalRef(loader);
        instance_.reset(context);
    }
    void LoadDex(JNIEnv *, PreloadedDex &&) override {}
    void SetupEntryClass(JNIEnv *) override {}
};

extern "C" JNIEXPORT jboolean JNICALL
Java_NativeHookProbe_init(JNIEnv *env, jclass, jobject loader) {
    ProbeContext::Setup(env, loader);
    const auto *art = ElfSymbolCache::GetArt();
    if (!art) return false;
    if (!lsplant::Init(env, {
            .inline_hooker = [](void *target, void *replace) -> void * {
                void *backup = nullptr;
                return HookInline(target, replace, &backup) == 0 ? backup : nullptr;
            },
            .inline_unhooker = [](void *target) { return UnhookInline(target) == 0; },
            .art_symbol_resolver = [art](auto symbol) { return art->getSymbAddress(symbol); },
            .art_symbol_prefix_resolver = [art](auto symbol) {
                return art->getSymbPrefixFirstAddress(symbol);
            },
        })) return false;
    jni::RegisterHookBridge(env);
    return !env->ExceptionCheck();
}

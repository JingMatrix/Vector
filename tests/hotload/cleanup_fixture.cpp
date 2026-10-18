// Host fixture only: install callback records without modifying any ART methods.
// HookItem, the callback operations and JNIMonitor are extracted verbatim by run_tests.py.
#include <jni.h>
#include <atomic>
#include <limits>
#include <map>
#include <memory>
#include <shared_mutex>
#include <parallel_hashmap/phmap.h>

#define DISALLOW_COPY_AND_ASSIGN(Type) \
    Type(const Type&) = delete; Type& operator=(const Type&) = delete
namespace lsplant {
// INSERT_PRODUCTION_MONITOR
}

#define JNI_START [[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz
#define VECTOR_DEF_NATIVE_METHOD(ret, owner, name, ...) \
    extern "C" JNIEXPORT ret JNICALL Java_org_matrix_vector_nativebridge_##owner##_##name( \
        JNI_START, ##__VA_ARGS__)

// INSERT_PRODUCTION_REGISTRY
// INSERT_PRODUCTION_OPERATIONS

extern "C" JNIEXPORT void JNICALL Java_HookCleanupTest_install(
        JNIEnv* env, jclass, jobject method, jobject record, jboolean modern, jint priority) {
    HookItem* item = nullptr;
    const auto id = env->FromReflectedMethod(method);
    hooked_methods.lazy_emplace_l(id,
        [&](auto& it) { item = it.second.get(); },
        [&](const auto& ctor) {
            auto created = std::make_unique<HookItem>();
            created->SetBackup(env->NewGlobalRef(method));
            item = created.get();
            ctor(id, std::move(created));
        });
    lsplant::JNIMonitor lock(env, item->GetBackup());
    auto& callbacks = modern ? item->modern_callbacks : item->legacy_callbacks;
    callbacks.emplace(priority, env->NewGlobalRef(record));
}

thread_local const JNINativeInterface_* original_jni;
thread_local int array_allocations;
jobjectArray counted_array(JNIEnv* env, jsize size, jclass type, jobject init) {
    ++array_allocations;
    return original_jni->NewObjectArray(env, size, type, init);
}
extern "C" JNIEXPORT jint JNICALL Java_HookCleanupTest_snapshotAllocations(
        JNIEnv* env, jclass, jclass record_type, jobject method) {
    original_jni = env->functions;
    auto counted = *original_jni;
    counted.NewObjectArray = counted_array;
    array_allocations = 0;
    env->functions = &counted;
    auto result = Java_org_matrix_vector_nativebridge_HookBridge_callbackSnapshot(
        env, nullptr, record_type, method);
    env->functions = original_jni;
    if (result) env->DeleteLocalRef(result);
    return array_allocations;
}

// These two originals use host reflection: the fixture never installs an ART trampoline.
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, invokeOriginalMethod,
        jobject method, jobject receiver, jobjectArray args) {
    auto type = env->FindClass("java/lang/reflect/Method");
    auto invoke = env->GetMethodID(type, "invoke",
        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    return env->CallObjectMethod(method, invoke, receiver, args);
}
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, invokeOriginal,
        jobject method, jcharArray, jobjectArray, jclass, jboolean, jboolean,
        jobject receiver, jobjectArray args) {
    return Java_org_matrix_vector_nativebridge_HookBridge_invokeOriginalMethod(
        env, clazz, method, receiver, args);
}

extern "C" JNIEXPORT void JNICALL Java_HookCleanupTest_releaseFixture(JNIEnv* env, jclass) {
    // Tests have joined every reader before releasing fixture-owned JNI references.
    for (auto& entry : hooked_methods) {
        auto& item = *entry.second;
        for (auto& callback : item.modern_callbacks) env->DeleteGlobalRef(callback.second);
        for (auto& callback : item.legacy_callbacks) env->DeleteGlobalRef(callback.second);
        env->DeleteGlobalRef(item.GetBackup());
    }
    hooked_methods.clear();
}

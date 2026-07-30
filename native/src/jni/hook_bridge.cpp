#include <alloca.h>
#include <parallel_hashmap/phmap.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <lsplant.hpp>
#include <limits>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <vector>

#include "jni/jni_bridge.h"
#include "jni/jni_hooks.h"

namespace {

/**
 * @struct HookItem
 * @brief Holds all state associated with a single hooked method.
 *
 * This includes lists of all registered callback functions
 * (both modern and legacy), sorted by priority.
 *
 * It also manages a thread-safe "backup" object,
 * which is a handle to the original, un-hooked method.
 */
struct HookItem {
    // Callbacks are stored in multimaps, keyed by priority.
    // std::greater<> ensures that higher priority numbers are processed first.
    std::multimap<jint, jobject, std::greater<>> legacy_callbacks;
    std::multimap<jint, jobject, std::greater<>> modern_callbacks;

private:
    // The backup is an atomic jobject.
    // This is crucial for thread safety during the initial hooking process.
    // It can be in one of three states:
    // - nullptr: The hook has not been initialized yet.
    // - FAILED: The hook attempt failed.
    // - A valid jobject: A handle to the original method.
    std::atomic<jobject> backup{nullptr};
    static_assert(decltype(backup)::is_always_lock_free);
    // A sentinel value to indicate that the hooking process failed.
    inline static jobject FAILED = reinterpret_cast<jobject>(std::numeric_limits<uintptr_t>::max());

public:
    /**
     * @brief Atomically and safely retrieves the backup method handle.
     * If another thread is currently setting up the hook, this method will wait until
     * the process is complete, to prevent race conditions.
     */
    jobject GetBackup() {
        // Wait until the 'backup' atomic is no longer nullptr.
        backup.wait(nullptr, std::memory_order_acquire);
        if (auto bk = backup.load(std::memory_order_relaxed); bk != FAILED) {
            return bk;
        } else {
            return nullptr;
        }
    }

    /**
     * @brief Atomically sets the backup method handle once after hooking.
     * This method uses compare_exchange_strong to ensure it only sets the value once.
     * After setting, it notifies any waiting threads.
     */
    void SetBackup(jobject newBackup) {
        jobject null = nullptr;
        // Attempt to transition from nullptr to the new backup (or FAILED).
        // memory_order_acq_rel ensures memory synchronization
        // with both waiting threads (acquire) and subsequent reads (release).
        backup.compare_exchange_strong(null, newBackup ? newBackup : FAILED,
                                       std::memory_order_acq_rel, std::memory_order_relaxed);
        // Wake up all threads that were waiting in GetBackup().
        backup.notify_all();
    }
};

// A type alias for a thread-safe parallel hash map.
// This map is the central registry, mapping a method's ID to its HookItem.
// It uses a std::shared_mutex to allow concurrent reads but exclusive writes.
template <class K, class V, class Hash = phmap::priv::hash_default_hash<K>,
          class Eq = phmap::priv::hash_default_eq<K>,
          class Alloc = phmap::priv::Allocator<phmap::priv::Pair<const K, V>>, size_t N = 4>
using SharedHashMap = phmap::parallel_flat_hash_map<K, V, Hash, Eq, Alloc, N, std::shared_mutex>;

// The global map of all hooked methods.
SharedHashMap<jmethodID, std::unique_ptr<HookItem>> hooked_methods;

// Cached JNI method and field IDs for performance.
jmethodID invoke = nullptr;

/**
 * Depth of the framework's own hook dispatch on this thread.
 *
 * Non-zero means a hooked method was entered from inside the dispatch itself, which must run the
 * original rather than start a second dispatch. The dispatch cannot promise to avoid hookable
 * methods, because it does not get to choose which ones it calls: AGP 9's R8 compiles Kotlin's
 * parameter null checks into Object.getClass(), and one of those lands as the first instruction of
 * the callback. A module hooking such a method would otherwise make the dispatch re-enter itself
 * until the stack is gone, before any hooker runs. See #798.
 *
 * The counter is read before any Java frame exists, which is why the trampoline entry point is a
 * native method: nothing a compiler emits can run ahead of it.
 */
thread_local uint32_t dispatch_depth = 0;

/**
 * How deep hook dispatch is nested on this thread, counting only dispatches that actually entered
 * Java. Unlike dispatch_depth this is never suspended, because it exists to bound the one thing the
 * guard cannot reach: a hooker that calls the method it hooks, directly or through a call the
 * compiler generated for it. That recursion lives entirely in module code, runs with the guard
 * down by design, and would otherwise take the process out with it.
 *
 * Thirty-two is deep enough that no chain of one hook calling into another will reach it, and
 * shallow enough that a runaway costs a small constant instead of thousands of frames. A method
 * whose hooks genuinely nest deeper than this - a hooked recursive method, say - stops being hooked
 * past the limit, which is why hitting it is reported.
 */
thread_local uint32_t dispatch_nesting = 0;
constexpr uint32_t kMaxDispatchNesting = 32;

/**
 * Set when the cap is reached, cleared when the thread unwinds back out of dispatch entirely.
 *
 * Without it the cap bounds depth but not work. A hooker that re-enters more than once per frame -
 * two calls, or one call the compiler wrote twice - branches at every level, so re-arming the cap on
 * the way back down turns one call into a tree of that many dispatches. Latching instead means the
 * thread serves originals from the moment it is in trouble until it is out of it.
 */
thread_local bool dispatch_degraded = false;

// Bounded so a runaway cannot itself flood the log. Eight is enough to name several distinct
// offenders in one boot.
std::atomic<int> runaway_reports{0};
constexpr int kMaxRunawayReports = 8;

// Resolved from the hooker class the first time a hook is installed. That call is the only place
// the class is available: the framework dex is repackaged per build, so its name cannot be
// looked up, and it is also the earliest moment anything can reach the trampoline.
std::once_flag hooker_init;
bool hooker_ready = false;
jmethodID hooker_dispatch = nullptr;
jfieldID hooker_method = nullptr;
jfieldID hooker_is_static = nullptr;
jclass object_class = nullptr;
jmethodID to_string = nullptr;
jclass invocation_target_exception = nullptr;
jmethodID get_cause = nullptr;

/**
 * @brief Invokes a hooked method's original implementation, or the method itself if unhooked.
 */
jobject InvokeBackup(JNIEnv *env, jobject executable, jobject thiz, jobjectArray args) {
    auto target = env->FromReflectedMethod(executable);
    HookItem *hook_item = nullptr;
    hooked_methods.if_contains(target,
                               [&hook_item](const auto &it) { hook_item = it.second.get(); });

    // If a hook item exists, invoke its backup. Otherwise, invoke the method directly
    // (though this case should be rare if called from a hook callback).
    jobject method_to_invoke = hook_item ? hook_item->GetBackup() : executable;
    if (!method_to_invoke) {
        // Hooking might have failed or is not complete.
        return nullptr;
    }
    return env->CallObjectMethod(method_to_invoke, invoke, thiz, args);
}

/**
 * @brief Serves a trampoline hit that arrived from inside the dispatch, without entering Java.
 *
 * Runs the original and hands its exception back unwrapped, the way the call site that the
 * compiler generated would have seen it. Nothing here may call back into the framework: this path
 * exists precisely because the framework is already on the stack below it.
 */
jobject InvokeOriginalReentrant(JNIEnv *env, jobject hooker, jobjectArray args) {
    jobject executable = env->GetObjectField(hooker, hooker_method);
    jobject receiver = nullptr;
    jobjectArray actual = args;

    if (!env->GetBooleanField(hooker, hooker_is_static)) {
        receiver = env->GetObjectArrayElement(args, 0);
        const jsize count = env->GetArrayLength(args) - 1;
        actual = env->NewObjectArray(count, object_class, nullptr);
        for (jsize i = 0; i < count; ++i) {
            jobject arg = env->GetObjectArrayElement(args, i + 1);
            env->SetObjectArrayElement(actual, i, arg);
            env->DeleteLocalRef(arg);
        }
    }

    jobject result = InvokeBackup(env, executable, receiver, actual);

    // Method.invoke reports whatever the target threw wrapped; the caller here is ordinary code
    // that never went through reflection, so hand it the cause instead.
    if (jthrowable thrown = env->ExceptionOccurred(); thrown) {
        env->ExceptionClear();
        jthrowable to_throw = thrown;
        if (env->IsInstanceOf(thrown, invocation_target_exception)) {
            if (auto cause = env->CallObjectMethod(thrown, get_cause); cause) {
                to_throw = static_cast<jthrowable>(cause);
            }
        }
        env->Throw(to_throw);
        return nullptr;
    }
    return result;
}

/**
 * @brief Names the method whose hooks stopped nesting, at most a few times per process.
 *
 * Reads the executable and asks it for its name only here, on the rare path, so the dispatch does
 * not pay for a diagnostic it almost never emits. The guard is raised for the duration because
 * Executable.toString() is ordinary Java and may itself touch whatever the module hooked.
 */
void ReportRunaway(JNIEnv *env, jobject hooker) {
    if (runaway_reports.fetch_add(1, std::memory_order_relaxed) >= kMaxRunawayReports) return;

    ++dispatch_depth;
    jobject executable = env->GetObjectField(hooker, hooker_method);
    auto name = static_cast<jstring>(env->CallObjectMethod(executable, to_string));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (name) {
        if (const char *chars = env->GetStringUTFChars(name, nullptr); chars) {
            LOGE(
                "Hook dispatch nested past {} for {}; running the original instead. A hooker that "
                "calls the method it hooks recurses into itself, and the compiler emits such calls "
                "on its behalf - a null check becomes Object.getClass(), for one.",
                kMaxDispatchNesting, chars);
            env->ReleaseStringUTFChars(name, chars);
        }
    }
    if (name) env->DeleteLocalRef(name);
    env->DeleteLocalRef(executable);
    --dispatch_depth;
}

/**
 * @brief The trampoline entry point, registered onto VectorNativeHooker.callback.
 *
 * Native so that the re-entrancy check is genuinely first: a Kotlin body would let the compiler
 * put its own prologue ahead of it, which is the whole of #798.
 */
jobject HookerCallback(JNIEnv *env, jobject hooker, jobjectArray args) {
    if (dispatch_depth != 0) {
        return InvokeOriginalReentrant(env, hooker, args);
    }

    if (dispatch_degraded || dispatch_nesting >= kMaxDispatchNesting) {
        if (!dispatch_degraded) {
            dispatch_degraded = true;
            ReportRunaway(env, hooker);
        }
        return InvokeOriginalReentrant(env, hooker, args);
    }

    ++dispatch_nesting;
    ++dispatch_depth;
    jobject result = env->CallObjectMethod(hooker, hooker_dispatch, args);
    // Restored on the exception path too: CallObjectMethod returns with the exception pending, and
    // leaving either counter raised would silently disable every hook on this thread from here on.
    --dispatch_depth;
    if (--dispatch_nesting == 0) dispatch_degraded = false;
    return result;
}
}  // namespace

namespace vector::native::jni {
/**
 * @brief JNI method to install a hook on a given method or constructor.
 * @param useModernApi Distinguishes between the legacy and modern callback
 * types.
 * @param hookMethod The java.lang.reflect.Executable to be hooked.
 * @param hooker The Java class that acts as the hook trampoline.
 * @param priority The priority of this callback.
 * @param callback The Java callback object.
 * @return JNI_TRUE on success, JNI_FALSE on failure.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, hookMethod, jboolean useModernApi,
                         jobject hookMethod, jclass hooker, jint priority, jobject callback) {
    bool newHook = false;

#ifndef NDEBUG
    // Simple RAII struct for performance timing in debug builds.
    struct finally {
        std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
        bool &newHook;
        ~finally() {
            auto finish = std::chrono::steady_clock::now();
            if (newHook) {
                LOGV("New hook took {}us",
                     std::chrono::duration_cast<std::chrono::microseconds>(finish - start).count());
            }
        }
    } finally{.newHook = newHook};
#endif

    // The hooker class arrives only here, and this call is also the earliest moment anything can
    // reach its trampoline, so bind the native entry point and the members it reads now.
    std::call_once(hooker_init, [env, hooker] {
        static const JNINativeMethod entry[] = {
            {"callback", "([Ljava/lang/Object;)Ljava/lang/Object;",
             VECTOR_JNI_CAST(void *)(HookerCallback)},
        };
        if (env->RegisterNatives(hooker, entry, 1) != JNI_OK) {
            LOGF("Cannot register the hook trampoline entry point");
            return;
        }
        hooker_dispatch =
            env->GetMethodID(hooker, "dispatch", "([Ljava/lang/Object;)Ljava/lang/Object;");
        hooker_method = env->GetFieldID(hooker, "method", "Ljava/lang/reflect/Executable;");
        hooker_is_static = env->GetFieldID(hooker, "isStatic", "Z");

        auto object = env->FindClass("java/lang/Object");
        object_class = static_cast<jclass>(env->NewGlobalRef(object));
        to_string = env->GetMethodID(object, "toString", "()Ljava/lang/String;");
        env->DeleteLocalRef(object);

        auto ite = env->FindClass("java/lang/reflect/InvocationTargetException");
        invocation_target_exception = static_cast<jclass>(env->NewGlobalRef(ite));
        env->DeleteLocalRef(ite);

        auto throwable = env->FindClass("java/lang/Throwable");
        get_cause = env->GetMethodID(throwable, "getCause", "()Ljava/lang/Throwable;");
        env->DeleteLocalRef(throwable);

        hooker_ready = hooker_dispatch && hooker_method && hooker_is_static && object_class &&
                       to_string && invocation_target_exception && get_cause;
        if (!hooker_ready) LOGF("Cannot resolve the hook trampoline members");
    });

    // Refusing the hook is the only honest outcome: the trampoline would land on an unregistered
    // native method and throw UnsatisfiedLinkError out of whatever the app was calling, which is
    // a great deal harder to trace back than a module being told its hook did not take.
    if (!hooker_ready) return JNI_FALSE;

    auto target = env->FromReflectedMethod(hookMethod);
    HookItem *hook_item = nullptr;

    // Atomically find or create an entry for the target method.
    // This is a highly concurrent operation.
    hooked_methods.lazy_emplace_l(
        target,
        // Lambda for existing element: just get the pointer.
        [&hook_item](auto &it) { hook_item = it.second.get(); },
        // Lambda for new element: create the HookItem and mark it as a new hook.
        [&hook_item, &target, &newHook](const auto &ctor) {
            auto ptr = std::make_unique<HookItem>();
            hook_item = ptr.get();
            ctor(target, std::move(ptr));
            newHook = true;
        });

    // If this is the first time this method is being hooked,
    // we need to perform the actual native hook using lsplant.
    if (newHook) {
        auto init = env->GetMethodID(hooker, "<init>", "(Ljava/lang/reflect/Executable;)V");
        auto callback_method = env->ToReflectedMethod(
            hooker, env->GetMethodID(hooker, "callback", "([Ljava/lang/Object;)Ljava/lang/Object;"),
            false);
        auto hooker_object = env->NewObject(hooker, init, hookMethod);
        // Use lsplant to replace the target method with our trampoline.
        // The returned jobject is a handle to the original method.
        hook_item->SetBackup(lsplant::Hook(env, hookMethod, hooker_object, callback_method));
        env->DeleteLocalRef(hooker_object);
    }

    // Wait for the backup to become available (it might be set by another thread).
    jobject backup = hook_item->GetBackup();
    if (!backup) return JNI_FALSE;

    // Use an RAII monitor to lock the backup object,
    // ensuring thread-safe modification of the callback lists.
    lsplant::JNIMonitor monitor(env, backup);

    // Store a global reference to the callback object itself.
    if (useModernApi) {
        hook_item->modern_callbacks.emplace(priority, env->NewGlobalRef(callback));
    } else {
        hook_item->legacy_callbacks.emplace(priority, env->NewGlobalRef(callback));
    }
    return JNI_TRUE;
}

/**
 * @brief JNI method to remove a previously installed hook callback.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, unhookMethod, jboolean useModernApi,
                         jobject hookMethod, jobject callback) {
    auto target = env->FromReflectedMethod(hookMethod);
    HookItem *hook_item = nullptr;
    // Find the HookItem for the target method.
    hooked_methods.if_contains(target,
                               [&hook_item](const auto &it) { hook_item = it.second.get(); });
    if (!hook_item) return JNI_FALSE;

    jobject backup = hook_item->GetBackup();
    if (!backup) return JNI_FALSE;

    // Lock to safely modify the callback list.
    lsplant::JNIMonitor monitor(env, backup);

    // Select the correct multimap
    auto &callbacks = useModernApi ? hook_item->modern_callbacks : hook_item->legacy_callbacks;

    // Find the callback by comparing the jobject directly.
    for (auto i = callbacks.begin(); i != callbacks.end(); ++i) {
        if (env->IsSameObject(i->second, callback)) {
            env->DeleteGlobalRef(i->second);  // Clean up the global reference.
            callbacks.erase(i);
            return JNI_TRUE;
        }
    }

    return JNI_FALSE;
}

/**
 * @brief JNI method to request de-optimization of a method.
 * This can be necessary for some types of hooks to work correctly on JIT-compiled methods.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, deoptimizeMethod, jobject hookMethod) {
    return lsplant::Deoptimize(env, hookMethod);
}

/**
 * @brief JNI method to invoke the original, un-hooked method.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, invokeOriginalMethod, jobject hookMethod,
                         jobject thiz, jobjectArray args) {
    return InvokeBackup(env, hookMethod, thiz, args);
}

/**
 * @brief Lowers the dispatch guard for the duration of a call into module code.
 *
 * A hooker, and the original method the chain ends in, are entitled to a full dispatch of whatever
 * they call; only the framework's own bookkeeping must not re-enter. The previous depth is
 * returned rather than assumed to be one, so nesting restores exactly what it found.
 */
VECTOR_DEF_NATIVE_METHOD(jint, HookBridge, suspendDispatch) {
    const auto saved = dispatch_depth;
    dispatch_depth = 0;
    return static_cast<jint>(saved);
}

/** @brief Restores the depth returned by suspendDispatch. */
VECTOR_DEF_NATIVE_METHOD(void, HookBridge, resumeDispatch, jint depth) {
    dispatch_depth = static_cast<uint32_t>(depth);
}

/**
 * @brief Raises the guard over a stretch of framework code, returning the depth to restore.
 *
 * The chain is re-entered from module code, with the guard already down, so it has to raise the
 * guard for its own bookkeeping rather than assume it holds. Allocating one chain node calls
 * getClass twice - R8's null checks on the constructor's parameters - and without this each of
 * those would dispatch, allocate another node, and call getClass again.
 */
VECTOR_DEF_NATIVE_METHOD(jint, HookBridge, raiseDispatch) {
    const auto saved = dispatch_depth;
    dispatch_depth = 1;
    return static_cast<jint>(saved);
}

/**
 * @brief JNI wrapper around AllocObject.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, allocateObject, jclass cls) {
    return env->AllocObject(cls);
}

/**
 * Core JNI backend for non-virtual method invocation and special object initialization.
 *
 * Implementation details:
 * 1. Dispatches using JNI CallNonvirtual<Type>MethodA.
 * 2. Employs stack allocation (alloca) for JNI argument mapping.
 * 3. Safely mirrors standard Java reflection (NPEs on null primitives/receivers).
 * 4. Prevents JNI Type Confusion and memory leaks by caching primitive wrappers globally,
 *    while leveraging java.lang.Number for fast implicit widening/narrowing.
 * 5. Accurately catches and wraps target method exceptions into InvocationTargetException.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, invokeSpecialMethod, jobject method,
                         jcharArray shorty, jclass cls, jobject thiz, jobjectArray args) {
    // --- JNI Global Reference Caching ---
    // Cached once per process lifecycle to maintain extreme performance and prevent JNI aborts.
    static jclass cls_Number = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Number"));
    static jclass cls_Boolean = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Boolean"));
    static jclass cls_Character = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Character"));

    // Globally cache primitive wrapper classes for safe return value boxing
    static jclass cls_Integer = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Integer"));
    static jclass cls_Double = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Double"));
    static jclass cls_Long = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Long"));
    static jclass cls_Float = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Float"));
    static jclass cls_Short = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Short"));
    static jclass cls_Byte = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Byte"));

    static jclass cls_ITE =
        (jclass)env->NewGlobalRef(env->FindClass("java/lang/reflect/InvocationTargetException"));

    static auto *const ctor_ite = env->GetMethodID(cls_ITE, "<init>", "(Ljava/lang/Throwable;)V");

    static auto *const get_int = env->GetMethodID(cls_Number, "intValue", "()I");
    static auto *const get_double = env->GetMethodID(cls_Number, "doubleValue", "()D");
    static auto *const get_long = env->GetMethodID(cls_Number, "longValue", "()J");
    static auto *const get_float = env->GetMethodID(cls_Number, "floatValue", "()F");
    static auto *const get_short = env->GetMethodID(cls_Number, "shortValue", "()S");
    static auto *const get_byte = env->GetMethodID(cls_Number, "byteValue", "()B");

    static auto *const get_char = env->GetMethodID(cls_Character, "charValue", "()C");
    static auto *const get_boolean = env->GetMethodID(cls_Boolean, "booleanValue", "()Z");

    static auto *const set_int =
        env->GetStaticMethodID(cls_Integer, "valueOf", "(I)Ljava/lang/Integer;");
    static auto *const set_double =
        env->GetStaticMethodID(cls_Double, "valueOf", "(D)Ljava/lang/Double;");
    static auto *const set_long =
        env->GetStaticMethodID(cls_Long, "valueOf", "(J)Ljava/lang/Long;");
    static auto *const set_float =
        env->GetStaticMethodID(cls_Float, "valueOf", "(F)Ljava/lang/Float;");
    static auto *const set_short =
        env->GetStaticMethodID(cls_Short, "valueOf", "(S)Ljava/lang/Short;");
    static auto *const set_byte =
        env->GetStaticMethodID(cls_Byte, "valueOf", "(B)Ljava/lang/Byte;");
    static auto *const set_char =
        env->GetStaticMethodID(cls_Character, "valueOf", "(C)Ljava/lang/Character;");
    static auto *const set_boolean =
        env->GetStaticMethodID(cls_Boolean, "valueOf", "(Z)Ljava/lang/Boolean;");

    auto target = env->FromReflectedMethod(method);
    auto param_len = env->GetArrayLength(shorty) - 1;

    // --- Argument & Receiver Validation ---
    auto args_len = args != nullptr ? env->GetArrayLength(args) : 0;
    if (args_len != param_len) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "args.length does not match parameter count");
        return nullptr;
    }

    if (thiz == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "null receiver");
        return nullptr;
    }

    // Allocate jvalue array on the stack
    jvalue *a = param_len > 0 ? static_cast<jvalue *>(alloca(param_len * sizeof(jvalue))) : nullptr;

    auto *const shorty_char = env->GetCharArrayElements(shorty, nullptr);
    if (shorty_char == nullptr) {
        return nullptr;  // JVM already threw OutOfMemoryError
    }

    // RAII/Helper for clean JNI array exits
    auto abort_and_return = [&]() {
        env->ReleaseCharArrayElements(shorty, shorty_char, JNI_ABORT);
        return nullptr;
    };

    // --- Safe Unboxing ---
    for (jint i = 0; i != param_len; ++i) {
        jobject element = env->GetObjectArrayElement(args, i);
        if (env->ExceptionCheck()) return abort_and_return();

        char type = shorty_char[i + 1];

        if (element == nullptr) {
            if (type != 'L' && type != '[') {
                env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                              "null primitive argument");
                return abort_and_return();
            }
            a[i].l = nullptr;
        } else {
            if (type == 'Z') {
                if (!env->IsInstanceOf(element, cls_Boolean)) {
                    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                                  "Expected Boolean");
                    return abort_and_return();
                }
                a[i].z = env->CallBooleanMethod(element, get_boolean);
            } else if (type == 'C') {
                if (!env->IsInstanceOf(element, cls_Character)) {
                    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                                  "Expected Character");
                    return abort_and_return();
                }
                a[i].c = env->CallCharMethod(element, get_char);
            } else if (type != 'L' && type != '[') {
                bool is_number = env->IsInstanceOf(element, cls_Number) == JNI_TRUE;
                bool is_character =
                    !is_number && (env->IsInstanceOf(element, cls_Character) == JNI_TRUE);

                if (!is_number && !is_character) {
                    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                                  "Expected Number or Character");
                    return abort_and_return();
                }

                // If a Character is passed to a numeric parameter, extract its value for widening
                jchar c_val = 0;
                if (is_character) {
                    c_val = env->CallCharMethod(element, get_char);
                    if (env->ExceptionCheck()) return abort_and_return();
                }

                switch (type) {
                case 'I':
                    a[i].i = env->CallIntMethod(element, get_int);
                    break;
                case 'D':
                    a[i].d = env->CallDoubleMethod(element, get_double);
                    break;
                case 'J':
                    a[i].j = env->CallLongMethod(element, get_long);
                    break;
                case 'F':
                    a[i].f = env->CallFloatMethod(element, get_float);
                    break;
                case 'S':
                    a[i].s = env->CallShortMethod(element, get_short);
                    break;
                case 'B':
                    a[i].b = env->CallByteMethod(element, get_byte);
                    break;
                }
            } else {
                a[i].l = element;
                element =
                    nullptr;  // Transferred ownership to jvalue array; will be freed on return
            }
        }

        if (element) env->DeleteLocalRef(element);
        if (env->ExceptionCheck()) return abort_and_return();
    }

    // --- Non-virtual Invocation ---
    jvalue ret_val;
    switch (shorty_char[0]) {
    case 'I':
        ret_val.i = env->CallNonvirtualIntMethodA(thiz, cls, target, a);
        break;
    case 'D':
        ret_val.d = env->CallNonvirtualDoubleMethodA(thiz, cls, target, a);
        break;
    case 'J':
        ret_val.j = env->CallNonvirtualLongMethodA(thiz, cls, target, a);
        break;
    case 'F':
        ret_val.f = env->CallNonvirtualFloatMethodA(thiz, cls, target, a);
        break;
    case 'S':
        ret_val.s = env->CallNonvirtualShortMethodA(thiz, cls, target, a);
        break;
    case 'B':
        ret_val.b = env->CallNonvirtualByteMethodA(thiz, cls, target, a);
        break;
    case 'C':
        ret_val.c = env->CallNonvirtualCharMethodA(thiz, cls, target, a);
        break;
    case 'Z':
        ret_val.z = env->CallNonvirtualBooleanMethodA(thiz, cls, target, a);
        break;
    case 'L':
        ret_val.l = env->CallNonvirtualObjectMethodA(thiz, cls, target, a);
        break;
    default:
        env->CallNonvirtualVoidMethodA(thiz, cls, target, a);
        break;
    }

    // --- Exception Wrapping ---
    jthrowable target_exception = env->ExceptionOccurred();
    if (target_exception) {
        env->ExceptionClear();
        jobject ite = env->NewObject(cls_ITE, ctor_ite, target_exception);
        // Ensure NewObject didn't fail due to OOM before throwing
        if (ite) {
            env->Throw(static_cast<jthrowable>(ite));
        }
        return abort_and_return();
    }

    // --- Box Return Value ---
    jobject value = nullptr;
    switch (shorty_char[0]) {
    case 'I':
        value = env->CallStaticObjectMethod(cls_Integer, set_int, ret_val.i);
        break;
    case 'D':
        value = env->CallStaticObjectMethod(cls_Double, set_double, ret_val.d);
        break;
    case 'J':
        value = env->CallStaticObjectMethod(cls_Long, set_long, ret_val.j);
        break;
    case 'F':
        value = env->CallStaticObjectMethod(cls_Float, set_float, ret_val.f);
        break;
    case 'S':
        value = env->CallStaticObjectMethod(cls_Short, set_short, ret_val.s);
        break;
    case 'B':
        value = env->CallStaticObjectMethod(cls_Byte, set_byte, ret_val.b);
        break;
    case 'C':
        value = env->CallStaticObjectMethod(cls_Character, set_char, ret_val.c);
        break;
    case 'Z':
        value = env->CallStaticObjectMethod(cls_Boolean, set_boolean, ret_val.z);
        break;
    case 'L':
        value = ret_val.l;
        break;
    case 'V':
        value = nullptr;
        break;
    }

    env->ReleaseCharArrayElements(shorty, shorty_char, JNI_ABORT);
    return value;
}

/**
 * @brief JNI wrapper around IsInstanceOf.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, instanceOf, jobject object, jclass expected_class) {
    return env->IsInstanceOf(object, expected_class);
}

/**
 * @brief JNI wrapper to mark a DEX file loaded from memory as trusted.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, setTrusted, jobject cookie) {
    return lsplant::MakeDexFileTrusted(env, cookie);
}

/**
 * @brief Creates a snapshot of all registered callbacks for a given method.
 * This is useful for debugging and introspection from the Java side.

 * @return An Object[2][] array where index 0 contains modern callbacks and
 *         index 1 contains legacy callbacks.
 */
VECTOR_DEF_NATIVE_METHOD(jobjectArray, HookBridge, callbackSnapshot, jclass callback_class,
                         jobject method) {
    auto target = env->FromReflectedMethod(method);
    HookItem *hook_item = nullptr;
    hooked_methods.if_contains(target,
                               [&hook_item](const auto &it) { hook_item = it.second.get(); });
    if (!hook_item) return nullptr;

    jobject backup = hook_item->GetBackup();
    if (!backup) return nullptr;

    // Lock to ensure a consistent snapshot of the callback lists.
    lsplant::JNIMonitor monitor(env, backup);

    // Get the generic Object class
    jclass obj_class = env->FindClass("java/lang/Object");

    // Construct the result array Object[2][]
    // Use an existing array to reliably get the Class for Object[]
    jobjectArray dummy_array = env->NewObjectArray(0, obj_class, nullptr);
    jclass obj_array_class = env->GetObjectClass(dummy_array);
    jobjectArray res = env->NewObjectArray(2, obj_array_class, nullptr);

    // Create modern and legacy arrays
    // Use 'callback_class' (VectorHookRecord) for the modern array for strict type safety
    jobjectArray modern =
        env->NewObjectArray((jsize)hook_item->modern_callbacks.size(), callback_class, nullptr);
    jobjectArray legacy =
        env->NewObjectArray((jsize)hook_item->legacy_callbacks.size(), obj_class, nullptr);

    jsize i = 0;
    for (const auto &callback_pair : hook_item->modern_callbacks) {
        env->SetObjectArrayElement(modern, i++, callback_pair.second);
    }

    i = 0;
    for (const auto &callback_pair : hook_item->legacy_callbacks) {
        env->SetObjectArrayElement(legacy, i++, callback_pair.second);
    }

    env->SetObjectArrayElement(res, 0, modern);
    env->SetObjectArrayElement(res, 1, legacy);
    env->DeleteLocalRef(modern);
    env->DeleteLocalRef(legacy);
    return res;
}

/**
 * @brief Reports whether the pages spanning [addr, addr + len) are mapped.
 *
 * msync on an unmapped range fails with ENOMEM, which turns a read that would raise SIGSEGV into
 * an answer. Used for the one candidate below that cannot be bracketed by known-good members.
 */
static bool IsMapped(uintptr_t addr, size_t len) {
    static const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    if (page == 0) return false;
    const uintptr_t start = addr & ~(page - 1);
    const size_t span = ((addr + len) - start + page - 1) & ~(page - 1);
    return msync(reinterpret_cast<void *>(start), span, MS_ASYNC) == 0;
}

/**
 * @brief Finds a class's static initializer without initializing the class.
 *
 * GetStaticMethodID cannot be used: JNI specifies that resolving a method id initializes the
 * class, which is exactly the event a <clinit> hook exists to observe. Java reflection cannot be
 * used either, because it hides <clinit> entirely.
 *
 * ART stores a class's ArtMethods in one contiguous array: direct methods, then declared virtual
 * methods, then methods copied in from interfaces. Reflection reports every one of the first two
 * groups except <clinit>, so the addresses the caller passes are a run of evenly spaced slots with
 * <clinit> missing from it. Finding the hole finds the method, and a hole is bracketed by two
 * members that are known to be inside the array, so nothing has to be assumed about where the
 * array begins.
 *
 * The hole is only at the very start - below every address the caller can see - when no declared
 * direct method sorts ahead of "<clinit>". Dex method ids are ordered by name, and while "<init>"
 * does sort after it, '$' and '-' do not: an enum's $values, and the -$$Nest$ accessors javac
 * emits for nestmates, both take the first slot instead. So the slot below the run is one
 * possibility among several rather than the answer, and it is the only one that can fall outside
 * the array, which is what a class with no static initializer looks like. It is checked last and
 * only once its page is known to be mapped.
 *
 * Every candidate is then confirmed by two plain word reads before anything dereferences it: its
 * declaring class must match the run's, and its access flags must say static constructor.
 *
 * The caller passes ArtMethod addresses read from java.lang.reflect.Executable.artMethod rather
 * than jmethodIDs, because a Java-debuggable process hands out index based ids instead of
 * pointers.
 *
 * @return The static initializer as a reflected object, or nullptr if the class has none or the
 *         layout is not what this relies on.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, findStaticInitializer, jclass target_class,
                         jlongArray art_methods, jlong art_method_size) {
    const jsize count = art_methods ? env->GetArrayLength(art_methods) : 0;
    // One member is enough to anchor the run; the element size is a property of the runtime, so
    // the caller derives it once elsewhere. A class whose only members are <clinit> and an
    // implicit constructor leaves exactly one member visible to reflection, and that is the
    // commonest shape for wanting this hook.
    if (count < 1) return nullptr;

    std::vector<uintptr_t> ids(count);
    {
        std::vector<jlong> raw(count);
        env->GetLongArrayRegion(art_methods, 0, count, raw.data());
        for (jsize i = 0; i < count; ++i) {
            auto id = static_cast<uintptr_t>(raw[i]);
            if (id < 0x1000 || (id % alignof(void *)) != 0) return nullptr;
            ids[i] = id;
        }
    }

    std::sort(ids.begin(), ids.end());
    const auto stride = static_cast<uintptr_t>(art_method_size);
    // An ArtMethod is a few dozen bytes on every supported release; refuse rather than guess when
    // the size is not one a contiguous method array could have.
    if (stride < 16 || stride > 128 || (stride % alignof(void *)) != 0) return nullptr;

    // Slots the caller cannot see. Interior ones come first because each is bracketed by a member
    // on either side, so it is inside the array whatever the class turns out to look like. A class
    // may have more than one hole: reflection also hides members the hidden API policy blocks.
    constexpr size_t kMaxCandidates = 64;
    std::vector<uintptr_t> candidates;
    for (size_t i = 1; i < ids.size(); ++i) {
        const uintptr_t delta = ids[i] - ids[i - 1];
        // Uneven spacing means these are not one run of ArtMethods and none of this holds.
        if (delta == 0 || (delta % stride) != 0) return nullptr;
        for (uintptr_t slot = ids[i - 1] + stride; slot < ids[i]; slot += stride) {
            if (candidates.size() >= kMaxCandidates) return nullptr;
            candidates.push_back(slot);
        }
    }
    // The slot below the run, which may be outside the array altogether.
    const uintptr_t below = ids.front() - stride;
    if (IsMapped(below, 2 * sizeof(uint32_t))) candidates.push_back(below);

    // ArtMethod starts with GcRoot<mirror::Class> declaring_class_ followed by uint32_t
    // access_flags_, so both live in the first eight bytes of a slot.
    const auto declaring_of = [](uintptr_t m) { return *reinterpret_cast<const uint32_t *>(m); };
    const auto flags_of = [](uintptr_t m) {
        return *reinterpret_cast<const uint32_t *>(m + sizeof(uint32_t));
    };

    constexpr uint32_t kAccStatic = 0x0008;
    constexpr uint32_t kAccConstructor = 0x00010000;
    const uint32_t declaring = declaring_of(ids.front());

    for (const uintptr_t candidate : candidates) {
        if (declaring_of(candidate) != declaring) continue;
        const uint32_t flags = flags_of(candidate);
        if ((flags & kAccStatic) == 0 || (flags & kAccConstructor) == 0) continue;
        return env->ToReflectedMethod(target_class, reinterpret_cast<jmethodID>(candidate),
                                      JNI_TRUE);
    }
    return nullptr;
}

// Array of native method descriptors for JNI registration.
static JNINativeMethod gMethods[] = {
    VECTOR_NATIVE_METHOD(HookBridge, hookMethod,
                         "(ZLjava/lang/reflect/Executable;Ljava/lang/Class;ILjava/"
                         "lang/Object;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, unhookMethod,
                         "(ZLjava/lang/reflect/Executable;Ljava/lang/Object;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, deoptimizeMethod, "(Ljava/lang/reflect/Executable;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, invokeOriginalMethod,
                         "(Ljava/lang/reflect/Executable;Ljava/lang/Object;[Ljava/"
                         "lang/Object;)Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, invokeSpecialMethod,
                         "(Ljava/lang/reflect/Executable;[CLjava/lang/Class;Ljava/"
                         "lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, allocateObject, "(Ljava/lang/Class;)Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, instanceOf, "(Ljava/lang/Object;Ljava/lang/Class;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, setTrusted, "(Ljava/lang/Object;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, callbackSnapshot,
                         "(Ljava/lang/Class;Ljava/lang/reflect/"
                         "Executable;)[[Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, findStaticInitializer,
                         "(Ljava/lang/Class;[JJ)Ljava/lang/reflect/Executable;"),
    VECTOR_NATIVE_METHOD(HookBridge, suspendDispatch, "()I"),
    VECTOR_NATIVE_METHOD(HookBridge, resumeDispatch, "(I)V"),
    VECTOR_NATIVE_METHOD(HookBridge, raiseDispatch, "()I"),
};

/**
 * @brief Registers all native methods with the JVM when the library is loaded.
 */
void RegisterHookBridge(JNIEnv *env) {
    // Cache the Method.invoke methodID for use in invokeOriginalMethod.
    jclass method = env->FindClass("java/lang/reflect/Method");
    invoke = env->GetMethodID(method, "invoke",
                              "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(method);
    REGISTER_VECTOR_NATIVE_METHODS(HookBridge);
}
}  // namespace vector::native::jni

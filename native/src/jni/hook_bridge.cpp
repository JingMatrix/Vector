#include <alloca.h>
#include <parallel_hashmap/phmap.h>

#include <lsplant.hpp>
#include <memory>
#include <shared_mutex>

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
    auto target = env->FromReflectedMethod(hookMethod);
    HookItem *hook_item = nullptr;
    hooked_methods.if_contains(target,
                               [&hook_item](const auto &it) { hook_item = it.second.get(); });

    // If a hook item exists, invoke its backup. Otherwise, invoke the method directly
    // (though this case should be rare if called from a hook callback).
    jobject method_to_invoke = hook_item ? hook_item->GetBackup() : hookMethod;
    if (!method_to_invoke) {
        // Hooking might have failed or is not complete.
        return nullptr;
    }
    return env->CallObjectMethod(method_to_invoke, invoke, thiz, args);
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
 * @brief  Retrieves the static initializer (<clinit>) of a class as a Method object.
 * @param target_class The class to inspect.
 * @return A Method object for the static initializer, or null if it doesn't exist.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, getStaticInitializer, jclass target_class) {
    // <clinit> is the internal name for a static initializer.
    // Its signature is always ()V (no arguments, void return).
    jmethodID mid = env->GetStaticMethodID(target_class, "<clinit>", "()V");
    if (!mid) {
        // If GetStaticMethodID fails, it throws an exception.
        // We clear it and return null to let the Java side handle it gracefully.
        env->ExceptionClear();
        return nullptr;
    }
    // Convert the method ID to a java.lang.reflect.Method object.
    // The last parameter must be JNI_TRUE because it's a static method.
    return env->ToReflectedMethod(target_class, mid, JNI_TRUE);
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
    VECTOR_NATIVE_METHOD(HookBridge, getStaticInitializer,
                         "(Ljava/lang/Class;)Ljava/lang/reflect/Method;"),
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

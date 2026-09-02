#include <alloca.h>
#include <parallel_hashmap/phmap.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <lsplant.hpp>
#include <limits>
#include <memory>
#include <shared_mutex>
#include <vector>

#include "core/config_bridge.h"
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"
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
 * @struct PrimitiveWrapper
 * @brief One boxed primitive type, with its own accessor and its own valueOf.
 *
 * The accessor has to be the wrapper's own. java.lang.Character is not a java.lang.Number, so
 * calling Number.intValue() on a Character reads Number's vtable index out of Character's vtable,
 * which lands on an unrelated method or past the end of it.
 */
struct PrimitiveWrapper {
    char shorty;
    jclass clazz;
    jmethodID unbox;
    jmethodID box;
};

constexpr size_t kWrapperCount = 8;

// The eight wrappers, resolved once and held as global references.
struct WrapperTable {
    PrimitiveWrapper entries[kWrapperCount];

    explicit WrapperTable(JNIEnv *env) {
        // Ordered by how often an argument turns out to be one: identifying an argument's wrapper
        // is a walk of this table comparing its class against each entry's.
        static constexpr struct {
            char shorty;
            const char *name;
            const char *accessor;
            const char *accessor_signature;
            const char *box_signature;
        } kSpecs[kWrapperCount] = {
            {'I', "java/lang/Integer", "intValue", "()I", "(I)Ljava/lang/Integer;"},
            {'Z', "java/lang/Boolean", "booleanValue", "()Z", "(Z)Ljava/lang/Boolean;"},
            {'J', "java/lang/Long", "longValue", "()J", "(J)Ljava/lang/Long;"},
            {'D', "java/lang/Double", "doubleValue", "()D", "(D)Ljava/lang/Double;"},
            {'F', "java/lang/Float", "floatValue", "()F", "(F)Ljava/lang/Float;"},
            {'C', "java/lang/Character", "charValue", "()C", "(C)Ljava/lang/Character;"},
            {'B', "java/lang/Byte", "byteValue", "()B", "(B)Ljava/lang/Byte;"},
            {'S', "java/lang/Short", "shortValue", "()S", "(S)Ljava/lang/Short;"},
        };

        for (size_t i = 0; i < kWrapperCount; ++i) {
            jclass local = env->FindClass(kSpecs[i].name);
            entries[i].shorty = kSpecs[i].shorty;
            entries[i].clazz = static_cast<jclass>(env->NewGlobalRef(local));
            entries[i].unbox =
                env->GetMethodID(local, kSpecs[i].accessor, kSpecs[i].accessor_signature);
            entries[i].box = env->GetStaticMethodID(local, "valueOf", kSpecs[i].box_signature);
            env->DeleteLocalRef(local);
        }
    }
};

const WrapperTable &Wrappers(JNIEnv *env) {
    static const WrapperTable table(env);
    return table;
}

// The wrapper an argument actually is, which is what decides whether the conversion the parameter
// asks for is a widening one. Null for anything that is not a boxed primitive.
//
// Exact class identity, not IsInstanceOf: one JNI call and then pointer comparisons, instead of up
// to eight round trips per argument on the framework's own invocation path. It is also what the
// widening matrix means. No class can extend a wrapper - all eight are final - but plenty extend
// java.lang.Number, and reflection converts none of them.
const PrimitiveWrapper *WrapperOf(JNIEnv *env, jobject value) {
    jclass value_class = env->GetObjectClass(value);
    const PrimitiveWrapper *found = nullptr;
    for (const auto &entry : Wrappers(env).entries) {
        if (env->IsSameObject(value_class, entry.clazz) == JNI_TRUE) {
            found = &entry;
            break;
        }
    }
    env->DeleteLocalRef(value_class);
    return found;
}

/**
 * @brief The name ART puts in a reflective refusal.
 *
 * Class#getTypeName is the Java side of ART's PrettyDescriptor: dotted, and "int[]" rather than
 * "[I". Only reached on the way to throwing, so what it costs does not matter.
 */
std::string PrettyName(JNIEnv *env, jclass cls) {
    static jclass cls_Class = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Class"));
    static auto *const get_type_name =
        env->GetMethodID(cls_Class, "getTypeName", "()Ljava/lang/String;");

    // Only an allocation failure can fail either step, and the caller is on its way to throwing a
    // refusal that says more than an OutOfMemoryError would - so the pending one is cleared rather
    // than left for the next JNI call to trip over.
    auto name = (jstring)env->CallObjectMethod(cls, get_type_name);
    if (name == nullptr) {
        env->ExceptionClear();
        return "?";
    }
    std::string result = "?";
    if (const char *chars = env->GetStringUTFChars(name, nullptr); chars != nullptr) {
        result = chars;
        env->ReleaseStringUTFChars(name, chars);
    } else {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(name);
    return result;
}

// The wrapper that boxes the primitive `shorty` names.
const PrimitiveWrapper *WrapperFor(JNIEnv *env, char shorty) {
    for (const auto &entry : Wrappers(env).entries) {
        if (entry.shorty == shorty) return &entry;
    }
    return nullptr;
}

/**
 * @brief Whether a value of the primitive `from` may be passed where `to` is declared.
 *
 * The identity conversion plus the widening primitive conversions of JLS 5.1.2, which is all
 * java.lang.reflect performs on an argument. Every other pair is an IllegalArgumentException there,
 * rather than the silent truncation an unchecked unboxing would produce.
 */
constexpr bool Widens(char from, char to) {
    if (from == to) return true;
    switch (from) {
    case 'B':
        return to == 'S' || to == 'I' || to == 'J' || to == 'F' || to == 'D';
    case 'S':
    case 'C':
        return to == 'I' || to == 'J' || to == 'F' || to == 'D';
    case 'I':
        return to == 'J' || to == 'F' || to == 'D';
    case 'J':
        return to == 'F' || to == 'D';
    case 'F':
        return to == 'D';
    default:
        return false;
    }
}

/**
 * @brief Unboxes `value` with its own accessor and stores it as the primitive `to` names.
 *
 * Widens() has already refused every pair that is not a widening conversion, so none of the casts
 * below narrows anything.
 */
void StoreWidened(JNIEnv *env, const PrimitiveWrapper &from, char to, jobject value, jvalue &out) {
    if (from.shorty == 'Z') {
        out.z = env->CallBooleanMethod(value, from.unbox);
        return;
    }

    jlong integral = 0;
    jdouble floating = 0;
    switch (from.shorty) {
    case 'B':
        integral = env->CallByteMethod(value, from.unbox);
        break;
    case 'C':
        integral = env->CallCharMethod(value, from.unbox);
        break;
    case 'S':
        integral = env->CallShortMethod(value, from.unbox);
        break;
    case 'I':
        integral = env->CallIntMethod(value, from.unbox);
        break;
    case 'J':
        integral = env->CallLongMethod(value, from.unbox);
        break;
    case 'F':
        floating = env->CallFloatMethod(value, from.unbox);
        break;
    default:
        floating = env->CallDoubleMethod(value, from.unbox);
        break;
    }

    const bool from_floating = from.shorty == 'F' || from.shorty == 'D';
    switch (to) {
    case 'B':
        out.b = static_cast<jbyte>(integral);
        break;
    case 'C':
        out.c = static_cast<jchar>(integral);
        break;
    case 'S':
        out.s = static_cast<jshort>(integral);
        break;
    case 'I':
        out.i = static_cast<jint>(integral);
        break;
    case 'J':
        out.j = integral;
        break;
    case 'F':
        out.f = from_floating ? static_cast<jfloat>(floating) : static_cast<jfloat>(integral);
        break;
    default:
        out.d = from_floating ? floating : static_cast<jdouble>(integral);
        break;
    }
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
 * @brief Swaps one registered callback for another in a single locked step.
 *
 * API 102's HookHandle#replaceHook and HookBuilder#setId both promise that a replacement is atomic:
 * no window in which both the old and the new hooker are on the chain, and none in which neither
 * is. Doing it as unhook-then-hook from Java can promise neither.
 *
 * The lock taken here is the one callbackSnapshot takes, so a snapshot sees exactly one of the two.
 * A snapshot taken before the swap keeps working afterwards because it copied the reference into a
 * Java array, which is a strong reference of its own - that is what lets a call already in flight
 * keep running the old hooker, as the interface requires, without the chain having to freeze a
 * hooker list of its own on every single hooked call.
 *
 * The entry keeps its place among equal priorities when the priority does not change, which is what
 * replaceHook means by "keeps the priority": re-inserting would move it behind its peers.
 *
 * @return JNI_TRUE when oldCallback was found and replaced.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, replaceCallback, jboolean useModernApi,
                         jobject hookMethod, jobject oldCallback, jobject newCallback,
                         jint newPriority) {
    auto target = env->FromReflectedMethod(hookMethod);
    HookItem *hook_item = nullptr;
    hooked_methods.if_contains(target,
                               [&hook_item](const auto &it) { hook_item = it.second.get(); });
    if (!hook_item) return JNI_FALSE;

    jobject backup = hook_item->GetBackup();
    if (!backup) return JNI_FALSE;

    lsplant::JNIMonitor monitor(env, backup);

    auto &callbacks = useModernApi ? hook_item->modern_callbacks : hook_item->legacy_callbacks;

    for (auto i = callbacks.begin(); i != callbacks.end(); ++i) {
        if (!env->IsSameObject(i->second, oldCallback)) continue;

        auto replacement = env->NewGlobalRef(newCallback);
        // Nothing has been changed yet, so the caller's hook is still whatever it was.
        if (!replacement) return JNI_FALSE;

        env->DeleteGlobalRef(i->second);
        if (i->first == newPriority) {
            i->second = replacement;
        } else {
            callbacks.erase(i);
            callbacks.emplace(newPriority, replacement);
        }
        return JNI_TRUE;
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
 *
 * The trampoline's terminal, and only that: it is reached from inside a hook callback, so on every
 * call that matters the hook item exists and its backup is the original body. Everything that
 * dispatches an executable which may carry no hook at all goes through invokeOriginal instead,
 * which does not depend on the reflected object being accessible.
 *
 * The two fallbacks below are what a hook whose installation failed leaves behind: no hook item at
 * all, and the FAILED sentinel. lsplant replaced no entry point in either case, so the executable
 * still carries its own body - but the first reaches it through the caller's own reflected object,
 * where ART does run an access check, and the second reports it as a null return the caller cannot
 * tell from a method that returned null. Neither is reachable from the trampoline.
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
 * @brief JNI wrapper around AllocObject, refusing what AllocObject has no answer for.
 *
 * AllocObject is only defined for an instantiable non-array class. CheckJNI aborts the process on
 * anything else, and without it ART allocates from a class whose instance size means nothing.
 * Constructor#newInstance reports that as InstantiationException, which is what CtorInvoker
 * documents and what this method has always declared.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, allocateObject, jclass cls) {
    static jclass cls_Class = (jclass)env->NewGlobalRef(env->FindClass("java/lang/Class"));
    static auto *const is_interface = env->GetMethodID(cls_Class, "isInterface", "()Z");
    static auto *const is_array = env->GetMethodID(cls_Class, "isArray", "()Z");
    static auto *const is_primitive = env->GetMethodID(cls_Class, "isPrimitive", "()Z");
    static auto *const get_modifiers = env->GetMethodID(cls_Class, "getModifiers", "()I");
    constexpr jint kAccAbstract = 0x0400;

    if (cls == nullptr || env->CallBooleanMethod(cls, is_interface) == JNI_TRUE ||
        env->CallBooleanMethod(cls, is_array) == JNI_TRUE ||
        env->CallBooleanMethod(cls, is_primitive) == JNI_TRUE ||
        (env->CallIntMethod(cls, get_modifiers) & kAccAbstract) != 0) {
        jclass error = env->FindClass("java/lang/InstantiationException");
        env->ThrowNew(error, "no instance of this class can be allocated");
        env->DeleteLocalRef(error);
        return nullptr;
    }
    return env->AllocObject(cls);
}

/**
 * @brief Runs an executable's own body: the one dispatch primitive behind every invoker.
 *
 * The invoker family and the legacy bridge both land here, and everything they need to differ on is
 * a parameter. `is_static` and `non_virtual` pick the JNI call form, `declaring_class` is the class
 * to dispatch against - the superclass, for a newInstanceSpecial - and `parameter_types` is what an
 * argument has to match, which the shorty cannot say because every reference type is 'L'.
 *
 * JNI performs no access control, which is what makes an invocation through an invoker bypass
 * access checks as the interface promises. The other way round, java.lang.reflect.Method.invoke on
 * the caller's own Executable, runs ART's check with this class as the caller and so refuses every
 * member that is not public in a public class.
 *
 * It also performs no argument or receiver check, and a violation is not reported but executed, so
 * everything reflection would refuse is refused here first.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, invokeOriginal, jobject executable, jcharArray shorty,
                         jobjectArray parameter_types, jclass declaring_class, jboolean is_static,
                         jboolean non_virtual, jobject thiz, jobjectArray args) {
    static jclass cls_ITE =
        (jclass)env->NewGlobalRef(env->FindClass("java/lang/reflect/InvocationTargetException"));
    static auto *const ctor_ite = env->GetMethodID(cls_ITE, "<init>", "(Ljava/lang/Throwable;)V");

    // Everything raised here is the caller's own mistake rather than something the call produced,
    // and Method#invoke reports exactly those unwrapped. The wording is ART's own where this side
    // knows what ART would have printed; the per-argument refusals below cannot have it, because
    // ART's form names the resolved method and nothing here carries that name.
    const auto raise = [env](const char *type, const char *message) -> jobject {
        jclass cls = env->FindClass(type);
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
        return nullptr;
    };

    auto target = env->FromReflectedMethod(executable);
    HookItem *hook_item = nullptr;
    hooked_methods.if_contains(target,
                               [&hook_item](const auto &it) { hook_item = it.second.get(); });

    if (hook_item) {
        // lsplant hooks by rewriting this ArtMethod's entry point, and CallNonvirtual only skips
        // the vtable lookup rather than the entry point, so the original body is reachable through
        // the backup alone. No jmethodID can name the backup either - lsplant rewrites a backup's
        // id to its target's, which is how it keeps the index based ids of a debuggable process
        // meaningful - so it is invoked the one way that reads the ArtMethod off the reflected
        // object instead: Method.invoke. lsplant made the backup accessible, and private when it is
        // not static, so that call bypasses access checks and is direct whichever form was asked
        // for, and reflection's own conversions apply to arguments this side has already coerced.
        if (jobject backup = hook_item->GetBackup(); backup) {
            return env->CallObjectMethod(backup, invoke, thiz, args);
        }
        // A null backup is the failed-hook sentinel. lsplant never replaced the entry point, so
        // the executable still carries its own body and dispatching it is what runs the original.
    }

    // Method#invoke ignores the receiver of a static executable, and refuses a missing or a foreign
    // one rather than letting the callee read another layout's fields at this class's offsets.
    if (is_static) {
        thiz = nullptr;
    } else if (thiz == nullptr) {
        return raise("java/lang/NullPointerException", "null receiver");
    } else if (env->IsInstanceOf(thiz, declaring_class) != JNI_TRUE) {
        jclass actual = env->GetObjectClass(thiz);
        auto message = fmt::format("Expected receiver of type {}, but got {}",
                                   PrettyName(env, declaring_class), PrettyName(env, actual));
        env->DeleteLocalRef(actual);
        return raise("java/lang/IllegalArgumentException", message.c_str());
    }

    const jint param_len = parameter_types != nullptr ? env->GetArrayLength(parameter_types) : 0;
    // A null argument array is how Method#invoke spells "no arguments", so it is one here too.
    const jint args_len = args != nullptr ? env->GetArrayLength(args) : 0;
    if (args_len != param_len) {
        return raise(
            "java/lang/IllegalArgumentException",
            fmt::format("Wrong number of arguments; expected {}, got {}", param_len, args_len)
                .c_str());
    }
    // No executable declares more than 255 parameters, so anything above that is a caller that
    // built its own arrays wrong - and the stack allocation below has to be bounded by something.
    if (param_len > 255 || env->GetArrayLength(shorty) != param_len + 1) {
        return raise("java/lang/IllegalArgumentException",
                     "parameter types and shorty do not describe the same executable");
    }

    jvalue *a = param_len > 0 ? static_cast<jvalue *>(alloca(param_len * sizeof(jvalue))) : nullptr;

    auto *const shorty_char = env->GetCharArrayElements(shorty, nullptr);
    if (shorty_char == nullptr) {
        return nullptr;  // JVM already threw OutOfMemoryError
    }
    const auto release = [&] { env->ReleaseCharArrayElements(shorty, shorty_char, JNI_ABORT); };

    // --- Safe Unboxing ---
    for (jint i = 0; i != param_len; ++i) {
        jobject element = env->GetObjectArrayElement(args, i);
        if (env->ExceptionCheck()) {
            release();
            return nullptr;
        }

        const char declared = shorty_char[i + 1];
        if (declared == 'L') {
            if (element != nullptr) {
                auto param = (jclass)env->GetObjectArrayElement(parameter_types, i);
                const bool assignable = env->IsInstanceOf(element, param) == JNI_TRUE;
                env->DeleteLocalRef(param);
                if (!assignable) {
                    env->DeleteLocalRef(element);
                    release();
                    return raise("java/lang/IllegalArgumentException", "argument type mismatch");
                }
            }
            // The local reference lives until this frame returns, which is exactly as long as the
            // jvalue holding it is read.
            a[i].l = element;
            continue;
        }

        if (element == nullptr) {
            release();
            return raise("java/lang/IllegalArgumentException", "null primitive argument");
        }

        const PrimitiveWrapper *wrapper = WrapperOf(env, element);
        if (wrapper == nullptr || !Widens(wrapper->shorty, declared)) {
            env->DeleteLocalRef(element);
            release();
            return raise("java/lang/IllegalArgumentException", "argument type mismatch");
        }
        StoreWidened(env, *wrapper, declared, element, a[i]);
        env->DeleteLocalRef(element);
        if (env->ExceptionCheck()) {
            release();
            return nullptr;
        }
    }

    // --- Invocation ---
    jvalue ret_val{};
    const char returns = shorty_char[0];

    // JNI spells the call form in the function name rather than taking it as a value, so the three
    // ways to reach a body are three calls for every return kind.
#define VECTOR_DISPATCH(Kind, member)                                                              \
    ret_val.member =                                                                               \
        is_static     ? env->CallStatic##Kind##MethodA(declaring_class, target, a)                 \
        : non_virtual ? env->CallNonvirtual##Kind##MethodA(thiz, declaring_class, target, a)       \
                      : env->Call##Kind##MethodA(thiz, target, a)

    switch (returns) {
    case 'I':
        VECTOR_DISPATCH(Int, i);
        break;
    case 'D':
        VECTOR_DISPATCH(Double, d);
        break;
    case 'J':
        VECTOR_DISPATCH(Long, j);
        break;
    case 'F':
        VECTOR_DISPATCH(Float, f);
        break;
    case 'S':
        VECTOR_DISPATCH(Short, s);
        break;
    case 'B':
        VECTOR_DISPATCH(Byte, b);
        break;
    case 'C':
        VECTOR_DISPATCH(Char, c);
        break;
    case 'Z':
        VECTOR_DISPATCH(Boolean, z);
        break;
    case 'L':
        VECTOR_DISPATCH(Object, l);
        break;
    default:
        if (is_static) {
            env->CallStaticVoidMethodA(declaring_class, target, a);
        } else if (non_virtual) {
            env->CallNonvirtualVoidMethodA(thiz, declaring_class, target, a);
        } else {
            env->CallVoidMethodA(thiz, target, a);
        }
        break;
    }

#undef VECTOR_DISPATCH

    // The shorty is not read again, and releasing it first is what keeps every JNI call below out
    // of the window in which the call's own exception is still pending.
    release();

    // --- Exception Wrapping ---
    // Only what the call threw is wrapped; every refusal above is the caller's and stays raw.
    if (jthrowable thrown = env->ExceptionOccurred(); thrown) {
        env->ExceptionClear();
        jobject ite = env->NewObject(cls_ITE, ctor_ite, thrown);
        // NewObject failing leaves its own OutOfMemoryError pending, which is the truer answer.
        if (ite) {
            env->Throw(static_cast<jthrowable>(ite));
        }
        return nullptr;
    }

    // --- Box Return Value ---
    jobject value = nullptr;
    if (returns == 'L') {
        value = ret_val.l;
    } else if (returns != 'V') {
        // valueOf reads the jvalue member its own shorty names, which is the one the call wrote.
        if (const PrimitiveWrapper *wrapper = WrapperFor(env, returns); wrapper != nullptr) {
            value = env->CallStaticObjectMethodA(wrapper->clazz, wrapper->box, &ret_val);
        }
    }

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
 * @brief Reports whether the pages spanning [addr, addr + len) are mapped.
 *
 * msync on an unmapped range fails with ENOMEM, which turns a read that would raise SIGSEGV into
 * an answer, for the two readers below that work out an address instead of being handed one.
 * Defined next to the second of them.
 */
static bool IsMapped(uintptr_t addr, size_t len);

/**
 * @brief Clears ACC_FINAL on a field, so that reflection will write it again.
 *
 * Android 17 refuses every reflective write to a static final field
 * (`ThrowIAEIfFieldIsNotOverwritable` in `runtime/native/java_lang_reflect_Field.cc`), whatever
 * the Field's accessible flag says, and clearing the reflective copy's ACC_FINAL does not help
 * because the check reads the ArtField. This clears it where the check looks.
 *
 * The runtime's own JNI SetStatic*Field is the other way in, and is not taken here: it is
 * `LOG(FATAL)` for anything ART considers unmodifiable, and the carve-out that would spare
 * android.os.Build carries a TODO to remove it. A field that is no longer final is unmodifiable
 * to nobody, so this stays a write and never becomes an abort.
 *
 * [modifiers] is what java.lang.reflect.Field reports, and the ArtField's access flags have to
 * agree with it before anything is written: that is what says an address is an ArtField laid out
 * the way this expects, rather than a JNI index id or a layout that has moved.
 *
 * @return JNI_TRUE when the field is no longer final.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, makeFieldWritable, jobject field, jint modifiers) {
    constexpr uintptr_t kMinArtFieldAddr = 0x1000u;
    constexpr uint32_t kAccJavaFlagsMask = 0xFFFFu;
    constexpr uint32_t kAccFinal = 0x0010u;

    // Newer ART marks a direct field pointer in the high bits of the id: DecodeArtFieldInternal
    // tests bit 55 and clears bits 54-55 before using the rest as the address. The constant is
    // built in 64 bits and narrowed, so it is simply zero where a pointer has no such room.
    constexpr uintptr_t kTagBits = static_cast<uintptr_t>(uint64_t{3} << 54);

    // Clears ACC_FINAL at [candidate], if what lives there really is this field's ArtField.
    //
    // `access_flags_` follows the four-byte compressed `declaring_class_` root that starts the
    // ArtField. Requiring the Java-visible flags to equal `modifiers` is what says this address is
    // an ArtField laid out the way this expects, rather than a decode that has gone wrong or a
    // layout that has moved; and msync answers whether the address is mapped without reading it,
    // so a candidate that is neither costs a refusal rather than the process.
    auto clear_final_at = [&](uintptr_t candidate) {
        if (candidate < kMinArtFieldAddr) return false;
        if (!IsMapped(candidate, 2 * sizeof(uint32_t))) return false;

        auto *art_field = reinterpret_cast<uint32_t *>(candidate);
        uint32_t flags = art_field[1];

        if ((flags & kAccJavaFlagsMask) != static_cast<uint32_t>(modifiers)) return false;

        art_field[1] = flags & ~kAccFinal;
        return true;
    };

    // A jfieldID is the ArtField pointer under JniIdType kPointer, and was once nothing else. It
    // is not that simple any more, so every reading of the id is offered as a candidate and none
    // is trusted: the id as it comes, and the id with ART's tag bits cleared. An odd id is not an
    // address under any encoding -- JniIdManager::IsIndexId calls a null or odd id an index, and
    // encodes one as (index << 1) + 1 -- which is what a debuggable process running kIndices
    // returns, and it is left to the mirror::Field path below.
    const auto id = reinterpret_cast<uintptr_t>(env->FromReflectedField(field));

    if (id != 0 && (id & 1u) == 0u) {
        if (clear_final_at(id)) return JNI_TRUE;
        if ((id & kTagBits) != 0 && clear_final_at(id & ~kTagBits)) return JNI_TRUE;
    }

    // Decode the reflected Field to its mirror::Field and read the ArtField off it, which is
    // independent of the id encoding. Neither symbol is exported on every release, so this stays a
    // fallback rather than the first choice.
    using CurrentFromGdb = void *(*)();
    using DecodeJObject = void *(*)(void * /*Thread*/, jobject);  // Thread::DecodeJObject() const
    using GetArtField = void *(*)(void * /*mirror::Field*/);      // mirror::Field::GetArtField()

    static const auto *art = ElfSymbolCache::GetArt();
    static const auto current_thread =
        art ? art->getSymbAddress<CurrentFromGdb>("_ZN3art6Thread14CurrentFromGdbEv") : nullptr;
    static const auto decode_jobject =
        art ? art->getSymbAddress<DecodeJObject>("_ZNK3art6Thread13DecodeJObjectEP8_jobject")
            : nullptr;
    static const auto get_art_field =
        art ? art->getSymbAddress<GetArtField>("_ZN3art6mirror5Field11GetArtFieldEv") : nullptr;

    if (current_thread && decode_jobject && get_art_field) {
        if (void *self = current_thread()) {
            if (void *field_obj = decode_jobject(self, field)) {
                return clear_final_at(reinterpret_cast<uintptr_t>(get_art_field(field_obj)))
                           ? JNI_TRUE
                           : JNI_FALSE;
            }
        }
    }

    return JNI_FALSE;
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
 * @brief The class name prefixes of the legacy Xposed API as this process will be asked for them.
 *
 * API 102 forbids a module that targets it from calling the legacy API, and the only place that can
 * be enforced is the module class loader - which is handed a name. A literal "de.robv.android.xposed"
 * is not that name: the daemon rewrites those prefixes in the framework dex and in every module dex
 * when dex obfuscation is on, so the name a module asks for is a different random string on every
 * boot. Resolving them through the same map the rest of the framework uses is what makes the guard
 * hold in both configurations.
 *
 * The one entry is the one package the spec names. The obfuscation table also covers
 * AndroidAppHelper and the XResources / XModuleResources family, and guarding those too was the
 * wider reading of the same sentence - but the interface says "legacy {@code de.robv.android.xposed}
 * APIs" and names nothing else, and API 102 offers no resource API of its own, so the wider reading
 * left a module targeting it with no way to touch resources at all.
 */
VECTOR_DEF_NATIVE_METHOD(jobjectArray, HookBridge, legacyApiPrefixes) {
    // In the dotted form the obfuscation map is served in - the same form loadClass receives.
    static constexpr const char *kLegacyKeys[] = {
        "de.robv.android.xposed.",
    };

    const auto count = static_cast<jsize>(ArraySize(kLegacyKeys));
    auto string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    auto result = env->NewObjectArray(count, string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (!result) return nullptr;

    auto *bridge = ConfigBridge::GetInstance();
    for (jsize i = 0; i < count; ++i) {
        std::string name = kLegacyKeys[i];
        if (bridge) {
            const auto &map = bridge->obfuscation_map();
            // Absent means the map never arrived; the unobfuscated name is then the right answer,
            // because a build with no map is a build with no obfuscation.
            if (auto it = map.find(name); it != map.end()) name = it->second;
        }
        auto value = env->NewStringUTF(name.c_str());
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

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
    VECTOR_NATIVE_METHOD(HookBridge, replaceCallback,
                         "(ZLjava/lang/reflect/Executable;Ljava/lang/Object;Ljava/"
                         "lang/Object;I)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, deoptimizeMethod, "(Ljava/lang/reflect/Executable;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, invokeOriginalMethod,
                         "(Ljava/lang/reflect/Executable;Ljava/lang/Object;[Ljava/"
                         "lang/Object;)Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, invokeOriginal,
                         "(Ljava/lang/reflect/Executable;[C[Ljava/lang/Class;Ljava/"
                         "lang/Class;ZZLjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, allocateObject, "(Ljava/lang/Class;)Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, instanceOf, "(Ljava/lang/Object;Ljava/lang/Class;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, setTrusted, "(Ljava/lang/Object;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, makeFieldWritable, "(Ljava/lang/reflect/Field;I)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, callbackSnapshot,
                         "(Ljava/lang/Class;Ljava/lang/reflect/"
                         "Executable;)[[Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, findStaticInitializer,
                         "(Ljava/lang/Class;[JJ)Ljava/lang/reflect/Executable;"),
    VECTOR_NATIVE_METHOD(HookBridge, legacyApiPrefixes, "()[Ljava/lang/String;"),
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

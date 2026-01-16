#include "jni/jni_bridge.h"
#include "jni/jni_hooks.h"
#include <lsplant.hpp>
#include <memory>
#include <parallel_hashmap/phmap.h>
#include <shared_mutex>

namespace {
/**
 * @struct ModuleCallback
 * @brief Stores the jmethodIDs for the "modern" callback API.
 * This API separates the logic that runs before and after the original method.
 */
struct ModuleCallback {
  jmethodID before_method;
  jmethodID after_method;
};

/**
 * @struct HookItem
 * @brief Holds all state associated with a single hooked method.
 *
 * This includes lists of all registered callback functions (both modern and
 * legacy), sorted by priority. It also manages a thread-safe "backup" object,
 * which is a handle to the original, un-hooked method.
 */
struct HookItem {
  // Callbacks are stored in multimaps, keyed by priority.
  // std::greater<> ensures that higher priority numbers are processed first.
  std::multimap<jint, jobject, std::greater<>> legacy_callbacks;
  std::multimap<jint, ModuleCallback, std::greater<>> modern_callbacks;

private:
  // The backup is an atomic jobject. This is crucial for thread safety during
  // the initial hooking process. It can be in one of three states:
  // - nullptr: The hook has not been initialized yet.
  // - FAILED: The hook attempt failed.
  // - A valid jobject: A handle to the original method.
  std::atomic<jobject> backup{nullptr};
  static_assert(decltype(backup)::is_always_lock_free);
  // A sentinel value to indicate that the hooking process failed.
  inline static jobject FAILED =
      reinterpret_cast<jobject>(std::numeric_limits<uintptr_t>::max());

public:
  /**
   * @brief Atomically and safely retrieves the backup method handle.
   * If another thread is currently setting up the hook, this method will wait
   * until the process is complete. This prevents race conditions.
   */
  jobject GetBackup() {
    // Wait until the 'backup' atomic is no longer nullptr.
    // The memory_order_acquire ensures that any writes from the thread that
    // set the backup are visible to this thread.
    backup.wait(nullptr, std::memory_order_acquire);
    if (auto bk = backup.load(std::memory_order_relaxed); bk != FAILED) {
      return bk;
    } else {
      return nullptr;
    }
  }

  /**
   * @brief Atomically sets the backup method handle once after hooking.
   * This method uses compare_exchange_strong to ensure it only sets the value
   * once. After setting, it notifies any waiting threads.
   */
  void SetBackup(jobject newBackup) {
    jobject null = nullptr;
    // Attempt to transition from nullptr to the new backup (or FAILED).
    // memory_order_acq_rel ensures memory synchronization with both waiting
    // threads (acquire) and subsequent reads (release).
    backup.compare_exchange_strong(null, newBackup ? newBackup : FAILED,
                                   std::memory_order_acq_rel,
                                   std::memory_order_relaxed);
    // Wake up all threads that were waiting in GetBackup().
    backup.notify_all();
  }
};

// A type alias for a thread-safe parallel hash map.
// This map is the central registry, mapping a method's ID to its HookItem.
// It uses a std::shared_mutex to allow concurrent reads but exclusive writes.
template <class K, class V, class Hash = phmap::priv::hash_default_hash<K>,
          class Eq = phmap::priv::hash_default_eq<K>,
          class Alloc = phmap::priv::Allocator<phmap::priv::Pair<const K, V>>,
          size_t N = 4>
using SharedHashMap =
    phmap::parallel_flat_hash_map<K, V, Hash, Eq, Alloc, N, std::shared_mutex>;

// The global map of all hooked methods.
SharedHashMap<jmethodID, std::unique_ptr<HookItem>> hooked_methods;

// Cached JNI method and field IDs for performance.
// Looking these up frequently is slow, so they are cached on first use.
jmethodID invoke = nullptr;
jmethodID callback_ctor = nullptr;
jfieldID before_method_field = nullptr;
jfieldID after_method_field = nullptr;
} // namespace

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
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, hookMethod,
                         jboolean useModernApi, jobject hookMethod,
                         jclass hooker, jint priority, jobject callback) {
  bool newHook = false;
#ifndef NDEBUG
  // Simple RAII struct for performance timing in debug builds.
  struct finally {
    std::chrono::steady_clock::time_point start =
        std::chrono::steady_clock::now();
    bool &newHook;
    ~finally() {
      auto finish = std::chrono::steady_clock::now();
      if (newHook) {
        LOGV("New hook took {}us",
             std::chrono::duration_cast<std::chrono::microseconds>(finish -
                                                                   start)
                 .count());
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

  // If this is the first time this method is being hooked, we need to
  // perform the actual native hook using lsplant.
  if (newHook) {
    auto init =
        env->GetMethodID(hooker, "<init>", "(Ljava/lang/reflect/Executable;)V");
    auto callback_method = env->ToReflectedMethod(
        hooker,
        env->GetMethodID(hooker, "callback",
                         "([Ljava/lang/Object;)Ljava/lang/Object;"),
        false);
    auto hooker_object = env->NewObject(hooker, init, hookMethod);
    // Use lsplant to replace the target method with our trampoline.
    // The returned jobject is a handle to the original method.
    hook_item->SetBackup(
        lsplant::Hook(env, hookMethod, hooker_object, callback_method));
    env->DeleteLocalRef(hooker_object);
  }

  // Wait for the backup to become available (it might be set by another
  // thread).
  jobject backup = hook_item->GetBackup();
  if (!backup)
    return JNI_FALSE;

  // Use an RAII monitor to lock the backup object, ensuring thread-safe
  // modification of the callback lists.
  lsplant::JNIMonitor monitor(env, backup);

  if (useModernApi) {
    // Lazy initialization of JNI IDs for the modern API.
    if (before_method_field == nullptr) {
      auto callback_class = env->GetObjectClass(callback);
      callback_ctor = env->GetMethodID(
          callback_class, "<init>",
          "(Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;)V");
      before_method_field = env->GetFieldID(callback_class, "beforeInvocation",
                                            "Ljava/lang/reflect/Method;");
      after_method_field = env->GetFieldID(callback_class, "afterInvocation",
                                           "Ljava/lang/reflect/Method;");
    }
    // Extract the before/after methods from the Java callback object.
    auto before_method = env->GetObjectField(callback, before_method_field);
    auto after_method = env->GetObjectField(callback, after_method_field);
    auto callback_type = ModuleCallback{
        .before_method = env->FromReflectedMethod(before_method),
        .after_method = env->FromReflectedMethod(after_method),
    };
    hook_item->modern_callbacks.emplace(priority, callback_type);
  } else {
    // For the legacy API, store a global reference to the callback object
    // itself.
    hook_item->legacy_callbacks.emplace(priority, env->NewGlobalRef(callback));
  }
  return JNI_TRUE;
}

/**
 * @brief JNI method to remove a previously installed hook callback.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, unhookMethod,
                         jboolean useModernApi, jobject hookMethod,
                         jobject callback) {
  auto target = env->FromReflectedMethod(hookMethod);
  HookItem *hook_item = nullptr;
  // Find the HookItem for the target method.
  hooked_methods.if_contains(
      target, [&hook_item](const auto &it) { hook_item = it.second.get(); });
  if (!hook_item)
    return JNI_FALSE;

  jobject backup = hook_item->GetBackup();
  if (!backup)
    return JNI_FALSE;

  // Lock to safely modify the callback list.
  lsplant::JNIMonitor monitor(env, backup);

  if (useModernApi) {
    auto before_method = env->GetObjectField(callback, before_method_field);
    auto before = env->FromReflectedMethod(before_method);
    // Find the callback by comparing the before_method's ID.
    for (auto i = hook_item->modern_callbacks.begin();
         i != hook_item->modern_callbacks.end(); ++i) {
      if (before == i->second.before_method) {
        hook_item->modern_callbacks.erase(i);
        return JNI_TRUE;
      }
    }
  } else {
    // Find the callback by comparing the jobject directly.
    for (auto i = hook_item->legacy_callbacks.begin();
         i != hook_item->legacy_callbacks.end(); ++i) {
      if (env->IsSameObject(i->second, callback)) {
        env->DeleteGlobalRef(i->second); // Clean up the global reference.
        hook_item->legacy_callbacks.erase(i);
        return JNI_TRUE;
      }
    }
  }
  return JNI_FALSE;
}

/**
 * @brief JNI method to request de-optimization of a method.
 * This can be necessary for some types of hooks to work correctly on
 * JIT-compiled methods.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, deoptimizeMethod,
                         jobject hookMethod) {
  return lsplant::Deoptimize(env, hookMethod);
}

/**
 * @brief JNI method to invoke the original, un-hooked method.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, invokeOriginalMethod,
                         jobject hookMethod, jobject thiz, jobjectArray args) {
  auto target = env->FromReflectedMethod(hookMethod);
  HookItem *hook_item = nullptr;
  hooked_methods.if_contains(
      target, [&hook_item](const auto &it) { hook_item = it.second.get(); });

  // If a hook item exists, invoke its backup. Otherwise, invoke the method
  // directly (though this case should be rare if called from a hook callback).
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
 * @brief A high-performance, low-level implementation of Method.invoke for
 * super.method() calls.
 *
 * This function manually unboxes arguments from a jobject array into a jvalue
 * C-style array, calls the appropriate JNI `CallNonvirtual...MethodA` function,
 * and then boxes the return value back into a jobject. This avoids the overhead
 * of Java reflection.
 *
 * @warning This is a very sensitive function. The `shorty` descriptor must
 * perfectly match the method's actual signature.
 */
VECTOR_DEF_NATIVE_METHOD(jobject, HookBridge, invokeSpecialMethod,
                         jobject method, jcharArray shorty, jclass cls,
                         jobject thiz, jobjectArray args) {
  // --- Cache all necessary MethodIDs for boxing/unboxing primitive wrappers
  // --- This is a major performance optimization, done only once.
  static auto *const get_int =
      env->GetMethodID(env->FindClass("java/lang/Integer"), "intValue", "()I");
  static auto *const get_double = env->GetMethodID(
      env->FindClass("java/lang/Double"), "doubleValue", "()D");
  static auto *const get_long =
      env->GetMethodID(env->FindClass("java/lang/Long"), "longValue", "()J");
  static auto *const get_float =
      env->GetMethodID(env->FindClass("java/lang/Float"), "floatValue", "()F");
  static auto *const get_short =
      env->GetMethodID(env->FindClass("java/lang/Short"), "shortValue", "()S");
  static auto *const get_byte =
      env->GetMethodID(env->FindClass("java/lang/Byte"), "byteValue", "()B");
  static auto *const get_char = env->GetMethodID(
      env->FindClass("java/lang/Character"), "charValue", "()C");
  static auto *const get_boolean = env->GetMethodID(
      env->FindClass("java/lang/Boolean"), "booleanValue", "()Z");
  static auto *const set_int = env->GetStaticMethodID(
      env->FindClass("java/lang/Integer"), "valueOf", "(I)Ljava/lang/Integer;");
  static auto *const set_double = env->GetStaticMethodID(
      env->FindClass("java/lang/Double"), "valueOf", "(D)Ljava/lang/Double;");
  static auto *const set_long = env->GetStaticMethodID(
      env->FindClass("java/lang/Long"), "valueOf", "(J)Ljava/lang/Long;");
  static auto *const set_float = env->GetStaticMethodID(
      env->FindClass("java/lang/Float"), "valueOf", "(F)Ljava/lang/Float;");
  static auto *const set_short = env->GetStaticMethodID(
      env->FindClass("java/lang/Short"), "valueOf", "(S)Ljava/lang/Short;");
  static auto *const set_byte = env->GetStaticMethodID(
      env->FindClass("java/lang/Byte"), "valueOf", "(B)Ljava/lang/Byte;");
  static auto *const set_char =
      env->GetStaticMethodID(env->FindClass("java/lang/Character"), "valueOf",
                             "(C)Ljava/lang/Character;");
  static auto *const set_boolean = env->GetStaticMethodID(
      env->FindClass("java/lang/Boolean"), "valueOf", "(Z)Ljava/lang/Boolean;");

  auto target = env->FromReflectedMethod(method);
  auto param_len =
      env->GetArrayLength(shorty) - 1; // First char is return type.

  // --- Argument Validation ---
  if (env->GetArrayLength(args) != param_len) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                  "args.length does not match parameter count");
    return nullptr;
  }
  if (thiz == nullptr) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                  "`this` cannot be null for a non-virtual call");
    return nullptr;
  }

  // --- Unbox Arguments ---
  std::vector<jvalue> a(param_len);
  auto *const shorty_char = env->GetCharArrayElements(shorty, nullptr);
  for (jint i = 0; i != param_len; ++i) {
    jobject element = env->GetObjectArrayElement(args, i);
    if (env->ExceptionCheck()) {
      env->ReleaseCharArrayElements(shorty, shorty_char, JNI_ABORT);
      return nullptr;
    }

    // The shorty string at index i+1 describes the type of the i-th parameter.
    switch (shorty_char[i + 1]) {
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
    case 'C':
      a[i].c = env->CallCharMethod(element, get_char);
      break;
    case 'Z':
      a[i].z = env->CallBooleanMethod(element, get_boolean);
      break;
    default: // Assumes 'L' or '[' for object types
      a[i].l = element;
      // Set element to null so we don't delete the local ref twice.
      // The reference is stored in the jvalue array and is still valid.
      element = nullptr;
      break;
    }

    // Clean up the local reference for the wrapper object if it was created.
    if (element)
      env->DeleteLocalRef(element);

    // Check for exceptions during the unboxing call (e.g.,
    // NullPointerException).
    if (env->ExceptionCheck()) {
      env->ReleaseCharArrayElements(shorty, shorty_char, JNI_ABORT);
      return nullptr;
    }
  }

  // --- Call Non-virtual Method and Box Return Value ---
  jobject value = nullptr;
  // The shorty string at index 0 describes the return type.
  switch (shorty_char[0]) {
  case 'I':
    value = env->CallStaticObjectMethod(
        jclass{nullptr},
        set_int, // Use Integer.valueOf() to box
        env->CallNonvirtualIntMethodA(thiz, cls, target, a.data()));
    break;
  case 'D':
    value = env->CallStaticObjectMethod(
        jclass{nullptr}, set_double,
        env->CallNonvirtualDoubleMethodA(thiz, cls, target, a.data()));
    break;
  case 'J':
    value = env->CallStaticObjectMethod(
        jclass{nullptr}, set_long,
        env->CallNonvirtualLongMethodA(thiz, cls, target, a.data()));
    break;
  case 'F':
    value = env->CallStaticObjectMethod(
        jclass{nullptr}, set_float,
        env->CallNonvirtualFloatMethodA(thiz, cls, target, a.data()));
    break;
  case 'S':
    value = env->CallStaticObjectMethod(
        jclass{nullptr}, set_short,
        env->CallNonvirtualShortMethodA(thiz, cls, target, a.data()));
    break;
  case 'B':
    value = env->CallStaticObjectMethod(
        jclass{nullptr}, set_byte,
        env->CallNonvirtualByteMethodA(thiz, cls, target, a.data()));
    break;
  case 'C':
    value = env->CallStaticObjectMethod(
        jclass{nullptr}, set_char,
        env->CallNonvirtualCharMethodA(thiz, cls, target, a.data()));
    break;
  case 'Z':
    value = env->CallStaticObjectMethod(
        jclass{nullptr}, set_boolean,
        env->CallNonvirtualBooleanMethodA(thiz, cls, target, a.data()));
    break;
  case 'L': // Return type is an object, no boxing needed.
    value = env->CallNonvirtualObjectMethodA(thiz, cls, target, a.data());
    break;
  default: // Assumes 'V' for void return type.
  case 'V':
    env->CallNonvirtualVoidMethodA(thiz, cls, target, a.data());
    break;
  }

  env->ReleaseCharArrayElements(shorty, shorty_char, JNI_ABORT);
  return value;
}

/**
 * @brief JNI wrapper around IsInstanceOf.
 */
VECTOR_DEF_NATIVE_METHOD(jboolean, HookBridge, instanceOf, jobject object,
                         jclass expected_class) {
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
 * index 1 contains legacy callbacks.
 */
VECTOR_DEF_NATIVE_METHOD(jobjectArray, HookBridge, callbackSnapshot,
                         jclass callback_class, jobject method) {
  auto target = env->FromReflectedMethod(method);
  HookItem *hook_item = nullptr;
  hooked_methods.if_contains(
      target, [&hook_item](const auto &it) { hook_item = it.second.get(); });
  if (!hook_item)
    return nullptr;

  jobject backup = hook_item->GetBackup();
  if (!backup)
    return nullptr;

  // Lock to ensure a consistent snapshot of the callback lists.
  lsplant::JNIMonitor monitor(env, backup);

  auto res =
      env->NewObjectArray(2, env->FindClass("[Ljava/lang/Object;"), nullptr);
  auto modern =
      env->NewObjectArray((jsize)hook_item->modern_callbacks.size(),
                          env->FindClass("java/lang/Object"), nullptr);
  auto legacy =
      env->NewObjectArray((jsize)hook_item->legacy_callbacks.size(),
                          env->FindClass("java/lang/Object"), nullptr);

  jsize i = 0;
  for (const auto &callback_pair : hook_item->modern_callbacks) {
    // The clazz argument refers to the Java class where the native method is
    // declared, provided by the macro VECTOR_DEF_NATIVE_METHOD.
    auto before_method = env->ToReflectedMethod(
        clazz, callback_pair.second.before_method, JNI_FALSE);
    auto after_method = env->ToReflectedMethod(
        clazz, callback_pair.second.after_method, JNI_FALSE);
    // Re-create the Java callback object from the stored method IDs.
    auto callback_object = env->NewObject(callback_class, callback_ctor,
                                          before_method, after_method);
    env->SetObjectArrayElement(modern, i++, callback_object);
    // Clean up local references created during object construction.
    env->DeleteLocalRef(before_method);
    env->DeleteLocalRef(after_method);
    env->DeleteLocalRef(callback_object);
  }

  i = 0;
  for (const auto &callback_pair : hook_item->legacy_callbacks) {
    // The legacy list already stores a global ref to the callback object.
    env->SetObjectArrayElement(legacy, i++, callback_pair.second);
  }

  env->SetObjectArrayElement(res, 0, modern);
  env->SetObjectArrayElement(res, 1, legacy);
  env->DeleteLocalRef(modern);
  env->DeleteLocalRef(legacy);
  return res;
}

// Array of native method descriptors for JNI registration.
static JNINativeMethod gMethods[] = {
    VECTOR_NATIVE_METHOD(
        HookBridge, hookMethod,
        "(ZLjava/lang/reflect/Executable;Ljava/lang/Class;ILjava/"
        "lang/Object;)Z"),
    VECTOR_NATIVE_METHOD(
        HookBridge, unhookMethod,
        "(ZLjava/lang/reflect/Executable;Ljava/lang/Object;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, deoptimizeMethod,
                         "(Ljava/lang/reflect/Executable;)Z"),
    VECTOR_NATIVE_METHOD(
        HookBridge, invokeOriginalMethod,
        "(Ljava/lang/reflect/Executable;Ljava/lang/Object;[Ljava/"
        "lang/Object;)Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(
        HookBridge, invokeSpecialMethod,
        "(Ljava/lang/reflect/Executable;[CLjava/lang/Class;Ljava/"
        "lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, allocateObject,
                         "(Ljava/lang/Class;)Ljava/lang/Object;"),
    VECTOR_NATIVE_METHOD(HookBridge, instanceOf,
                         "(Ljava/lang/Object;Ljava/lang/Class;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, setTrusted, "(Ljava/lang/Object;)Z"),
    VECTOR_NATIVE_METHOD(HookBridge, callbackSnapshot,
                         "(Ljava/lang/Class;Ljava/lang/reflect/"
                         "Executable;)[[Ljava/lang/Object;"),
};

/**
 * @brief Registers all native methods with the JVM when the library is loaded.
 */
void RegisterHookBridge(JNIEnv *env) {
  // Cache the Method.invoke methodID for use in invokeOriginalMethod.
  jclass method = env->FindClass("java/lang/reflect/Method");
  invoke = env->GetMethodID(
      method, "invoke",
      "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
  env->DeleteLocalRef(method);
  REGISTER_VECTOR_NATIVE_METHODS(HookBridge);
}
} // namespace vector::native::jni

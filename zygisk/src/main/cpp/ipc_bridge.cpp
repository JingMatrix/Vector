#include "ipc_bridge.h"

#include <elf/elf_image.h>
#include <elf/symbol_cache.h>
#include <unistd.h>

#include <atomic>
#include <string_view>
#include <thread>

using namespace std::literals::string_view_literals;

#include <common/logging.h>

namespace vector::native::module {

// Store the ID of the last caller whose framework transaction failed.
// It's initialized to a value that won't match.
static std::atomic<uint64_t> g_last_failed_id = ~0;

/**
 * @class BinderCaller
 * @brief A helper to get the UID and PID of the current Binder caller.
 *
 * This class encapsulates the logic for finding and calling private functions
 * within libbinder.so to identify the origin of an IPC transaction.
 */
class BinderCaller {
public:
    /**
     * @brief Initializes the helper by finding the required function pointers.
     * This must be called once before GetId() is used.
     */
    static void Initialize() {
        // Use the native library's symbol cache to find symbols in the loaded libbinder.so
        auto libbinder = ElfSymbolCache::GetLibBinder();
        if (!libbinder) {
            LOGW("libbinder.so not found in cache, cannot get caller ID.");
            return;
        }

        s_self_or_null_fn = (IPCThreadState * (*)()) libbinder->getSymbAddress(
            "_ZN7android14IPCThreadState10selfOrNullEv");
        s_get_calling_pid_fn = (pid_t(*)(IPCThreadState *))libbinder->getSymbAddress(
            "_ZNK7android14IPCThreadState13getCallingPidEv");
        s_get_calling_uid_fn = (uid_t(*)(IPCThreadState *))libbinder->getSymbAddress(
            "_ZNK7android14IPCThreadState13getCallingUidEv");

        if (!s_self_or_null_fn || !s_get_calling_pid_fn || !s_get_calling_uid_fn) {
            LOGW("Could not resolve all IPCThreadState symbols. Caller ID check will be disabled.");
        } else {
            LOGV("IPCThreadState symbols resolved successfully.");
        }
    }

    /**
     * @brief Gets the unique 64-bit ID of the current Binder caller.
     * @return A combined UID and PID, or 0 if symbols are not available.
     */
    static uint64_t GetId() {
        if (!s_self_or_null_fn) [[unlikely]]
            return 0;

        IPCThreadState *self = s_self_or_null_fn();
        if (!self) return 0;

        auto uid = s_get_calling_uid_fn(self);
        auto pid = s_get_calling_pid_fn(self);
        return (static_cast<uint64_t>(uid) << 32) | pid;
    }

private:
    // Forward declare the opaque struct from libbinder.
    struct IPCThreadState;

    inline static IPCThreadState *(*s_self_or_null_fn)() = nullptr;
    inline static pid_t (*s_get_calling_pid_fn)(IPCThreadState *) = nullptr;
    inline static uid_t (*s_get_calling_uid_fn)(IPCThreadState *) = nullptr;
};

// --- Binder IPC Protocol Constants ---
// These are the "secret handshakes" used to communicate with the Vector manager service.

// The name of the system service we use as a rendezvous point to find our manager service.
// Using "activity" is a common technique as it's always available.
constexpr auto kBridgeServiceName = "activity"sv;

// Transaction codes for specific actions.
constexpr jint kBridgeTransactionCode = ('_' << 24) | ('V' << 16) | ('E' << 8) | 'C';
constexpr jint kDexTransactionCode = ('_' << 24) | ('D' << 16) | ('E' << 8) | 'X';
constexpr jint kObfuscationMapTransactionCode = ('_' << 24) | ('O' << 16) | ('B' << 8) | 'F';

// Action codes sent within a kBridgeTransactionCode transaction.
constexpr jint kActionGetBinder = 2;

// =========================================================================================
// Implementation of IPCBridge::ParcelWrapper (Private Nested Class)
// =========================================================================================

/**
 * @brief Constructs the ParcelWrapper, obtaining two new Parcel objects from the pool.
 * @param env A valid JNI environment pointer.
 * @param bridge A pointer to the parent IPCBridge instance to access its cached JNI IDs.
 */
IPCBridge::ParcelWrapper::ParcelWrapper(JNIEnv *env, IPCBridge *bridge)
    : data(lsplant::JNI_CallStaticObjectMethod(env, bridge->parcel_class_, bridge->obtain_method_)),
      reply(
          lsplant::JNI_CallStaticObjectMethod(env, bridge->parcel_class_, bridge->obtain_method_)),
      env_(env),
      bridge_(bridge) {}

/**
 * @brief Destructs the ParcelWrapper, ensuring both Parcel objects are recycled.
 *
 * This is the core of the RAII pattern for Parcels.
 * This destructor guarantees that recycle() is called, preventing resource leaks even if
 * errors occur during the transaction.
 */
IPCBridge::ParcelWrapper::~ParcelWrapper() {
    // Check if the parcel was successfully obtained before trying to recycle it.
    if (data) {
        lsplant::JNI_CallVoidMethod(env_, data.get(), bridge_->recycle_method_);
    }
    if (reply) {
        lsplant::JNI_CallVoidMethod(env_, reply.get(), bridge_->recycle_method_);
    }
}

// =========================================================================================
// IPCBridge Implementation
// =========================================================================================

IPCBridge &IPCBridge::GetInstance() {
    static IPCBridge instance;
    return instance;
}

void IPCBridge::Initialize(JNIEnv *env) {
    if (initialized_) {
        return;
    }

    // --- Cache JNI Classes and Method IDs ---
    // Caching these at startup is more efficient and robust than looking them up on every IPC call.
    // If any of these fail, the IPC bridge is unusable.

    // ServiceManager
    auto sm_class = lsplant::ScopedLocalRef(env, env->FindClass("android/os/ServiceManager"));
    if (!sm_class) {
        LOGE("IPCBridge: ServiceManager class not found!");
        return;
    }
    service_manager_class_ = (jclass)env->NewGlobalRef(sm_class.get());
    get_service_method_ = lsplant::JNI_GetStaticMethodID(
        env, service_manager_class_, "getService", "(Ljava/lang/String;)Landroid/os/IBinder;");
    if (!get_service_method_) {
        LOGE("IPCBridge: ServiceManager.getService method not found!");
        return;
    }

    // IBinder
    auto ibinder_class = lsplant::ScopedLocalRef(env, env->FindClass("android/os/IBinder"));
    if (!ibinder_class) {
        LOGE("IPCBridge: IBinder class not found!");
        return;
    }
    transact_method_ = lsplant::JNI_GetMethodID(env, ibinder_class.get(), "transact",
                                                "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z");
    if (!transact_method_) {
        LOGE("IPCBridge: IBinder.transact method not found!");
        return;
    }

    // Binder
    auto binder_class = lsplant::ScopedLocalRef(env, env->FindClass("android/os/Binder"));
    if (!binder_class) {
        LOGE("IPCBridge: Binder class not found!");
        return;
    }
    binder_class_ = (jclass)env->NewGlobalRef(binder_class.get());
    binder_ctor_ = lsplant::JNI_GetMethodID(env, binder_class_, "<init>", "()V");
    if (!binder_ctor_) {
        LOGE("IPCBridge: Binder constructor not found!");
        return;
    }

    // Parcel
    auto parcel_class = lsplant::ScopedLocalRef(env, env->FindClass("android/os/Parcel"));
    if (!parcel_class) {
        LOGE("IPCBridge: Parcel class not found!");
        return;
    }
    parcel_class_ = (jclass)env->NewGlobalRef(parcel_class.get());
    obtain_method_ =
        lsplant::JNI_GetStaticMethodID(env, parcel_class_, "obtain", "()Landroid/os/Parcel;");
    recycle_method_ = lsplant::JNI_GetMethodID(env, parcel_class_, "recycle", "()V");
    write_interface_token_method_ = lsplant::JNI_GetMethodID(
        env, parcel_class_, "writeInterfaceToken", "(Ljava/lang/String;)V");
    write_int_method_ = lsplant::JNI_GetMethodID(env, parcel_class_, "writeInt", "(I)V");
    write_string_method_ =
        lsplant::JNI_GetMethodID(env, parcel_class_, "writeString", "(Ljava/lang/String;)V");
    write_strong_binder_method_ = lsplant::JNI_GetMethodID(env, parcel_class_, "writeStrongBinder",
                                                           "(Landroid/os/IBinder;)V");
    read_exception_method_ = lsplant::JNI_GetMethodID(env, parcel_class_, "readException", "()V");
    read_strong_binder_method_ =
        lsplant::JNI_GetMethodID(env, parcel_class_, "readStrongBinder", "()Landroid/os/IBinder;");
    read_file_descriptor_method_ = lsplant::JNI_GetMethodID(
        env, parcel_class_, "readFileDescriptor", "()Landroid/os/ParcelFileDescriptor;");
    read_int_method_ = lsplant::JNI_GetMethodID(env, parcel_class_, "readInt", "()I");
    read_long_method_ = lsplant::JNI_GetMethodID(env, parcel_class_, "readLong", "()J");
    read_string_method_ =
        lsplant::JNI_GetMethodID(env, parcel_class_, "readString", "()Ljava/lang/String;");

    // ParcelFileDescriptor
    auto pfd_class =
        lsplant::ScopedLocalRef(env, env->FindClass("android/os/ParcelFileDescriptor"));
    if (!pfd_class) {
        LOGE("IPCBridge: ParcelFileDescriptor class not found!");
        return;
    }
    parcel_fd_class_ = (jclass)env->NewGlobalRef(pfd_class.get());
    detach_fd_method_ = lsplant::JNI_GetMethodID(env, parcel_fd_class_, "detachFd", "()I");
    if (!detach_fd_method_) {
        LOGE("IPCBridge: ParcelFileDescriptor.detachFd method not found!");
        return;
    }

    LOGV("IPCBridge initialized successfully.");
    initialized_ = true;
}

lsplant::ScopedLocalRef<jobject> IPCBridge::RequestAppBinder(JNIEnv *env, jstring nice_name) {
    if (!initialized_) {
        LOGE("RequestAppBinder failed: IPCBridge not initialized.");
        return {env, nullptr};
    }

    // Get the rendezvous service from the Android ServiceManager.
    auto service_name = lsplant::ScopedLocalRef(env, env->NewStringUTF(kBridgeServiceName.data()));
    auto bridge_service = lsplant::JNI_CallStaticObjectMethod(
        env, service_manager_class_, get_service_method_, service_name.get());
    if (!bridge_service) {
        LOGE("Could not get rendezvous service '{}'. Manager not available?",
             kBridgeServiceName.data());
        return {env, nullptr};
    }

    // Prepare the IPC transaction.
    ParcelWrapper parcels(env, this);
    if (!parcels.data || !parcels.reply) {
        LOGE("Failed to obtain parcels for IPC.");
        return {env, nullptr};
    }

    // This is a "heartbeat" binder.
    // If our process dies, the manager service will be notified that this binder has died,
    // allowing it to clean up resources.
    auto heartbeat_binder =
        lsplant::ScopedLocalRef(env, env->NewObject(binder_class_, binder_ctor_));
    if (!heartbeat_binder) {
        LOGE("Failed to create heartbeat binder.");
        return {env, nullptr};
    }

    // Write the request data to the 'data' parcel.
    lsplant::JNI_CallVoidMethod(env, parcels.data.get(), write_int_method_, kActionGetBinder);
    lsplant::JNI_CallVoidMethod(env, parcels.data.get(), write_string_method_, nice_name);
    lsplant::JNI_CallVoidMethod(env, parcels.data.get(), write_strong_binder_method_,
                                heartbeat_binder.get());

    // Perform the transaction.
    bool success = lsplant::JNI_CallBooleanMethod(env, bridge_service.get(), transact_method_,
                                                  kBridgeTransactionCode, parcels.data.get(),
                                                  parcels.reply.get(), 0);

    lsplant::ScopedLocalRef<jobject> result_binder = {env, nullptr};
    if (success) {
        // Read the reply. CRITICAL: must call readException first.
        lsplant::JNI_CallVoidMethod(env, parcels.reply.get(), read_exception_method_);
        if (env->ExceptionCheck()) {
            LOGW("Remote exception received while requesting app binder.");
            env->ExceptionClear();
        } else {
            result_binder =
                lsplant::JNI_CallObjectMethod(env, parcels.reply.get(), read_strong_binder_method_);
            if (result_binder) {
                // IMPORTANT: Keep the heartbeat binder alive by making it a global ref.
                // If we don't do this, it gets garbage collected and the remote service
                // thinks our process has died.
                env->NewGlobalRef(heartbeat_binder.get());
            }
        }
    } else {
        LOGD("Transact call to request app binder failed.");
    }

    return result_binder;
}

lsplant::ScopedLocalRef<jobject> IPCBridge::RequestSystemServerBinder(
    JNIEnv *env, std::string bridgeServiceName) {
    if (!initialized_) {
        LOGE("RequestSystemServerBinder failed: IPCBridge not initialized.");
        return {env, nullptr};
    }

    auto service_name = lsplant::ScopedLocalRef(env, env->NewStringUTF(bridgeServiceName.data()));
    lsplant::ScopedLocalRef<jobject> binder = {env, nullptr};

    // The daemon process and system_server specialization run in parallel.
    // On slower devices, the daemon may take several seconds to call addService.
    // We poll for up to 10 seconds to ensure the early IPC channel for system_server is available.
    const int max_retry = 10;
    for (int i = 0; i < max_retry; ++i) {
        binder = lsplant::JNI_CallStaticObjectMethod(env, service_manager_class_,
                                                     get_service_method_, service_name.get());
        if (binder) {
            LOGI("Got system server binder via {} on attempt {}.", bridgeServiceName.data(), i + 1);
            return binder;
        }
        LOGW("Failed to get system server binder via {}, will retry in 1 second...",
             bridgeServiceName.data());
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    LOGE("Failed to get system server binder after {} attempts. Aborting.", max_retry);
    return {env, nullptr};
}

lsplant::ScopedLocalRef<jobject> IPCBridge::RequestManagerBinderFromSystemServer(
    JNIEnv *env, jobject system_server_binder) {
    ParcelWrapper parcels(env, this);
    auto heartbeat_binder =
        lsplant::ScopedLocalRef(env, env->NewObject(binder_class_, binder_ctor_));
    auto system_name = lsplant::ScopedLocalRef(env, env->NewStringUTF("system"));

    // Populate the request
    lsplant::JNI_CallVoidMethod(env, parcels.data.get(), write_int_method_, getuid());
    lsplant::JNI_CallVoidMethod(env, parcels.data.get(), write_int_method_, getpid());
    lsplant::JNI_CallVoidMethod(env, parcels.data.get(), write_string_method_, system_name.get());
    lsplant::JNI_CallVoidMethod(env, parcels.data.get(), write_strong_binder_method_,
                                heartbeat_binder.get());

    // Transact
    bool success = lsplant::JNI_CallBooleanMethod(env, system_server_binder, transact_method_,
                                                  kBridgeTransactionCode, parcels.data.get(),
                                                  parcels.reply.get(), 0);

    lsplant::ScopedLocalRef<jobject> result_binder = {env, nullptr};
    if (success) {
        lsplant::JNI_CallVoidMethod(env, parcels.reply.get(), read_exception_method_);
        if (env->ExceptionCheck()) {
            LOGW("Remote exception while getting manager binder from system_server.");
            env->ExceptionClear();
        } else {
            result_binder =
                lsplant::JNI_CallObjectMethod(env, parcels.reply.get(), read_strong_binder_method_);
            if (result_binder) {
                env->NewGlobalRef(heartbeat_binder.get());  // Keep heartbeat alive
            }
        }
    }
    LOGD("Manager binder from system_server: {}", static_cast<void *>(result_binder.get()));
    return result_binder;
}

std::tuple<int, size_t> IPCBridge::FetchFrameworkDex(JNIEnv *env, jobject binder) {
    if (!initialized_ || !binder) {
        return {-1, 0};
    }

    ParcelWrapper parcels(env, this);
    bool success =
        lsplant::JNI_CallBooleanMethod(env, binder, transact_method_, kDexTransactionCode,
                                       parcels.data.get(), parcels.reply.get(), 0);

    if (!success) {
        LOGE("DEX fetch transaction failed.");
        return {-1, 0};
    }

    lsplant::JNI_CallVoidMethod(env, parcels.reply.get(), read_exception_method_);
    if (env->ExceptionCheck()) {
        LOGE("Remote exception received while fetching DEX.");
        env->ExceptionClear();
        return {-1, 0};
    }

    auto pfd =
        lsplant::JNI_CallObjectMethod(env, parcels.reply.get(), read_file_descriptor_method_);
    if (!pfd) {
        LOGE("Received null ParcelFileDescriptor for DEX.");
        return {-1, 0};
    }

    int fd = lsplant::JNI_CallIntMethod(env, pfd.get(), detach_fd_method_);
    size_t size = static_cast<size_t>(
        lsplant::JNI_CallLongMethod(env, parcels.reply.get(), read_long_method_));

    LOGV("Fetched framework DEX: fd={}, size={}", fd, size);
    return {fd, size};
}

std::map<std::string, std::string> IPCBridge::FetchObfuscationMap(JNIEnv *env, jobject binder) {
    std::map<std::string, std::string> result_map;
    if (!initialized_ || !binder) {
        return result_map;
    }

    ParcelWrapper parcels(env, this);
    bool success = lsplant::JNI_CallBooleanMethod(env, binder, transact_method_,
                                                  kObfuscationMapTransactionCode,
                                                  parcels.data.get(), parcels.reply.get(), 0);

    if (!success) {
        LOGE("Obfuscation map fetch transaction failed.");
        return result_map;
    }

    lsplant::JNI_CallVoidMethod(env, parcels.reply.get(), read_exception_method_);
    if (env->ExceptionCheck()) {
        LOGE("Remote exception received while fetching obfuscation map.");
        env->ExceptionClear();
        return result_map;
    }

    int size = lsplant::JNI_CallIntMethod(env, parcels.reply.get(), read_int_method_);
    if (size < 0 || (size % 2 != 0)) {
        LOGE("Invalid size for obfuscation map received: %d", size);
        return result_map;
    }

    for (int i = 0; i < size / 2; ++i) {
        auto key_jstr = lsplant::JNI_Cast<jstring>(
            lsplant::JNI_CallObjectMethod(env, parcels.reply.get(), read_string_method_));

        auto val_jstr = lsplant::JNI_Cast<jstring>(
            lsplant::JNI_CallObjectMethod(env, parcels.reply.get(), read_string_method_));

        if (env->ExceptionCheck() || !key_jstr || !val_jstr) {
            LOGE("Error reading string from parcel for obfuscation map.");
            env->ExceptionClear();
            result_map.clear();  // Return an empty map on error
            return result_map;
        }

        lsplant::JUTFString key_str(env, key_jstr.get());
        lsplant::JUTFString val_str(env, val_jstr.get());
        result_map[key_str.get()] = val_str.get();
    }

    LOGV("Fetched obfuscation map with {} entries.", result_map.size());
    return result_map;
}

jboolean IPCBridge::ExecTransact_Replace(jboolean *res, JNIEnv *env, jobject obj, va_list args) {
    va_list copy;
    va_copy(copy, args);
    // Extract arguments from the va_list for Binder.execTransact(int, long, long, int)
    auto code = va_arg(copy, jint);
    auto data_obj = va_arg(copy, jlong);
    auto reply_obj = va_arg(copy, jlong);
    auto flags = va_arg(copy, jint);
    va_end(copy);

    // If the transaction code matches our special code, intercept it.
    if (code == kBridgeTransactionCode) {
        // Call the static Java method in our framework's BridgeService to handle the call.
        *res = env->CallStaticBooleanMethod(GetInstance().bridge_service_class_,
                                            GetInstance().exec_transact_replace_method_id_, obj,
                                            code, data_obj, reply_obj, flags);
        if (env->ExceptionCheck()) {
            LOGW("Exception in Java BridgeService.execTransact handler.");
            env->ExceptionClear();
        }
        if (*res == JNI_FALSE) {
            uint64_t caller_id = BinderCaller::GetId();
            if (caller_id != 0) {
                // LOGV("Caller {} rejected by bridge service.", caller_id);
                g_last_failed_id.store(caller_id, std::memory_order_relaxed);
            }
        } else {
            g_last_failed_id.store(~0, std::memory_order_relaxed);
        }
        return true;  // Return true to indicate we handled the call.
    }
    return false;  // Not our transaction, let the original method run.
}

jboolean JNICALL IPCBridge::CallBooleanMethodV_Hook(JNIEnv *env, jobject obj, jmethodID methodId,
                                                    va_list args) {
    // Check if the method being called is the one we want to intercept: Binder.execTransact()
    if (methodId == GetInstance().exec_transact_backup_method_id_) {
        uint64_t current_caller_id = BinderCaller::GetId();

        jboolean res = false;
        // Attempt to handle the transaction with our replacement logic.
        if (current_caller_id != 0 &&
            // If this caller is the one that just failed,
            // skip interception and go straight to the original function.
            current_caller_id != g_last_failed_id.load(std::memory_order_relaxed) &&
            ExecTransact_Replace(&res, env, obj, args)) {
            return res;  // If we handled it, return the result directly.
        }
        // If not handled, fall through to call the original method.
    }
    // Call the original, real CallBooleanMethodV function.
    return GetInstance().call_boolean_method_v_backup_(env, obj, methodId, args);
}

void IPCBridge::HookBridge(JNIEnv *env) {
    if (!initialized_) {
        LOGE("Cannot hook bridge: IPCBridge is not initialized.");
        return;
    }

    // Get framework-specific Java classes and methods ---
    const auto &obfs_map = ConfigBridge::GetInstance()->obfuscation_map();
    std::string bridge_service_class_name;
    bridge_service_class_name = obfs_map.at("org.matrix.vector.service.") + "BridgeService";

    auto bridge_class_ref =
        Context::GetInstance()->FindClassFromCurrentLoader(env, bridge_service_class_name);
    if (!bridge_class_ref) {
        LOGE("Failed to find BridgeService class '{}'", bridge_service_class_name.c_str());
        return;
    }
    bridge_service_class_ = lsplant::JNI_NewGlobalRef(env, bridge_class_ref);

    exec_transact_replace_method_id_ = lsplant::JNI_GetStaticMethodID(
        env, bridge_service_class_, "execTransact", "(Landroid/os/IBinder;IJJI)Z");
    if (!exec_transact_replace_method_id_) {
        LOGE("Failed to find static method BridgeService.execTransact!");
        return;
    }

    // --- Prepare the JNI hook ---
    // Get the original method ID for android.os.Binder.execTransact
    exec_transact_backup_method_id_ =
        lsplant::JNI_GetMethodID(env, binder_class_, "execTransact", "(IJJI)Z");
    if (!exec_transact_backup_method_id_) {
        LOGE("Failed to find original method Binder.execTransact!");
        return;
    }

    // Use the native library's API to get the JNI table override function.
    auto set_table_override_func =
        (void (*)(const JNINativeInterface *))ElfSymbolCache::GetArt()->getSymbAddress(
            "_ZN3art9JNIEnvExt16SetTableOverrideEPK18JNINativeInterface");
    if (!set_table_override_func) {
        LOGE("Failed to find ART symbol SetTableOverride!");
        return;
    }

    // --- Step 3: Install the hook ---
    // Make a full copy of the existing JNI function table.
    memcpy(&native_interface_hook_, env->functions, sizeof(JNINativeInterface));

    // Store a pointer to the original function we are about to replace.
    call_boolean_method_v_backup_ = env->functions->CallBooleanMethodV;

    // Overwrite the function pointer in our copy with our hook.
    native_interface_hook_.CallBooleanMethodV = &CallBooleanMethodV_Hook;

    // Atomically swap the process's JNI function table with our modified one.
    set_table_override_func(&native_interface_hook_);

    BinderCaller::Initialize();

    LOGI("IPC Bridge JNI hook installed successfully.");
}

}  // namespace vector::native::module

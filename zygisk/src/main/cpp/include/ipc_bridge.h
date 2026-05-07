#pragma once

#include <jni.h>

#include <map>
#include <string>
#include <tuple>

// This module is a client of the 'native' library.
// We include the JNI bridge from 'native' to leverage its helper functions.
#include <jni/jni_bridge.h>

namespace vector::native::module {

/**
 * @class IPCBridge
 * @brief Manages Binder IPC communication with the Vector host service.
 *
 * This singleton class is the communication arm of the Zygisk module. Its key responsibilities are:
 * 1. Discovering and connecting to the central host service (the "manager").
 * 2. Requesting the framework's DEX file and obfuscation map from the service.
 * 3. Caching all necessary JNI class and method IDs for efficient reuse.
 */
class IPCBridge {
public:
    // Enforce singleton pattern.
    IPCBridge(const IPCBridge &) = delete;
    IPCBridge &operator=(const IPCBridge &) = delete;

    /**
     * @brief Gets the singleton instance of the IPCBridge.
     */
    static IPCBridge &GetInstance();

    /**
     * @brief Caches JNI class and method IDs needed for Binder communication.
     * @param env A valid JNI environment pointer.
     */
    void Initialize(JNIEnv *env);

    /**
     * @brief Requests an application-specific Binder from the host service.
     * @param env JNI environment pointer.
     * @param nice_name The process name.
     * @return A ScopedLocalRef to the Binder object, or nullptr on failure.
     */
    lsplant::ScopedLocalRef<jobject> RequestAppBinder(JNIEnv *env, jstring nice_name);

    /**
     * @brief Requests the system_server's dedicated Binder from the host service.
     * @param env JNI environment pointer.
     * @param bridgeServiceName rendezvous point used by the system_server
     * @return A ScopedLocalRef to the Binder object, or nullptr on failure.
     */
    lsplant::ScopedLocalRef<jobject> RequestSystemServerBinder(JNIEnv *env,
                                                               std::string bridgeServiceName);

    /**
     * @brief Asks the system_server binder for the application manager binder.
     * @param env JNI environment pointer.
     * @param system_server_binder The binder connected to the system server service.
     * @return A ScopedLocalRef to the application manager Binder, or nullptr on failure.
     */
    lsplant::ScopedLocalRef<jobject> RequestManagerBinderFromSystemServer(
        JNIEnv *env, jobject system_server_binder);

    /**
     * @brief Fetches the framework DEX file via the provided Binder connection.
     * @param env JNI environment pointer.
     * @param binder A live Binder connection to the host service.
     * @return A tuple containing the file descriptor and size of the DEX file.
     *         Returns {-1, 0} on failure.
     */
    std::tuple<int, size_t> FetchFrameworkDex(JNIEnv *env, jobject binder);

    /**
     * @brief Fetches the framework's obfuscation map via the provided Binder.
     * @param env JNI environment pointer.
     * @param binder A live Binder connection to the host service.
     * @return A map of original names to obfuscated names.
     */
    std::map<std::string, std::string> FetchObfuscationMap(JNIEnv *env, jobject binder);

    /**
     * @brief Sets up the JNI hook to intercept Binder transactions.
     *
     * This is the core of the IPC interception mechanism.
     * It replaces the JNI function pointer for CallBooleanMethodV to
     * inspect calls to Binder.execTransact, allowing the framework to
     * handle its own custom transaction codes directly.
     * @param env JNI environment pointer.
     */
    void HookBridge(JNIEnv *env);

private:
    /**
     * @class ParcelWrapper
     * @brief A private RAII wrapper to ensure Parcel objects are always recycled.
     *
     * As a private nested class, it has access to the private members of IPCBridge
     * (like parcel_class_ and recycle_method_).
     */
    class ParcelWrapper {
    public:
        ParcelWrapper(JNIEnv *env, IPCBridge *bridge);
        ~ParcelWrapper();
        // Disable copy operations
        ParcelWrapper(const ParcelWrapper &) = delete;
        ParcelWrapper &operator=(const ParcelWrapper &) = delete;

        lsplant::ScopedLocalRef<jobject> data;
        lsplant::ScopedLocalRef<jobject> reply;

    private:
        JNIEnv *env_;
        IPCBridge *bridge_;
    };

    // Private constructor for singleton.
    IPCBridge() = default;

    bool initialized_ = false;

    // --- Cached JNI References ---
    // These are initialized once and stored as global references for performance.

    // android.os.ServiceManager
    jclass service_manager_class_ = nullptr;
    jmethodID get_service_method_ = nullptr;

    // android.os.IBinder
    jmethodID transact_method_ = nullptr;

    // android.os.Binder
    jclass binder_class_ = nullptr;
    jmethodID binder_ctor_ = nullptr;

    // android.os.Parcel
    jclass parcel_class_ = nullptr;
    jmethodID obtain_method_ = nullptr;
    jmethodID recycle_method_ = nullptr;
    jmethodID write_interface_token_method_ = nullptr;
    jmethodID write_int_method_ = nullptr;
    jmethodID write_string_method_ = nullptr;
    jmethodID write_strong_binder_method_ = nullptr;
    jmethodID read_exception_method_ = nullptr;
    jmethodID read_strong_binder_method_ = nullptr;
    jmethodID read_file_descriptor_method_ = nullptr;
    jmethodID read_int_method_ = nullptr;
    jmethodID read_long_method_ = nullptr;
    jmethodID read_string_method_ = nullptr;

    // android.os.ParcelFileDescriptor
    jclass parcel_fd_class_ = nullptr;
    jmethodID detach_fd_method_ = nullptr;

    // --- JNI Hooking Members ---
    // These are required to store the state for our JNI function table override.

    // The C++ hook function that will replace the original CallBooleanMethodV.
    static jboolean JNICALL CallBooleanMethodV_Hook(JNIEnv *env, jobject obj, jmethodID methodId,
                                                    va_list args);
    // The helper function that handles our specific transaction code.
    static jboolean ExecTransact_Replace(jboolean *res, JNIEnv *env, jobject obj, va_list args);

    // A complete copy of the original JNI function table, with our hook installed.
    JNINativeInterface native_interface_hook_{};
    // The original jmethodID for android.os.Binder.execTransact().
    jmethodID exec_transact_backup_method_id_ = nullptr;
    // A function pointer to the original CallBooleanMethodV implementation.
    jboolean (*call_boolean_method_v_backup_)(JNIEnv *, jobject, jmethodID, va_list) = nullptr;

    // A global reference to the framework's Java BridgeService class.
    jclass bridge_service_class_ = nullptr;
    // The jmethodID for the static Java method that handles the intercepted transaction.
    jmethodID exec_transact_replace_method_id_ = nullptr;
};

}  // namespace vector::native::module

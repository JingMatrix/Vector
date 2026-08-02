#pragma once

#include <sys/mman.h>

#include <lsplant.hpp>
#include <memory>
#include <string_view>
#include <utils/jni_helper.hpp>

#include "common/logging.h"

/**
 * @file context.h
 * @brief Defines the core runtime context for the Vector native environment.
 *
 * The Context class is a singleton that holds essential runtime information, such as
 * the injected class loader, and provides core functionalities like class finding and DEX loading.
 * It serves as the central hub for native operations.
 */

namespace vector::native {

// Forward declaration from another module.
class ConfigBridge;

/**
 * @class Context
 * @brief Manages the global state and core operations of the native library.
 *
 * This singleton is responsible for initializing hooks, managing DEX files,
 * and providing access to the application's class loader.
 * It orchestrates the setup process when the library is loaded into the target application.
 */
class Context {
public:
    Context(const Context &) = delete;
    Context &operator=(const Context &) = delete;

    /**
     * @brief Gets the singleton instance of the Context.
     * @return A pointer to the global Context instance.
     */
    static Context *GetInstance();

    /**
     * @brief Releases ownership of the singleton instance.
     *
     * This is typically used during shutdown to clean up resources.
     * After this call, GetInstance() will return nullptr until a new instance is created.
     *
     * @return A unique_ptr owning the Context instance.
     */
    static std::unique_ptr<Context> ReleaseInstance();

    /**
     * @brief Gets the class loader used for injecting framework classes.
     * @return A global JNI reference to the class loader.
     */
    [[nodiscard]] jobject GetCurrentClassLoader() const { return inject_class_loader_; }

    /**
     * @brief Finds a class using the framework's injected class loader.
     *
     * This is the primary method for looking up classes that are part of the
     * Vector framework's Java components.
     *
     * @param env The JNI environment.
     * @param class_name The fully qualified name of the class to find (dot-separated).
     * @return A ScopedLocalRef containing the jclass object, or nullptr if not found.
     */
    [[nodiscard]] lsplant::ScopedLocalRef<jclass> FindClassFromCurrentLoader(
        JNIEnv *env, std::string_view class_name) const {
        return FindClassFromLoader(env, GetCurrentClassLoader(), class_name);
    }

    virtual ~Context() = default;

protected:
    /**
     * @class PreloadedDex
     * @brief Manages a memory-mapped DEX file.
     *
     * This helper class handles the mapping of a DEX file from a file descriptor
     * into memory and ensures it is unmapped upon destruction.
     */
    class PreloadedDex {
    public:
        PreloadedDex() : addr_(nullptr), size_(0) {}
        PreloadedDex(const PreloadedDex &) = delete;
        PreloadedDex &operator=(const PreloadedDex &) = delete;

        /**
         * @brief Memory-maps a DEX file from a file descriptor.
         * @param fd The file descriptor of the DEX file.
         * @param size The size of the file.
         */
        PreloadedDex(int fd, size_t size);

        PreloadedDex(PreloadedDex &&other) noexcept : addr_(other.addr_), size_(other.size_) {
            other.addr_ = nullptr;
            other.size_ = 0;
        }

        PreloadedDex &operator=(PreloadedDex &&other) noexcept {
            if (this != &other) {
                if (addr_) {
                    munmap(addr_, size_);
                }
                addr_ = other.addr_;
                size_ = other.size_;
                other.addr_ = nullptr;
                other.size_ = 0;
            }
            return *this;
        }

        ~PreloadedDex();

        /// Checks if the DEX file was successfully mapped.
        explicit operator bool() const { return addr_ != nullptr && size_ > 0; }
        /// Returns the size of the mapped DEX data.
        [[nodiscard]] auto size() const { return size_; }
        /// Returns a pointer to the beginning of the mapped DEX data.
        [[nodiscard]] auto data() const { return addr_; }

    private:
        void *addr_;
        size_t size_;
    };

    Context() = default;

    /**
     * @brief Finds a class from a specific class loader instance.
     * @param env The JNI environment.
     * @param class_loader The class loader to use for the lookup.
     * @param class_name The name of the class to find.
     * @return A ScopedLocalRef containing the jclass, or nullptr if not found.
     */
    static lsplant::ScopedLocalRef<jclass> FindClassFromLoader(JNIEnv *env, jobject class_loader,
                                                               std::string_view class_name);

    /**
     * @brief Finds and calls a static void method on the framework's entry class.
     *
     * A utility for internal communication between the native and Java layers.
     *
     * A Java method that throws does not unwind into C++: the exception is left *pending* on this
     * thread, and until it is cleared almost every JNI function is illegal to call. With CheckJNI
     * on, the next one aborts the process; without it, the exception stays pending until control
     * returns to Java and is then thrown somewhere with nothing to do with us — typically inside
     * the starting application, where no stack frame points back here. So the exception is reported
     * where it happened, with the Java stack that only exists at this moment, and whether the call
     * arrived is something the caller can act on.
     *
     * @tparam Args Argument types for the method call.
     * @param env The JNI environment.
     * @param method_name The name of the static method.
     * @param method_sig The JNI signature of the method.
     * @param args The arguments to pass to the method.
     * @return Whether the method was found and returned without throwing.
     */
    template <typename... Args>
    bool FindAndCall(JNIEnv *env, std::string_view method_name, std::string_view method_sig,
                     Args &&...args) const {
        if (!entry_class_) {
            LOGE("Cannot call method '{}', entry class is null", method_name.data());
            return false;
        }
        jmethodID mid = lsplant::JNI_GetStaticMethodID(env, entry_class_, method_name, method_sig);
        if (!mid) {
            LOGE("Static method '{}' with signature '{}' not found", method_name.data(),
                 method_sig.data());
            return false;
        }
        env->CallStaticVoidMethod(entry_class_, mid,
                                  lsplant::UnwrapScope(std::forward<Args>(args))...);
        // ClearException, not ExceptionDescribe: the latter writes the trace to stderr, and a
        // process forked from the zygote has nowhere for stderr to go, so the trace is simply lost.
        // This asks Java to render it and logs the result under our own tag, which is the only
        // place the stack still exists to be read.
        if (auto trace = lsplant::ClearException(env)) {
            LOGE("Java entry '{}' threw:\n{}", method_name.data(),
                 lsplant::JUTFString(env, trace.get()).get());
            return false;
        }
        return true;
    }

    // --- Virtual methods for platform-specific implementations ---

    /// Initializes the ART hooking framework (LSPlant).
    virtual void InitArtHooker(JNIEnv *env, const lsplant::InitInfo &initInfo);

    /// Registers all necessary JNI bridges and native hooks.
    virtual void InitHooks(JNIEnv *env);

    /// Loads a DEX file into the target application.
    virtual void LoadDex(JNIEnv *env, PreloadedDex &&dex) = 0;

    /// Sets up the main entry class for native-to-Java calls.
    virtual void SetupEntryClass(JNIEnv *env) = 0;

protected:
    /// The singleton instance of the Context.
    static std::unique_ptr<Context> instance_;

    /// Global reference to the classloader used to load the framework.
    jobject inject_class_loader_ = nullptr;

    /// Global reference to the primary entry point class in the Java framework.
    jclass entry_class_ = nullptr;
};

}  // namespace vector::native

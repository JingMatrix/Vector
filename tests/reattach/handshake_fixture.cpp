#include <cassert>
#include <chrono>
#include <iostream>
#include <string>
#include <unistd.h>
#include <vector>

// JNI/transport are controlled; both IPCBridge functions below are taken from production.
struct Handle { int kind; };
using jobject = Handle*;
using jstring = Handle*;
Handle ordinary{1}, working{2}, throwing{3}, framework{4}, heartbeat{5}, text{6}, parcel{7};
struct JNIEnv {
    bool exception = false;
    int retained = 0, cleared = 0;
    jobject NewObject(int, int) { return &heartbeat; }
    jstring NewStringUTF(const char*) { return &text; }
    bool ExceptionCheck() { return exception; }
    void ExceptionClear() { exception = false; ++cleared; }
    jobject NewGlobalRef(jobject object) { ++retained; return object; }
};
namespace lsplant {
template<class T = jobject> struct ScopedLocalRef {
    T object;
    ScopedLocalRef(JNIEnv*, T object) : object(object) {}
    T get() const { return object; }
    explicit operator bool() const { return object != nullptr; }
};
std::vector<jobject> services;
int polls, transactions, waits;
ScopedLocalRef<jobject> JNI_CallStaticObjectMethod(JNIEnv* env, int, int, jobject) {
    jobject result = services.at(polls++);
    return {env, result};
}
template<class... Args> void JNI_CallVoidMethod(JNIEnv*, jobject, int, Args...) {}
template<class... Args> bool JNI_CallBooleanMethod(JNIEnv* env, jobject binder, int, Args...) {
    ++transactions;
    if (binder == &throwing) env->exception = true;
    return binder == &working;
}
ScopedLocalRef<jobject> JNI_CallObjectMethod(JNIEnv* env, jobject, int) { return {env, &framework}; }
}
namespace fixture {
void sleep_for(std::chrono::seconds) { ++lsplant::waits; }
}
#define LOGE(...) ((void)0)
#define LOGW(...) ((void)0)
#define LOGI(...) ((void)0)
#define LOGD(...) ((void)0)
constexpr int kBridgeTransactionCode = 0;
class IPCBridge {
public:
    bool initialized_ = true;
    int service_manager_class_ = 0, get_service_method_ = 0, binder_class_ = 0, binder_ctor_ = 0;
    int write_int_method_ = 0, write_string_method_ = 0, write_strong_binder_method_ = 0;
    int transact_method_ = 0, read_exception_method_ = 0, read_strong_binder_method_ = 0;
    struct ParcelWrapper {
        lsplant::ScopedLocalRef<jobject> data, reply;
        ParcelWrapper(JNIEnv* env, IPCBridge*) : data(env, &parcel), reply(env, &parcel) {}
    };
    lsplant::ScopedLocalRef<jobject> RequestSystemServerBinder(JNIEnv*, std::string);
    lsplant::ScopedLocalRef<jobject> RequestManagerBinderFromSystemServer(JNIEnv*, jobject);
};

// PRODUCTION_FUNCTIONS

int main() {
    IPCBridge bridge;
    for (const auto& name : {"serial", "serial_vector"}) {
        JNIEnv env;
        lsplant::services = {nullptr, &ordinary, &throwing, &working};
        lsplant::polls = lsplant::transactions = lsplant::waits = 0;
        auto result = bridge.RequestSystemServerBinder(&env, name);
        assert(result.get() == &framework);
        assert(lsplant::polls == 4 && lsplant::transactions == 3 && lsplant::waits == 3);
        assert(!env.exception && env.cleared == 1 && env.retained == 1);
        std::cout << "PASS " << name << ": absent/occupied/exception endpoints retried until handshake\n";
    }
    JNIEnv env;
    lsplant::services.assign(10, &ordinary);
    lsplant::polls = lsplant::transactions = lsplant::waits = 0;
    assert(!bridge.RequestSystemServerBinder(&env, "serial"));
    assert(lsplant::polls == 10 && env.retained == 0);
    bridge.initialized_ = false;
    assert(!bridge.RequestSystemServerBinder(&env, "serial"));
    assert(lsplant::polls == 10);
    std::cout << "PASS bounded failure, no unverified service returned, no failed-attempt heartbeat retained\n";
}

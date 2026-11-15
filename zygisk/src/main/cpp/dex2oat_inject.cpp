#include <android/api-level.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <spawn.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include <common/logging.h>
#include <core/native_api.h>

#include "zygisk_next_api.h"

extern "C" char **environ;

namespace vector::native::dex2oat {

namespace {

constexpr const char *kWrapper32 = "/data/adb/modules/zygisk_vector/bin/dex2oat32";
constexpr const char *kWrapper64 = "/data/adb/modules/zygisk_vector/bin/dex2oat64";
constexpr const char *kMonitorDir = "/data/adb/lspd";
constexpr const char *kMonitorPrefix = "dex2oat_monitor_";
constexpr const char *kPreloadPrefix = "LD_PRELOAD=";
constexpr const char *kCmdPrefix = "DEX2OAT_CMD=";
constexpr char kRequestMagic = 'V';

constexpr size_t kMaxArgv = 256;
constexpr size_t kMaxEnvp = 256;
constexpr size_t kMaxMonitors = 4;
constexpr size_t kMaxMonitorName = 32;

using ExecveFn = int (*)(const char *, char *const[], char *const[]);
using ExecvFn = int (*)(const char *, char *const[]);
using PosixSpawnFn = int (*)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                             const posix_spawnattr_t *, char *const[], char *const[]);

const ZygiskNextAPI *g_api = nullptr;

ExecveFn g_orig_execve = nullptr;
ExecvFn g_orig_execv = nullptr;
PosixSpawnFn g_orig_posix_spawn = nullptr;
PosixSpawnFn g_orig_posix_spawnp = nullptr;

std::atomic<int> g_ready{-1};
std::atomic<bool> g_arming{false};
std::atomic<int> g_spawn_depth{0};

struct MonitorSlot {
    std::atomic<bool> claimed{false};
    std::atomic<bool> serving{false};
    std::atomic<char> status{0};
    char name[kMaxMonitorName] {};
};

MonitorSlot g_monitors[kMaxMonitors];
pthread_mutex_t g_monitor_lock = PTHREAD_MUTEX_INITIALIZER;

struct TransformBuffers {
    char *argv[kMaxArgv + 2];
    char *envp[kMaxEnvp + 2];
    const char *path;
    char cmd[4200];
};

bool WriteAll(int fd, const void *data, size_t size) {
    const char *p = static_cast<const char *>(data);
    size_t written = 0;
    while (written < size) {
        ssize_t n = write(fd, p + written, size - written);
        if (n <= 0) return false;
        written += static_cast<size_t>(n);
    }
    return true;
}

bool ReadAll(int fd, void *data, size_t size) {
    char *p = static_cast<char *>(data);
    size_t done = 0;
    while (done < size) {
        ssize_t n = read(fd, p + done, size - done);
        if (n <= 0) return false;
        done += static_cast<size_t>(n);
    }
    return true;
}

std::string ProcessName() {
    char buffer[256] {};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t n = read(fd, buffer, sizeof(buffer) - 1);
        close(fd);
        if (n > 0) {
            buffer[n] = '\0';
            std::string name(buffer);
            auto nul = name.find('\0');
            if (nul != std::string::npos) name.resize(nul);
            auto slash = name.rfind('/');
            if (slash != std::string::npos) name.erase(0, slash + 1);
            if (!name.empty()) return name;
        }
    }

    fd = open("/proc/self/comm", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t n = read(fd, buffer, sizeof(buffer) - 1);
        close(fd);
        if (n > 0) {
            buffer[n] = '\0';
            std::string name(buffer);
            while (!name.empty() && (name.back() == '\n' || name.back() == '\r')) name.pop_back();
            if (!name.empty()) return name;
        }
    }
    return "unknown";
}

std::string SafeName(const std::string &name) {
    std::string out;
    for (char c : name) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' ||
            c == '-' || c == '.') {
            out.push_back(c);
        }
    }
    if (out.empty() || out == "." || out == "..") return "unknown";
    return out;
}

bool IsDex2Oat(const char *path, char *const argv[]) {
    if (path != nullptr && strstr(path, "dex2oat") != nullptr) return true;
    return argv != nullptr && argv[0] != nullptr && strstr(argv[0], "dex2oat") != nullptr;
}

bool IsArtExec(const char *path, char *const argv[]) {
    if (path != nullptr && strstr(path, "art_exec") != nullptr) return true;
    return argv != nullptr && argv[0] != nullptr && strstr(argv[0], "art_exec") != nullptr;
}

bool Is64BitExecutable(const char *path, char *const argv[]) {
    const char *candidate = path;
    if (candidate == nullptr && argv != nullptr) candidate = argv[0];
    if (candidate != nullptr) {
        int fd = open(candidate, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            unsigned char header[5] {};
            ssize_t n = read(fd, header, sizeof(header));
            close(fd);
            if (n == 5 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' &&
                header[3] == 'F') {
                return header[4] == 2;
            }
        }
        if (strstr(candidate, "64") != nullptr) return true;
        if (strstr(candidate, "32") != nullptr) return false;
    }
    return sizeof(void *) == 8;
}

bool WrapperMounted(const char *path, bool is64) {
    if (path == nullptr) return false;
    struct stat target {};
    struct stat wrapper {};
    if (stat(path, &target) != 0) return false;
    if (stat(is64 ? kWrapper64 : kWrapper32, &wrapper) != 0) return false;
    return target.st_dev == wrapper.st_dev && target.st_ino == wrapper.st_ino;
}

bool WrapperReady(bool is64) {
    struct stat wrapper {};
    return stat(is64 ? kWrapper64 : kWrapper32, &wrapper) == 0;
}

bool BuildEnv(char *const env_in[], const char *cmd, TransformBuffers &buffers) {
    if (cmd == nullptr) return false;

    snprintf(buffers.cmd, sizeof(buffers.cmd), "%s%s", kCmdPrefix, cmd);

    size_t envc = 0;
    char *const *env = env_in != nullptr ? env_in : environ;
    for (size_t i = 0; env != nullptr && env[i] != nullptr; i++) {
        if (strncmp(env[i], kPreloadPrefix, strlen(kPreloadPrefix)) == 0) continue;
        if (strncmp(env[i], kCmdPrefix, strlen(kCmdPrefix)) == 0) continue;
        if (envc + 2 >= kMaxEnvp) return false;
        buffers.envp[envc++] = env[i];
    }
    buffers.envp[envc++] = buffers.cmd;
    buffers.envp[envc] = nullptr;
    return true;
}

bool Build(const char *path, char *const argv_in[], char *const env_in[], bool is64,
           TransformBuffers &buffers) {
    if (argv_in == nullptr) return false;

    const char *cmd = nullptr;
    if (path != nullptr && path[0] == '/') {
        cmd = path;
    } else if (argv_in[0] != nullptr && argv_in[0][0] == '/') {
        cmd = argv_in[0];
    } else {
        return false;
    }

    size_t argc = 0;
    while (argv_in[argc] != nullptr) {
        if (argc >= kMaxArgv) return false;
        buffers.argv[argc] = argv_in[argc];
        argc++;
    }
    buffers.argv[argc] = nullptr;

    buffers.path = is64 ? kWrapper64 : kWrapper32;
    return BuildEnv(env_in, cmd, buffers);
}

bool BuildArtExec(const char *path, char *const argv_in[], char *const env_in[],
                  TransformBuffers &buffers) {
    size_t argc = 0;
    size_t separator = SIZE_MAX;
    while (argv_in[argc] != nullptr) {
        if (argc >= kMaxArgv) return false;
        if (separator == SIZE_MAX && strcmp(argv_in[argc], "--") == 0) separator = argc;
        argc++;
    }
    if (separator == SIZE_MAX || separator + 1 >= argc) return false;

    const char *target = argv_in[separator + 1];
    if (target == nullptr || strstr(target, "dex2oat") == nullptr) return false;
    bool is64 = Is64BitExecutable(target, nullptr);
    if (WrapperMounted(target, is64)) return false;
    if (!WrapperReady(is64)) return false;

    const char *wrapper = is64 ? kWrapper64 : kWrapper32;
    size_t out = 0;
    for (size_t i = 0; i < argc; i++) {
        buffers.argv[out++] = i == separator + 1 ? const_cast<char *>(wrapper) : argv_in[i];
    }
    buffers.argv[out] = nullptr;

    buffers.path = path;
    return BuildEnv(env_in, target, buffers);
}

bool Transform(const char *path, char *const argv_in[], char *const env_in[],
               TransformBuffers &buffers) {
    buffers.path = path;
    if (IsDex2Oat(path, argv_in)) {
        bool is64 = Is64BitExecutable(path, argv_in);
        if (WrapperMounted(path, is64)) return false;
        if (!WrapperReady(is64)) return false;
        return Build(path, argv_in, env_in, is64, buffers);
    }
    if (IsArtExec(path, argv_in)) return BuildArtExec(path, argv_in, env_in, buffers);
    return false;
}

int HookExecve(const char *path, char *const argv[], char *const envp[]) {
    if (g_orig_execve == nullptr) return -1;
    if (g_spawn_depth.load(std::memory_order_relaxed) > 0) {
        return g_orig_execve(path, argv, envp);
    }
    if (!IsDex2Oat(path, argv) && !IsArtExec(path, argv)) {
        return g_orig_execve(path, argv, envp);
    }
    TransformBuffers buffers;
    if (!Transform(path, argv, envp, buffers)) return g_orig_execve(path, argv, envp);
    return g_orig_execve(buffers.path, buffers.argv, buffers.envp);
}

int HookExecv(const char *path, char *const argv[]) {
    if (g_orig_execv == nullptr) return -1;
    if (g_spawn_depth.load(std::memory_order_relaxed) > 0) return g_orig_execv(path, argv);
    if (!IsDex2Oat(path, argv) && !IsArtExec(path, argv)) return g_orig_execv(path, argv);
    TransformBuffers buffers;
    if (!Transform(path, argv, nullptr, buffers)) return g_orig_execv(path, argv);
    if (g_orig_execve != nullptr) return g_orig_execve(buffers.path, buffers.argv, buffers.envp);
    char **saved = environ;
    environ = buffers.envp;
    int result = g_orig_execv(buffers.path, buffers.argv);
    environ = saved;
    return result;
}

int HookPosixSpawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                   const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (g_orig_posix_spawn == nullptr) return -1;
    if (g_spawn_depth.load(std::memory_order_relaxed) > 0) {
        return g_orig_posix_spawn(pid, path, actions, attr, argv, envp);
    }
    if (!IsDex2Oat(path, argv) && !IsArtExec(path, argv)) {
        return g_orig_posix_spawn(pid, path, actions, attr, argv, envp);
    }
    TransformBuffers buffers;
    if (!Transform(path, argv, envp, buffers)) {
        return g_orig_posix_spawn(pid, path, actions, attr, argv, envp);
    }
    g_spawn_depth.fetch_add(1, std::memory_order_relaxed);
    int result =
        g_orig_posix_spawn(pid, buffers.path, actions, attr, buffers.argv, buffers.envp);
    g_spawn_depth.fetch_sub(1, std::memory_order_relaxed);
    return result;
}

int HookPosixSpawnp(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                    const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (g_orig_posix_spawnp == nullptr) return -1;
    if (g_spawn_depth.load(std::memory_order_relaxed) > 0) {
        return g_orig_posix_spawnp(pid, path, actions, attr, argv, envp);
    }
    if (!IsDex2Oat(path, argv) && !IsArtExec(path, argv)) {
        return g_orig_posix_spawnp(pid, path, actions, attr, argv, envp);
    }
    TransformBuffers buffers;
    if (!Transform(path, argv, envp, buffers)) {
        return g_orig_posix_spawnp(pid, path, actions, attr, argv, envp);
    }
    g_spawn_depth.fetch_add(1, std::memory_order_relaxed);
    int result =
        g_orig_posix_spawnp(pid, buffers.path, actions, attr, buffers.argv, buffers.envp);
    g_spawn_depth.fetch_sub(1, std::memory_order_relaxed);
    return result;
}

template <typename T>
bool InstallAt(void *target, T replacement, T *original) {
    if (target == nullptr) return false;

    void *backup = nullptr;
    int result = ZN_FAILED;
    if (g_api != nullptr && g_api->inlineHook != nullptr) {
        result = g_api->inlineHook(target, reinterpret_cast<void *>(replacement), &backup);
    }
    if (result != ZN_SUCCESS) {
        if (HookInline(target, reinterpret_cast<void *>(replacement), &backup) != 0) return false;
    }
    *original = reinterpret_cast<T>(backup);
    return true;
}

bool InstallHooks() {
    void *execve_target = dlsym(RTLD_DEFAULT, "execve");
    void *execv_target = dlsym(RTLD_DEFAULT, "execv");
    void *spawn_target = dlsym(RTLD_DEFAULT, "posix_spawn");
    void *spawnp_target = dlsym(RTLD_DEFAULT, "posix_spawnp");

    bool execve = InstallAt(execve_target, HookExecve, &g_orig_execve);
    if (execv_target != execve_target) InstallAt(execv_target, HookExecv, &g_orig_execv);
    bool spawn = InstallAt(spawn_target, HookPosixSpawn, &g_orig_posix_spawn);
    bool spawnp = spawnp_target != spawn_target &&
                  InstallAt(spawnp_target, HookPosixSpawnp, &g_orig_posix_spawnp);
    return execve && (spawn || spawnp);
}

bool ArmHooks() {
    if (!InstallHooks()) {
        LOGW("dex2oat injection: cannot hook exec");
        return false;
    }
    LOGI("dex2oat injection: armed in {}", ProcessName().c_str());
    return true;
}

void ReportStatus(void *self_handle, bool armed) {
    if (g_api == nullptr || g_api->connectCompanion == nullptr) return;

    int socket = g_api->connectCompanion(self_handle);
    if (socket < 0) {
        LOGW("dex2oat injection: the companion is unreachable");
        return;
    }

    struct timeval timeout {};
    timeout.tv_sec = 2;
    setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    std::string name = SafeName(ProcessName());
    if (name.size() > 255) name.resize(255);
    unsigned char header[3] = {static_cast<unsigned char>(kRequestMagic),
                               static_cast<unsigned char>(sizeof(void *) == 8 ? 1 : 0),
                               static_cast<unsigned char>(name.size())};
    char status = armed ? 1 : 0;
    if (!WriteAll(socket, header, sizeof(header)) || !WriteAll(socket, name.data(), name.size()) ||
        !WriteAll(socket, &status, 1)) {
        LOGW("dex2oat injection: cannot report the status: {}", strerror(errno));
    }
    close(socket);
}

MonitorSlot *ClaimMonitor(const std::string &name) {
    pthread_mutex_lock(&g_monitor_lock);
    MonitorSlot *slot = nullptr;
    for (auto &entry : g_monitors) {
        if (entry.claimed.load(std::memory_order_acquire)) {
            if (strncmp(entry.name, name.c_str(), sizeof(entry.name) - 1) == 0) {
                slot = &entry;
                break;
            }
            continue;
        }
        slot = &entry;
        strncpy(slot->name, name.c_str(), sizeof(slot->name) - 1);
        slot->name[sizeof(slot->name) - 1] = '\0';
        slot->status.store(0);
        slot->claimed.store(true, std::memory_order_release);
        break;
    }
    pthread_mutex_unlock(&g_monitor_lock);
    return slot;
}

void *ServeMonitor(void *cookie) {
    auto *slot = static_cast<MonitorSlot *>(cookie);
    std::string path = std::string(kMonitorDir) + "/" + kMonitorPrefix + slot->name;
    unlink(path.c_str());

    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0) {
        LOGE("dex2oat injection: cannot create {}: {}", path.c_str(), strerror(errno));
        return nullptr;
    }

    struct sockaddr_un address {};
    address.sun_family = AF_UNIX;
    strncpy(address.sun_path, path.c_str(), sizeof(address.sun_path) - 1);
    if (bind(listener, reinterpret_cast<struct sockaddr *>(&address), sizeof(address)) != 0 ||
        listen(listener, 8) != 0) {
        LOGE("dex2oat injection: cannot bind {}: {}", path.c_str(), strerror(errno));
        close(listener);
        return nullptr;
    }
    chmod(path.c_str(), 0600);
    LOGI("dex2oat injection: reporting status on {}", path.c_str());

    for (;;) {
        int client = accept(listener, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR) continue;
            break;
        }
        char status = slot->status.load(std::memory_order_acquire);
        if (write(client, &status, 1) != 1) {
            LOGD("dex2oat injection: a monitor client left early");
        }
        close(client);
    }
    close(listener);
    return nullptr;
}

}

void OnModuleLoaded(void *self_handle, const ZygiskNextAPI *api) {
    g_api = api;
    if (api == nullptr) return;
    if (android_get_device_api_level() < 29) return;

    int ready = g_ready.load(std::memory_order_acquire);
    if (ready < 0 && !g_arming.exchange(true)) {
        bool armed = ArmHooks();
        g_ready.store(armed ? 1 : 0, std::memory_order_release);
        g_arming.store(false);
        ready = armed ? 1 : 0;
    }
    ReportStatus(self_handle, ready == 1);
}

void OnCompanionLoaded() {
    LOGI("dex2oat injection: companion loaded in pid {}", static_cast<int>(getpid()));
}

void OnModuleConnected(int fd) {
    struct timeval timeout {};
    timeout.tv_sec = 5;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    unsigned char header[3] {};
    if (!ReadAll(fd, header, sizeof(header)) || header[0] != kRequestMagic) {
        close(fd);
        return;
    }

    uint8_t name_length = header[2];
    std::string name(name_length, '\0');
    if (name_length > 0 && !ReadAll(fd, name.data(), name_length)) {
        close(fd);
        return;
    }

    char status = 0;
    if (!ReadAll(fd, &status, 1)) {
        close(fd);
        return;
    }
    close(fd);

    MonitorSlot *slot = ClaimMonitor(SafeName(name));
    if (slot == nullptr) return;
    slot->status.store(status == 1 ? 1 : 0, std::memory_order_release);
    if (slot->serving.exchange(true)) return;

    mkdir(kMonitorDir, 0700);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, ServeMonitor, slot) != 0) {
        slot->serving.store(false);
        LOGE("dex2oat injection: cannot start the monitor thread");
        return;
    }
    pthread_detach(thread);
}

}

extern "C" __attribute__((visibility("default"))) ZygiskNextModule zn_module = {
    ZYGISK_NEXT_API_VERSION, vector::native::dex2oat::OnModuleLoaded};

extern "C" __attribute__((visibility("default"))) ZygiskNextCompanionModule zn_companion_module = {
    ZYGISK_NEXT_API_VERSION, vector::native::dex2oat::OnCompanionLoaded,
    vector::native::dex2oat::OnModuleConnected};

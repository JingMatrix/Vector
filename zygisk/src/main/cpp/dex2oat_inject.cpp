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

constexpr const char *kHooker32 = "/data/adb/modules/zygisk_vector/bin/liboat_hook32.so";
constexpr const char *kHooker64 = "/data/adb/modules/zygisk_vector/bin/liboat_hook64.so";
constexpr const char *kWrapper32 = "/data/adb/modules/zygisk_vector/bin/dex2oat32";
constexpr const char *kWrapper64 = "/data/adb/modules/zygisk_vector/bin/dex2oat64";
constexpr const char *kMonitorDir = "/data/adb/lspd";
constexpr const char *kMonitorPrefix = "dex2oat_monitor_";
constexpr const char *kPreloadPrefix = "LD_PRELOAD=";
constexpr const char *kCmdPrefix = "DEX2OAT_CMD=";
constexpr const char *kKeepFdsPrefix = "--keep-fds=";
constexpr char kRequestMagic = 'V';

constexpr size_t kMaxArgv = 256;
constexpr size_t kMaxEnvp = 256;

using ExecveFn = int (*)(const char *, char *const[], char *const[]);
using ExecvFn = int (*)(const char *, char *const[]);
using PosixSpawnFn = int (*)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                             const posix_spawnattr_t *, char *const[], char *const[]);

const ZygiskNextAPI *g_api = nullptr;

ExecveFn g_orig_execve = nullptr;
ExecvFn g_orig_execv = nullptr;
PosixSpawnFn g_orig_posix_spawn = nullptr;
PosixSpawnFn g_orig_posix_spawnp = nullptr;

std::atomic<int> g_hook_fd32{-1};
std::atomic<int> g_hook_fd64{-1};
std::atomic<char> g_companion_status{0};
std::atomic<bool> g_monitor_started{false};
std::atomic<int> g_spawn_depth{0};
std::string g_monitor_name;

struct TransformBuffers {
    char *argv[kMaxArgv + 2];
    char *envp[kMaxEnvp + 3];
    char preload[64];
    char preload_env[96];
    char keepfds[128];
    char env_preload[192];
    char env_cmd[4300];
    char env_injected[40];
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

bool SendFds(int fd, uint8_t mask, const int *fds, int count) {
    char byte = static_cast<char>(mask);
    struct iovec iov {};
    iov.iov_base = &byte;
    iov.iov_len = 1;

    char control[CMSG_SPACE(sizeof(int) * 2)] {};
    struct msghdr msg {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    if (count > 0) {
        msg.msg_control = control;
        msg.msg_controllen = CMSG_SPACE(sizeof(int) * count);
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int) * count);
        memcpy(CMSG_DATA(cmsg), fds, sizeof(int) * count);
    }

    return sendmsg(fd, &msg, 0) == 1;
}

bool ReceiveFds(int fd, uint8_t &mask, int fds[2]) {
    char byte = 0;
    struct iovec iov {};
    iov.iov_base = &byte;
    iov.iov_len = 1;

    char control[CMSG_SPACE(sizeof(int) * 2)] {};
    struct msghdr msg {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);

    if (recvmsg(fd, &msg, 0) != 1) return false;

    mask = static_cast<uint8_t>(byte);
    int count = ((mask & 1) ? 1 : 0) + ((mask & 2) ? 1 : 0);
    if (count == 0) return true;

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (cmsg == nullptr || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) {
        return false;
    }
    memcpy(fds, CMSG_DATA(cmsg), sizeof(int) * count);
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

int HookFdFor(bool is64) {
    return (is64 ? g_hook_fd64 : g_hook_fd32).load(std::memory_order_relaxed);
}

bool WrapperMounted(const char *path, bool is64) {
    if (path == nullptr) return false;
    struct stat target {};
    struct stat wrapper {};
    if (stat(path, &target) != 0) return false;
    if (stat(is64 ? kWrapper64 : kWrapper32, &wrapper) != 0) return false;
    return target.st_dev == wrapper.st_dev && target.st_ino == wrapper.st_ino;
}

bool Build(const char *path, char *const argv_in[], char *const env_in[], int fd,
           TransformBuffers &buffers) {
    if (argv_in == nullptr) return false;

    size_t argc = 0;
    while (argv_in[argc] != nullptr) {
        if (argc >= kMaxArgv) return false;
        buffers.argv[argc] = argv_in[argc];
        argc++;
    }
    buffers.argv[argc] = nullptr;

    const char *cmd = nullptr;
    if (path != nullptr && path[0] == '/') {
        cmd = path;
    } else if (argv_in[0] != nullptr && argv_in[0][0] == '/') {
        cmd = argv_in[0];
    } else {
        cmd = path;
    }

    snprintf(buffers.preload, sizeof(buffers.preload), "/proc/self/fd/%d", fd);
    snprintf(buffers.preload_env, sizeof(buffers.preload_env), "%s%s", kPreloadPrefix,
             buffers.preload);
    snprintf(buffers.cmd, sizeof(buffers.cmd), "%s%s", kCmdPrefix, cmd != nullptr ? cmd : "");

    size_t envc = 0;
    char *const *env = env_in != nullptr ? env_in : environ;
    for (size_t i = 0; env != nullptr && env[i] != nullptr; i++) {
        if (strncmp(env[i], kPreloadPrefix, strlen(kPreloadPrefix)) == 0) continue;
        if (strncmp(env[i], kCmdPrefix, strlen(kCmdPrefix)) == 0) continue;
        if (envc >= kMaxEnvp) return false;
        buffers.envp[envc++] = env[i];
    }
    buffers.envp[envc++] = buffers.preload_env;
    buffers.envp[envc++] = buffers.cmd;
    buffers.envp[envc++] = const_cast<char *>("VECTOR_INJECTED=1");
    buffers.envp[envc] = nullptr;
    return true;
}

bool BuildArtExec(const char *path, char *const argv_in[], char *const env_in[],
                  TransformBuffers &buffers) {
    (void)path;
    size_t argc = 0;
    size_t separator = SIZE_MAX;
    size_t keep_index = SIZE_MAX;
    while (argv_in[argc] != nullptr) {
        if (argc >= kMaxArgv) return false;
        if (separator == SIZE_MAX && strcmp(argv_in[argc], "--") == 0) separator = argc;
        if (separator == SIZE_MAX &&
            strncmp(argv_in[argc], kKeepFdsPrefix, strlen(kKeepFdsPrefix)) == 0) {
            keep_index = argc;
        }
        argc++;
    }
    if (separator == SIZE_MAX || separator + 1 >= argc) return false;

    const char *target = argv_in[separator + 1];
    if (target == nullptr || strstr(target, "dex2oat") == nullptr) return false;
    bool is64 = Is64BitExecutable(target, nullptr);
    if (WrapperMounted(target, is64)) return false;
    int fd = HookFdFor(is64);
    if (fd < 0) return false;

    if (keep_index != SIZE_MAX) {
        const char *old = argv_in[keep_index] + strlen(kKeepFdsPrefix);
        if (*old == '\0') {
            snprintf(buffers.keepfds, sizeof(buffers.keepfds), "%s%d", kKeepFdsPrefix, fd);
        } else {
            snprintf(buffers.keepfds, sizeof(buffers.keepfds), "%s%s:%d", kKeepFdsPrefix, old, fd);
        }
    } else {
        snprintf(buffers.keepfds, sizeof(buffers.keepfds), "%s%d", kKeepFdsPrefix, fd);
    }

    snprintf(buffers.preload, sizeof(buffers.preload), "/proc/self/fd/%d", fd);
    snprintf(buffers.preload_env, sizeof(buffers.preload_env), "%s%s", kPreloadPrefix,
             buffers.preload);
    snprintf(buffers.cmd, sizeof(buffers.cmd), "%s%s", kCmdPrefix, target);
    snprintf(buffers.env_preload, sizeof(buffers.env_preload), "--env=%s", buffers.preload_env);
    snprintf(buffers.env_cmd, sizeof(buffers.env_cmd), "--env=%s", buffers.cmd);
    snprintf(buffers.env_injected, sizeof(buffers.env_injected), "--env=VECTOR_INJECTED=1");

    size_t out = 0;
    for (size_t i = 0; i < argc; i++) {
        if (i == separator) {
            if (keep_index == SIZE_MAX) {
                if (out + 5 >= kMaxArgv) return false;
                buffers.argv[out++] = buffers.keepfds;
            }
            if (out + 5 >= kMaxArgv) return false;
            buffers.argv[out++] = buffers.env_preload;
            buffers.argv[out++] = buffers.env_cmd;
            buffers.argv[out++] = buffers.env_injected;
        }
        if (i == keep_index) {
            buffers.argv[out++] = buffers.keepfds;
            continue;
        }
        buffers.argv[out++] = argv_in[i];
    }
    buffers.argv[out] = nullptr;

    size_t envc = 0;
    char *const *env = env_in != nullptr ? env_in : environ;
    for (size_t i = 0; env != nullptr && env[i] != nullptr; i++) {
        if (strncmp(env[i], kPreloadPrefix, strlen(kPreloadPrefix)) == 0) continue;
        if (strncmp(env[i], kCmdPrefix, strlen(kCmdPrefix)) == 0) continue;
        if (strncmp(env[i], "VECTOR_INJECTED=", 16) == 0) continue;
        if (envc >= kMaxEnvp) return false;
        buffers.envp[envc++] = env[i];
    }
    buffers.envp[envc] = nullptr;
    return true;
}

bool Transform(const char *path, char *const argv_in[], char *const env_in[],
               TransformBuffers &buffers) {
    if (IsDex2Oat(path, argv_in)) {
        bool is64 = Is64BitExecutable(path, argv_in);
        if (WrapperMounted(path, is64)) return false;
        int fd = HookFdFor(is64);
        if (fd < 0) return false;
        return Build(path, argv_in, env_in, fd, buffers);
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
    return g_orig_execve(path, buffers.argv, buffers.envp);
}

int HookExecv(const char *path, char *const argv[]) {
    if (g_orig_execv == nullptr) return -1;
    if (g_spawn_depth.load(std::memory_order_relaxed) > 0) return g_orig_execv(path, argv);
    if (!IsDex2Oat(path, argv) && !IsArtExec(path, argv)) return g_orig_execv(path, argv);
    TransformBuffers buffers;
    if (!Transform(path, argv, nullptr, buffers)) return g_orig_execv(path, argv);
    if (g_orig_execve != nullptr) return g_orig_execve(path, buffers.argv, buffers.envp);
    char **saved = environ;
    environ = buffers.envp;
    int result = g_orig_execv(path, buffers.argv);
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
    int result = g_orig_posix_spawn(pid, path, actions, attr, buffers.argv, buffers.envp);
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
    int result = g_orig_posix_spawnp(pid, path, actions, attr, buffers.argv, buffers.envp);
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

bool AcquireHookFds(void *self_handle, int &fd32, int &fd64) {
    if (g_api == nullptr || g_api->connectCompanion == nullptr) return false;

    int socket = g_api->connectCompanion(self_handle);
    if (socket < 0) return false;

    struct timeval timeout {};
    timeout.tv_sec = 2;
    setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    std::string name = SafeName(ProcessName());
    unsigned char header[3] = {static_cast<unsigned char>(kRequestMagic),
                               static_cast<unsigned char>(sizeof(void *) == 8 ? 1 : 0),
                               static_cast<unsigned char>(name.size())};
    if (!WriteAll(socket, header, sizeof(header)) ||
        !WriteAll(socket, name.data(), name.size())) {
        close(socket);
        return false;
    }

    uint8_t mask = 0;
    int fds[2] = {-1, -1};
    if (!ReceiveFds(socket, mask, fds)) {
        close(socket);
        return false;
    }

    int index = 0;
    fd32 = (mask & 1) ? fds[index++] : -1;
    fd64 = (mask & 2) ? fds[index++] : -1;
    if (fd32 >= 0) fcntl(fd32, F_SETFD, 0);
    if (fd64 >= 0) fcntl(fd64, F_SETFD, 0);

    bool usable = fd32 >= 0 || fd64 >= 0;
    if (usable) usable = InstallHooks();
    char status = usable ? 1 : 0;
    WriteAll(socket, &status, 1);
    close(socket);

    if (!usable) {
        if (fd32 >= 0) close(fd32);
        if (fd64 >= 0) close(fd64);
        fd32 = -1;
        fd64 = -1;
    }
    return usable;
}

void *ServeMonitor(void *) {
    std::string path = std::string(kMonitorDir) + "/" + kMonitorPrefix + g_monitor_name;
    unlink(path.c_str());

    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0) {
        LOGE("dex2oat injection: cannot create monitor socket: {}", strerror(errno));
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
        char status = g_companion_status.load();
        if (write(client, &status, 1) != 1) {
            LOGD("dex2oat injection: a monitor client left early");
        }
        close(client);
    }
    close(listener);
    return nullptr;
}

void StartMonitor(const std::string &name, char status) {
    g_companion_status.store(status);
    if (g_monitor_started.exchange(true)) return;
    g_monitor_name = SafeName(name);
    mkdir(kMonitorDir, 0700);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, ServeMonitor, nullptr) != 0) {
        LOGE("dex2oat injection: cannot start the monitor thread");
        return;
    }
    pthread_detach(thread);
}

}

void OnModuleLoaded(void *self_handle, const ZygiskNextAPI *api) {
    g_api = api;
    if (api == nullptr) return;
    if (android_get_device_api_level() < 29) return;

    int fd32 = -1;
    int fd64 = -1;
    if (!AcquireHookFds(self_handle, fd32, fd64)) {
        LOGW("dex2oat injection: no usable hook library; leaving dex2oat alone");
        return;
    }

    g_hook_fd32.store(fd32, std::memory_order_relaxed);
    g_hook_fd64.store(fd64, std::memory_order_relaxed);
    LOGI("dex2oat injection: armed in {}", ProcessName().c_str());
}

void OnCompanionLoaded() {
    LOGI("dex2oat injection: companion loaded in pid {}", static_cast<int>(getpid()));
}

void OnModuleConnected(int fd) {
    struct timeval timeout {};
    timeout.tv_sec = 2;
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

    int fds[2] = {-1, -1};
    uint8_t mask = 0;
    int count = 0;

    int fd32 = open(kHooker32, O_RDONLY | O_CLOEXEC);
    if (fd32 >= 0) {
        mask |= 1;
        fds[count++] = fd32;
    }
    int fd64 = open(kHooker64, O_RDONLY | O_CLOEXEC);
    if (fd64 >= 0) {
        mask |= 2;
        fds[count++] = fd64;
    }

    bool sent = SendFds(fd, mask, fds, count);
    if (fd32 >= 0) close(fd32);
    if (fd64 >= 0) close(fd64);
    if (!sent) {
        close(fd);
        return;
    }

    char status = 0;
    ReadAll(fd, &status, 1);
    char effective = (status == 1 && count > 0) ? 1 : 0;
    StartMonitor(name, effective);
    close(fd);
}

}

extern "C" __attribute__((visibility("default"))) ZygiskNextModule zn_module = {
    ZYGISK_NEXT_API_VERSION, vector::native::dex2oat::OnModuleLoaded};

extern "C" __attribute__((visibility("default"))) ZygiskNextCompanionModule zn_companion_module = {
    ZYGISK_NEXT_API_VERSION, vector::native::dex2oat::OnCompanionLoaded,
    vector::native::dex2oat::OnModuleConnected};

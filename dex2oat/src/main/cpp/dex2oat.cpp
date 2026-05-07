#include <linux/memfd.h>
#include <sys/sendfile.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "logging.h"

// Access to the process environment variables
extern "C" char **environ;

#if defined(__LP64__)
#define LP_SELECT(lp32, lp64) lp64
#else
#define LP_SELECT(lp32, lp64) lp32
#endif

namespace {

constexpr char kSockName[] = "5291374ceda0aef7c5d86cd2a4f6a3ac";

/**
 * Calculates a vector ID based on architecture and debug status.
 */
inline int get_id_vec(bool is64, bool is_debug) {
    return (static_cast<int>(is64) << 1) | static_cast<int>(is_debug);
}

/**
 * Wraps recvmsg with error logging.
 */
ssize_t xrecvmsg(int sockfd, struct msghdr *msg, int flags) {
    ssize_t rec = recvmsg(sockfd, msg, flags);
    if (rec < 0) {
        PLOGE("recvmsg");
    }
    return rec;
}

/**
 * Receives file descriptors passed over a Unix domain socket using SCM_RIGHTS.
 *
 * @return Pointer to the FD data on success, nullptr on failure.
 */
void *recv_fds(int sockfd, char *cmsgbuf, size_t bufsz, int cnt) {
    struct iovec iov = {
        .iov_base = &cnt,
        .iov_len = sizeof(cnt),
    };
    struct msghdr msg = {.msg_name = nullptr,
                         .msg_namelen = 0,
                         .msg_iov = &iov,
                         .msg_iovlen = 1,
                         .msg_control = cmsgbuf,
                         .msg_controllen = bufsz,
                         .msg_flags = 0};

    if (xrecvmsg(sockfd, &msg, MSG_WAITALL) < 0) return nullptr;

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);

    if (msg.msg_controllen != bufsz || cmsg == nullptr ||
        cmsg->cmsg_len != CMSG_LEN(sizeof(int) * cnt) || cmsg->cmsg_level != SOL_SOCKET ||
        cmsg->cmsg_type != SCM_RIGHTS) {
        return nullptr;
    }

    return CMSG_DATA(cmsg);
}

/**
 * Helper to receive a single FD from the socket.
 */
int recv_fd(int sockfd) {
    char cmsgbuf[CMSG_SPACE(sizeof(int))];
    void *data = recv_fds(sockfd, cmsgbuf, sizeof(cmsgbuf), 1);
    if (data == nullptr) return -1;

    int result;
    std::memcpy(&result, data, sizeof(int));
    return result;
}

/**
 * Reads an integer acknowledgment from the socket.
 */
int read_int(int fd) {
    int val;
    if (read(fd, &val, sizeof(val)) != sizeof(val)) return -1;
    return val;
}

/**
 * Writes an integer command/ID to the socket.
 */
void write_int(int fd, int val) {
    if (fd < 0) return;
    (void)write(fd, &val, sizeof(val));
}

}  // namespace

int main(int argc, char **argv) {
    LOGD("dex2oat wrapper ppid=%d", getppid());

    // Prepare Unix domain socket address (Abstract Namespace)
    struct sockaddr_un sock = {};
    sock.sun_family = AF_UNIX;
    // sock.sun_path[0] is already \0, so we copy name into sun_path + 1
    std::strncpy(sock.sun_path + 1, kSockName, sizeof(sock.sun_path) - 2);

    // Abstract socket length: family + leading \0 + string length
    socklen_t len = sizeof(sock.sun_family) + strlen(kSockName) + 1;

    // 1. Get original dex2oat binary FD
    int sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (connect(sock_fd, reinterpret_cast<struct sockaddr *>(&sock), len)) {
        PLOGE("failed to connect to %s", sock.sun_path + 1);
        return 1;
    }

    bool is_debug = (argv[0] != nullptr && std::strstr(argv[0], "dex2oatd") != nullptr);
    write_int(sock_fd, get_id_vec(LP_SELECT(false, true), is_debug));

    int stock_fd = recv_fd(sock_fd);
    read_int(sock_fd);  // Sync
    close(sock_fd);

    // 2. Get liboat_hook.so FD
    sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (connect(sock_fd, reinterpret_cast<struct sockaddr *>(&sock), len)) {
        PLOGE("failed to connect to %s", sock.sun_path + 1);
        return 1;
    }

    write_int(sock_fd, LP_SELECT(4, 5));
    int hooker_fd = recv_fd(sock_fd);
    read_int(sock_fd);  // Sync
    close(sock_fd);

    if (hooker_fd == -1) {
        LOGE("failed to read liboat_hook.so");
    } else {
        int mem_fd = syscall(__NR_memfd_create, "liboat_hook_memfd", 0);
        if (mem_fd >= 0) {
            // Get the exact size of the original library
            LOGD("Copying %d as mem_fd %d", hooker_fd, mem_fd);
            struct stat st;
            if (fstat(hooker_fd, &st) == 0) {
                // Tell the kernel to copy the entire file directly to the memfd
                off_t offset = 0;
                sendfile(mem_fd, hooker_fd, &offset, st.st_size);

                // Swap the old FD with the new memfd
                close(hooker_fd);
                hooker_fd = mem_fd;
            } else {
                PLOGE("fstat failed");
                close(mem_fd);
            }
        } else {
            PLOGE("memfd_create failed, falling back to original fd");
        }
    }

    LOGD("sock: %s stock_fd: %d", sock.sun_path + 1, stock_fd);

    // Prepare arguments for execve
    // Logic: [linker] [/proc/self/fd/stock_fd] [original_args...] [--inline-max-code-units=0]
    std::vector<const char *> exec_argv;

    const char *linker_path =
        LP_SELECT("/apex/com.android.runtime/bin/linker", "/apex/com.android.runtime/bin/linker64");

    char stock_fd_path[64];
    std::snprintf(stock_fd_path, sizeof(stock_fd_path), "/proc/self/fd/%d", stock_fd);

    exec_argv.push_back(linker_path);
    exec_argv.push_back(stock_fd_path);

    // Append original arguments starting from argv[1]
    for (int i = 1; i < argc; ++i) {
        exec_argv.push_back(argv[i]);
    }

    // Append hooking flags to disable inline, which is our purpose of this wrapper, since we cannot
    // hook inlined target methods.
    exec_argv.push_back("--inline-max-code-units=0");
    exec_argv.push_back(nullptr);

    // Setup Environment variables
    // Clear LD_LIBRARY_PATH to let the linker use internal config
    unsetenv("LD_LIBRARY_PATH");

    // Set LD_PRELOAD to point to the hooker library FD
    std::string preload_val = "LD_PRELOAD=/proc/self/fd/" + std::to_string(hooker_fd);
    LOGD("Inject oat hook via %s", preload_val.data());
    setenv("LD_PRELOAD", ("/proc/self/fd/" + std::to_string(hooker_fd)).c_str(), 1);

    // Pass original argv[0] as DEX2OAT_CMD
    if (argv[0]) {
        setenv("DEX2OAT_CMD", argv[0], 1);
        LOGD("DEX2OAT_CMD set to %s", argv[0]);
    }

    LOGI("Executing via linker: %s executing %s", linker_path, stock_fd_path);

    // Perform the execution
    execve(linker_path, const_cast<char *const *>(exec_argv.data()), environ);

    // If we reach here, execve failed
    PLOGE("execve failed");
    return 2;
}

#include <fcntl.h>
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

#include "logging.h"

extern "C" char **environ;

#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif

#if defined(__LP64__)
#define LP_SELECT(lp32, lp64) lp64
#else
#define LP_SELECT(lp32, lp64) lp32
#endif

namespace {

constexpr char kSockName[] = "5291374ceda0aef7c5d86cd2a4f6a3ac";

int get_id_vec(bool is64, bool is_debug) {
    return (static_cast<int>(is64) << 1) | static_cast<int>(is_debug);
}

ssize_t xrecvmsg(int sockfd, struct msghdr *msg) {
    ssize_t rec = recvmsg(sockfd, msg, MSG_WAITALL);
    if (rec < 0) PLOGE("recvmsg");
    return rec;
}

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

    if (xrecvmsg(sockfd, &msg) < 0) return nullptr;

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (msg.msg_controllen != bufsz || cmsg == nullptr ||
        cmsg->cmsg_len != CMSG_LEN(sizeof(int) * cnt) || cmsg->cmsg_level != SOL_SOCKET ||
        cmsg->cmsg_type != SCM_RIGHTS) {
        return nullptr;
    }
    return CMSG_DATA(cmsg);
}

int recv_fd(int sockfd) {
    char cmsgbuf[CMSG_SPACE(sizeof(int))];
    void *data = recv_fds(sockfd, cmsgbuf, sizeof(cmsgbuf), 1);
    if (data == nullptr) return -1;
    int result;
    std::memcpy(&result, data, sizeof(result));
    return result;
}

int read_int(int fd) {
    int val;
    if (read(fd, &val, sizeof(val)) != sizeof(val)) return -1;
    return val;
}

void write_int(int fd, int val) {
    if (fd < 0) return;
    (void)write(fd, &val, sizeof(val));
}

int connect_daemon(const struct sockaddr_un &sock, socklen_t len) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        PLOGE("socket");
        return -1;
    }
    if (connect(fd, reinterpret_cast<const struct sockaddr *>(&sock), len) != 0) {
        PLOGE("connect");
        close(fd);
        return -1;
    }
    return fd;
}

int copy_to_memfd(int fd) {
    int mem_fd = static_cast<int>(syscall(__NR_memfd_create, "liboat_hook_memfd", 0));
    if (mem_fd < 0) {
        PLOGE("memfd_create");
        return fd;
    }

    struct stat st {};
    if (fstat(fd, &st) != 0) {
        PLOGE("fstat");
        close(mem_fd);
        return fd;
    }

    off_t offset = 0;
    if (sendfile(mem_fd, fd, &offset, st.st_size) != st.st_size) {
        PLOGE("sendfile");
        close(mem_fd);
        return fd;
    }
    close(fd);
    return mem_fd;
}

int fallback(const char *cmd, char **argv) {
    if (cmd == nullptr || cmd[0] != '/') return 1;
    argv[0] = const_cast<char *>(cmd);
    unsetenv("DEX2OAT_CMD");
    execve(cmd, argv, environ);
    PLOGE("execve %s", cmd);
    return 1;
}

}  // namespace

int main(int argc, char **argv) {
    (void)argc;
    LOGD("dex2oat wrapper ppid=%d", getppid());

    struct sockaddr_un sock = {};
    sock.sun_family = AF_UNIX;
    std::strncpy(sock.sun_path + 1, kSockName, sizeof(sock.sun_path) - 2);
    socklen_t len = sizeof(sock.sun_family) + std::strlen(kSockName) + 1;

    char cmd_buf[256] {};
    const char *cmd = getenv("DEX2OAT_CMD");
    if (cmd != nullptr) {
        std::snprintf(cmd_buf, sizeof(cmd_buf), "%s", cmd);
        cmd = cmd_buf;
    }
    const char *name = cmd != nullptr ? cmd : argv[0];
    bool is_debug = name != nullptr && std::strstr(name, "dex2oatd") != nullptr;

    int sock_fd = connect_daemon(sock, len);
    if (sock_fd < 0) return fallback(cmd, argv);

    write_int(sock_fd, get_id_vec(LP_SELECT(false, true), is_debug));
    int stock_fd = recv_fd(sock_fd);
    read_int(sock_fd);
    close(sock_fd);
    if (stock_fd < 0) return fallback(cmd, argv);

    sock_fd = connect_daemon(sock, len);
    if (sock_fd < 0) return fallback(cmd, argv);

    write_int(sock_fd, LP_SELECT(4, 5));
    int hooker_fd = recv_fd(sock_fd);
    read_int(sock_fd);
    close(sock_fd);
    if (hooker_fd < 0) return fallback(cmd, argv);

    hooker_fd = copy_to_memfd(hooker_fd);

    char preload[64];
    std::snprintf(preload, sizeof(preload), "/proc/self/fd/%d", hooker_fd);
    setenv("LD_PRELOAD", preload, 1);
    unsetenv("LD_LIBRARY_PATH");

    if (cmd != nullptr) {
        argv[0] = const_cast<char *>(cmd);
        unsetenv("DEX2OAT_CMD");
    }

    LOGD("executing %s with %s", argv[0], preload);
    syscall(__NR_execveat, stock_fd, "", argv, environ, AT_EMPTY_PATH);
    PLOGE("execveat failed");
    return fallback(cmd, argv);
}

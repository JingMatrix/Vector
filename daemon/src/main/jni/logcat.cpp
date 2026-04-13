#include "logcat.h"

#include <android/log.h>
#include <jni.h>
#include <sys/system_properties.h>
#include <sys/uio.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <string_view>
#include <thread>

using namespace std::string_view_literals;
using namespace std::chrono_literals;

// Log rotation thresholds
constexpr size_t kMaxLogSize = 4 * 1024 * 1024;  // 4MB per part
constexpr long kLogBufferSize = 128 * 1024;      // Internal logd buffer size (128KB)

namespace {
// Standard Logcat priority characters
constexpr std::array<char, ANDROID_LOG_SILENT + 1> kLogChar = {
    /*ANDROID_LOG_UNKNOWN*/ '?',
    /*ANDROID_LOG_DEFAULT*/ '?',
    /*ANDROID_LOG_VERBOSE*/ 'V',
    /*ANDROID_LOG_DEBUG*/ 'D',
    /*ANDROID_LOG_INFO*/ 'I',
    /*ANDROID_LOG_WARN*/ 'W',
    /*ANDROID_LOG_ERROR*/ 'E',
    /*ANDROID_LOG_FATAL*/ 'F',
    /*ANDROID_LOG_SILENT*/ 'S',
};

// Module tags are sorted for O(log N) binary search.
// These always route to the 'modules' (Xposed) log stream.
constexpr auto kModuleTags = std::array{"VectorContext"sv, "VectorLegacyBridge"sv,
                                        "VectorModuleManager"sv, "XSharedPreferences"sv};

// These route to the 'verbose' stream only.
constexpr auto kExactTags = std::array{"APatchD"sv, "Dobby"sv,  "KernelSU"sv, "LSPlant"sv,
                                       "LSPlt"sv,   "Magisk"sv, "SELinux"sv,  "TEESimulator"sv};

// Partial matches for dynamic components like Zygisk modules or Vector/LSPosed components.
constexpr auto kPrefixTags = std::array{"LSPosed"sv, "Vector"sv, "dex2oat"sv, "zygisk"sv};

// RAII Wrapper for File Descriptors to ensure files are closed during JNI rotation.
struct UniqueFd {
    int fd = -1;
    ~UniqueFd() {
        if (fd >= 0) close(fd);
    }
    void reset(int n) {
        if (fd >= 0) close(fd);
        fd = n;
    }
    operator int() const { return fd; }
};
}  // namespace

class Logcat {
public:
    Logcat(JNIEnv* env, jobject thiz, jmethodID method)
        : env_(env), thiz_(thiz), refresh_fd_method_(method) {}

    [[noreturn]] void Run();

private:
    void RefreshFd(bool is_verbose);
    size_t FastWrite(const AndroidLogEntry& entry, int fd);
    void LogRaw(std::string_view str);
    void OnCrash(int err);
    void ProcessBuffer(struct log_msg* buf);

    JNIEnv* env_;
    jobject thiz_;
    jmethodID refresh_fd_method_;

    UniqueFd modules_fd_{};
    size_t modules_written_ = 0;
    size_t modules_part_ = 0;

    UniqueFd verbose_fd_{};
    size_t verbose_written_ = 0;
    size_t verbose_part_ = 0;

    pid_t my_pid_ = getpid();
    bool verbose_enabled_ = true;
};

// 'Scatter-Gather' I/O (writev)
size_t Logcat::FastWrite(const AndroidLogEntry& entry, int fd) {
    if (fd < 0) return 0;

    char time_buf[32], meta_buf[96];
    struct tm tm_info;
    time_t sec = entry.tv_sec;
    localtime_r(&sec, &tm_info);

    // Format fixed-width metadata
    size_t t_len = strftime(time_buf, sizeof(time_buf), "%Y-%m-%dT%H:%M:%S", &tm_info);
    int m_len = snprintf(meta_buf, sizeof(meta_buf), ".%03ld %8d:%6d:%6d %c/%-15.*s ] ",
                         entry.tv_nsec / 1000000, entry.uid, entry.pid, entry.tid,
                         kLogChar[entry.priority], (int)entry.tagLen, entry.tag);

    // Ensure line has a trailing newline
    bool add_nl = (entry.messageLen == 0 || entry.message[entry.messageLen - 1] != '\n');
    struct iovec iov[5] = {{(void*)"[ ", 2},
                           {time_buf, t_len},
                           {meta_buf, (size_t)m_len},
                           {(void*)entry.message, entry.messageLen},
                           {(void*)"\n", add_nl ? 1U : 0U}};

    ssize_t n = writev(fd, iov, 5);
    // If write fails, return kMaxLogSize to force a rotation attempt.
    return (n <= 0) ? kMaxLogSize : static_cast<size_t>(n);
}

void Logcat::LogRaw(std::string_view str) {
    if (verbose_enabled_ && verbose_fd_ >= 0) write(verbose_fd_, str.data(), str.size());
    if (modules_fd_ >= 0) write(modules_fd_, str.data(), str.size());
}

// RefreshFd: Handshakes with the Kotlin layer to swap file descriptors.
void Logcat::RefreshFd(bool is_verbose) {
    char buf[64];
    auto& fd_obj = is_verbose ? verbose_fd_ : modules_fd_;
    auto& part = is_verbose ? verbose_part_ : modules_part_;

    if (fd_obj >= 0) {
        int len = snprintf(buf, sizeof(buf), "-----part %zu end----\n", part);
        write(fd_obj, buf, len);
    }

    // Call Kotlin refreshFd(boolean) to get a new detatched FD
    int new_fd = env_->CallIntMethod(thiz_, refresh_fd_method_, is_verbose ? JNI_TRUE : JNI_FALSE);
    fd_obj.reset(new_fd);  // UniqueFd handles closing the old FD
    part++;
    if (is_verbose)
        verbose_written_ = 0;
    else
        modules_written_ = 0;

    if (fd_obj >= 0) {
        int len = snprintf(buf, sizeof(buf), "----part %zu start----\n", part);
        write(fd_obj, buf, len);
    }
}

// OnCrash: Handles logd daemon resets.
void Logcat::OnCrash(int err) {
    static size_t crash_count = 0;
    static size_t restart_wait = 8;
    if (++crash_count >= restart_wait) {
        LogRaw("\nLogd crashed too many times, trying manually start...\n");
        __system_property_set("ctl.restart", "logd");
        if (restart_wait < 1024)
            restart_wait <<= 1;
        else
            crash_count = 0;
    } else {
        std::string err_msg =
            "\nLogd maybe crashed (err=" + std::string(strerror(err)) + "), retrying in 1s...\n";
        LogRaw(err_msg);
    }
    std::this_thread::sleep_for(1s);
}

void Logcat::ProcessBuffer(struct log_msg* buf) {
    AndroidLogEntry entry;
    if (android_log_processLogBuffer(&buf->entry, &entry) < 0) return;

    // Zero-copy tag extraction (excluding null terminator)
    std::string_view tag(entry.tag, entry.tagLen > 0 ? entry.tagLen - 1 : 0);

    // Check if tag is in the Module list
    bool is_module = std::binary_search(kModuleTags.begin(), kModuleTags.end(), tag);
    if (is_module) {
        modules_written_ += FastWrite(entry, modules_fd_);
    }

    // Filtering logic for Verbose stream
    bool match_exact = std::binary_search(kExactTags.begin(), kExactTags.end(), tag);
    bool match_prefix = std::any_of(kPrefixTags.begin(), kPrefixTags.end(),
                                    [&](auto p) { return tag.starts_with(p); });
    if (verbose_enabled_ && (entry.pid == my_pid_ || is_module || buf->id() == LOG_ID_CRASH ||
                             match_exact || match_prefix)) {
        verbose_written_ += FastWrite(entry, verbose_fd_);
    }

    // Feedback Loop: The daemon listens to its own Logcat output for remote commands.
    if (entry.pid == my_pid_ && tag == "VectorLogcat"sv) {
        std::string_view msg(entry.message, entry.messageLen);
        if (msg == "!!start_verbose!!"sv) {
            verbose_enabled_ = true;
            verbose_written_ += FastWrite(entry, verbose_fd_);
        } else if (msg == "!!stop_verbose!!"sv) {
            verbose_enabled_ = false;
        } else if (msg == "!!refresh_modules!!"sv) {
            RefreshFd(false);
        } else if (msg == "!!refresh_verbose!!"sv) {
            RefreshFd(true);
        }
    }
}

void Logcat::Run() {
    size_t tail = 0;  // Start with no history
    RefreshFd(true);
    RefreshFd(false);

    while (true) {
        // tail=10 on reconnect ensures that if the logger crashes, we resume with the last 10 lines
        // of context to help debug the crash itself.
        auto* list = android_logger_list_alloc(0, tail, 0);
        tail = 10;

        for (log_id_t id : {LOG_ID_MAIN, LOG_ID_CRASH}) {
            auto* logger = android_logger_open(list, id);
            if (logger && android_logger_get_log_size(logger) < kLogBufferSize) {
                android_logger_set_log_size(logger, kLogBufferSize);
            }
        }

        struct log_msg msg;
        // This blocks while waiting for new logs from the logd Unix Socket
        while (android_logger_list_read(list, &msg) > 0) {
            ProcessBuffer(&msg);

            // Check for rotation triggers
            if (modules_written_ >= kMaxLogSize) [[unlikely]]
                RefreshFd(false);
            if (verbose_written_ >= kMaxLogSize) [[unlikely]]
                RefreshFd(true);
        }

        android_logger_list_free(list);
        OnCrash(errno);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_matrix_vector_daemon_env_LogcatMonitor_runLogcat(JNIEnv* env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jmethodID method = env->GetMethodID(clazz, "refreshFd", "(Z)I");
    Logcat daemon(env, thiz, method);
    daemon.Run();
}

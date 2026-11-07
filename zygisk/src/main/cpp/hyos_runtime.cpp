#include <dirent.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "common/logging.h"
#include "core/native_api.h"

#include "zygisk_next_api.h"

/**
 * @file hyos_runtime.cpp
 * @brief Native hooking support for applications running on the HyperOS Rust Runtime.
 *
 * A process spawned by /system_ext/bin/hyos_spawner is not an ART process. It has no JVM, no
 * JNIEnv, and no binder of its own, so none of the machinery the rest of Vector is built on is
 * available inside it: the framework DEX cannot be loaded, and the daemon cannot be asked what is
 * in scope. What such a process does have is the dynamic loader, and therefore native libraries.
 *
 * This translation unit is the whole of Vector's presence there. It is compiled into the same
 * libzygisk.so the Zygisk loader injects everywhere else, and it is reached through Zygisk Next's
 * Runtime API, which the module declares itself for in zn_modules.txt:
 *
 *   path=/system_ext/bin/hyos_spawner companion zygisk/arm64-v8a.so
 *
 * The flow, in order:
 *
 *   1. Zygisk Next injects this library into hyos_spawner and calls `zn_module`'s onModuleLoaded.
 *      getRuntime() reports ZN_RUNTIME_HYOS, and we register `zn_companion_module`'s callbacks.
 *   2. The spawner forks an application process, applies its uid, gid, groups and SELinux context,
 *      and calls onAppSpecialized in the child.
 *   3. The child asks the companion -- a root process Zygisk Next forked for us, reached over a
 *      socket the spawner connected and every child inherited -- for the path the Vector daemon
 *      publishes its per-package state under.
 *   4. The child reads the index for its own package, which names the native libraries of every
 *      Xposed module in scope for it, and loads each one. Each library's `native_init` is then
 *      called with the same NativeAPIEntries an ART process gets, so a module's native part needs
 *      no change to work here: it hooks the HyperOS process with the primitives it was already
 *      written against.
 *
 * The hook primitives come from Zygisk Next rather than from Vector's own Dobby, for the reason
 * SetHookBackend documents: two engines patching the same address destroy each other's trampolines,
 * and the engine that already owns this process has the better claim.
 */

namespace vector::native::hyos {

namespace {

// --- The published state, and how it reaches this process --------------------------------------

/**
 * Where the daemon writes what each HyperOS Runtime process should load.
 *
 * Inside Vector's own tree rather than in the shared /data/misc, because nobody but the companion
 * reads it: the answer is looked up by the companion and handed to the spawner over the connection
 * the two already share, long before any fork. Nothing here has to be reachable by the application
 * the index describes, which is why there is no pointer file any more and no path published
 * anywhere -- the channel is the companion connection, not the filesystem.
 *
 * Magisk holds /data/adb at mode 0700 owned by root, so this subtree is root-only by construction.
 */
constexpr auto kIndexDir = "/data/adb/lspd/hyos";
constexpr std::string_view kGenerationName = ".generation";
constexpr std::string_view kIndexMagic = "vector-hyos 1";

/// The two things the companion can be asked for.
constexpr char kRequestGeneration = 'G';
constexpr char kRequestTable = 'T';

/// Refuse a table larger than this rather than believe a length field.
constexpr uint32_t kMaxTableLength = 8u << 20;

/**
 * The one socket this side of the runtime is reached through.
 *
 * The Vector daemon connects and reads a single byte: `1` means the Zygisk Next Runtime API was
 * registered and applications will be specialized, anything else means the runtime did not want us.
 *
 * The path and the leading byte are LSPosed 2.2.0's, recovered from its released daemon -- its
 * `ILSPManagerService` transaction 67 connects here and reads exactly that byte, and its manager
 * turns the answer into the notice the user sees.
 *
 * Only root can reach it, and that is a property of the tree rather than of this file: Magisk holds
 * /data/adb at mode 0700 owned by root and labels it adb_data_file, so no application process can
 * traverse it whatever mode the socket is given -- LSPosed's own copy is chmod 0600 on top of that.
 * The daemon is therefore the only caller this socket will ever have, and the status byte is the
 * whole of what it is asked. A process the runtime spawned cannot come here for its libraries and
 * does not try: those arrive over the connection the spawner holds to the companion, the one
 * channel a forked child already has.
 */
constexpr auto kMonitorPath = "/data/adb/lspd/hyos_monitor";

/// How long the companion waits for the spawner's status byte before assuming the worst.
constexpr time_t kStatusByteTimeoutSeconds = 2;

/// How long either side waits on the companion connection.
constexpr time_t kCompanionTimeoutSeconds = 2;

// --- The Zygisk Next view of this process -----------------------------------------------------

const ZygiskNextAPI *g_api = nullptr;

// The companion connection, established by the spawner before any fork. -1 when the companion could
// not be started, which costs everything: the status byte, and the table itself.
int g_companion_fd = -1;

// Whether the HyperOS Runtime API was actually registered here. Zygisk Next's Runtime API is an
// optional feature of that loader: an older build does not offer it, and one that does can still
// refuse the registration. None of that is this process's problem, so every such path turns the
// feature off rather than failing -- an ordinary Zygisk process and a hyos_spawner running without
// us are both perfectly good outcomes.
bool g_registered = false;

// What the monitor hands out: 1 once the Runtime API is registered, 0 until then and forever if it
// never is. Written by the companion when the spawner reports, read by every monitor client -- two
// threads of one process, which is why it is atomic rather than plain.
std::atomic<char> g_injection_status{0};

/// One module native library to load into this process.
struct LibraryEntry {
    std::string module_package;
    std::string library_name;
    std::string library_path;
};

// What the spawner fetched, and which generation of the daemon's index it came from. Built before
// the fork, read after it: a process the runtime spawns looks its own name up in this and does no
// I/O at all, which is the point -- the callback runs in a child of a multithreaded parent, where
// reaching for a file or a socket means reaching for a lock some other thread may have held.
std::map<std::string, std::vector<LibraryEntry>> g_index;
uint64_t g_generation = 0;
bool g_index_loaded = false;

// Whether this process has already done its work. A child is forked once and specializes once, but
// a runtime is free to call the callback again, and loading a module's libraries twice would run
// its native_init twice.
bool g_specialized = false;

// --- Small I/O helpers -------------------------------------------------------------------------

/// Reads a whole file, up to a sane bound. False when it cannot be read at all.
bool ReadFile(const std::string &path, std::string &out) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    out.clear();
    char buf[512];
    for (;;) {
        const ssize_t n = read(fd, buf, sizeof(buf));
        if (n < 0) {
            close(fd);
            out.clear();
            return false;
        }
        if (n == 0) break;
        out.append(buf, static_cast<size_t>(n));
        if (out.size() > (1u << 20)) {
            close(fd);
            out.clear();
            return false;
        }
    }
    close(fd);
    return true;
}

/// Writes every byte, or reports that it could not.
bool WriteAll(int fd, const std::string &data) {
    size_t written = 0;
    while (written < data.size()) {
        const ssize_t n = write(fd, data.data() + written, data.size() - written);
        if (n <= 0) return false;
        written += static_cast<size_t>(n);
    }
    return true;
}

/// Reads exactly `length` bytes, or reports that it could not.
bool ReadAll(int fd, void *data, size_t length) {
    auto *out = static_cast<char *>(data);
    size_t read_so_far = 0;
    while (read_so_far < length) {
        const ssize_t n = read(fd, out + read_so_far, length - read_so_far);
        if (n <= 0) return false;
        read_so_far += static_cast<size_t>(n);
    }
    return true;
}

void AppendU32(std::string &out, uint32_t value) {
    out.append(reinterpret_cast<const char *>(&value), sizeof(value));
}

bool TakeU32(const std::string &in, size_t &pos, uint32_t &value) {
    if (pos + sizeof(value) > in.size()) return false;
    memcpy(&value, in.data() + pos, sizeof(value));
    pos += sizeof(value);
    return true;
}

/// Splits one index line into its three tab-separated fields.
bool ParseIndexLine(const std::string &line, LibraryEntry &out) {
    const auto first = line.find('\t');
    if (first == std::string::npos) return false;
    const auto second = line.find('\t', first + 1);
    if (second == std::string::npos) return false;

    out.module_package = line.substr(0, first);
    out.library_name = line.substr(first + 1, second - first - 1);
    out.library_path = line.substr(second + 1);
    return !out.library_name.empty() && !out.library_path.empty();
}

/// Turns one published index into the libraries it names. The first line is the format's marker.
std::vector<LibraryEntry> ParseIndex(const std::string &content) {
    std::vector<LibraryEntry> entries;
    bool first = true;
    for (size_t start = 0; start < content.size();) {
        const auto end = content.find('\n', start);
        const std::string line =
            content.substr(start, end == std::string::npos ? std::string::npos : end - start);
        start = end == std::string::npos ? content.size() : end + 1;

        if (first) {
            first = false;
            if (line == kIndexMagic) continue;
            LOGE("VectorHyperRuntime: an index does not start with its marker; ignoring it.");
            return {};
        }
        if (line.empty() || line[0] == '#') continue;

        LibraryEntry entry;
        if (!ParseIndexLine(line, entry)) {
            LOGW("VectorHyperRuntime: skipping a malformed index line.");
            continue;
        }
        entries.push_back(std::move(entry));
    }
    return entries;
}

// --- The companion's half ----------------------------------------------------------------------

/// The daemon's generation stamp, which moves whenever the published set changes.
uint64_t ReadGeneration() {
    std::string content;
    const std::string path = std::string(kIndexDir) + "/" + std::string(kGenerationName);
    if (!ReadFile(path, content)) return 0;
    return strtoull(content.c_str(), nullptr, 10);
}

/**
 * @brief Packs every published index into one blob.
 *
 * Sent as a whole rather than answered per name, because the spawner does not know which name it
 * will be asked about: the runtime picks the package after the fork, so the only safe moment to
 * learn the answer is before it. One transfer per generation, into memory the fork then shares.
 */
std::string BuildTable() {
    std::vector<std::pair<std::string, std::string>> records;

    DIR *dir = opendir(kIndexDir);
    if (dir != nullptr) {
        while (struct dirent *entry = readdir(dir)) {
            if (entry->d_name[0] == '.') continue;
            std::string content;
            const std::string path = std::string(kIndexDir) + "/" + entry->d_name;
            if (ReadFile(path, content)) records.emplace_back(entry->d_name, std::move(content));
        }
        closedir(dir);
    }

    std::string payload;
    AppendU32(payload, static_cast<uint32_t>(records.size()));
    for (const auto &record : records) {
        AppendU32(payload, static_cast<uint32_t>(record.first.size()));
        payload += record.first;
        AppendU32(payload, static_cast<uint32_t>(record.second.size()));
        payload += record.second;
    }
    return payload;
}

/**
 * @brief Serves the spawner: a generation to compare against, or the table itself.
 *
 * One client only -- the spawner -- so the exchange needs no correlation and no care about replies
 * arriving out of order. The companion is also the only reader of the index, and it runs as root.
 */
void *ServeCompanion(void *arg) {
    const int fd = static_cast<int>(reinterpret_cast<intptr_t>(arg));
    for (;;) {
        char request = 0;
        if (read(fd, &request, 1) != 1) break;

        if (request == kRequestGeneration) {
            const uint64_t generation = ReadGeneration();
            if (!WriteAll(fd, std::string(reinterpret_cast<const char *>(&generation),
                                          sizeof(generation)))) {
                break;
            }
            continue;
        }
        if (request == kRequestTable) {
            const std::string payload = BuildTable();
            const uint32_t length = static_cast<uint32_t>(payload.size());
            std::string framed(reinterpret_cast<const char *>(&length), sizeof(length));
            framed += payload;
            if (!WriteAll(fd, framed)) break;
            LOGI("VectorHyperRuntime: published {} bytes to the spawner.", payload.size());
            continue;
        }
        LOGD("VectorHyperRuntime: ignoring request {}.", static_cast<int>(request));
    }
    close(fd);
    return nullptr;
}

/**
 * @brief Rebuilds the lookup the spawned processes will read, if the daemon has published since.
 *
 * Runs in the spawner, and on the parent side of the fork, because that is the last moment at which
 * this process may still open a socket: what it produces is memory, and memory is what the fork
 * copies. The generation stamp is asked for first and is eight bytes, so the ordinary case -- a
 * fork with nothing republished since the last one -- costs one round trip and no payload.
 */
void RefreshIndex() {
    if (g_companion_fd < 0) return;

    const char request = kRequestGeneration;
    if (write(g_companion_fd, &request, 1) != 1) return;
    uint64_t generation = 0;
    if (!ReadAll(g_companion_fd, &generation, sizeof(generation))) return;
    if (generation == 0) {
        LOGD("VectorHyperRuntime: the daemon has published nothing yet.");
        return;
    }
    if (g_index_loaded && generation == g_generation) return;

    const char fetch = kRequestTable;
    if (write(g_companion_fd, &fetch, 1) != 1) return;
    uint32_t length = 0;
    if (!ReadAll(g_companion_fd, &length, sizeof(length)) || length > kMaxTableLength) {
        LOGW("VectorHyperRuntime: the companion sent no usable table.");
        return;
    }
    std::string payload(length, '\0');
    if (!ReadAll(g_companion_fd, payload.data(), length)) return;

    size_t pos = 0;
    uint32_t count = 0;
    if (!TakeU32(payload, pos, count)) return;
    std::map<std::string, std::vector<LibraryEntry>> parsed;
    for (uint32_t i = 0; i < count; i++) {
        uint32_t key_length = 0;
        if (!TakeU32(payload, pos, key_length) || pos + key_length > payload.size()) return;
        std::string key = payload.substr(pos, key_length);
        pos += key_length;

        uint32_t content_length = 0;
        if (!TakeU32(payload, pos, content_length) || pos + content_length > payload.size()) {
            return;
        }
        parsed[key] = ParseIndex(payload.substr(pos, content_length));
        pos += content_length;
    }

    g_index = std::move(parsed);
    g_generation = generation;
    g_index_loaded = true;
    LOGI("VectorHyperRuntime: holding scopes for {} process name(s).", g_index.size());
}

/// Runs in the parent, before the fork that creates the next application process.
void PrepareIndex() {
    if (g_registered) RefreshIndex();
}

void LoadModuleLibraries(const std::vector<LibraryEntry> &entries) {
    // The interception of the loader was installed by the spawner before it forked, so it is
    // already in place here and inherited. InstallNativeAPI is idempotent and reports that state.
    const bool intercepts_dlopen = InstallNativeAPI();
    if (!intercepts_dlopen) {
        LOGW("VectorHyperRuntime: the loader is not intercepted. Module libraries will still be "
             "loaded, but nothing will observe the loads that follow.");
    }

    const NativeAPIEntries *api_entries = GetNativeAPIEntries();
    if (api_entries == nullptr) {
        LOGE("VectorHyperRuntime: the native API entries could not be built; not loading modules.");
        return;
    }

    for (const auto &entry : entries) {
        // Registering first is what lets the loader hook recognize this very dlopen and call
        // native_init itself. When the hook is not there it is called by hand below, so a module is
        // initialized either way.
        if (intercepts_dlopen) RegisterNativeLib(entry.library_name);

        void *handle = dlopen(entry.library_path.c_str(), RTLD_NOW);
        if (handle == nullptr) {
            LOGE("VectorHyperRuntime: cannot load {} for '{}': {}.", entry.library_path.c_str(),
                 entry.module_package.c_str(), dlerror());
            continue;
        }
        LOGI("VectorHyperRuntime: loaded {} for '{}'.", entry.library_path.c_str(),
             entry.module_package.c_str());

        if (intercepts_dlopen) continue;
        void *init_sym = dlsym(handle, "native_init");
        if (init_sym == nullptr) {
            LOGW("VectorHyperRuntime: {} does not export native_init.", entry.library_path.c_str());
            continue;
        }
        reinterpret_cast<NativeInit>(init_sym)(api_entries);
    }
}

}  // namespace

/**
 * @brief Called once per specialized application process, after its uid, gid, groups and SELinux
 *        context have been applied.
 *
 * This runs in a process forked from a possibly multithreaded parent, so it does no I/O and starts
 * no threads: the answer was fetched by the spawner before the fork and is already in memory, and
 * all this does with it is look the process up and dlopen what it names. Nothing here reaches for a
 * file or a socket, which is deliberate -- a lock the parent held at fork time is held forever in
 * the child, and the launch would hang on it with nothing to report.
 */
void OnAppSpecialized(const ZnHyosAppSpecializeArgs *args) {
    if (!g_registered) return;
    if (g_specialized) return;
    g_specialized = true;
    if (args == nullptr || args->package_name == nullptr) {
        LOGE("VectorHyperRuntime: specialization arrived without a package name.");
        return;
    }

    LOGI("VectorHyperRuntime: specializing process '{}' (package '{}').", args->process_name,
         args->package_name);

    // The process name first, because that is what a scope is keyed by and what this process
    // actually is: a package with more than one process has a different scope in each. The package
    // name is the fallback, for a runtime that gave us a name we cannot use -- an inherited one, or
    // one truncated to the kernel's fifteen characters -- and it always resolves to the main
    // process, which is the more useful of the two wrong answers.
    const std::vector<LibraryEntry> *entries = nullptr;
    if (args->process_name != nullptr) {
        const auto found = g_index.find(args->process_name);
        if (found != g_index.end()) entries = &found->second;
    }
    if (entries == nullptr) {
        const auto found = g_index.find(args->package_name);
        if (found != g_index.end()) entries = &found->second;
    }
    if (entries == nullptr || entries->empty()) {
        LOGD("VectorHyperRuntime: '{}' is in no module's scope.", args->package_name);
        return;
    }

    LOGI("VectorHyperRuntime: '{}' has {} module librar{} to load.", args->package_name,
         entries->size(), entries->size() == 1 ? "y" : "ies");
    LoadModuleLibraries(*entries);
}

/**
 * @brief Answers the daemon's question about this runtime.
 *
 * Started with the companion rather than with the first connection, because "started and nothing
 * registered yet" has to be answerable: it is what a runtime the loader refused looks like, and the
 * difference between that and no companion at all is the whole reason the daemon asks.
 */
void *ServeMonitor(void *) {
    // A socket left behind by a companion that was killed would make every later bind fail, and the
    // daemon would keep reading the old inode instead of ours.
    unlink(kMonitorPath);

    const int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0) {
        LOGE("VectorHyperRuntime: cannot create {}: {}.", kMonitorPath, strerror(errno));
        return nullptr;
    }

    struct sockaddr_un address {};
    address.sun_family = AF_UNIX;
    strlcpy(address.sun_path, kMonitorPath, sizeof(address.sun_path));
    if (bind(listener, reinterpret_cast<struct sockaddr *>(&address), sizeof(address)) != 0 ||
        listen(listener, 8) != 0) {
        LOGE("VectorHyperRuntime: cannot listen on {}: {}.", kMonitorPath, strerror(errno));
        close(listener);
        return nullptr;
    }
    // The daemon runs as root and the directory is root-only, so the mode is not what admits it;
    // it is set anyway so that a stale socket is never what refuses a legitimate reader.
    if (chmod(kMonitorPath, 0666) != 0) {
        LOGW("VectorHyperRuntime: cannot relax {}: {}.", kMonitorPath, strerror(errno));
    }
    LOGI("VectorHyperRuntime: reporting injection status on {}.", kMonitorPath);

    for (;;) {
        const int client = accept(listener, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR) continue;
            break;
        }
        const char status = g_injection_status.load();
        if (write(client, &status, 1) != 1) {
            LOGD("VectorHyperRuntime: a monitor client went away before its answer.");
        }
        close(client);
    }
    close(listener);
    return nullptr;
}

void OnCompanionLoaded() {
    LOGI("VectorHyperRuntime: companion loaded in pid {}.", static_cast<int>(getpid()));
    pthread_t thread;
    if (pthread_create(&thread, nullptr, ServeMonitor, nullptr) != 0) {
        LOGE("VectorHyperRuntime: cannot serve {} on a thread; the daemon will see no runtime.",
             kMonitorPath);
        return;
    }
    pthread_detach(thread);
}

void OnModuleConnected(int fd) {
    LOGI("VectorHyperRuntime: companion connected on fd {}.", fd);

    // The spawner's first write is its status byte, and it is written before any fork, so it is
    // already on its way when this runs. Bounded anyway: a spawner that died between connecting and
    // writing must not leave the companion -- and the loader's own loop that called us -- stuck.
    struct timeval timeout {};
    timeout.tv_sec = kStatusByteTimeoutSeconds;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    char status = 0;
    if (read(fd, &status, 1) == 1 && status == 1) {
        g_injection_status.store(1);
        LOGI("VectorHyperRuntime: the runtime API is registered; the daemon will be told so.");
    } else {
        g_injection_status.store(0);
        LOGW("VectorHyperRuntime: no registration status arrived; the daemon will be told that "
             "injection is not working.");
    }

    pthread_t thread;
    auto *argument = reinterpret_cast<void *>(static_cast<intptr_t>(fd));
    if (pthread_create(&thread, nullptr, ServeCompanion, argument) == 0) {
        pthread_detach(thread);
        return;
    }
    LOGE("VectorHyperRuntime: cannot serve the companion on a thread; serving inline.");
    ServeCompanion(argument);
}

void OnModuleLoaded(void *self_handle, const ZygiskNextAPI *api) {
    if (api == nullptr) {
        LOGW("VectorHyperRuntime: the loader handed us no API; disabled in this process.");
        return;
    }
    g_api = api;

    // Every refusal below is a warning, not an error. The Runtime API is an optional part of the
    // loader: an older build does not have it, one that does can still say no, and in both cases
    // the right outcome is the same -- nothing of ours runs here, and the process carries on
    // exactly as it would without this feature. Saying so at warning level is the whole report.
    const ZygiskNextRuntime *runtime = api->getRuntime ? api->getRuntime() : nullptr;
    if (runtime == nullptr) {
        LOGW("VectorHyperRuntime: this loader offers no Runtime API; disabled in this process.");
        return;
    }
    if (runtime->type != ZN_RUNTIME_HYOS) {
        LOGW("VectorHyperRuntime: runtime type {} is not the HyperOS one; disabled in this process.",
             static_cast<int>(runtime->type));
        return;
    }
    if (runtime->api_version < ZYGISK_NEXT_HYOS_API_VERSION) {
        LOGW("VectorHyperRuntime: the runtime speaks API {}, we need {}; disabled in this process.",
             runtime->api_version, ZYGISK_NEXT_HYOS_API_VERSION);
        return;
    }
    if (runtime->registerModule == nullptr) {
        LOGW("VectorHyperRuntime: the runtime cannot register modules; disabled in this process.");
        return;
    }

    // Take the runtime's own hook engine before the native API is handed to any module: this is the
    // process the hooks will be installed in, and the runtime is the one that knows what it has
    // already patched. Failing here is not fatal -- the API keeps Dobby -- but it is worth saying,
    // because two engines on one address is exactly what the handover avoids.
    if (api->inlineHook == nullptr || api->inlineUnhook == nullptr) {
        LOGW("VectorHyperRuntime: the runtime offers no inline hooks; falling back to Dobby.");
    } else {
        SetHookBackend(api->inlineHook, api->inlineUnhook);
    }

    static const ZygiskNextHyosModule hyos_module = {
        .target_api_version = ZYGISK_NEXT_HYOS_API_VERSION,
        .onAppSpecialized = OnAppSpecialized,
    };
    if (runtime->registerModule(&hyos_module) != ZN_SUCCESS) {
        LOGW("VectorHyperRuntime: the runtime refused our specialization callback; disabled in this "
             "process.");
        return;
    }
    g_registered = true;
    LOGI("VectorHyperRuntime: registered; applications spawned here will be specialized.");

    // Installed here, in the spawner, and inherited by every process it forks. Here rather than in
    // the child is the point: this runs before main, on one thread, with nothing else in the
    // process to race against and no other thread's lock for the fork to have caught mid-hook.
    // Only worth doing now that a callback is coming -- without one, nothing would ever load a
    // module library, and patching a system process's loader for that is a change with no purpose.
    if (!InstallNativeAPI()) {
        LOGW("VectorHyperRuntime: the loader cannot be intercepted; module libraries will still be "
             "loaded on specialization, but later loads will be invisible to them.");
    }

    // One connection, made now and kept for the life of the process. The spawner is its only
    // client -- a child never touches it -- so the exchange on it needs no correlation and no
    // protection against replies crossing.
    if (api->connectCompanion == nullptr) {
        LOGW("VectorHyperRuntime: the loader offers no companion connection; applications spawned "
             "here will run unhooked.");
        return;
    }
    g_companion_fd = api->connectCompanion(self_handle);
    if (g_companion_fd < 0) {
        LOGW("VectorHyperRuntime: no companion, so the target list cannot be obtained; applications "
             "spawned here will run unhooked.");
    } else {
        // Set here rather than in the child, because it is a property of the connection every child
        // shares and setting it once is enough. The child's read is on the application's startup
        // path; without a bound, a companion that stopped answering would hold the launch open.
        struct timeval timeout{};
        timeout.tv_sec = kCompanionTimeoutSeconds;
        if (setsockopt(g_companion_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0) {
            LOGW("VectorHyperRuntime: cannot bound the companion reply: {}.", strerror(errno));
        }
        LOGI("VectorHyperRuntime: companion connection established on fd {}.", g_companion_fd);

        // The companion's first read is this byte, before it serves anything else, so it has to
        // arrive before the first fork -- which it does, because it is written here and children
        // only exist once the spawner's main runs. It is the answer the daemon will be handed when
        // it asks whether this runtime is being injected at all.
        const char status = g_registered ? 1 : 0;
        if (write(g_companion_fd, &status, 1) != 1) {
            LOGW("VectorHyperRuntime: cannot report the injection status to the companion: {}.",
                 strerror(errno));
        }

        // The table is fetched now so that the first fork has something to hand down, and again on
        // the parent side of every later fork. The hook is registered here rather than in the child
        // for the same reason the loader hook is: this runs before main, on one thread.
        pthread_atfork(PrepareIndex, nullptr, nullptr);
        RefreshIndex();
    }
}

}  // namespace vector::native::hyos

// =========================================================================================
// Zygisk Next module registration
// =========================================================================================
//
// Both structures are looked up by name in this library when Zygisk Next injects it into
// /system_ext/bin/hyos_spawner, which is what zn_modules.txt asks for. The ordinary Zygisk entry
// point above (REGISTER_ZYGISK_MODULE in module.cpp) is untouched: a library exports as many entry
// points as the loaders loading it need, and the two never run in the same process.

extern "C" __attribute__((visibility("default"))) ZygiskNextModule zn_module = {
    .target_api_version = ZYGISK_NEXT_API_VERSION,
    .onModuleLoaded = vector::native::hyos::OnModuleLoaded,
};

extern "C" __attribute__((visibility("default"))) ZygiskNextCompanionModule zn_companion_module = {
    .target_api_version = ZYGISK_NEXT_API_VERSION,
    .onCompanionLoaded = vector::native::hyos::OnCompanionLoaded,
    .onModuleConnected = vector::native::hyos::OnModuleConnected,
};

#include <dlfcn.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <lsplt.hpp>
#include <map>
#include <string>
#include <string_view>
#include <vector>

#include "logging.h"
#include "oat.h"

/**
 * This library is injected into dex2oat to intercept the generation of OAT headers. Our wrapper
 * runs dex2oat via the linker with extra flags. Without this hook, the resulting OAT file would
 * record the transferred fd path of wrapper and the extra flags in its "dex2oat-cmdline" key, which
 * can be used to detect the wrapper.
 */

namespace {
const std::string_view kParamToRemove = "--inline-max-code-units=0";
std::string g_binary_path = getenv("DEX2OAT_CMD");  // The original binary path
}  // namespace

/**
 * Sanitizes the command line string by:
 * 1. Replacing the first token (the linker/binary path) with the original dex2oat path.
 * 2. Removing the specific optimization flag we injected.
 */
std::string process_cmd(std::string_view sv, std::string_view new_cmd_path) {
    std::vector<std::string> tokens;
    std::string current;

    // Simple split by space
    for (char c : sv) {
        if (c == ' ') {
            if (!current.empty()) {
                tokens.push_back(std::move(current));
                current.clear();
            }
        } else {
            current.push_back(c);
        }
    }
    if (!current.empty()) tokens.push_back(std::move(current));

    // 1. Replace the command path (argv[0])
    if (!tokens.empty()) {
        tokens[0] = std::string(new_cmd_path);
    }

    // 2. Remove the injected parameter if it exists
    auto it = std::remove(tokens.begin(), tokens.end(), std::string(kParamToRemove));
    tokens.erase(it, tokens.end());

    // 3. Join tokens back into a single string
    std::string result;
    for (size_t i = 0; i < tokens.size(); ++i) {
        result += tokens[i];
        if (i != tokens.size() - 1) result += ' ';
    }
    return result;
}

/**
 * Re-serializes the Key-Value map back into the OAT header memory space.
 */
uint8_t* WriteKeyValueStore(const std::map<std::string, std::string>& key_values, uint8_t* store) {
    LOGD("Writing KeyValueStore back to memory");
    char* data_ptr = reinterpret_cast<char*>(store);

    for (const auto& [key, value] : key_values) {
        // Copy key + null terminator
        std::memcpy(data_ptr, key.c_str(), key.length() + 1);
        data_ptr += key.length() + 1;
        // Copy value + null terminator
        std::memcpy(data_ptr, value.c_str(), value.length() + 1);
        data_ptr += value.length() + 1;
    }
    LOGD("Written KeyValueStore with size: %zu", reinterpret_cast<uint8_t*>(data_ptr) - store);

    return reinterpret_cast<uint8_t*>(data_ptr);
}

// Helper function to test if a header field could have variable length
bool IsNonDeterministic(const std::string_view& key) {
    auto variable_fields = art::OatHeader::kNonDeterministicFieldsAndLengths;
    return std::any_of(variable_fields.begin(), variable_fields.end(),
                       [&key](const auto& pair) { return pair.first.compare(key) == 0; });
}

/**
 * Parses the OAT KeyValueStore and spoofs the "dex2oat-cmdline" entry.
 *
 * @return true if the store was modified in-place or successfully rebuilt.
 */
bool SpoofKeyValueStore(uint8_t* store) {
    if (!store) return false;

    uint32_t* const store_size_ptr = reinterpret_cast<uint32_t*>(store - sizeof(uint32_t));
    uint32_t const store_size = *store_size_ptr;

    const char* ptr = reinterpret_cast<const char*>(store);
    const char* const store_end = ptr + store_size;
    std::map<std::string, std::string> new_store_map;
    LOGI("Parsing KeyValueStore [%p - %p] of size %u", ptr, store_end, store_size);

    bool store_modified = false;

    while (ptr < store_end && *ptr != '\0') {
        // Find key
        const char* key_end = reinterpret_cast<const char*>(std::memchr(ptr, 0, store_end - ptr));
        if (!key_end) break;
        std::string_view key(ptr, key_end - ptr);

        // Find value
        const char* value_start = key_end + 1;
        if (value_start >= store_end) break;
        const char* value_end =
            reinterpret_cast<const char*>(std::memchr(value_start, 0, store_end - value_start));
        if (!value_end) break;
        std::string_view value(value_start, value_end - value_start);

        const bool has_padding =
            value_end + 1 < store_end && *(value_end + 1) == '\0' && IsNonDeterministic(key);

        if (key == art::OatHeader::kDex2OatCmdLineKey &&
            value.find(kParamToRemove) != std::string_view::npos) {
            std::string cleaned_cmd = process_cmd(value, g_binary_path);
            LOGI("Spoofing cmdline: Original size %zu -> New size %zu", value.length(),
                 cleaned_cmd.length());

            // We can overwrite in-place if the padding is enabled
            if (has_padding) {
                LOGI("In-place spoofing dex2oat-cmdline (padding detected)");

                // Zero out the entire original value range to be safe
                size_t original_capacity = value.length();
                std::memset(const_cast<char*>(value_start), 0, original_capacity);

                // Write the new command.
                std::memcpy(const_cast<char*>(value_start), cleaned_cmd.c_str(),
                            std::min(cleaned_cmd.length(), original_capacity));
                return true;
            }

            // Standard logic: store in map and rebuild later
            new_store_map[std::string(key)] = std::move(cleaned_cmd);
            store_modified = true;
        } else {
            new_store_map[std::string(key)] = std::string(value);
            LOGI("Parsed item:\t[%s:%s]", key.data(), value.data());
        }

        ptr = value_end + 1;
        if (has_padding) {
            while (*ptr == '\0') {
                ptr++;
            }
        }
    }

    if (store_modified) {
        uint8_t* const new_store_end = WriteKeyValueStore(new_store_map, store);
        *store_size_ptr = new_store_end - store;
        LOGI("Store size set to %u", *store_size_ptr);
        return true;
    }
    return false;
}

#define DCL_HOOK_FUNC(ret, func, ...)                                                              \
    ret (*old_##func)(__VA_ARGS__) = nullptr;                                                      \
    ret new_##func(__VA_ARGS__)

// For Android version < 16
DCL_HOOK_FUNC(uint8_t*, _ZNK3art9OatHeader16GetKeyValueStoreEv, void* header) {
    uint8_t* const key_value_store = old__ZNK3art9OatHeader16GetKeyValueStoreEv(header);

    SpoofKeyValueStore(key_value_store);

    return key_value_store;
}

// For Android version 16+ : Intercept during checksum calculation
DCL_HOOK_FUNC(void, _ZNK3art9OatHeader15ComputeChecksumEPj, void* header, uint32_t* checksum) {
    auto* oat_header = reinterpret_cast<art::OatHeader*>(header);
    uint8_t* const store = const_cast<uint8_t*>(oat_header->getKeyValueStore());

    SpoofKeyValueStore(store);

    // Call original to compute checksum on our modified data
    old__ZNK3art9OatHeader15ComputeChecksumEPj(header, checksum);
    LOGV("OAT Checksum recalculated: 0x%08X", *checksum);
}

#undef DCL_HOOK_FUNC

void register_hook(dev_t dev, ino_t inode, const char* symbol, void* new_func, void** old_func) {
    if (!lsplt::RegisterHook(dev, inode, symbol, new_func, old_func)) {
        LOGE("Failed to register PLT hook: %s", symbol);
    }
}

#define PLT_HOOK_REGISTER_SYM(DEV, INODE, SYM, NAME)                                               \
    register_hook(DEV, INODE, SYM, reinterpret_cast<void*>(new_##NAME),                            \
                  reinterpret_cast<void**>(&old_##NAME))

#define PLT_HOOK_REGISTER(DEV, INODE, NAME) PLT_HOOK_REGISTER_SYM(DEV, INODE, #NAME, NAME)

__attribute__((constructor)) static void initialize() {
    dev_t dev = 0;
    ino_t inode = 0;

    // Locate the dex2oat binary in memory to get its device and inode for PLT hooking
    for (const auto& info : lsplt::MapInfo::Scan()) {
        if (info.path.find("bin/dex2oat") != std::string::npos) {
            dev = info.dev;
            inode = info.inode;
            if (g_binary_path.empty()) g_binary_path = std::string(info.path);
            LOGD("Found target: %s (dev: %ju, inode: %ju)", info.path.data(), (uintmax_t)dev,
                 (uintmax_t)inode);
            break;
        }
    }

    if (dev == 0) {
        LOGE("Could not locate dex2oat memory map");
        return;
    }

    // Register hook for the standard KeyValueStore getter
    PLT_HOOK_REGISTER(dev, inode, _ZNK3art9OatHeader16GetKeyValueStoreEv);

    // If the standard store hook fails (e.g., on Android 16+), try the Checksum hook
    if (!lsplt::CommitHook()) {
        PLT_HOOK_REGISTER(dev, inode, _ZNK3art9OatHeader15ComputeChecksumEPj);
        lsplt::CommitHook();
    }
}

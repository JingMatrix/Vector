#include "obfuscation.h"

#include <android/sharedmem.h>
#include <android/sharedmem_jni.h>
#include <fcntl.h>
#include <jni.h>
#include <slicer/dex_utf8.h>
#include <slicer/reader.h>
#include <slicer/writer.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <map>
#include <mutex>
#include <random>
#include <string>
#include <string_view>
#include <utils/jni_helper.hpp>

namespace {

std::once_flag init_flag;

std::map<std::string, std::string> signatures = {
    {"Lde/robv/android/xposed/", ""},         {"Landroid/app/AndroidApp", ""},
    {"Landroid/content/res/XRes", ""},        {"Landroid/content/res/XModule", ""},
    {"Lio/github/libxposed/api/Xposed", ""},  {"Lorg/matrix/vector/core/", ""},
    {"Lorg/matrix/vector/nativebridge/", ""}, {"Lorg/matrix/vector/service/", ""},
};

jclass class_file_descriptor = nullptr;
jmethodID method_file_descriptor_ctor = nullptr;

jclass class_shared_memory = nullptr;
jmethodID method_shared_memory_ctor = nullptr;

}  // anonymous namespace

static bool rangeFits(size_t file_size, dex::u4 offset, size_t byte_count) {
    auto start = static_cast<size_t>(offset);
    return start <= file_size && byte_count <= file_size - start;
}

static bool isAligned(dex::u4 value, dex::u4 alignment) {
    return value % alignment == 0;
}

static bool isStandardDexMagic(const dex::u1 *magic) {
    return std::memcmp(magic, "dex\n", 4) == 0 && magic[4] >= '0' && magic[4] <= '9' &&
           magic[5] >= '0' && magic[5] <= '9' && magic[6] >= '0' && magic[6] <= '9' &&
           magic[7] == '\0';
}

static bool sectionFits(size_t file_size, dex::u4 offset, dex::u4 count, size_t item_size,
                        const char *name) {
    if (count == 0) {
        if (offset == 0) return true;
        LOGW("Invalid DEX %s section: empty section has non-zero offset %u", name, offset);
        return false;
    }
    if (offset == 0 || !isAligned(offset, 4)) {
        LOGW("Invalid DEX %s section: offset=%u count=%u", name, offset, count);
        return false;
    }
    auto start = static_cast<size_t>(offset);
    if (start > file_size || count > (file_size - start) / item_size) {
        LOGW("Invalid DEX %s section: offset=%u count=%u item_size=%zu file_size=%zu", name,
             offset, count, item_size, file_size);
        return false;
    }
    return true;
}

static bool dataRangeFits(const dex::Header *header, size_t file_size, dex::u4 offset,
                          size_t byte_count, const char *name, bool allow_zero) {
    if (offset == 0) return allow_zero;
    if (offset < header->data_off || !rangeFits(file_size, offset, byte_count)) {
        LOGW("Invalid DEX %s offset: offset=%u data_off=%u size=%zu file_size=%zu", name, offset,
             header->data_off, byte_count, file_size);
        return false;
    }
    return true;
}

static bool typeListFits(const dex::u1 *base, const dex::Header *header, size_t file_size,
                         dex::u4 offset, const char *name) {
    if (offset == 0) return true;
    if (!isAligned(offset, 4) ||
        !dataRangeFits(header, file_size, offset, sizeof(dex::TypeList), name, false)) {
        return false;
    }

    const auto *type_list = reinterpret_cast<const dex::TypeList *>(base + offset);
    auto start = static_cast<size_t>(offset) + sizeof(dex::u4);
    if (start > file_size || type_list->size > (file_size - start) / sizeof(dex::TypeItem)) {
        LOGW("Invalid DEX %s type list: offset=%u count=%u file_size=%zu", name, offset,
             type_list->size, file_size);
        return false;
    }

    for (dex::u4 i = 0; i < type_list->size; ++i) {
        if (type_list->list[i].type_idx >= header->type_ids_size) {
            LOGW("Invalid DEX %s type list item: type_idx=%u type_count=%u", name,
                 type_list->list[i].type_idx, header->type_ids_size);
            return false;
        }
    }
    return true;
}

// Slicer's own structural checks compile out under NDEBUG, so validate the
// table ranges and indexed references that CreateFullIr() will touch first.
static bool isDexSafeForSlicer(const void *dex_data, size_t mapped_size) {
    if (mapped_size < sizeof(dex::Header)) {
        LOGW("Invalid DEX: mapped size %zu is smaller than header size %zu", mapped_size,
             sizeof(dex::Header));
        return false;
    }

    const auto *base = reinterpret_cast<const dex::u1 *>(dex_data);
    const auto *header = reinterpret_cast<const dex::Header *>(base);
    if (!isStandardDexMagic(header->magic)) {
        LOGW("Invalid DEX: unsupported magic");
        return false;
    }

    auto file_size = static_cast<size_t>(header->file_size);
    if (file_size < sizeof(dex::Header) || file_size > mapped_size) {
        LOGW("Invalid DEX: file_size=%zu mapped_size=%zu", file_size, mapped_size);
        return false;
    }
    if (header->header_size != sizeof(dex::Header)) {
        LOGW("Invalid DEX: unsupported header_size=%u", header->header_size);
        return false;
    }
    if (header->endian_tag != dex::kEndianConstant) {
        LOGW("Invalid DEX: unsupported endian tag 0x%x", header->endian_tag);
        return false;
    }
    if (header->link_size != 0 || header->link_off != 0) {
        LOGW("Invalid DEX: link section is not supported");
        return false;
    }
    if ((header->data_size != 0 && (header->data_off == 0 || !isAligned(header->data_off, 4))) ||
        !rangeFits(file_size, header->data_off, header->data_size)) {
        LOGW("Invalid DEX data section: offset=%u size=%u file_size=%zu", header->data_off,
             header->data_size, file_size);
        return false;
    }
    if (header->type_ids_size >= 65536 || header->proto_ids_size >= 65536) {
        LOGW("Invalid DEX: type_ids_size=%u proto_ids_size=%u", header->type_ids_size,
             header->proto_ids_size);
        return false;
    }

    if (header->map_off == 0 || !isAligned(header->map_off, 4) ||
        header->map_off < header->data_off || !rangeFits(file_size, header->map_off, sizeof(dex::u4))) {
        LOGW("Invalid DEX map section: offset=%u data_off=%u file_size=%zu", header->map_off,
             header->data_off, file_size);
        return false;
    }
    const auto *map_list = reinterpret_cast<const dex::MapList *>(base + header->map_off);
    auto map_items_start = static_cast<size_t>(header->map_off) + sizeof(dex::u4);
    if (map_list->size == 0 ||
        map_items_start > file_size ||
        map_list->size > (file_size - map_items_start) / sizeof(dex::MapItem)) {
        LOGW("Invalid DEX map list: offset=%u count=%u file_size=%zu", header->map_off,
             map_list->size, file_size);
        return false;
    }

    if (!sectionFits(file_size, header->string_ids_off, header->string_ids_size,
                     sizeof(dex::StringId), "string_ids") ||
        !sectionFits(file_size, header->type_ids_off, header->type_ids_size, sizeof(dex::TypeId),
                     "type_ids") ||
        !sectionFits(file_size, header->proto_ids_off, header->proto_ids_size,
                     sizeof(dex::ProtoId), "proto_ids") ||
        !sectionFits(file_size, header->field_ids_off, header->field_ids_size,
                     sizeof(dex::FieldId), "field_ids") ||
        !sectionFits(file_size, header->method_ids_off, header->method_ids_size,
                     sizeof(dex::MethodId), "method_ids") ||
        !sectionFits(file_size, header->class_defs_off, header->class_defs_size,
                     sizeof(dex::ClassDef), "class_defs")) {
        return false;
    }

    const auto *string_ids = reinterpret_cast<const dex::StringId *>(base + header->string_ids_off);
    for (dex::u4 i = 0; i < header->string_ids_size; ++i) {
        if (!dataRangeFits(header, file_size, string_ids[i].string_data_off, sizeof(dex::u1),
                           "string_data", false)) {
            return false;
        }
    }

    const auto *type_ids = reinterpret_cast<const dex::TypeId *>(base + header->type_ids_off);
    for (dex::u4 i = 0; i < header->type_ids_size; ++i) {
        if (type_ids[i].descriptor_idx >= header->string_ids_size) {
            LOGW("Invalid DEX type_id: descriptor_idx=%u string_count=%u",
                 type_ids[i].descriptor_idx, header->string_ids_size);
            return false;
        }
    }

    const auto *proto_ids = reinterpret_cast<const dex::ProtoId *>(base + header->proto_ids_off);
    for (dex::u4 i = 0; i < header->proto_ids_size; ++i) {
        if (proto_ids[i].shorty_idx >= header->string_ids_size ||
            proto_ids[i].return_type_idx >= header->type_ids_size ||
            !typeListFits(base, header, file_size, proto_ids[i].parameters_off,
                          "proto parameters")) {
            LOGW("Invalid DEX proto_id: shorty_idx=%u return_type_idx=%u", proto_ids[i].shorty_idx,
                 proto_ids[i].return_type_idx);
            return false;
        }
    }

    const auto *field_ids = reinterpret_cast<const dex::FieldId *>(base + header->field_ids_off);
    for (dex::u4 i = 0; i < header->field_ids_size; ++i) {
        if (field_ids[i].class_idx >= header->type_ids_size ||
            field_ids[i].type_idx >= header->type_ids_size ||
            field_ids[i].name_idx >= header->string_ids_size) {
            LOGW("Invalid DEX field_id: class_idx=%u type_idx=%u name_idx=%u",
                 field_ids[i].class_idx, field_ids[i].type_idx, field_ids[i].name_idx);
            return false;
        }
    }

    const auto *method_ids = reinterpret_cast<const dex::MethodId *>(base + header->method_ids_off);
    for (dex::u4 i = 0; i < header->method_ids_size; ++i) {
        if (method_ids[i].class_idx >= header->type_ids_size ||
            method_ids[i].proto_idx >= header->proto_ids_size ||
            method_ids[i].name_idx >= header->string_ids_size) {
            LOGW("Invalid DEX method_id: class_idx=%u proto_idx=%u name_idx=%u",
                 method_ids[i].class_idx, method_ids[i].proto_idx, method_ids[i].name_idx);
            return false;
        }
    }

    const auto *class_defs = reinterpret_cast<const dex::ClassDef *>(base + header->class_defs_off);
    for (dex::u4 i = 0; i < header->class_defs_size; ++i) {
        const auto &class_def = class_defs[i];
        if (class_def.class_idx >= header->type_ids_size ||
            (class_def.superclass_idx != dex::kNoIndex &&
             class_def.superclass_idx >= header->type_ids_size) ||
            (class_def.source_file_idx != dex::kNoIndex &&
             class_def.source_file_idx >= header->string_ids_size) ||
            !typeListFits(base, header, file_size, class_def.interfaces_off, "class interfaces") ||
            !dataRangeFits(header, file_size, class_def.annotations_off,
                           sizeof(dex::AnnotationsDirectoryItem),
                           "class annotations", true) ||
            !dataRangeFits(header, file_size, class_def.class_data_off, sizeof(dex::u1),
                           "class data", true) ||
            !dataRangeFits(header, file_size, class_def.static_values_off, sizeof(dex::u1),
                           "static values", true)) {
            LOGW("Invalid DEX class_def at index %u", i);
            return false;
        }
    }

    return true;
}

static jobject wrapSharedMemoryFd(JNIEnv *env, int fd) {
    auto java_fd =
        lsplant::JNI_NewObject(env, class_file_descriptor, method_file_descriptor_ctor, fd);
    auto java_sm =
        lsplant::JNI_NewObject(env, class_shared_memory, method_shared_memory_ctor, java_fd);
    return java_sm.release();
}

static jobject returnOriginalSharedMemory(jobject memory, int fd) {
    if (fd >= 0) close(fd);
    return memory;
}

// Converts Dex signatures to Java format.
// Trailing slashes are translated to dots, which correctly aligns with
// Java's string matching expectations for package prefixes.
static std::string to_java(const std::string &signature) {
    std::string java(signature, 1);
    std::replace(java.begin(), java.end(), '/', '.');
    return java;
}

static void ensureInitialized(JNIEnv *env) {
    // Thread-safe one-time initialization
    std::call_once(init_flag, [&]() {
        LOGD("ObfuscationManager.init");

        if (auto file_descriptor = lsplant::JNI_FindClass(env, "java/io/FileDescriptor")) {
            class_file_descriptor =
                static_cast<jclass>(lsplant::JNI_NewGlobalRef(env, file_descriptor));
        } else
            return;

        method_file_descriptor_ctor =
            lsplant::JNI_GetMethodID(env, class_file_descriptor, "<init>", "(I)V");

        if (auto shared_memory = lsplant::JNI_FindClass(env, "android/os/SharedMemory")) {
            class_shared_memory =
                static_cast<jclass>(lsplant::JNI_NewGlobalRef(env, shared_memory));
        } else
            return;

        method_shared_memory_ctor = lsplant::JNI_GetMethodID(env, class_shared_memory, "<init>",
                                                             "(Ljava/io/FileDescriptor;)V");

        auto regen = [](std::string_view original_signature) {
            static constexpr auto chrs = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

            thread_local static std::mt19937 rg{std::random_device{}()};
            thread_local static std::uniform_int_distribution<std::string::size_type> pick(
                0, strlen(chrs) - 1);
            thread_local static std::uniform_int_distribution<std::string::size_type> choose_slash(
                0, 10);

            std::string out;
            size_t length = original_signature.size();
            out.reserve(length);
            out += "L";

            for (size_t i = 1; i < length - 1; i++) {
                if (choose_slash(rg) > 8 &&  // 20% chance for a slash
                    out.back() != '/' &&     // Avoid consecutive slashes
                    i != 1 &&                // No slash immediately after 'L'
                    i != length - 2) {       // No slash right before the end
                    out += "/";
                } else {
                    out += chrs[pick(rg)];
                }
            }

            // Respect the original termination character type to prevent
            if (original_signature.back() == '/') {
                out += "/";
            } else {
                out += chrs[pick(rg)];
            }

            if (out.length() != original_signature.length()) {
                LOGE("Length mismatch! Org: %zu vs New: %zu. '%s' -> '%s'",
                     original_signature.length(), out.length(),
                     std::string(original_signature).c_str(), out.c_str());
            }

            return out;
        };

        for (auto &i : signatures) {
            i.second = regen(i.first);
            LOGV("%s => %s", i.first.c_str(), i.second.c_str());
        }

        LOGD("ObfuscationManager init successfully");
    });
}

static jobject stringMapToJavaHashMap(JNIEnv *env, const std::map<std::string, std::string> &map) {
    jclass mapClass = env->FindClass("java/util/HashMap");
    if (mapClass == nullptr) return nullptr;

    jmethodID init = env->GetMethodID(mapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(mapClass, init);
    jmethodID put = env->GetMethodID(mapClass, "put",
                                     "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    for (const auto &[key, value] : map) {
        jstring keyJava = env->NewStringUTF(key.c_str());
        jstring valueJava = env->NewStringUTF(value.c_str());

        env->CallObjectMethod(hashMap, put, keyJava, valueJava);

        env->DeleteLocalRef(keyJava);
        env->DeleteLocalRef(valueJava);
    }

    jobject hashMapGlobal = env->NewGlobalRef(hashMap);
    env->DeleteLocalRef(hashMap);
    env->DeleteLocalRef(mapClass);

    return hashMapGlobal;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_matrix_vector_daemon_utils_ObfuscationManager_getSignatures(
    JNIEnv *env, [[maybe_unused]] jclass clazz) {
    ensureInitialized(env);

    static jobject signatures_jni = nullptr;
    static std::once_flag jni_map_flag;

    // Thread-safe, one-time JNI HashMap translation
    std::call_once(jni_map_flag, [&]() {
        std::map<std::string, std::string> signatures_java;
        for (const auto &i : signatures) {
            signatures_java[to_java(i.first)] = to_java(i.second);
        }
        signatures_jni = stringMapToJavaHashMap(env, signatures_java);
    });

    return signatures_jni;
}

static int obfuscateDexBuffer(const void *dex_data, size_t size) {
    dex::Reader reader{reinterpret_cast<const dex::u1 *>(dex_data), size};
    reader.CreateFullIr();
    auto ir = reader.GetIr();

    LOGD("Mutating strings in-place");
    // Mutate strings in-place.
    for (auto &i : ir->strings) {
        const char *s = i->c_str();
        for (const auto &signature : signatures) {
            char *p = const_cast<char *>(strstr(s, signature.first.c_str()));
            if (p) memcpy(p, signature.second.c_str(), signature.first.length());
        }
    }

    dex::Writer writer(ir);
    size_t new_size;
    DexAllocator allocator;

    // CreateImage calls allocator.Allocate()
    auto *image = writer.CreateImage(&allocator, &new_size);
    LOGD("writer.CreateImage returned: %p", image);

    return allocator.GetFd();
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_matrix_vector_daemon_utils_ObfuscationManager_obfuscateDex(JNIEnv *env,
                                                                    [[maybe_unused]] jclass clazz,
                                                                    jobject memory) {
    ensureInitialized(env);

    int fd = ASharedMemory_dupFromJava(env, memory);
    if (fd < 0) {
        LOGE("Failed to duplicate input dex shared memory");
        return memory;
    }

    auto size = ASharedMemory_getSize(fd);
    if (size <= 0) {
        LOGE("Invalid input dex shared memory size: %zd", static_cast<ssize_t>(size));
        return returnOriginalSharedMemory(memory, fd);
    }
    auto mapped_size = static_cast<size_t>(size);
    LOGV("obfuscateDex: fd=%d, size=%zu", fd, mapped_size);

    // CRITICAL: We MUST use MAP_SHARED here, not MAP_PRIVATE.
    // 1. Android's SharedMemory is backed by ashmem or memfd. Mapping these as
    //    MAP_PRIVATE creates a Copy-On-Write (COW) layer. In many Android kernel
    //    configurations, this COW layer does not correctly fault-in the initial
    //    contents from the shared source, resulting in the JNI side seeing
    //    unpopulated zero-pages. This causes slicer to fail immediately.
    // 2. Using MAP_SHARED ensures we have direct access to the same physical
    //    pages populated by the Java layer.
    // 3. ZERO-COPY MUTATION: Slicer's Intermediate Representation (IR) points
    //    directly into this mapped memory for string data. By mutating the
    //    buffer in-place, we update the IR's state without any additional
    //    heap allocations. This is safe here because the Daemon owns the
    //    lifecycle of this temporary buffer and the Java caller will discard
    //    the un-obfuscated original anyway.
    void *mem = mmap(nullptr, mapped_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mem == MAP_FAILED) {
        LOGE("Failed to map input dex");
        return returnOriginalSharedMemory(memory, fd);
    }

    bool needs_obfuscation = false;
    for (const auto &sig : signatures) {
        if (memmem(mem, mapped_size, sig.first.c_str(), sig.first.length()) != nullptr) {
            needs_obfuscation = true;
            break;
        }
    }

    if (!needs_obfuscation) {
        LOGV("No target signatures found in fd=%d, skipping slicer.", fd);
        munmap(mem, mapped_size);
        return returnOriginalSharedMemory(memory, fd);
    }

    if (!isDexSafeForSlicer(mem, mapped_size)) {
        LOGW("Skipping DEX obfuscation for malformed input fd=%d", fd);
        munmap(mem, mapped_size);
        return returnOriginalSharedMemory(memory, fd);
    }
    auto dex_file_size =
        static_cast<size_t>(reinterpret_cast<const dex::Header *>(mem)->file_size);

    // Process the DEX and obtain a new file descriptor for the output
    int new_fd = obfuscateDexBuffer(mem, dex_file_size);

    // Safely unmap and close the input buffer mapping
    munmap(mem, mapped_size);

    if (new_fd < 0) {
        LOGE("Obfuscation failed to create new dex buffer");
        return returnOriginalSharedMemory(memory, fd);
    }
    close(fd);

    // Construct new SharedMemory object around the new_fd
    return wrapSharedMemoryFd(env, new_fd);
}

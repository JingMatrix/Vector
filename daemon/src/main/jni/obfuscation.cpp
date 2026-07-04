#include "obfuscation.h"

#include <android/sharedmem.h>
#include <android/sharedmem_jni.h>
#include <fcntl.h>
#include <jni.h>
#include <slicer/dex_bytecode.h>
#include <slicer/dex_utf8.h>
#include <slicer/reader.h>
#include <slicer/writer.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <new>
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
        // An empty section may legitimately carry a non-zero offset in DEX
        // files produced by some toolchains; only flag a misaligned one.
        if (offset == 0 || isAligned(offset, 4)) return true;
        LOGW("Invalid DEX %s section: empty section has unaligned offset %u", name, offset);
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

static bool readULeb128Checked(const dex::u1 **ptr, const dex::u1 *limit, dex::u4 *value,
                               const char *name) {
    dex::u4 result = 0;
    for (int i = 0; i < 5; ++i) {
        if (*ptr >= limit) {
            LOGW("Invalid DEX %s: truncated uleb128", name);
            return false;
        }
        dex::u1 byte = *(*ptr)++;
        if (i == 4 && (byte & 0xf0) != 0) {
            LOGW("Invalid DEX %s: uleb128 overflow", name);
            return false;
        }
        result |= static_cast<dex::u4>(byte & 0x7f) << (i * 7);
        if ((byte & 0x80) == 0) {
            *value = result;
            return true;
        }
    }
    LOGW("Invalid DEX %s: unterminated uleb128", name);
    return false;
}

static bool readSLeb128Checked(const dex::u1 **ptr, const dex::u1 *limit, dex::s4 *value,
                               const char *name) {
    dex::u4 result = 0;
    int shift = 0;
    dex::u1 byte = 0;
    for (int i = 0; i < 5; ++i) {
        if (*ptr >= limit) {
            LOGW("Invalid DEX %s: truncated sleb128", name);
            return false;
        }
        byte = *(*ptr)++;
        result |= static_cast<dex::u4>(byte & 0x7f) << shift;
        shift += 7;
        if ((byte & 0x80) == 0) {
            if (shift < 32 && (byte & 0x40) != 0) {
                result |= ~0u << shift;
            }
            *value = static_cast<dex::s4>(result);
            return true;
        }
    }
    LOGW("Invalid DEX %s: unterminated sleb128", name);
    return false;
}

static bool consumeBytes(const dex::u1 **ptr, const dex::u1 *limit, size_t byte_count,
                         const char *name) {
    if (*ptr > limit || byte_count > static_cast<size_t>(limit - *ptr)) {
        LOGW("Invalid DEX %s: truncated encoded data", name);
        return false;
    }
    *ptr += byte_count;
    return true;
}

static bool applyMemberIndexDelta(dex::u4 delta, dex::u4 *base_index, dex::u4 count,
                                  const char *name) {
    if (*base_index != dex::kNoIndex && delta == 0) {
        LOGW("Invalid DEX %s: repeated member index delta", name);
        return false;
    }
    uint64_t index = delta;
    if (*base_index != dex::kNoIndex) {
        index += *base_index;
    }
    if (index >= count) {
        LOGW("Invalid DEX %s: index=%llu count=%u", name,
             static_cast<unsigned long long>(index), count);
        return false;
    }
    *base_index = static_cast<dex::u4>(index);
    return true;
}

static bool stringDataFits(const dex::u1 *base, const dex::Header *header, size_t file_size,
                           dex::u4 offset) {
    if (!dataRangeFits(header, file_size, offset, sizeof(dex::u1), "string_data", false)) {
        return false;
    }

    const dex::u1 *ptr = base + offset;
    const dex::u1 *limit = base + file_size;
    dex::u4 utf16_size;
    if (!readULeb128Checked(&ptr, limit, &utf16_size, "string_data utf16_size")) {
        return false;
    }
    if (ptr > limit || std::memchr(ptr, '\0', static_cast<size_t>(limit - ptr)) == nullptr) {
        LOGW("Invalid DEX string_data: missing MUTF-8 terminator at offset=%u", offset);
        return false;
    }
    return true;
}

static bool encodedValueFits(const dex::u1 **ptr, const dex::u1 *limit,
                             const dex::Header *header, int depth);

static bool annotationFits(const dex::u1 **ptr, const dex::u1 *limit,
                           const dex::Header *header, int depth) {
    dex::u4 type_index;
    dex::u4 elements_count;
    if (!readULeb128Checked(ptr, limit, &type_index, "annotation type") ||
        !readULeb128Checked(ptr, limit, &elements_count, "annotation elements")) {
        return false;
    }
    if (type_index >= header->type_ids_size) {
        LOGW("Invalid DEX annotation: type_idx=%u type_count=%u", type_index,
             header->type_ids_size);
        return false;
    }

    for (dex::u4 i = 0; i < elements_count; ++i) {
        dex::u4 name_index;
        if (!readULeb128Checked(ptr, limit, &name_index, "annotation element name")) {
            return false;
        }
        if (name_index >= header->string_ids_size) {
            LOGW("Invalid DEX annotation element: name_idx=%u string_count=%u", name_index,
                 header->string_ids_size);
            return false;
        }
        if (!encodedValueFits(ptr, limit, header, depth + 1)) {
            return false;
        }
    }
    return true;
}

static bool encodedArrayFits(const dex::u1 **ptr, const dex::u1 *limit,
                             const dex::Header *header, int depth) {
    dex::u4 count;
    if (!readULeb128Checked(ptr, limit, &count, "encoded array size")) {
        return false;
    }
    for (dex::u4 i = 0; i < count; ++i) {
        if (!encodedValueFits(ptr, limit, header, depth + 1)) {
            return false;
        }
    }
    return true;
}

static bool readEncodedIndexChecked(const dex::u1 **ptr, const dex::u1 *limit, dex::u1 arg,
                                    dex::u4 count, const char *name) {
    if (arg > 3) {
        LOGW("Invalid DEX %s: encoded index uses %u bytes", name, arg + 1);
        return false;
    }
    if (!consumeBytes(ptr, limit, static_cast<size_t>(arg) + 1, name)) {
        return false;
    }

    const dex::u1 *value = *ptr - (static_cast<size_t>(arg) + 1);
    dex::u4 index = 0;
    for (dex::u1 i = 0; i <= arg; ++i) {
        index |= static_cast<dex::u4>(value[i]) << (i * 8);
    }
    if (index >= count) {
        LOGW("Invalid DEX %s: index=%u count=%u", name, index, count);
        return false;
    }
    return true;
}

static bool encodedValueFits(const dex::u1 **ptr, const dex::u1 *limit,
                             const dex::Header *header, int depth) {
    if (depth > 32) {
        LOGW("Invalid DEX encoded value: nesting is too deep");
        return false;
    }
    if (*ptr >= limit) {
        LOGW("Invalid DEX encoded value: missing header");
        return false;
    }

    dex::u1 encoded_header = *(*ptr)++;
    dex::u1 type = encoded_header & dex::kEncodedValueTypeMask;
    dex::u1 arg = encoded_header >> dex::kEncodedValueArgShift;

    switch (type) {
        case dex::kEncodedByte:
            if (arg != 0) {
                LOGW("Invalid DEX encoded byte: arg=%u", arg);
                return false;
            }
            return consumeBytes(ptr, limit, 1, "encoded byte");
        case dex::kEncodedShort:
        case dex::kEncodedChar:
            if (arg > 1) {
                LOGW("Invalid DEX encoded 16-bit value: arg=%u", arg);
                return false;
            }
            return consumeBytes(ptr, limit, static_cast<size_t>(arg) + 1, "encoded 16-bit value");
        case dex::kEncodedInt:
        case dex::kEncodedFloat:
            if (arg > 3) {
                LOGW("Invalid DEX encoded 32-bit value: arg=%u", arg);
                return false;
            }
            return consumeBytes(ptr, limit, static_cast<size_t>(arg) + 1, "encoded 32-bit value");
        case dex::kEncodedLong:
        case dex::kEncodedDouble:
            if (arg > 7) {
                LOGW("Invalid DEX encoded 64-bit value: arg=%u", arg);
                return false;
            }
            return consumeBytes(ptr, limit, static_cast<size_t>(arg) + 1, "encoded 64-bit value");
        case dex::kEncodedString:
            return readEncodedIndexChecked(ptr, limit, arg, header->string_ids_size,
                                           "encoded string");
        case dex::kEncodedType:
            return readEncodedIndexChecked(ptr, limit, arg, header->type_ids_size,
                                           "encoded type");
        case dex::kEncodedField:
        case dex::kEncodedEnum:
            return readEncodedIndexChecked(ptr, limit, arg, header->field_ids_size,
                                           "encoded field");
        case dex::kEncodedMethod:
            return readEncodedIndexChecked(ptr, limit, arg, header->method_ids_size,
                                           "encoded method");
        case dex::kEncodedArray:
            return arg == 0 && encodedArrayFits(ptr, limit, header, depth + 1);
        case dex::kEncodedAnnotation:
            return arg == 0 && annotationFits(ptr, limit, header, depth + 1);
        case dex::kEncodedNull:
            if (arg != 0) {
                LOGW("Invalid DEX encoded null: arg=%u", arg);
                return false;
            }
            return true;
        case dex::kEncodedBoolean:
            if (arg > 1) {
                LOGW("Invalid DEX encoded boolean: arg=%u", arg);
                return false;
            }
            return true;
        default:
            LOGW("Invalid DEX encoded value type: 0x%x", type);
            return false;
    }
}

static bool encodedArrayItemFits(const dex::u1 *base, const dex::Header *header,
                                 size_t file_size, dex::u4 offset, const char *name) {
    if (offset == 0) return true;
    if (!dataRangeFits(header, file_size, offset, sizeof(dex::u1), name, false)) {
        return false;
    }
    const dex::u1 *ptr = base + offset;
    const dex::u1 *limit = base + file_size;
    return encodedArrayFits(&ptr, limit, header, 0);
}

static bool annotationItemFits(const dex::u1 *base, const dex::Header *header, size_t file_size,
                               dex::u4 offset) {
    if (!dataRangeFits(header, file_size, offset, sizeof(dex::AnnotationItem), "annotation item",
                       false)) {
        return false;
    }
    const dex::u1 *ptr = base + offset + offsetof(dex::AnnotationItem, annotation);
    const dex::u1 *limit = base + file_size;
    return annotationFits(&ptr, limit, header, 0);
}

static bool annotationSetFits(const dex::u1 *base, const dex::Header *header, size_t file_size,
                              dex::u4 offset) {
    if (offset == 0) return true;
    if (!isAligned(offset, 4) ||
        !dataRangeFits(header, file_size, offset, sizeof(dex::AnnotationSetItem),
                       "annotation set", false)) {
        return false;
    }
    const auto *set = reinterpret_cast<const dex::AnnotationSetItem *>(base + offset);
    auto entries_start = static_cast<size_t>(offset) + sizeof(dex::u4);
    if (entries_start > file_size || set->size > (file_size - entries_start) / sizeof(dex::u4)) {
        LOGW("Invalid DEX annotation set: offset=%u count=%u file_size=%zu", offset, set->size,
             file_size);
        return false;
    }
    for (dex::u4 i = 0; i < set->size; ++i) {
        if (set->entries[i] == 0 || !annotationItemFits(base, header, file_size, set->entries[i])) {
            LOGW("Invalid DEX annotation set entry at index %u", i);
            return false;
        }
    }
    return true;
}

static bool annotationSetRefListFits(const dex::u1 *base, const dex::Header *header,
                                     size_t file_size, dex::u4 offset) {
    if (!isAligned(offset, 4) ||
        !dataRangeFits(header, file_size, offset, sizeof(dex::AnnotationSetRefList),
                       "annotation set ref list", false)) {
        return false;
    }
    const auto *list = reinterpret_cast<const dex::AnnotationSetRefList *>(base + offset);
    auto entries_start = static_cast<size_t>(offset) + sizeof(dex::u4);
    if (entries_start > file_size ||
        list->size > (file_size - entries_start) / sizeof(dex::AnnotationSetRefItem)) {
        LOGW("Invalid DEX annotation set ref list: offset=%u count=%u file_size=%zu", offset,
             list->size, file_size);
        return false;
    }
    for (dex::u4 i = 0; i < list->size; ++i) {
        dex::u4 annotations_off = list->list[i].annotations_off;
        if (annotations_off != 0 && !annotationSetFits(base, header, file_size, annotations_off)) {
            LOGW("Invalid DEX annotation set ref entry at index %u", i);
            return false;
        }
    }
    return true;
}

static bool checkedTableBytes(size_t start, dex::u4 count, size_t item_size, size_t file_size,
                              const char *name, size_t *bytes);

static bool annotationsDirectoryFits(const dex::u1 *base, const dex::Header *header,
                                     size_t file_size, dex::u4 offset) {
    if (offset == 0) return true;
    if (!isAligned(offset, 4) ||
        !dataRangeFits(header, file_size, offset, sizeof(dex::AnnotationsDirectoryItem),
                       "class annotations", false)) {
        return false;
    }
    const auto *directory = reinterpret_cast<const dex::AnnotationsDirectoryItem *>(base + offset);
    auto fields_start = static_cast<size_t>(offset) + sizeof(dex::AnnotationsDirectoryItem);
    size_t fields_bytes;
    if (!checkedTableBytes(fields_start, directory->fields_size,
                           sizeof(dex::FieldAnnotationsItem), file_size, "field annotations",
                           &fields_bytes)) {
        LOGW("Invalid DEX annotations directory: offset=%u file_size=%zu", offset, file_size);
        return false;
    }
    auto methods_start = fields_start + fields_bytes;
    size_t methods_bytes;
    if (!checkedTableBytes(methods_start, directory->methods_size,
                           sizeof(dex::MethodAnnotationsItem), file_size, "method annotations",
                           &methods_bytes)) {
        LOGW("Invalid DEX annotations directory: offset=%u file_size=%zu", offset, file_size);
        return false;
    }
    auto parameters_start = methods_start + methods_bytes;
    size_t parameters_bytes;
    if (!checkedTableBytes(parameters_start, directory->parameters_size,
                           sizeof(dex::ParameterAnnotationsItem), file_size,
                           "parameter annotations", &parameters_bytes)) {
        LOGW("Invalid DEX annotations directory: offset=%u file_size=%zu", offset, file_size);
        return false;
    }

    if (directory->class_annotations_off != 0 &&
        !annotationSetFits(base, header, file_size, directory->class_annotations_off)) {
        return false;
    }

    const auto *field_annotations =
        reinterpret_cast<const dex::FieldAnnotationsItem *>(base + fields_start);
    for (dex::u4 i = 0; i < directory->fields_size; ++i) {
        if (field_annotations[i].field_idx >= header->field_ids_size ||
            field_annotations[i].annotations_off == 0 ||
            !annotationSetFits(base, header, file_size, field_annotations[i].annotations_off)) {
            LOGW("Invalid DEX field annotation at index %u", i);
            return false;
        }
    }

    const auto *method_annotations = reinterpret_cast<const dex::MethodAnnotationsItem *>(
        reinterpret_cast<const dex::u1 *>(field_annotations) + fields_bytes);
    for (dex::u4 i = 0; i < directory->methods_size; ++i) {
        if (method_annotations[i].method_idx >= header->method_ids_size ||
            method_annotations[i].annotations_off == 0 ||
            !annotationSetFits(base, header, file_size, method_annotations[i].annotations_off)) {
            LOGW("Invalid DEX method annotation at index %u", i);
            return false;
        }
    }

    const auto *param_annotations = reinterpret_cast<const dex::ParameterAnnotationsItem *>(
        reinterpret_cast<const dex::u1 *>(method_annotations) + methods_bytes);
    for (dex::u4 i = 0; i < directory->parameters_size; ++i) {
        if (param_annotations[i].method_idx >= header->method_ids_size ||
            param_annotations[i].annotations_off == 0 ||
            !annotationSetRefListFits(base, header, file_size, param_annotations[i].annotations_off)) {
            LOGW("Invalid DEX parameter annotation at index %u", i);
            return false;
        }
    }

    return true;
}

static bool debugInfoIndexFits(dex::u4 index_plus_one, dex::u4 count, const char *name) {
    if (index_plus_one == 0) return true;
    dex::u4 index = index_plus_one - 1;
    if (index >= count) {
        LOGW("Invalid DEX debug info %s: index=%u count=%u", name, index, count);
        return false;
    }
    return true;
}

static bool checkedTableBytes(size_t start, dex::u4 count, size_t item_size, size_t file_size,
                              const char *name, size_t *bytes) {
    if (start > file_size || count > (file_size - start) / item_size) {
        LOGW("Invalid DEX %s table: start=%zu count=%u item_size=%zu file_size=%zu",
             name, start, count, item_size, file_size);
        return false;
    }
    *bytes = static_cast<size_t>(count) * item_size;
    return true;
}

static bool debugInfoFits(const dex::u1 *base, const dex::Header *header, size_t file_size,
                          dex::u4 offset) {
    if (offset == 0) return true;
    if (!dataRangeFits(header, file_size, offset, sizeof(dex::u1), "debug info", false)) {
        return false;
    }
    const dex::u1 *ptr = base + offset;
    const dex::u1 *limit = base + file_size;
    dex::u4 value;
    if (!readULeb128Checked(&ptr, limit, &value, "debug line_start") ||
        !readULeb128Checked(&ptr, limit, &value, "debug parameters_size")) {
        return false;
    }
    dex::u4 parameters_size = value;
    for (dex::u4 i = 0; i < parameters_size; ++i) {
        dex::u4 name_index;
        if (!readULeb128Checked(&ptr, limit, &name_index, "debug parameter name") ||
            !debugInfoIndexFits(name_index, header->string_ids_size, "parameter name")) {
            return false;
        }
    }

    while (true) {
        if (ptr >= limit) {
            LOGW("Invalid DEX debug info: missing end sequence");
            return false;
        }
        dex::u1 opcode = *ptr++;
        switch (opcode) {
            case dex::DBG_END_SEQUENCE:
                return true;
            case dex::DBG_ADVANCE_PC:
                if (!readULeb128Checked(&ptr, limit, &value, "debug advance pc")) return false;
                break;
            case dex::DBG_ADVANCE_LINE: {
                dex::s4 line_diff;
                if (!readSLeb128Checked(&ptr, limit, &line_diff, "debug advance line")) {
                    return false;
                }
                break;
            }
            case dex::DBG_START_LOCAL: {
                dex::u4 register_num;
                dex::u4 name_index;
                dex::u4 type_index;
                if (!readULeb128Checked(&ptr, limit, &register_num, "debug local register") ||
                    !readULeb128Checked(&ptr, limit, &name_index, "debug local name") ||
                    !debugInfoIndexFits(name_index, header->string_ids_size, "local name") ||
                    !readULeb128Checked(&ptr, limit, &type_index, "debug local type") ||
                    !debugInfoIndexFits(type_index, header->type_ids_size, "local type")) {
                    return false;
                }
                break;
            }
            case dex::DBG_START_LOCAL_EXTENDED: {
                dex::u4 register_num;
                dex::u4 name_index;
                dex::u4 type_index;
                dex::u4 sig_index;
                if (!readULeb128Checked(&ptr, limit, &register_num, "debug local register") ||
                    !readULeb128Checked(&ptr, limit, &name_index, "debug local name") ||
                    !debugInfoIndexFits(name_index, header->string_ids_size, "local name") ||
                    !readULeb128Checked(&ptr, limit, &type_index, "debug local type") ||
                    !debugInfoIndexFits(type_index, header->type_ids_size, "local type") ||
                    !readULeb128Checked(&ptr, limit, &sig_index, "debug local signature") ||
                    !debugInfoIndexFits(sig_index, header->string_ids_size, "local signature")) {
                    return false;
                }
                break;
            }
            case dex::DBG_END_LOCAL:
            case dex::DBG_RESTART_LOCAL:
                if (!readULeb128Checked(&ptr, limit, &value, "debug local register")) {
                    return false;
                }
                break;
            case dex::DBG_SET_FILE:
                if (!readULeb128Checked(&ptr, limit, &value, "debug source file") ||
                    !debugInfoIndexFits(value, header->string_ids_size, "source file")) {
                    return false;
                }
                break;
            default:
                break;
        }
    }
}

static bool instructionIndexFits(const dex::Header *header, dex::InstructionIndexType type,
                                 dex::u4 index, dex::u4 index2, const char *opcode) {
    switch (type) {
        case dex::kIndexNone:
        case dex::kIndexInlineMethod:
        case dex::kIndexVtableOffset:
        case dex::kIndexFieldOffset:
            return true;
        case dex::kIndexStringRef:
            if (index < header->string_ids_size) return true;
            break;
        case dex::kIndexTypeRef:
            if (index < header->type_ids_size) return true;
            break;
        case dex::kIndexFieldRef:
            if (index < header->field_ids_size) return true;
            break;
        case dex::kIndexMethodRef:
            if (index < header->method_ids_size) return true;
            break;
        case dex::kIndexProtoRef:
            if (index < header->proto_ids_size) return true;
            break;
        case dex::kIndexMethodAndProtoRef:
            if (index < header->method_ids_size && index2 < header->proto_ids_size) return true;
            break;
        default:
            break;
    }

    LOGW("Invalid DEX bytecode reference: opcode=%s index=%u index2=%u type=%u",
         opcode, index, index2, type);
    return false;
}

static bool instructionsFit(const dex::Header *header, const dex::u2 *insns,
                            dex::u4 insns_size) {
    dex::u4 cursor = 0;
    while (cursor < insns_size) {
        const dex::u2 *ptr = insns + cursor;
        size_t remaining = static_cast<size_t>(insns_size - cursor);
        size_t width;
        if (*ptr == dex::kPackedSwitchSignature) {
            if (remaining < 2) {
                LOGW("Invalid DEX packed-switch payload header: cursor=%u", cursor);
                return false;
            }
            width = 4 + static_cast<size_t>(ptr[1]) * 2;
        } else if (*ptr == dex::kSparseSwitchSignature) {
            if (remaining < 2) {
                LOGW("Invalid DEX sparse-switch payload header: cursor=%u", cursor);
                return false;
            }
            width = 2 + static_cast<size_t>(ptr[1]) * 4;
        } else if (*ptr == dex::kArrayDataSignature) {
            if (remaining < 4) {
                LOGW("Invalid DEX array-data payload header: cursor=%u", cursor);
                return false;
            }
            dex::u4 length = ptr[2] | (static_cast<dex::u4>(ptr[3]) << 16);
            auto element_width = static_cast<size_t>(ptr[1]);
            if (element_width == 0 ||
                (length != 0 &&
                 element_width > (std::numeric_limits<size_t>::max() - 1) / length)) {
                LOGW("Invalid DEX array-data payload size: width=%zu length=%u",
                     element_width, length);
                return false;
            }
            auto data_bytes = element_width * static_cast<size_t>(length);
            auto data_units = (data_bytes + 1) / 2;
            if (data_units > remaining - 4) {
                LOGW("Invalid DEX array-data payload bounds: cursor=%u units=%zu remaining=%zu",
                     cursor, data_units, remaining);
                return false;
            }
            width = 4 + data_units;
        } else {
            width = dex::GetWidthFromFormat(dex::GetFormatFromOpcode(dex::OpcodeFromBytecode(*ptr)));
        }
        if (width == 0 || width > insns_size - cursor) {
            LOGW("Invalid DEX bytecode width: cursor=%u width=%zu insns_size=%u",
                 cursor, width, insns_size);
            return false;
        }
        if (*ptr == dex::kPackedSwitchSignature ||
            *ptr == dex::kSparseSwitchSignature ||
            *ptr == dex::kArrayDataSignature) {
            cursor += static_cast<dex::u4>(width);
            continue;
        }

        auto opcode = dex::OpcodeFromBytecode(*ptr);
        if ((dex::GetVerifyFlagsFromOpcode(opcode) & dex::kVerifyError) != 0) {
            LOGW("Invalid DEX bytecode opcode: opcode=%s", dex::GetOpcodeName(opcode));
            return false;
        }

        dex::u4 index = dex::kNoIndex;
        dex::u4 index2 = dex::kNoIndex;
        auto instruction = dex::DecodeInstruction(ptr);
        switch (dex::GetFormatFromOpcode(instruction.opcode)) {
            case dex::k20bc:
            case dex::k21c:
            case dex::k31c:
            case dex::k35c:
            case dex::k3rc:
                index = instruction.vB;
                break;
            case dex::k45cc:
            case dex::k4rcc:
                index = instruction.vB;
                index2 = instruction.arg[4];
                break;
            case dex::k22c:
                index = instruction.vC;
                break;
            default:
                break;
        }
        if (!instructionIndexFits(header, dex::GetIndexTypeFromOpcode(instruction.opcode),
                                  index, index2, dex::GetOpcodeName(instruction.opcode))) {
            return false;
        }
        cursor += static_cast<dex::u4>(width);
    }
    return cursor == insns_size;
}

static bool codeItemFits(const dex::u1 *base, const dex::Header *header, size_t file_size,
                         dex::u4 offset) {
    if (offset == 0) return true;
    if (!isAligned(offset, 4) ||
        !dataRangeFits(header, file_size, offset, offsetof(dex::Code, insns), "method code",
                       false)) {
        return false;
    }
    const auto *code = reinterpret_cast<const dex::Code *>(base + offset);
    auto insns_start = static_cast<size_t>(offset) + offsetof(dex::Code, insns);
    if (insns_start > file_size ||
        code->insns_size > (file_size - insns_start) / sizeof(dex::u2)) {
        LOGW("Invalid DEX method code: offset=%u insns_size=%u file_size=%zu", offset,
             code->insns_size, file_size);
        return false;
    }
    if (!instructionsFit(header, code->insns, code->insns_size)) {
        return false;
    }
    if (!debugInfoFits(base, header, file_size, code->debug_info_off)) {
        return false;
    }
    if (code->tries_size != 0) {
        auto aligned_insns = (static_cast<size_t>(code->insns_size) + 1) / 2 * 2;
        auto tries_start = insns_start + aligned_insns * sizeof(dex::u2);
        if (tries_start > file_size ||
            code->tries_size > (file_size - tries_start) / sizeof(dex::TryBlock)) {
            LOGW("Invalid DEX method code tries: offset=%u tries_size=%u file_size=%zu", offset,
                 code->tries_size, file_size);
            return false;
        }
        auto handlers_start = tries_start + static_cast<size_t>(code->tries_size) * sizeof(dex::TryBlock);
        if (handlers_start >= file_size) {
            LOGW("Invalid DEX method code handlers: offset=%u file_size=%zu", offset, file_size);
            return false;
        }
        const auto *tries = reinterpret_cast<const dex::TryBlock *>(base + tries_start);
        const dex::u1 *handlers = base + handlers_start;
        const dex::u1 *ptr = handlers;
        const dex::u1 *limit = base + file_size;
        dex::u4 handlers_count;
        if (!readULeb128Checked(&ptr, limit, &handlers_count, "catch handler list") ||
            handlers_count > code->tries_size) {
            LOGW("Invalid DEX catch handler list: handlers=%u tries=%u", handlers_count,
                 code->tries_size);
            return false;
        }
        for (dex::u4 handler_index = 0; handler_index < handlers_count; ++handler_index) {
            dex::s4 catch_count;
            if (!readSLeb128Checked(&ptr, limit, &catch_count, "catch handler size")) {
                return false;
            }
            auto typed_catches = catch_count < 0 ? -static_cast<int64_t>(catch_count)
                                                 : static_cast<int64_t>(catch_count);
            for (int64_t catch_index = 0; catch_index < typed_catches; ++catch_index) {
                dex::u4 type_index;
                dex::u4 address;
                if (!readULeb128Checked(&ptr, limit, &type_index, "catch handler type") ||
                    type_index >= header->type_ids_size ||
                    !readULeb128Checked(&ptr, limit, &address, "catch handler address") ||
                    address > code->insns_size) {
                    LOGW("Invalid DEX catch handler entry: type_idx=%u type_count=%u", type_index,
                         header->type_ids_size);
                    return false;
                }
            }
            if (catch_count <= 0) {
                dex::u4 catch_all_addr;
                if (!readULeb128Checked(&ptr, limit, &catch_all_addr, "catch-all handler") ||
                    catch_all_addr > code->insns_size) {
                    return false;
                }
            }
        }
        auto handlers_size = static_cast<size_t>(ptr - handlers);
        for (dex::u4 i = 0; i < code->tries_size; ++i) {
            if (tries[i].start_addr > code->insns_size ||
                tries[i].insn_count > code->insns_size - tries[i].start_addr ||
                tries[i].handler_off >= handlers_size) {
                LOGW("Invalid DEX try block at index %u", i);
                return false;
            }
        }
    }
    return true;
}

static bool classDataFits(const dex::u1 *base, const dex::Header *header, size_t file_size,
                          dex::u4 offset) {
    if (offset == 0) return true;
    if (!dataRangeFits(header, file_size, offset, sizeof(dex::u1), "class data", false)) {
        return false;
    }

    const dex::u1 *ptr = base + offset;
    const dex::u1 *limit = base + file_size;
    dex::u4 static_fields_count;
    dex::u4 instance_fields_count;
    dex::u4 direct_methods_count;
    dex::u4 virtual_methods_count;
    if (!readULeb128Checked(&ptr, limit, &static_fields_count, "class static_fields_count") ||
        !readULeb128Checked(&ptr, limit, &instance_fields_count, "class instance_fields_count") ||
        !readULeb128Checked(&ptr, limit, &direct_methods_count, "class direct_methods_count") ||
        !readULeb128Checked(&ptr, limit, &virtual_methods_count, "class virtual_methods_count")) {
        return false;
    }

    auto validate_fields = [&](dex::u4 count, const char *name) {
        dex::u4 base_index = dex::kNoIndex;
        for (dex::u4 i = 0; i < count; ++i) {
            dex::u4 field_idx_delta;
            dex::u4 access_flags;
            if (!readULeb128Checked(&ptr, limit, &field_idx_delta, name) ||
                !applyMemberIndexDelta(field_idx_delta, &base_index, header->field_ids_size, name) ||
                !readULeb128Checked(&ptr, limit, &access_flags, name)) {
                return false;
            }
        }
        return true;
    };

    auto validate_methods = [&](dex::u4 count, const char *name) {
        dex::u4 base_index = dex::kNoIndex;
        for (dex::u4 i = 0; i < count; ++i) {
            dex::u4 method_idx_delta;
            dex::u4 access_flags;
            dex::u4 code_off;
            if (!readULeb128Checked(&ptr, limit, &method_idx_delta, name) ||
                !applyMemberIndexDelta(method_idx_delta, &base_index, header->method_ids_size,
                                       name) ||
                !readULeb128Checked(&ptr, limit, &access_flags, name) ||
                !readULeb128Checked(&ptr, limit, &code_off, name) ||
                !codeItemFits(base, header, file_size, code_off)) {
                return false;
            }
        }
        return true;
    };

    return validate_fields(static_fields_count, "class static field") &&
           validate_fields(instance_fields_count, "class instance field") &&
           validate_methods(direct_methods_count, "class direct method") &&
           validate_methods(virtual_methods_count, "class virtual method");
}

// Slicer's own structural checks compile out under NDEBUG, so validate the
// table ranges and indexed references that CreateFullIr() will touch first.
// On success, *out_file_size receives the validated header file_size so the
// caller can feed slicer without re-reading the (potentially untrusted) header.
static bool isDexSafeForSlicer(const void *dex_data, size_t mapped_size,
                               size_t *out_file_size) {
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
        if (!stringDataFits(base, header, file_size, string_ids[i].string_data_off)) {
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
             !annotationsDirectoryFits(base, header, file_size, class_def.annotations_off) ||
             !classDataFits(base, header, file_size, class_def.class_data_off) ||
             !encodedArrayItemFits(base, header, file_size, class_def.static_values_off,
                                   "static values")) {
            LOGW("Invalid DEX class_def at index %u", i);
            return false;
        }
    }

    if (out_file_size != nullptr) *out_file_size = file_size;
    return true;
}

static jobject wrapSharedMemoryFd(JNIEnv *env, int fd) {
    auto java_fd =
        lsplant::JNI_NewObject(env, class_file_descriptor, method_file_descriptor_ctor, fd);
    if (!java_fd) {
        LOGE("Failed to construct FileDescriptor for fd=%d", fd);
        close(fd);
        return nullptr;
    }
    auto java_sm =
        lsplant::JNI_NewObject(env, class_shared_memory, method_shared_memory_ctor, java_fd);
    if (!java_sm) {
        LOGE("Failed to construct SharedMemory for fd=%d", fd);
        close(fd);
        return nullptr;
    }
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

static int obfuscateDexBuffer(void *dex_data, size_t size) {
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
    if (image == nullptr) {
        auto output_fd = allocator.GetFd();
        if (output_fd >= 0) close(output_fd);
        return -1;
    }

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

    void *mem = mmap(nullptr, mapped_size, PROT_READ, MAP_SHARED, fd, 0);
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

    size_t dex_file_size = 0;
    if (!isDexSafeForSlicer(mem, mapped_size, &dex_file_size)) {
        LOGW("Skipping DEX obfuscation for malformed input fd=%d", fd);
        munmap(mem, mapped_size);
        return returnOriginalSharedMemory(memory, fd);
    }

    auto mutable_dex = std::unique_ptr<dex::u1[]>(new (std::nothrow) dex::u1[dex_file_size]);
    if (mutable_dex == nullptr) {
        LOGE("Failed to allocate private dex obfuscation buffer, size=%zu", dex_file_size);
        munmap(mem, mapped_size);
        return returnOriginalSharedMemory(memory, fd);
    }
    memcpy(mutable_dex.get(), mem, dex_file_size);

    // Slicer mutates string storage through the IR, so run it on a private copy.
    int new_fd = obfuscateDexBuffer(mutable_dex.get(), dex_file_size);

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

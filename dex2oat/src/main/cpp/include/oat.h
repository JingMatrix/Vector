/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ART_RUNTIME_OAT_OAT_H_
#define ART_RUNTIME_OAT_OAT_H_

#include <array>
#include <cstddef>
#include <string_view>
#include <utility>

#include "base_macros.h"
#include "macros.h"

namespace art {

enum class InstructionSet;

class EXPORT PACKED(4) OatHeader {
public:
    static constexpr std::array<uint8_t, 4> kOatMagic{{'o', 'a', 't', '\n'}};
    // Last oat version changed reason: Ensure oat checksum determinism across hosts and devices.
    static constexpr std::array<uint8_t, 4> kOatVersion{{'2', '5', '9', '\0'}};

    static constexpr const char* kDex2OatCmdLineKey = "dex2oat-cmdline";
    static constexpr const char* kDebuggableKey = "debuggable";
    static constexpr const char* kNativeDebuggableKey = "native-debuggable";
    static constexpr const char* kCompilerFilter = "compiler-filter";
    static constexpr const char* kClassPathKey = "classpath";
    static constexpr const char* kBootClassPathKey = "bootclasspath";
    static constexpr const char* kBootClassPathChecksumsKey = "bootclasspath-checksums";
    static constexpr const char* kApexVersionsKey = "apex-versions";
    static constexpr const char* kConcurrentCopying = "concurrent-copying";
    static constexpr const char* kCompilationReasonKey = "compilation-reason";
    static constexpr const char* kRequiresImage = "requires-image";

    // Fields listed here are key value store fields that are deterministic across hosts and
    // devices, meaning they should have exactly the same value when the oat file is generated on
    // different hosts and devices for the same app / boot image and for the same device model with
    // the same compiler options. If you are adding a new field that doesn't hold this property, put
    // it in `kNonDeterministicFieldsAndLengths` and assign a length limit.
    //
    // When writing the oat header, the non-deterministic fields are padded to their length limits
    // and excluded from the oat checksum computation. This makes the oat checksum deterministic
    // across hosts and devices, which is important for Cloud Compilation, where we generate an oat
    // file on a host and use it on a device.
    static constexpr std::array<std::string_view, 9> kDeterministicFields{
        kDebuggableKey,     kNativeDebuggableKey,  kCompilerFilter,
        kClassPathKey,      kBootClassPathKey,     kBootClassPathChecksumsKey,
        kConcurrentCopying, kCompilationReasonKey, kRequiresImage,
    };

    static constexpr std::array<std::pair<std::string_view, size_t>, 2>
        kNonDeterministicFieldsAndLengths{
            std::make_pair(kDex2OatCmdLineKey, 2048),
            std::make_pair(kApexVersionsKey, 1024),
        };

    static constexpr const char kTrueValue[] = "true";
    static constexpr const char kFalseValue[] = "false";

    // Added helper to access the key_value_store_ field, which could be fragile across
    // different Android versions and compiler optimizations.
    const uint8_t* getKeyValueStore() const { return key_value_store_; }

    void ComputeChecksum(/*inout*/ uint32_t* checksum) const;

private:
    std::array<uint8_t, 4> magic_;
    std::array<uint8_t, 4> version_;
    uint32_t oat_checksum_;

    InstructionSet instruction_set_;
    uint32_t instruction_set_features_bitmap_;
    uint32_t dex_file_count_;
    uint32_t oat_dex_files_offset_;
    uint32_t bcp_bss_info_offset_;
    // Offset of the oat header (i.e. start of the oat data) in the ELF file.
    // It is used to additional validation of the oat header as it is not
    // page-aligned in the memory.
    uint32_t base_oat_offset_;
    uint32_t executable_offset_;
    uint32_t jni_dlsym_lookup_trampoline_offset_;
    uint32_t jni_dlsym_lookup_critical_trampoline_offset_;
    uint32_t quick_generic_jni_trampoline_offset_;
    uint32_t quick_imt_conflict_trampoline_offset_;
    uint32_t quick_resolution_trampoline_offset_;
    uint32_t quick_to_interpreter_bridge_offset_;
    uint32_t nterp_trampoline_offset_;

    uint32_t key_value_store_size_;
    uint8_t key_value_store_[0];  // note variable width data at end

    DISALLOW_COPY_AND_ASSIGN(OatHeader);
};
}  // namespace art
#endif  // ART_RUNTIME_OAT_OAT_H_

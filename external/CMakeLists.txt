cmake_minimum_required(VERSION 3.10)

project(external)

set(CMAKE_CXX_STANDARD 23)

set(XZ_SOURCES
	xz_crc32.c
	xz_crc64.c
	# xz_dec_bcj.c
	xz_dec_lzma2.c
	xz_dec_stream.c)
set(XZ_INCLUDES xz-embedded/linux/include/linux xz-embedded/userspace)
list(TRANSFORM XZ_SOURCES PREPEND xz-embedded/linux/lib/xz/)
add_library(xz_static STATIC ${XZ_SOURCES})
target_compile_options(xz_static PRIVATE -DXZ_USE_CRC64)
target_include_directories(xz_static PRIVATE ${XZ_INCLUDES})

option(LSPLANT_BUILD_SHARED OFF)
option(Plugin.SymbolResolver OFF)
option(FMT_INSTALL OFF)

if (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    enable_language(ASM)
    set(SHADOWHOOK_SRC ${CMAKE_CURRENT_SOURCE_DIR}/shadowhook/shadowhook/src/main/cpp)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(SHADOWHOOK_ARCH "arm64")
    else ()
        set(SHADOWHOOK_ARCH "arm")
    endif ()
    file(GLOB SHADOWHOOK_SOURCES
        ${SHADOWHOOK_SRC}/*.c
        ${SHADOWHOOK_SRC}/arch/${SHADOWHOOK_ARCH}/*.c
        ${SHADOWHOOK_SRC}/arch/${SHADOWHOOK_ARCH}/*.S
        ${SHADOWHOOK_SRC}/common/*.c
        ${SHADOWHOOK_SRC}/third_party/*/*.c
    )
	list(REMOVE_ITEM SHADOWHOOK_SOURCES ${SHADOWHOOK_SRC}/sh_linker.c)
    list(APPEND SHADOWHOOK_SOURCES
        ${CMAKE_CURRENT_SOURCE_DIR}/patches/shadowhook/sh_linker.c)
    add_library(shadowhook STATIC ${SHADOWHOOK_SOURCES})
    target_include_directories(shadowhook PUBLIC
        ${SHADOWHOOK_SRC}
        ${SHADOWHOOK_SRC}/include
        ${SHADOWHOOK_SRC}/arch/${SHADOWHOOK_ARCH}
        ${SHADOWHOOK_SRC}/common
        ${SHADOWHOOK_SRC}/third_party/xdl
        ${SHADOWHOOK_SRC}/third_party/bsd
        ${SHADOWHOOK_SRC}/third_party/lss
    )
    target_compile_options(shadowhook PRIVATE
        -Wno-unused-parameter
        -Wno-unused-but-set-variable
        -Wno-unused-variable
        -Wno-unused-function
    )
endif ()

add_subdirectory(dobby)
add_subdirectory(fmt)
add_subdirectory(lsplant/lsplant/src/main/jni)
target_compile_options(lsplant_static PUBLIC -Wno-gnu-anonymous-struct
	# lsplant.cc's `operator""_uarr()` is a string literal operator template, which is a GNU
	# extension clang warns about under -Wpedantic. Upstream code we do not own, and the
	# warning was printed once per ABI on every build.
	-Wno-gnu-string-literal-operator-template)
target_compile_definitions(fmt-header-only INTERFACE FMT_USE_LOCALE=0 FMT_USE_FLOAT=0 FMT_USE_DOUBLE=0 FMT_USE_LONG_DOUBLE=0 FMT_USE_BITINT=0)

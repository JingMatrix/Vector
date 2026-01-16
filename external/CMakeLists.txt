cmake_minimum_required(VERSION 3.10)

project(external)

set(XZ_SOURCES
	xz_crc32.c
	xz_crc64.c
	# xz_dec_bcj.c
	xz_dec_lzma2.c
	xz_dec_stream.c)
list(TRANSFORM XZ_SOURCES PREPEND xz-embedded/linux/lib/xz/)
set(XZ_INCLUDES xz-embedded/linux/include/linux xz-embedded/userspace)
add_library(xz_static STATIC ${XZ_SOURCES})
target_compile_options(xz_static PRIVATE -DXZ_USE_CRC64)
target_include_directories(xz_static PRIVATE ${XZ_INCLUDES})

option(LSPLANT_BUILD_SHARED OFF)
option(Plugin.SymbolResolver OFF)
option(FMT_INSTALL OFF)

add_subdirectory(dobby)
add_subdirectory(fmt)
add_subdirectory(lsplant/lsplant/src/main/jni)
target_compile_options(lsplant_static PUBLIC -Wno-gnu-anonymous-struct)
target_compile_definitions(fmt-header-only INTERFACE FMT_USE_LOCALE=0 FMT_USE_FLOAT=0 FMT_USE_DOUBLE=0 FMT_USE_LONG_DOUBLE=0 FMT_USE_BITINT=0)

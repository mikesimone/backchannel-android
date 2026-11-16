# Backchannel's offline speech engine: whisper.cpp (vendored, see ../../../README.md) plus a
# small JNI bridge. Built by Gradle (externalNativeBuild) for every ABI the app ships.
cmake_minimum_required(VERSION 3.22)
project(backchannel_whisper C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../whisper.cpp)

# Debug app builds still need a usable engine: whisper at -O0 is far too slow to try out.
foreach(lang C CXX)
  set(CMAKE_${lang}_FLAGS_DEBUG "-O2 -g")
  # Reproducible builds: no absolute build paths in the binaries.
  set(CMAKE_${lang}_FLAGS "${CMAKE_${lang}_FLAGS} -ffile-prefix-map=${WHISPER_DIR}=whisper.cpp -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=cpp")
endforeach()

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

# whisper.cpp: library only.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)

# ggml: CPU only, no OpenMP (ggml's own thread pool), never tuned for the build machine.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

# The CPU backend is loaded at run time (ggml_backend_load_all_from_path on the app's native
# library directory). On arm64 ggml builds one backend per SoC generation (armv8.0, armv8.2 +
# dotprod, + fp16, armv8.6 + i8mm, armv9 ...) and picks the best one the phone supports, so
# old phones work and new ones are fast.
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
  set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
elseif (ANDROID_ABI STREQUAL "x86_64")
  # The x86_64 Android ABI guarantees SSE4.2, nothing more (emulators, Chromebooks).
  foreach(feat AVX AVX2 AVX_VNNI AVX512 AVX512_VBMI AVX512_VNNI AVX512_BF16 FMA F16C BMI2 AMX_TILE AMX_INT8 AMX_BF16)
    set(GGML_${feat} OFF CACHE BOOL "" FORCE)
  endforeach()
  set(GGML_SSE42 ON CACHE BOOL "" FORCE)
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
  # 32-bit ARM phones: NEON with fp16 conversion (every Android 13 capable 32-bit ARM core has it).
  add_compile_options(-mfpu=neon-vfpv4)
endif()

add_subdirectory(${WHISPER_DIR} whisper.cpp EXCLUDE_FROM_ALL)

add_library(backchannel_whisper SHARED whisper_jni.cpp)
target_link_libraries(backchannel_whisper PRIVATE whisper ggml log)
target_compile_options(backchannel_whisper PRIVATE -fvisibility=hidden)

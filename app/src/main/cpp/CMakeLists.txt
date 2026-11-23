# Native analysis front end for Automix.
#
# Phase 1: whole-track DSP analysis (tempo/key/energy/structure via
# autocorrelation, no ML model). Phase 2 adds the log-mel front end the
# Beat This! ONNX beat/downbeat model reads (mel_spectrogram.cpp / mel_jni.cpp).
# Phase 3 adds the linear-frequency STFT the open-unmix vocal-separation model
# reads (vocal_spectrogram.cpp / vocal_jni.cpp). Both models themselves run
# through ONNX Runtime on the Kotlin side, not here. The shared analyzer
# sources live under the repo's root `native/` tree rather than here, so a
# later phase adding a Rubber Band-based transition renderer can add sources
# to that same tree without duplicating this module.

cmake_minimum_required(VERSION 3.22.1)
project(bitchord_analysis LANGUAGES C CXX)

get_filename_component(BITCHORD_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native"
    ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(bitchord_analysis SHARED
    ${BITCHORD_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/resampler.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(bitchord_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${BITCHORD_NATIVE_DIR}
)

# -ffast-math is deliberately NOT enabled: the analyzer's log/mel-adjacent
# arithmetic (see audio_analysis.cpp) is tuned against exact IEEE behaviour.
target_compile_options(bitchord_analysis PRIVATE -O3 -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(bitchord_analysis ${log-lib})

# 16KB page-size support (Android 15+ 16KB kernels, Play requirement): align
# LOAD segments to 16KB. A superset of 4KB alignment, so 4KB devices are
# unaffected. APK-side alignment of uncompressed .so files is handled by AGP.
target_link_options(bitchord_analysis PRIVATE "-Wl,-z,max-page-size=16384")

# Automix's loop mix: two Signalsmith Stretch decks looping whole bars on a
# ramping tempo, rendered inside the outgoing player's audio sink. Signalsmith
# Stretch and its FFT (signalsmith-linear) are MIT and header-only, vendored
# under native/third_party. Fast-math is safe here (nothing in it depends on
# exact IEEE behaviour) and lets the STFT's complex arithmetic vectorise.
add_library(bitchord_loopmix SHARED
    ${BITCHORD_NATIVE_DIR}/loopmix/loop_mix_renderer.cpp
    jni/loopmix_jni.cpp
)
target_include_directories(bitchord_loopmix PRIVATE
    ${BITCHORD_NATIVE_DIR}
    ${BITCHORD_NATIVE_DIR}/third_party
)
target_compile_options(bitchord_loopmix PRIVATE -O3 -ffast-math -fvisibility=hidden -Wno-unused-parameter)
target_link_options(bitchord_loopmix PRIVATE "-Wl,-z,max-page-size=16384")

# DSD playback (DSF/DFF). The decimator turns 1-bit DSD into float PCM for
# DsdExtractor; DST is the lossless compression SACD rips use inside DFF.
# DST is LGPL (see dsd/dst/NOTICE.md), so it is a library of its own that can
# be rebuilt or swapped without touching the rest.
add_library(bitchord_dsd SHARED dsd/dsd_decimator.cpp)
target_compile_options(bitchord_dsd PRIVATE -O3 -fno-fast-math -ffp-contract=off -fvisibility=hidden)
target_link_options(bitchord_dsd PRIVATE "-Wl,-z,max-page-size=16384")

add_library(bitchord_dst SHARED dsd/dst/decoder.c dsd/dst/jni.cpp)
target_compile_options(bitchord_dst PRIVATE -O3 -fno-strict-aliasing -fvisibility=hidden)
target_link_options(bitchord_dst PRIVATE "-Wl,-z,max-page-size=16384")

# JamesDSP's native engine, running in-process on BitChord's own Float32
# playback stream. See jamesdsp/CMakeLists.txt and JamesDspProcessor.kt.
add_subdirectory(jamesdsp)

# Bit-perfect output on a built-in DAC: an AAudio exclusive (MMAP) stream
# through Oboe, built from a pinned release with its format and rate
# conversion compiled out, so a stream that would have needed converting is
# one this library rejects. Oboe is Apache-2.0 and fetched at configure time
# (pinned by SHA-256); exclusive_output.cpp is adapted from Mornye
# (LGPL-3.0-only, see the file's header and bitperfect/README.md).
include(FetchContent)
set(OBOE_DISABLE_CONVERSION ON CACHE BOOL "" FORCE)
FetchContent_Declare(oboe
    URL https://github.com/google/oboe/archive/refs/tags/1.11.0.tar.gz
    URL_HASH SHA256=0ccf2110640a2b4489f1c93a404f2978656b434d8c5dc9cb6258f9cc95c79b15)
FetchContent_MakeAvailable(oboe)

add_library(bitchord_exclusive SHARED bitperfect/exclusive_output.cpp)
target_compile_options(bitchord_exclusive PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_link_libraries(bitchord_exclusive PRIVATE oboe ${log-lib})
# Only the JNI entry points are exported; Oboe is linked statically and its
# unused code is dropped rather than kept alive by exported symbols.
target_link_options(bitchord_exclusive PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")
set_property(TARGET bitchord_exclusive APPEND PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")

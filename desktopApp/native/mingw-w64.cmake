# Cross-building the Automix analyser for Windows from a Linux host.
#
# The sources are platform-neutral C++17 and plain JNI, so the only thing
# Windows-specific about the build is the toolchain and one header: `jni.h` is
# the same file everywhere, but it includes `jni_md.h`, which is per-platform
# and which a Linux JDK does not carry a Windows copy of. See
# `native/win32/jni_md.h`.
#
# Everything is linked statically so the result depends on nothing but the
# system DLLs — a portable archive that also had to carry libstdc++-6.dll and
# libgcc_s_seh-1.dll beside it would be a portable archive with a footnote.
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

# The POSIX threading model, where the toolchain offers one. The win32 model ships no <mutex>
# and no <thread> — the Windows libraries here use both — and Debian's alternatives point at it
# by default, so a cross-build that took the bare name failed to compile at the first lock.
# A compiler named on the command line wins: the build may have found a toolchain this file
# cannot see.
if(NOT CMAKE_CXX_COMPILER)
    find_program(BITCHORD_MINGW_CXX NAMES x86_64-w64-mingw32-g++-posix x86_64-w64-mingw32-g++)
    if(NOT BITCHORD_MINGW_CXX)
        message(FATAL_ERROR "No MinGW-w64 C++ compiler found; install mingw-w64.")
    endif()
    message(STATUS "BitChord cross compiler: ${BITCHORD_MINGW_CXX}")
    set(CMAKE_CXX_COMPILER ${BITCHORD_MINGW_CXX})
endif()
if(NOT CMAKE_C_COMPILER)
    find_program(BITCHORD_MINGW_CC NAMES x86_64-w64-mingw32-gcc-posix x86_64-w64-mingw32-gcc)
    set(CMAKE_C_COMPILER ${BITCHORD_MINGW_CC})
endif()
set(CMAKE_RC_COMPILER x86_64-w64-mingw32-windres)

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)

set(CMAKE_EXE_LINKER_FLAGS_INIT "-static -static-libgcc -static-libstdc++")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-static -static-libgcc -static-libstdc++")

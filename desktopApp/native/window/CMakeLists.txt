cmake_minimum_required(VERSION 3.16)

project(auris_window LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

foreach(suffix "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZEREL)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
endforeach()

add_library(auris_window SHARED window_jni.cpp)
set_target_properties(auris_window PROPERTIES PREFIX "")

if(DEFINED ENV{JAVA_HOME})
    target_include_directories(auris_window PRIVATE
        $ENV{JAVA_HOME}/include
        $ENV{JAVA_HOME}/include/win32
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(auris_window PRIVATE ${JNI_INCLUDE_DIRS})
endif()

# Cross-building from a Linux host, the one case where the JDK running the build has no header
# for the platform being built: `jni.h` is shared, `jni_md.h` is not. The vendored copy beside
# this project is the Windows one; listed after the JDK's own paths so a real Windows JDK wins.
if(CMAKE_SYSTEM_NAME STREQUAL "Windows" AND NOT CMAKE_HOST_WIN32)
    target_include_directories(auris_window PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../win32)
endif()

target_link_libraries(auris_window PRIVATE user32 dwmapi ole32 uuid)

if(MINGW)
    # The desktop app loads this DLL from its private runtime directory. Gradle daemons and
    # installed builds do not inherit the developer toolchain's bin directory, so leaving MinGW's
    # libwinpthread dynamic makes the bridge silently unavailable even though the DLL itself was
    # packaged correctly.
    target_link_options(auris_window PRIVATE -static -static-libgcc -static-libstdc++)
endif()

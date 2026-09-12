cmake_minimum_required(VERSION 3.22)
project(mpfr_kmp_jni C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_VISIBILITY_PRESET default)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- MPFR/GMP install prefix (vendored, see third_party/mpfr/) -------------
get_filename_component(_default_prefix "${CMAKE_CURRENT_LIST_DIR}/../../../third_party/mpfr/install" ABSOLUTE)
set(MPFR_KMP_INSTALL "${_default_prefix}" CACHE PATH "Prefix containing include/mpfr.h and lib/libmpfr")

find_path(MPFR_INCLUDE_DIR mpfr.h PATHS "${MPFR_KMP_INSTALL}/include" REQUIRED)
find_library(MPFR_LIB NAMES mpfr libmpfr-3 mpfr-3 PATHS "${MPFR_KMP_INSTALL}/lib" REQUIRED)
find_library(GMP_LIB NAMES gmp libmpz-gmp PATHS "${MPFR_KMP_INSTALL}/lib" REQUIRED)

# --- JNI headers from the JDK ----------------------------------------------
if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME (or -DJAVA_HOME=...) must point at a JDK for jni.h")
endif()
find_path(JNI_INCLUDE_DIR jni.h
    PATHS "$ENV{JAVA_HOME}/include"
    REQUIRED)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_path(JNI_MD_INCLUDE_DIR jni_md.h PATHS "$ENV{JAVA_HOME}/include/linux" REQUIRED)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Windows")
    find_path(JNI_MD_INCLUDE_DIR jni_md.h PATHS "$ENV{JAVA_HOME}/include/win32" REQUIRED)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    find_path(JNI_MD_INCLUDE_DIR jni_md.h PATHS "$ENV{JAVA_HOME}/include/darwin" REQUIRED)
endif()

add_library(mpfr_kmp SHARED mpfr_kmp_jni.c)
target_include_directories(mpfr_kmp PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${MPFR_INCLUDE_DIR}"
    "${JNI_INCLUDE_DIR}"
    "${JNI_MD_INCLUDE_DIR}")
target_link_libraries(mpfr_kmp PRIVATE "${MPFR_LIB}" "${GMP_LIB}")
if(THREADS_HAVE_PTHREAD_ARG)
    target_link_libraries(mpfr_kmp PRIVATE pthread)
endif()
find_package(Threads REQUIRED)
target_link_libraries(mpfr_kmp PRIVATE Threads::Threads)

if(CMAKE_SYSTEM_NAME STREQUAL "Windows")
    # mpfr/gmp shared builds import via __imp_ symbols when built with MSVC import
    # libs; with the MSYS2 toolchain the plain mpfr/gmp imports resolve directly.
    target_link_directories(mpfr_kmp PRIVATE "${MPFR_KMP_INSTALL}/lib")
endif()

install(TARGETS mpfr_kmp LIBRARY DESTINATION lib RUNTIME DESTINATION lib)

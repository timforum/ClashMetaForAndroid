cmake_minimum_required(VERSION 3.0)

# 版本信息由 Gradle 写入：它知道本仓库当前所在的提交，以及构建当天是哪一天。
# CMake 只在重新配置时才重写自己生成的头文件，之前由它生成会把日期冻结在上一次
# 配置的那天，之后每个更新的 APK 都带着同一个字符串。
# 这里只作为不经 Gradle 直接配置 CMake 时的兜底：那时还没有人写过该文件。

if (NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/version.h)
  file(READ ${CMAKE_CURRENT_SOURCE_DIR}/version.h.in FALLBACK_VERSION_HEADER)
  string(REPLACE "@GIT_VERSION@" "unknown_unknown_000000" FALLBACK_VERSION_HEADER "${FALLBACK_VERSION_HEADER}")
  file(WRITE ${CMAKE_CURRENT_SOURCE_DIR}/version.h "${FALLBACK_VERSION_HEADER}")
endif()

project(clash-bridge C)


set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_link_libraries(bridge log clash)

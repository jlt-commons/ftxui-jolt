# Builds libftxui_jolt — the C shim jolt binds — with FTXUI linked in statically,
# so the single shared object is all the runtime needs.
#
# Where FTXUI comes from, in order:
#   1. -DFTXUI_SOURCE_DIR=/path/to/FTXUI (or the env var of the same name):
#      a checkout, built as part of this project.
#   2. an installed FTXUI found by find_package (e.g. -Dftxui_DIR=...).
#   3. otherwise, fetched from GitHub at the pinned commit below.
#
# `jolt native` (see deps.edn) runs this and leaves the artifact in native/.
cmake_minimum_required(VERSION 3.14)
project(ftxui_jolt LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
# FTXUI is linked into a shared object, so its static archives must be PIC.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# The commit this shim was written and tested against (v7.0.3 + 23).
set(FTXUI_JOLT_GIT_TAG "98396ae02f921a3018a7f40fb4e9deaf03dfa7b6")

set(FTXUI_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(FTXUI_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(FTXUI_BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(FTXUI_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(FTXUI_QUIET ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

if(NOT FTXUI_SOURCE_DIR AND DEFINED ENV{FTXUI_SOURCE_DIR})
  set(FTXUI_SOURCE_DIR "$ENV{FTXUI_SOURCE_DIR}")
endif()

if(FTXUI_SOURCE_DIR)
  message(STATUS "ftxui-jolt: building FTXUI from ${FTXUI_SOURCE_DIR}")
  add_subdirectory("${FTXUI_SOURCE_DIR}" ftxui EXCLUDE_FROM_ALL)
else()
  find_package(ftxui 7 QUIET)
  if(ftxui_FOUND)
    message(STATUS "ftxui-jolt: using installed FTXUI ${ftxui_VERSION}")
  else()
    message(STATUS "ftxui-jolt: fetching FTXUI ${FTXUI_JOLT_GIT_TAG}")
    include(FetchContent)
    FetchContent_Declare(ftxui
      GIT_REPOSITORY https://github.com/ArthurSonzogni/FTXUI
      GIT_TAG ${FTXUI_JOLT_GIT_TAG}
      GIT_SHALLOW OFF)
    FetchContent_MakeAvailable(ftxui)
  endif()
endif()

add_library(ftxui_jolt SHARED ftxui_jolt.cpp)
target_include_directories(ftxui_jolt PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(ftxui_jolt PRIVATE ftxui::component ftxui::dom ftxui::screen)
target_compile_options(ftxui_jolt PRIVATE $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra>)

# Land the artifact beside the source, where deps.edn's :jolt/native looks.
set_target_properties(ftxui_jolt PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
  PREFIX "lib"
  OUTPUT_NAME "ftxui_jolt")
foreach(cfg IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
  set_target_properties(ftxui_jolt PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY_${cfg} "${CMAKE_CURRENT_SOURCE_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_${cfg} "${CMAKE_CURRENT_SOURCE_DIR}")
endforeach()

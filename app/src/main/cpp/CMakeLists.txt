cmake_minimum_required(VERSION 3.22.1)
project(texturecore)

include(FetchContent)

# Header-only tinygltf for GLB/GLTF parsing/writing hooks.
FetchContent_Declare(
    tinygltf
    GIT_REPOSITORY https://github.com/syoyo/tinygltf.git
    GIT_TAG v2.9.3
)
FetchContent_GetProperties(tinygltf)
if(NOT tinygltf_POPULATED)
    FetchContent_Populate(tinygltf)
endif()

# xatlas for automatic charting/packing.
FetchContent_Declare(
    xatlas_src
    GIT_REPOSITORY https://github.com/jpcy/xatlas.git
    GIT_TAG master
)
FetchContent_GetProperties(xatlas_src)
if(NOT xatlas_src_POPULATED)
    FetchContent_Populate(xatlas_src)
endif()

add_library(xatlas STATIC
    ${xatlas_src_SOURCE_DIR}/source/xatlas/xatlas.cpp
)
target_include_directories(xatlas PUBLIC
    ${xatlas_src_SOURCE_DIR}/source/xatlas
)

add_library(texturecore SHARED
    texture_core.cpp
)

target_include_directories(texturecore PRIVATE
    ${tinygltf_SOURCE_DIR}
    ${xatlas_src_SOURCE_DIR}/source/xatlas
)

target_link_libraries(texturecore
    xatlas
    android
    log
)

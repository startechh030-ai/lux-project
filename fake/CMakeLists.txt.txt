cmake_minimum_required(VERSION 3.22.1)
project(luxe-model-native)

add_library(luxe-model-native SHARED
    NativeModelController.cpp
)

target_compile_features(luxe-model-native PRIVATE cxx_std_17)

target_compile_options(luxe-model-native PRIVATE -O2 -Wall)

target_link_libraries(luxe-model-native
    android
    log
)

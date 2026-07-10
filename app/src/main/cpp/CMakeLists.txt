cmake_minimum_required(VERSION 3.22.1)
project(simple-camera-native)

add_library(simple-camera-native SHARED
    NativeCamera.cpp
)

target_compile_features(simple-camera-native PRIVATE cxx_std_17)

target_link_libraries(simple-camera-native
    android
    log
)

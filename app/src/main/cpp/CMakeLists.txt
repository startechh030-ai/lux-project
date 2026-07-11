cmake_minimum_required(VERSION 3.22.1)
project(luxe-texture3d-native)

add_library(native-camera SHARED
    nativecamera.cpp
)

target_compile_features(native-camera PRIVATE cxx_std_17)

target_compile_options(native-camera PRIVATE -O2 -Wall)

target_link_libraries(native-camera
    android
    log
)

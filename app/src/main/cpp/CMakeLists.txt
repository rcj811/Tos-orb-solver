cmake_minimum_required(VERSION 3.22.1)
project(tossolver CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(tossolver SHARED
    tos_solver.cpp
    jni_bridge.cpp)

target_compile_options(tossolver PRIVATE -O2 -Wall)

cmake_minimum_required(VERSION 3.18)
project(mnn_inference)

set(CMAKE_CXX_STANDARD 17)

# MNN 库路径（从 AAR 或预编译库中获取）
set(MNN_DIR "${CMAKE_SOURCE_DIR}/third_party/MNN")

# 头文件
include_directories(
    ${MNN_DIR}/include
    ${MNN_DIR}/include/MNN
)

# 预编译的 MNN 库
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES
    IMPORTED_LOCATION ${MNN_DIR}/libs/${ANDROID_ABI}/libMNN.so
)

add_library(MNN_Express SHARED IMPORTED)
set_target_properties(MNN_Express PROPERTIES
    IMPORTED_LOCATION ${MNN_DIR}/libs/${ANDROID_ABI}/libMNN_Express.so
)

# 我们的 JNI 库
add_library(mnn_inference SHARED mnn_inference.cpp)

# 链接
target_link_libraries(mnn_inference
    MNN
    MNN_Express
    android
    log
    jnigraphics
)

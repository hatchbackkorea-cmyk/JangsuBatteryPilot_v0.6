cmake_minimum_required(VERSION 3.22.1)
project(timegate_uvc_h264)

add_library(timegate_uvc_h264 SHARED timegate_uvc_h264.cpp)

target_compile_features(timegate_uvc_h264 PRIVATE cxx_std_17)
target_compile_options(timegate_uvc_h264 PRIVATE -Wall -Wextra)

target_link_libraries(timegate_uvc_h264
    android
    log
    dl
)

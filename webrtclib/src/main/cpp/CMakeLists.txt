# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

cmake_minimum_required(VERSION 3.22.1)

# WebRTC 原生桥接库
# 该库作为后续「自行编译 WebRTC .so + C++ 供 JNI 调用」的脚手架：
#   1. 当前阶段：编译出 libwebrtc_native.so，提供 JNI 桥接与占位实现，
#      验证 NDK + CMake 工具链在模块内可用。
#   2. 后续阶段：把官方 webrtc/src 编译产物（libjingle_peerconnection_so.so）
#      或自编译的静态库链接进来，在此处实现真正的 native PeerConnection 调用。
project("webrtc_native")

add_library(${PROJECT_NAME} SHARED
        webrtc_native.cpp)

# Android 平台基础库
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(${PROJECT_NAME}
        ${log-lib}
        ${android-lib})

# ---------------------------------------------------------------------------
# 后续自编译 WebRTC 时的链接示例（保留注释，按需启用）：
#
# # 假设自编译产物存放在 cpp/prebuilt/<abi>/ 下
# set(WEBRTC_PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})
# add_library(webrtc STATIC IMPORTED)
# set_target_properties(webrtc PROPERTIES IMPORTED_LOCATION
#         ${WEBRTC_PREBUILT_DIR}/libwebrtc.a)
#
# target_link_libraries(${PROJECT_NAME}
#         webrtc           # WebRTC 静态库
#         ${log-lib}
#         ${android-lib}
#         EGL
#         GLESv2
#         OpenSLES
#         -lz -latomic)
# ---------------------------------------------------------------------------

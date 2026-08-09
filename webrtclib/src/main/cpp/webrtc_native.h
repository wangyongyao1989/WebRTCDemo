#ifndef WANGYAO_WEBRTC_NATIVE_H_
#define WANGYAO_WEBRTC_NATIVE_H_

#include <jni.h>
#include <string>

namespace wangyao {
namespace webrtc {

/**
 * WebRTC 原生桥接层声明。
 *
 * 该头文件定义了 Java<->C++ 之间的桥接 API。当前为「脚手架」实现：
 * 提供 native 初始化、版本号、I420 帧处理的占位逻辑，用于验证 JNI 通道。
 *
 * 后续自编译 WebRTC 后，可在此扩展：
 *   - CreatePeerConnectionFactoryNative()
 *   - CreatePeerConnectionNative(...)
 *   - CreateOfferNative / CreateAnswerNative
 *   - SetLocalDescription / SetRemoteDescription
 *   - AddIceCandidate
 * 对应 Google WebRTC native API（api/peer_connection_interface.h 等）。
 */
class WebRtcNative {
 public:
  // 初始化原生层（创建线程、日志等）。成功返回 true。
  static bool Init(JNIEnv* env);

  // 返回原生桥接版本号（含构建信息），用于运行时校验 .so 是否正确加载。
  static std::string GetVersion();

  // I420 帧处理占位：返回建议的处理耗时（微秒）。
  // 参数：width/height 帧分辨率；y/u/v 指向三个平面的数据指针。
  // 后续可接入 libwebrtc 的 I420Buffer / VideoFrame 做真实处理。
  static long ProcessI420Frame(int width, int height,
                               const uint8_t* y, const uint8_t* u, const uint8_t* v);

  // 释放原生层资源。
  static void Release(JNIEnv* env);
};

}  // namespace webrtc
}  // namespace wangyao

#endif  // WANGYAO_WEBRTC_NATIVE_H_

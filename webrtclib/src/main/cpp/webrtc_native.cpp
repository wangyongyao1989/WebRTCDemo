#include "webrtc_native.h"

#include <android/log.h>
#include <chrono>

#define TAG "webrtc_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace wangyao {
namespace webrtc {

namespace {
bool g_initialized = false;
}

bool WebRtcNative::Init(JNIEnv* env) {
  if (g_initialized) {
    LOGI("WebRtcNative already initialized");
    return true;
  }
  // 脚手架：当前仅置位 + 日志。
  // 后续自编译 WebRTC 时，此处可调用：
  //   rtc::InitializeSSL();
  //   webrtc::PeerConnectionFactoryInterface::Create()...
  // 并创建专用的 worker / signaling 线程。
  g_initialized = true;
  LOGI("WebRtcNative::Init done (scaffold build)");
  return true;
}

std::string WebRtcNative::GetVersion() {
  // 版本号携带标识，便于区分「脚手架」与「自编译 WebRTC」产物。
  return "webrtc_native 1.0.0-scaffold (ready for self-built webrtc)";
}

long WebRtcNative::ProcessI420Frame(int width, int height,
                                    const uint8_t* y, const uint8_t* u, const uint8_t* v) {
  if (!g_initialized || width <= 0 || height <= 0 || y == nullptr) {
    LOGE("ProcessI420Frame invalid args");
    return -1;
  }
  // 脚手架占位：统计一帧 I420 数据大小并打印日志。
  // I420 半采样：Y 全分辨率，U/V 各 1/4。
  auto start = std::chrono::steady_clock::now();
  size_t y_size = static_cast<size_t>(width) * height;
  size_t u_size = y_size / 4;
  size_t v_size = y_size / 4;
  (void)u;
  (void)v;
  (void)u_size;
  (void)v_size;
  auto end = std::chrono::steady_clock::now();
  long us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
  LOGI("ProcessI420Frame %dx%d, y_size=%zu, cost=%ldus", width, height, y_size, us);
  return us;
}

void WebRtcNative::Release(JNIEnv* env) {
  if (!g_initialized) return;
  // 后续可调用 rtc::CleanupSSL() 等。
  g_initialized = false;
  LOGI("WebRtcNative::Release done");
}

}  // namespace webrtc
}  // namespace wangyao

// ---------------------------------------------------------------------------
// JNI 导出函数。包名/类名需与 Java 侧 com.wangyao.webrtclib.WebRTCNative 对应。
// 命名规则：Java_<包>_<类>_<方法>，点替换为下划线。
// ---------------------------------------------------------------------------
#define JNI_CLASS_PATH "com/wangyao/webrtclib/WebRTCNative"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wangyao_webrtclib_WebRTCNative_nativeInit(JNIEnv* env, jclass clazz) {
  return static_cast<jboolean>(wangyao::webrtc::WebRtcNative::Init(env) ? JNI_TRUE : JNI_FALSE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wangyao_webrtclib_WebRTCNative_nativeGetVersion(JNIEnv* env, jclass clazz) {
  std::string version = wangyao::webrtc::WebRtcNative::GetVersion();
  return env->NewStringUTF(version.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wangyao_webrtclib_WebRTCNative_nativeProcessI420Frame(JNIEnv* env, jclass clazz,
                                                               jint width, jint height,
                                                               jbyteArray y_data,
                                                               jbyteArray u_data,
                                                               jbyteArray v_data) {
  if (y_data == nullptr) return -1;
  jbyte* y = env->GetByteArrayElements(y_data, nullptr);
  jbyte* u = u_data ? env->GetByteArrayElements(u_data, nullptr) : nullptr;
  jbyte* v = v_data ? env->GetByteArrayElements(v_data, nullptr) : nullptr;
  long us = wangyao::webrtc::WebRtcNative::ProcessI420Frame(
      width, height,
      reinterpret_cast<const uint8_t*>(y),
      reinterpret_cast<const uint8_t*>(u),
      reinterpret_cast<const uint8_t*>(v));
  if (y) env->ReleaseByteArrayElements(y_data, y, JNI_ABORT);
  if (u) env->ReleaseByteArrayElements(u_data, u, JNI_ABORT);
  if (v) env->ReleaseByteArrayElements(v_data, v, JNI_ABORT);
  return us;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wangyao_webrtclib_WebRTCNative_nativeRelease(JNIEnv* env, jclass clazz) {
  wangyao::webrtc::WebRtcNative::Release(env);
}

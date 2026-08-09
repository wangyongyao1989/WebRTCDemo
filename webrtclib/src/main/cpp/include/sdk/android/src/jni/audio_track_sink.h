#ifndef SDK_ANDROID_SRC_JNI_AUDIO_TRACK_SINK_H_
#define SDK_ANDROID_SRC_JNI_AUDIO_TRACK_SINK_H_

#include <cstddef>
#include <cstdint>

#include "api/media_stream_interface.h"
#include "sdk/android/native_api/jni/scoped_java_ref.h"

namespace webrtc {
namespace jni {

// JNI ブリッジで AudioTrackSinkInterface のコールバックを
// Java 側の org.webrtc.AudioTrackSink に転送する
class AudioTrackSinkWrapper : public AudioTrackSinkInterface {
 public:
  AudioTrackSinkWrapper(JNIEnv* env,
                        const JavaRef<jobject>& j_sink);
  ~AudioTrackSinkWrapper() override;

  void OnData(const void* audio_data,
              int bits_per_sample,
              int sample_rate,
              size_t number_of_channels,
              size_t number_of_frames) override;

  int NumPreferredChannels() const override;

 private:
  ScopedJavaGlobalRef<jobject> j_sink_;
};

}  // namespace jni
}  // namespace webrtc

#endif  // SDK_ANDROID_SRC_JNI_AUDIO_TRACK_SINK_H_

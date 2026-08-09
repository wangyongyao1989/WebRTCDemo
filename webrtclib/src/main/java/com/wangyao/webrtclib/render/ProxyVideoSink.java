package com.wangyao.webrtclib.render;

import org.webrtc.Logging;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

/**
 * 视频 Sink 代理。
 *
 * 作用：把一路 VideoTrack 委托给内部的 target SurfaceViewRenderer。
 * 当需要在小窗/全屏之间切换渲染目标时，只需 {@link #setTarget(VideoSink)}
 * 切换 target，无需对 VideoTrack 反复 addSink/removeSink，避免黑屏与生命周期问题。
 *
 * 参考：webrtc_android/rtc-chat .../render/ProxyVideoSink.java
 */
public class ProxyVideoSink implements VideoSink {
    private static final String TAG = "ProxyVideoSink";
    private VideoSink target;

    @Override
    public synchronized void onFrame(VideoFrame frame) {
        if (target == null) {
            Logging.d(TAG, "Dropping frame in proxy because target is null.");
            return;
        }
        target.onFrame(frame);
    }

    public synchronized void setTarget(VideoSink target) {
        this.target = target;
    }
}

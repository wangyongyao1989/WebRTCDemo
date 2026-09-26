package com.wangyao.webrtclib.peer;

import android.content.Context;
import android.util.Log;

import com.wangyao.webrtclib.render.ProxyVideoSink;

import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStatsCollectorCallback;
import org.webrtc.RendererCommon;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 P2P 连接的封装：持有 PeerConnection + 远端渲染器。
 *
 * 采用「joiner 发起 Offer」的信令模型：
 *   - isOffer=true  ：本机为呼叫方，主动 createOffer。
 *   - isOffer=false ：本机为接收方，收到 Offer 后 createAnswer。
 *
 * SDP 状态机依据 signalingState 驱动，避免角色判断歧义。
 */
public class Peer implements SdpObserver, PeerConnection.Observer {
    private static final String TAG = "Peer";

    public interface PeerCallback {
        void onSendOffer(String userId, SessionDescription sdp);

        void onSendAnswer(String userId, SessionDescription sdp);

        void onSendIceCandidate(String userId, IceCandidate candidate);

        void onRemoteStream(String userId);

        void onUserLeave(String userId);

        /** ICE 连接状态变化（含 DISCONNECTED/FAILED），由管理器决定重连策略。 */
        void onIceConnectionStateChanged(String userId, PeerConnection.IceConnectionState state);
    }

    private final PeerConnection pc;
    private final String userId;
    private final boolean isOffer;
    private final PeerCallback callback;

    /** 远端描述未就绪（非 STABLE）时到达的 ICE 候选暂存队列；重协商期间同样复用。 */
    private final List<IceCandidate> remoteCandidateQueue = new ArrayList<>();
    private SessionDescription localSdp;
    /** Unified Plan 下 onAddTrack 会触发多次，远端流就绪只上抛一次。 */
    private volatile boolean remoteStreamNotified = false;

    public MediaStream remoteStream;
    public SurfaceViewRenderer renderer;
    public ProxyVideoSink sink;

    public Peer(PeerConnectionFactory factory, List<PeerConnection.IceServer> iceServers,
                String userId, boolean isOffer, PeerCallback callback) {
        this.userId = userId;
        this.isOffer = isOffer;
        this.callback = callback;
        this.pc = factory.createPeerConnection(iceServers, this);
        Log.d(TAG, "create Peer: " + userId + ", isOffer=" + isOffer + ", pc=" + pc);
    }

    public String getUserId() {
        return userId;
    }

    public void addLocalStream(MediaStream localStream) {
        if (pc == null || localStream == null) return;
        // Unified Plan（新版 WebRTC 默认语义）下 addStream 会触发 native CHECK 崩溃，
        // 必须逐轨道使用 addTrack(track, streamIds)。
        java.util.List<String> streamIds = java.util.Collections.singletonList(localStream.getId());
        for (org.webrtc.AudioTrack track : localStream.audioTracks) {
            pc.addTrack(track, streamIds);
        }
        for (VideoTrack track : localStream.videoTracks) {
            pc.addTrack(track, streamIds);
        }
    }

    public void createOffer(MediaConstraints constraints) {
        if (pc == null) return;
        Log.d(TAG, "createOffer: " + userId);
        pc.createOffer(this, constraints);
    }

    public void setRemoteDescription(SessionDescription sdp) {
        if (pc == null) return;
        Log.d(TAG, "setRemoteDescription: " + userId + ", type=" + sdp.type);
        pc.setRemoteDescription(this, sdp);
    }

    /** 添加远端 ICE 候选：信令非 STABLE（协商/重协商进行中）时入队，否则直接添加。 */
    public synchronized void addRemoteIceCandidate(IceCandidate candidate) {
        if (pc == null) return;
        if (pc.signalingState() == PeerConnection.SignalingState.STABLE
                && remoteCandidateQueue.isEmpty()) {
            pc.addIceCandidate(candidate);
        } else {
            remoteCandidateQueue.add(candidate);
        }
    }

    /** ICE 重启 + 重新发起 Offer（弱网断连恢复用，仅呼叫角色可调用）。 */
    public void restartIceAndRenegotiate() {
        if (pc == null) return;
        Log.w(TAG, "restartIceAndRenegotiate: " + userId);
        pc.restartIce();
        pc.createOffer(this, offerOrAnswerConstraints());
    }

    /** 读取 WebRTC 统计报告（NetworkQualityMonitor 用）。 */
    public void getStats(RTCStatsCollectorCallback callback) {
        if (pc != null) pc.getStats(callback);
    }

    public PeerConnection.IceConnectionState iceState() {
        return pc != null ? pc.iceConnectionState() : PeerConnection.IceConnectionState.CLOSED;
    }

    /** 限制本 peer 视频发送码率（RtpSender 参数调整，不触发重协商）。 */
    public void setVideoMaxBitrate(int maxBps) {
        if (pc == null) return;
        for (RtpSender sender : pc.getSenders()) {
            MediaStreamTrack track = sender.track();
            if (track instanceof VideoTrack) {
                RtpParameters params = sender.getParameters();
                if (params == null || params.encodings.isEmpty()) continue;
                for (RtpParameters.Encoding enc : params.encodings) {
                    enc.maxBitrateBps = maxBps;
                }
                boolean ok = sender.setParameters(params);
                Log.i(TAG, "setVideoMaxBitrate: " + userId + " " + maxBps + "bps -> " + ok);
            }
        }
    }

    /** 为该 peer 创建远端视频渲染器（由 UI 层调用并加入布局）。 */
    public SurfaceViewRenderer createRender(EglBase eglBase, Context context, boolean isOverlay) {
        renderer = new SurfaceViewRenderer(context);
        renderer.init(eglBase.getEglBaseContext(), new RendererCommon.RendererEvents() {
            @Override
            public void onFirstFrameRendered() {
                Log.d(TAG, "onFirstFrameRendered: " + userId);
            }

            @Override
            public void onFrameResolutionChanged(int w, int h, int rotation) {
            }
        });
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        renderer.setMirror(false);
        renderer.setZOrderMediaOverlay(isOverlay);
        sink = new ProxyVideoSink();
        sink.setTarget(renderer);
        if (remoteStream != null && remoteStream.videoTracks.size() > 0) {
            remoteStream.videoTracks.get(0).addSink(sink);
        }
        return renderer;
    }

    /** 关闭并释放该 peer 的所有资源。 */
    public void close() {
        if (sink != null) {
            sink.setTarget(null);
            sink = null;
        }
        if (renderer != null) {
            try {
                renderer.release();
            } catch (Exception e) {
                Log.w(TAG, "renderer.release: " + e.getMessage());
            }
            renderer = null;
        }
        if (pc != null) {
            try {
                pc.close();
                pc.dispose();
            } catch (Exception e) {
                Log.w(TAG, "pc.close: " + e.getMessage());
            }
        }
        // 解绑远端视频 sink
        if (remoteStream != null && remoteStream.videoTracks.size() > 0) {
            // remoteStream 生命周期由 pc 管理，这里无需额外操作
        }
    }

    // --------------------------- SdpObserver ---------------------------

    @Override
    public void onCreateSuccess(SessionDescription origSdp) {
        Log.d(TAG, "onCreateSuccess: " + userId + ", type=" + origSdp.type);
        localSdp = origSdp;
        if (pc != null) pc.setLocalDescription(this, origSdp);
    }

    @Override
    public void onSetSuccess() {
        if (pc == null) return;
        PeerConnection.SignalingState state = pc.signalingState();
        Log.d(TAG, "onSetSuccess: " + userId + ", state=" + state);
        switch (state) {
            case HAVE_LOCAL_OFFER:
                // 本机刚设置本地 Offer -> 发送给对端（呼叫方）
                callback.onSendOffer(userId, localSdp);
                break;
            case HAVE_REMOTE_OFFER:
                // 收到对端 Offer -> 生成 Answer（接收方）
                pc.createAnswer(this, offerOrAnswerConstraints());
                break;
            case STABLE:
                // 以本地 SDP 类型判断本轮角色（而非初始 isOffer）：
                // 重协商时即使本机是初始应答方，也可能主动发出 Offer。
                if (localSdp != null && localSdp.type == SessionDescription.Type.ANSWER) {
                    // 本机刚设置本地 Answer -> 发送给对端
                    callback.onSendAnswer(userId, localSdp);
                }
                drainCandidates();
                break;
            default:
                break;
        }
    }

    @Override
    public void onCreateFailure(String error) {
        Log.e(TAG, "onCreateFailure: " + userId + ", " + error);
    }

    @Override
    public void onSetFailure(String error) {
        Log.e(TAG, "onSetFailure: " + userId + ", " + error);
    }

    // ----------------------- PeerConnection.Observer -----------------------

    @Override
    public void onSignalingChange(PeerConnection.SignalingState state) {
        Log.d(TAG, "onSignalingChange: " + userId + ", " + state);
    }

    @Override
    public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
        Log.i(TAG, "onIceConnectionChange: " + userId + ", " + state);
        // 不再直接上抛离会：DISCONNECTED/FAILED 交由管理器尝试 ICE 重启恢复，
        // 连续失败后由管理器决定是否真正拆除 peer。
        callback.onIceConnectionStateChanged(userId, state);
    }

    @Override
    public void onIceConnectionReceivingChange(boolean receiving) {
    }

    @Override
    public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
    }

    @Override
    public void onIceCandidate(IceCandidate candidate) {
        Log.d(TAG, "onIceCandidate: " + userId);
        callback.onSendIceCandidate(userId, candidate);
    }

    @Override
    public void onIceCandidatesRemoved(IceCandidate[] candidates) {
    }

    @Override
    public void onAddStream(MediaStream stream) {
        Log.i(TAG, "onAddStream: " + userId
                + ", audio=" + stream.audioTracks.size() + ", video=" + stream.videoTracks.size());
        if (stream.audioTracks.size() > 0) {
            stream.audioTracks.get(0).setEnabled(true);
        }
        remoteStream = stream;
        // 若渲染器已创建（先 createRender 后到流），立即把流接到 sink
        if (sink != null && stream.videoTracks.size() > 0) {
            stream.videoTracks.get(0).addSink(sink);
        }
        callback.onRemoteStream(userId);
    }

    @Override
    public void onRemoveStream(MediaStream stream) {
        Log.w(TAG, "onRemoveStream: " + userId);
        callback.onUserLeave(userId);
    }

    @Override
    public void onDataChannel(DataChannel dataChannel) {
    }

    @Override
    public void onRenegotiationNeeded() {
    }

    @Override
    public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
        // Unified Plan 下远端每个轨道各回调一次（onAddStream 不再触发）。
        MediaStream stream = (mediaStreams != null && mediaStreams.length > 0) ? mediaStreams[0] : null;
        if (stream == null) return;
        if (receiver != null && receiver.track() instanceof org.webrtc.AudioTrack) {
            ((org.webrtc.AudioTrack) receiver.track()).setEnabled(true);
        }
        remoteStream = stream;
        Log.i(TAG, "onAddTrack: " + userId + ", track="
                + (receiver != null && receiver.track() != null ? receiver.track().kind() : "?")
                + ", video=" + stream.videoTracks.size() + ", audio=" + stream.audioTracks.size());
        if (!remoteStreamNotified && !stream.videoTracks.isEmpty()) {
            remoteStreamNotified = true;
            if (sink != null) {
                stream.videoTracks.get(0).addSink(sink);
            }
            callback.onRemoteStream(userId);
        }
    }

    // ----------------------------- 内部方法 -----------------------------

    private synchronized void drainCandidates() {
        if (remoteCandidateQueue.isEmpty()) return;
        Log.d(TAG, "drainCandidates: " + userId + ", count=" + remoteCandidateQueue.size());
        for (IceCandidate c : remoteCandidateQueue) {
            if (pc != null) pc.addIceCandidate(c);
        }
        remoteCandidateQueue.clear();
    }

    private MediaConstraints offerOrAnswerConstraints() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        return constraints;
    }
}

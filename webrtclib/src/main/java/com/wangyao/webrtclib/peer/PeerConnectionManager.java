package com.wangyao.webrtclib.peer;

import android.content.Context;
import android.util.Log;

import com.wangyao.webrtclib.signaling.SignalCallback;
import com.wangyao.webrtclib.signaling.WebSocketManager;

import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Peer 连接管理器：维护房间内所有 peer，衔接「信令层」与「WebRTC 媒体层」。
 *
 * 职责：
 *   1. 实现 {@link SignalCallback}：处理信令事件（_peers / _new_peer / _offer / ...）。
 *   2. 实现 {@link Peer.PeerCallback}：把 peer 产生的 Offer/Answer/ICE 通过信令发出，
 *      并把远端流到达/用户离开上抛给 {@link PeerManagerListener}。
 *   3. 所有 PeerConnection 操作均走单线程 executor，保证线程安全。
 *
 * 信令模型：joiner 主动向房间内已有用户发起 Offer。
 */
public class PeerConnectionManager implements SignalCallback, Peer.PeerCallback {

    public interface PeerManagerListener {
        /** 信令通道正在连接。 */
        void onSignalConnecting();

        /** 信令通道已连接。 */
        void onSignalConnected();

        /** 信令通道已断开。 */
        void onSignalClosed();

        /** 已加入房间，myId 为本机 socketId。 */
        void onJoinedRoom(String myId);

        /** 远端流到达，UI 可调用 setupRemoteVideo 获取渲染器。 */
        void onRemoteStreamReady(String userId);

        /** 远端用户离开。 */
        void onUserLeave(String userId);

        /** 网络质量分级变化（弱网检测）。 */
        void onNetworkQuality(String userId, NetworkQualityMonitor.Quality quality, String detail);

        /** ICE 连接异常，正在进行第 attempt 次重启重连。 */
        void onIceReconnecting(String userId, int attempt);
    }

    private static final String TAG = "PeerConnectionManager";

    private final Context context;
    private final PeerConnectionFactory factory;
    private final EglBase eglBase;
    private final List<PeerConnection.IceServer> iceServers;
    private final MediaStream localStream;
    private final WebSocketManager socketManager;
    private final PeerManagerListener listener;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    /** 每个 peer 一个弱网质量监测器（远端流到达后启动）。 */
    private final Map<String, NetworkQualityMonitor> monitors = new ConcurrentHashMap<>();
    /** ICE 重启次数计数（连接恢复后清零）。 */
    private final Map<String, Integer> iceRestartAttempts = new ConcurrentHashMap<>();
    /** 待执行的 ICE 状态复查任务（避免重复调度）。 */
    private final Map<String, ScheduledFuture<?>> pendingIceChecks = new ConcurrentHashMap<>();
    private static final int MAX_ICE_RESTART = 2;
    /** DISCONNECTED 后的宽限期：期间 WebRTC 可能自行恢复，超时未恢复才重启 ICE。 */
    private static final long ICE_DISCONNECT_GRACE_MS = 4000;
    /** ICE 重启后等待重协商+连通的时间，超时仍未连通则再次处理。 */
    private static final long ICE_RESTART_VERIFY_MS = 10000;
    private String myId;

    public PeerConnectionManager(Context context,
                                 PeerConnectionFactory factory,
                                 EglBase eglBase,
                                 List<PeerConnection.IceServer> iceServers,
                                 MediaStream localStream,
                                 WebSocketManager socketManager,
                                 PeerManagerListener listener) {
        this.context = context;
        this.factory = factory;
        this.eglBase = eglBase;
        this.iceServers = iceServers;
        this.localStream = localStream;
        this.socketManager = socketManager;
        this.listener = listener;
    }

    // ============================ SignalCallback ============================

    @Override
    public void onSignalOpen() {
        Log.i(TAG, "onSignalOpen");
        if (listener != null) listener.onSignalConnected();
    }

    @Override
    public void onSignalClose() {
        Log.i(TAG, "onSignalClose");
        if (listener != null) listener.onSignalClosed();
    }

    @Override
    public void onPeers(List<String> connections, String myId) {
        Log.i(TAG, "onPeers: myId=" + myId + ", connections=" + connections);
        this.myId = myId;
        executor.execute(() -> {
            for (String id : connections) {
                createPeer(id, true);
            }
        });
        if (listener != null) listener.onJoinedRoom(myId);
    }

    @Override
    public void onNewPeer(String socketId) {
        Log.i(TAG, "onNewPeer: " + socketId);
        executor.execute(() -> createPeer(socketId, false));
    }

    @Override
    public void onReceiveOffer(String socketId, String sdp) {
        Log.i(TAG, "onReceiveOffer: " + socketId);
        executor.execute(() -> {
            Peer peer = peers.get(socketId);
            if (peer == null) {
                // 兜底：理论上 _new_peer 已创建，此处防御性创建为接收方
                peer = createPeer(socketId, false);
            }
            peer.setRemoteDescription(new SessionDescription(SessionDescription.Type.OFFER, sdp));
        });
    }

    @Override
    public void onReceiveAnswer(String socketId, String sdp) {
        Log.i(TAG, "onReceiveAnswer: " + socketId);
        executor.execute(() -> {
            Peer peer = peers.get(socketId);
            if (peer != null) {
                peer.setRemoteDescription(new SessionDescription(SessionDescription.Type.ANSWER, sdp));
            }
        });
    }

    @Override
    public void onReceiveIceCandidate(String socketId, IceCandidate candidate) {
        Log.d(TAG, "onReceiveIceCandidate: " + socketId);
        executor.execute(() -> {
            Peer peer = peers.get(socketId);
            if (peer != null) {
                peer.addRemoteIceCandidate(candidate);
            }
        });
    }

    @Override
    public void onRemovePeer(String socketId) {
        Log.i(TAG, "onRemovePeer: " + socketId);
        executor.execute(() -> removePeer(socketId));
    }

    @Override
    public void onSignalError(String message) {
        Log.e(TAG, "onSignalError: " + message);
    }

    // ============================ Peer.PeerCallback ============================

    @Override
    public void onSendOffer(String userId, SessionDescription sdp) {
        socketManager.sendOffer(userId, sdp.description);
    }

    @Override
    public void onSendAnswer(String userId, SessionDescription sdp) {
        socketManager.sendAnswer(userId, sdp.description);
    }

    @Override
    public void onSendIceCandidate(String userId, IceCandidate candidate) {
        socketManager.sendIceCandidate(userId, candidate);
    }

    @Override
    public void onRemoteStream(String userId) {
        startMonitor(userId);
        if (listener != null) listener.onRemoteStreamReady(userId);
    }

    @Override
    public void onUserLeave(String userId) {
        if (listener != null) listener.onUserLeave(userId);
        executor.execute(() -> removePeer(userId));
    }

    @Override
    public void onIceConnectionStateChanged(String userId, PeerConnection.IceConnectionState state) {
        executor.execute(() -> handleIceState(userId, state));
    }

    // ============================ 弱网 ICE 重连状态机 ============================

    /**
     * ICE 异常处理策略：
     *   - CONNECTED/COMPLETED：清零重启计数；
     *   - DISCONNECTED：给 4s 宽限期（WebRTC 可自愈），仍未恢复则 restartIce；
     *   - FAILED：立即 restartIce + 重新 Offer；
     *   - 重启后 10s 复查，仍未连通则再试，最多 {@link #MAX_ICE_RESTART} 次，
     *     超限才真正按离会拆链。
     */
    private void handleIceState(String userId, PeerConnection.IceConnectionState state) {
        Log.i(TAG, "handleIceState: " + userId + ", " + state);
        switch (state) {
            case CONNECTED:
            case COMPLETED:
                iceRestartAttempts.remove(userId);
                cancelPendingIceCheck(userId);
                startMonitor(userId);
                break;
            case DISCONNECTED:
                scheduleIceCheck(userId, ICE_DISCONNECT_GRACE_MS);
                break;
            case FAILED:
                attemptIceRestart(userId);
                break;
            default:
                break;
        }
    }

    /** 延迟复查 ICE 状态：仍未连通则尝试重启。 */
    private void scheduleIceCheck(String userId, long delayMs) {
        if (pendingIceChecks.containsKey(userId)) return; // 已有在途检查
        ScheduledFuture<?> f = scheduler.schedule(() -> {
            pendingIceChecks.remove(userId);
            executor.execute(() -> {
                Peer peer = peers.get(userId);
                if (peer == null) return;
                PeerConnection.IceConnectionState s = peer.iceState();
                if (s != PeerConnection.IceConnectionState.CONNECTED
                        && s != PeerConnection.IceConnectionState.COMPLETED) {
                    attemptIceRestart(userId);
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);
        pendingIceChecks.put(userId, f);
    }

    private void cancelPendingIceCheck(String userId) {
        ScheduledFuture<?> f = pendingIceChecks.remove(userId);
        if (f != null) f.cancel(false);
    }

    private void attemptIceRestart(String userId) {
        Peer peer = peers.get(userId);
        if (peer == null) return;
        int n = iceRestartAttempts.merge(userId, 1, Integer::sum);
        if (n > MAX_ICE_RESTART) {
            Log.e(TAG, "attemptIceRestart: 已达上限 " + MAX_ICE_RESTART + " 次，放弃重连 " + userId);
            iceRestartAttempts.remove(userId);
            onUserLeave(userId);
            return;
        }
        Log.w(TAG, "attemptIceRestart #" + n + ": " + userId);
        peer.restartIceAndRenegotiate();
        if (listener != null) listener.onIceReconnecting(userId, n);
        scheduleIceCheck(userId, ICE_RESTART_VERIFY_MS);
    }

    // ============================ 弱网质量监测 ============================

    private void startMonitor(String userId) {
        Peer peer = peers.get(userId);
        if (peer == null || monitors.containsKey(userId)) return;
        NetworkQualityMonitor monitor = new NetworkQualityMonitor(peer, userId,
                (uid, quality, detail) -> {
                    if (listener != null) listener.onNetworkQuality(uid, quality, detail);
                });
        monitors.put(userId, monitor);
        monitor.start();
    }

    private void stopMonitor(String userId) {
        NetworkQualityMonitor m = monitors.remove(userId);
        if (m != null) m.stop();
    }

    // ============================ 对外方法 ============================

    /** 创建远端渲染器（UI 线程调用）。 */
    public SurfaceViewRenderer createRemoteRenderer(String userId, boolean isOverlay) {
        Peer peer = peers.get(userId);
        if (peer == null) {
            Log.w(TAG, "createRemoteRenderer: peer not found " + userId);
            return null;
        }
        return peer.createRender(eglBase, context, isOverlay);
    }

    /** 对所有活跃 peer 的视频 RtpSender 设置最大编码码率（弱网自适应降级/恢复）。 */
    public void setVideoMaxBitrate(int maxBps) {
        executor.execute(() -> {
            for (Peer peer : peers.values()) {
                peer.setVideoMaxBitrate(maxBps);
            }
        });
    }

    /** 关闭所有 peer 并清空。 */
    public void closeAll() {
        executor.execute(() -> {
            for (String id : new java.util.ArrayList<>(monitors.keySet())) {
                stopMonitor(id);
            }
            for (String id : new java.util.ArrayList<>(pendingIceChecks.keySet())) {
                cancelPendingIceCheck(id);
            }
            iceRestartAttempts.clear();
            for (Peer peer : peers.values()) {
                peer.close();
            }
            peers.clear();
        });
    }

    public String getMyId() {
        return myId;
    }

    // ============================ 内部方法 ============================

    private Peer createPeer(String socketId, boolean isOffer) {
        Peer peer = new Peer(factory, iceServers, socketId, isOffer, this);
        peer.addLocalStream(localStream);
        peers.put(socketId, peer);
        if (isOffer) {
            peer.createOffer(offerOrAnswerConstraints());
        }
        return peer;
    }

    private void removePeer(String socketId) {
        stopMonitor(socketId);
        cancelPendingIceCheck(socketId);
        iceRestartAttempts.remove(socketId);
        Peer peer = peers.remove(socketId);
        if (peer != null) {
            peer.close();
        }
    }

    private org.webrtc.MediaConstraints offerOrAnswerConstraints() {
        org.webrtc.MediaConstraints constraints = new org.webrtc.MediaConstraints();
        constraints.mandatory.add(new org.webrtc.MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new org.webrtc.MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        return constraints;
    }
}

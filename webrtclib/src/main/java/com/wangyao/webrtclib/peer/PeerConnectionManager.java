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
        /** 信令通道已连接。 */
        void onSignalConnected();

        /** 已加入房间，myId 为本机 socketId。 */
        void onJoinedRoom(String myId);

        /** 远端流到达，UI 可调用 setupRemoteVideo 获取渲染器。 */
        void onRemoteStreamReady(String userId);

        /** 远端用户离开。 */
        void onUserLeave(String userId);
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
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
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
        if (listener != null) listener.onSignalConnected();
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
        if (listener != null) listener.onRemoteStreamReady(userId);
    }

    @Override
    public void onUserLeave(String userId) {
        if (listener != null) listener.onUserLeave(userId);
        executor.execute(() -> removePeer(userId));
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

    /** 关闭所有 peer 并清空。 */
    public void closeAll() {
        executor.execute(() -> {
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

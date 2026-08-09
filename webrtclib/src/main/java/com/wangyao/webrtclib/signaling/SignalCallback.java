package com.wangyao.webrtclib.signaling;

import org.webrtc.IceCandidate;

/**
 * 信令层（WebSocketManager）解析出的业务事件，回调给 PeerConnectionManager 处理。
 * 事件命名与 server.py 保持一致：__join / _peers / _new_peer / _offer / _answer /
 * _ice_candidate / _remove_peer。
 */
public interface SignalCallback {

    /** WebSocket 已连接。 */
    void onSignalOpen();

    /** 收到 _peers：本机 myId 及房间内已存在的连接列表。 */
    void onPeers(java.util.List<String> connections, String myId);

    /** 收到 _new_peer：有新用户加入房间。 */
    void onNewPeer(String socketId);

    /** 收到 _offer：远端发来的 Offer SDP。 */
    void onReceiveOffer(String socketId, String sdp);

    /** 收到 _answer：远端发来的 Answer SDP。 */
    void onReceiveAnswer(String socketId, String sdp);

    /** 收到 _ice_candidate：远端 ICE 候选。 */
    void onReceiveIceCandidate(String socketId, IceCandidate candidate);

    /** 收到 _remove_peer：远端用户离开房间。 */
    void onRemovePeer(String socketId);

    /** 信令发生错误。 */
    void onSignalError(String message);
}

package com.wangyao.webrtclib;

/**
 * WebRTC 模块对外（UI 层）的事件回调。
 * 所有方法均在 WebRTC 内部线程触发，UI 层如需更新界面请自行切换到主线程。
 */
public interface WebRTCEventListener {

    /**
     * 信令通道正在连接。
     */
    void onSignalConnecting();

    /**
     * 信令通道已连接（WebSocket 握手成功）。
     */
    void onSignalConnected();

    /**
     * 信令通道已断开。
     */
    void onSignalClosed();

    /**
     * 加入房间成功，myId 为服务器分配的本机 socketId。
     */
    void onJoinedRoom(String myId);

    /**
     * 收到远端媒体流（对方视频/音频已到达）。
     *
     * @param userId 远端用户 socketId
     */
    void onRemoteStream(String userId);

    /**
     * 远端用户离开房间。
     */
    void onUserLeave(String userId);

    /**
     * 发生错误。
     */
    void onError(String message);
}

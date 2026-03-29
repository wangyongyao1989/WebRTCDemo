package com.wangyao.webrtcdemo.webrtc;

import com.wangyao.webrtcdemo.CallActivity;
import com.wangyao.webrtcdemo.MainActivity;
import com.wangyao.webrtcdemo.webrtc.peerconnection.PeerConnectionManager;
import com.wangyao.webrtcdemo.webrtc.socket.WebSocketManager;

import org.webrtc.EglBase;

public class WebRTCManager {
    private WebSocketManager webSocketManager;
    private PeerConnectionManager peerConnectionManager;
    private String roomId = "";
    private static final WebRTCManager ourInstance = new WebRTCManager();

    public static WebRTCManager getInstance() {
        return ourInstance;
    }

    private WebRTCManager() {
    }

    public void connect(MainActivity activity, String roomId) {
        this.roomId = roomId;
        peerConnectionManager = new PeerConnectionManager();
        webSocketManager = new WebSocketManager(activity, peerConnectionManager);
        webSocketManager.connect("wss://8.210.234.39/wss");
    }

    public void joinRoom(CallActivity callActivity, EglBase eglBase) {
        peerConnectionManager.initContext(callActivity, eglBase);
        webSocketManager.joinRoom(roomId);
    }
}

package com.wangyao.webrtcdemo.webrtc;

import android.util.Log;

import com.wangyao.webrtcdemo.CallActivity;
import com.wangyao.webrtcdemo.MainActivity;
import com.wangyao.webrtcdemo.webrtc.peerconnection.PeerConnectionManager;
import com.wangyao.webrtcdemo.webrtc.socket.WebSocketManager;

import org.webrtc.EglBase;

public class WebRTCManager {
    private static final String TAG = "WebRTCManager";
    private WebSocketManager webSocketManager;
    private PeerConnectionManager peerConnectionManager;
    private String roomId = "";
    private static final WebRTCManager ourInstance = new WebRTCManager();

    public static WebRTCManager getInstance() {
        return ourInstance;
    }

    private WebRTCManager() {
        Log.d(TAG, "WebRTCManager 初始化完成");
    }

    public void connect(MainActivity activity, String roomId) {
        Log.i(TAG, "========== 开始连接WebRTC服务 ==========");
        Log.i(TAG, "房间号: " + roomId);
        
        this.roomId = roomId;
        peerConnectionManager = new PeerConnectionManager();
        webSocketManager = new WebSocketManager(activity, peerConnectionManager);
        
        // 使用本地服务器地址（如果运行了本地服务器）
        // String wsUrl = "ws://10.0.2.2:3000";
        // 使用现有公网服务器
        String wsUrl = "wss://8.210.234.39/wss";
        Log.i(TAG, "WebSocket连接地址: " + wsUrl);
        webSocketManager.connect(wsUrl);
        
        Log.i(TAG, "WebSocket连接请求已发送");
    }

    public void joinRoom(CallActivity callActivity, EglBase eglBase) {
        Log.i(TAG, "========== 加入房间 ==========");
        Log.i(TAG, "房间号: " + roomId);
        Log.i(TAG, "EglBase: " + (eglBase != null ? "已创建" : "未创建"));
        
        peerConnectionManager.initContext(callActivity, eglBase);
        webSocketManager.joinRoom(roomId);
        
        Log.i(TAG, "加入房间请求已发送");
    }
}

package com.wangyao.webrtclib.signaling;

import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.webrtc.IceCandidate;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 信令层：负责与本地 WebRTC 信令服务器（server.py）通信。
 *
 * 协议（与 server.py 一致）：
 *   发送 __join        -> 加入房间
 *   接收 _peers        -> 房间内现有用户 + 本机 id
 *   接收 _new_peer     -> 新用户加入
 *   收发 __offer/_offer、__answer/_answer、__ice_candidate/_ice_candidate
 *   接收 _remove_peer  -> 用户离开
 *
 * 该类只做「信令收发 + 协议解析」，业务逻辑交给 {@link SignalCallback}。
 */
public class WebSocketManager {
    private static final String TAG = "WebSocketManager";

    private WebSocketClient mWebSocketClient;
    private SignalCallback callback;
    private String roomId;
    private String serverUrl;

    public void setCallback(SignalCallback callback) {
        this.callback = callback;
    }

    /**
     * 连接信令服务器，连接成功后自动发送 __join 加入房间。
     */
    public void connect(String url, String roomId) {
        this.serverUrl = url;
        this.roomId = roomId;
        Log.i(TAG, "connect url=" + url + ", room=" + roomId);

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            Log.e(TAG, "URI parse failed: " + e.getMessage());
            if (callback != null) callback.onSignalError("地址非法: " + e.getMessage());
            return;
        }

        mWebSocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                Log.i(TAG, "WebSocket onOpen, status=" + handshakedata.getHttpStatus() + ", message=" + handshakedata.getHttpStatusMessage());
                joinRoom(roomId);
                if (callback != null) callback.onSignalOpen();
            }

            @Override
            public void onMessage(String message) {
                Log.d(TAG, "WebSocket onMessage: " + message);
                handleMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                Log.w(TAG, "WebSocket onClose code=" + code + ", reason=" + reason + ", remote=" + remote);
                if (callback != null) callback.onSignalClose();
            }

            @Override
            public void onError(Exception ex) {
                Log.e(TAG, "WebSocket onError: " + (ex != null ? ex.getMessage() : "null"));
                if (callback != null && ex != null) callback.onSignalError(ex.getMessage());
            }
        };

        if (url.startsWith("wss")) {
            configSsl();
        }
        mWebSocketClient.connect();
    }

    /** 主动断开信令连接。 */
    public void disconnect() {
        if (mWebSocketClient != null) {
            try {
                mWebSocketClient.close();
            } catch (Exception e) {
                Log.w(TAG, "disconnect: " + e.getMessage());
            }
            mWebSocketClient = null;
        }
    }

    public boolean isConnected() {
        return mWebSocketClient != null && mWebSocketClient.isOpen();
    }

    // ----------------------------- 发送信令 ---------------------------------

    private void joinRoom(String roomId) {
        Map<String, Object> data = new HashMap<>();
        data.put("room", roomId);
        send("__join", data);
    }

    public void sendOffer(String socketId, String sdp) {
        Map<String, Object> sdpMap = new HashMap<>();
        sdpMap.put("type", "offer");
        sdpMap.put("sdp", sdp);
        Map<String, Object> data = new HashMap<>();
        data.put("socketId", socketId);
        data.put("sdp", sdpMap);
        send("__offer", data);
    }

    public void sendAnswer(String socketId, String sdp) {
        Map<String, Object> sdpMap = new HashMap<>();
        sdpMap.put("type", "answer");
        sdpMap.put("sdp", sdp);
        Map<String, Object> data = new HashMap<>();
        data.put("socketId", socketId);
        data.put("sdp", sdpMap);
        send("__answer", data);
    }

    public void sendIceCandidate(String socketId, IceCandidate candidate) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", candidate.sdpMid);
        data.put("label", candidate.sdpMLineIndex);
        data.put("candidate", candidate.sdp);
        data.put("socketId", socketId);
        send("__ice_candidate", data);
    }

    private void send(String eventName, Map<String, Object> data) {
        if (mWebSocketClient == null || !mWebSocketClient.isOpen()) {
            Log.w(TAG, "send failed (not connected): " + eventName);
            return;
        }
        Map<String, Object> map = new HashMap<>();
        map.put("eventName", eventName);
        map.put("data", data);
        String json = new JSONObject(map).toString();
        Log.d(TAG, "send: " + json);
        mWebSocketClient.send(json);
    }

    // ----------------------------- 接收解析 ---------------------------------

    @SuppressWarnings("unchecked")
    private void handleMessage(String message) {
        if (callback == null) return;
        Map<String, Object> map = JSON.parseObject(message, Map.class);
        String eventName = (String) map.get("eventName");
        if (eventName == null) return;
        Map<String, Object> data = (Map<String, Object>) map.get("data");
        if (data == null) data = new HashMap<>();

        switch (eventName) {
            case "_peers":
                handlePeers(data);
                break;
            case "_new_peer":
                callback.onNewPeer((String) data.get("socketId"));
                break;
            case "_offer":
                callback.onReceiveOffer((String) data.get("socketId"), extractSdp(data));
                break;
            case "_answer":
                callback.onReceiveAnswer((String) data.get("socketId"), extractSdp(data));
                break;
            case "_ice_candidate":
                handleIceCandidate(data);
                break;
            case "_remove_peer":
                callback.onRemovePeer((String) data.get("socketId"));
                break;
            default:
                Log.w(TAG, "unknown event: " + eventName);
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePeers(Map<String, Object> data) {
        JSONArray arr = (JSONArray) data.get("connections");
        ArrayList<String> connections = new ArrayList<>();
        if (arr != null) {
            connections.addAll(arr.toJavaList(String.class));
        }
        String myId = (String) data.get("you");
        callback.onPeers(connections, myId);
    }

    @SuppressWarnings("unchecked")
    private String extractSdp(Map<String, Object> data) {
        Map<String, Object> sdpDic = (Map<String, Object>) data.get("sdp");
        if (sdpDic == null) return "";
        return (String) sdpDic.get("sdp");
    }

    private void handleIceCandidate(Map<String, Object> data) {
        String socketId = (String) data.get("socketId");
        String sdpMid = (String) data.get("id");
        if (sdpMid == null) sdpMid = "video";
        int sdpMLineIndex = (int) Double.parseDouble(String.valueOf(data.get("label")));
        String candidate = (String) data.get("candidate");
        IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
        callback.onReceiveIceCandidate(socketId, iceCandidate);
    }

    // ----------------------------- SSL 配置 ---------------------------------

    private void configSsl() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            SSLSocketFactory factory = sslContext.getSocketFactory();
            if (factory != null) {
                mWebSocketClient.setSocket(factory.createSocket());
            }
        } catch (Exception e) {
            Log.e(TAG, "configSsl failed: " + e.getMessage());
        }
    }

    /** 信任所有证书（仅用于自建 wss 测试服务器，生产环境请替换为正式证书校验）。 */
    private static class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}

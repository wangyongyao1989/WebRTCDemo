package com.wangyao.webrtcdemo.webrtc.socket;

import android.util.Log;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.wangyao.webrtcdemo.CallActivity;
import com.wangyao.webrtcdemo.MainActivity;
import com.wangyao.webrtcdemo.webrtc.peerconnection.PeerConnectionManager;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.webrtc.IceCandidate;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class WebSocketManager {
    private static final String TAG = "WebSocketManager";
    PeerConnectionManager peerConnectionManager;

    private MainActivity activity;
    private WebSocketClient mWebSocketClient;

    public WebSocketManager(MainActivity activity, PeerConnectionManager peerConnectionManager) {
        Log.d(TAG, "WebSocketManager 初始化");
        this.activity = activity;
        this.peerConnectionManager = peerConnectionManager;
    }

    public void connect(String wss) {
        Log.i(TAG, "========== 开始连接WebSocket ==========");
        Log.i(TAG, "WebSocket地址: " + wss);
        
        URI uri = null;
        try {
            uri = new URI(wss);
            Log.i(TAG, "URI解析成功: " + uri);
        } catch (URISyntaxException e) {
            Log.e(TAG, "URI解析失败: " + e.getMessage());
            e.printStackTrace();
            return;
        }
        
        mWebSocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                Log.i(TAG, "========== WebSocket连接成功 ==========");
                Log.i(TAG, "握手状态: " + handshakedata.getHttpStatus());
                CallActivity.openActivity(activity);
            }

            @Override
            public void onMessage(String message) {
                Log.i(TAG, "========== 收到WebSocket消息 ==========");
                Log.d(TAG, "消息长度: " + (message != null ? message.length() : 0));
                Log.d(TAG, "消息内容: " + message);
                
                Map map = JSON.parseObject(message, Map.class);
                String eventName = (String) map.get("eventName");
                Log.d(TAG, "事件名称: " + eventName);
                
                if (eventName.equals("_peers")) {
                    Log.d(TAG, "处理事件: _peers (房间内现有用户)");
                    hanleJoinRoom(map);
                } else if (eventName.equals("_answer")) {
                    Log.d(TAG, "处理事件: _answer (收到Answer)");
                    handleAnswer(map);
                } else if (eventName.equals("_ice_candidate")) {
                    Log.d(TAG, "处理事件: _ice_candidate (收到ICE候选)");
                    handleRemoteCandidate(map);
                } else if (eventName.equals("_new_peer")) {
                    Log.d(TAG, "处理事件: _new_peer (新用户加入)");
                    handleRemoteInRoom(map);
                } else if (eventName.equals("_offer")) {
                    Log.d(TAG, "处理事件: _offer (收到Offer)");
                    handleOffer(map);
                } else {
                    Log.w(TAG, "未知事件: " + eventName);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                Log.w(TAG, "========== WebSocket连接关闭 ==========");
                Log.w(TAG, "关闭代码: " + code);
                Log.w(TAG, "关闭原因: " + reason);
                Log.w(TAG, "是否远程关闭: " + remote);
            }

            @Override
            public void onError(Exception ex) {
                Log.e(TAG, "========== WebSocket错误 ==========");
                Log.e(TAG, "错误信息: " + ex.getMessage());
                ex.printStackTrace();
            }
        };

        if (wss.startsWith("wss")) {
            Log.d(TAG, "使用WSS安全连接");
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{new TrustManagerTest()}, new SecureRandom());
                SSLSocketFactory factory = null;
                if (sslContext != null) {
                    factory = sslContext.getSocketFactory();
                }
                if (factory != null) {
                    mWebSocketClient.setSocket(factory.createSocket());
                    Log.i(TAG, "SSL配置完成");
                }
            } catch (Exception e) {
                Log.e(TAG, "SSL配置失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        Log.d(TAG, "开始WebSocket连接");
        mWebSocketClient.connect();
    }

    private void handleRemoteInRoom(Map map) {
        Log.i(TAG, "========== 处理新用户加入 ==========");
        Map data = (Map) map.get("data");
        String socketId;
        if (data != null) {
            socketId = (String) data.get("socketId");
            Log.i(TAG, "新用户ID: " + socketId);
            peerConnectionManager.onRemoteJoinToRoom(socketId);
        } else {
            Log.e(TAG, "数据为空");
        }
    }

    private void handleOffer(Map map) {
        Log.i(TAG, "========== 处理Offer ==========");
        Map data = (Map) map.get("data");
        Map sdpDic;
        if (data != null) {
            sdpDic = (Map) data.get("sdp");
            String socketId = (String) data.get("socketId");
            String sdp = (String) sdpDic.get("sdp");
            Log.i(TAG, "Offer来自: " + socketId);
            Log.d(TAG, "SDP长度: " + (sdp != null ? sdp.length() : 0));
            peerConnectionManager.onReceiveOffer(socketId, sdp);
        } else {
            Log.e(TAG, "数据为空");
        }
    }

    public void joinRoom(String roomId) {
        Log.i(TAG, "========== 发送加入房间请求 ==========");
        Log.i(TAG, "房间号: " + roomId);
        
        Map<String, Object> map = new HashMap<>();
        map.put("eventName", "__join");
        Map<String, String> childMap = new HashMap<>();
        childMap.put("room", roomId);
        map.put("data", childMap);
        JSONObject object = new JSONObject(map);
        final String jsonString = object.toString();
        
        Log.d(TAG, "发送消息: " + jsonString);
        
        if (mWebSocketClient != null && mWebSocketClient.isOpen()) {
            mWebSocketClient.send(jsonString);
            Log.i(TAG, "加入房间请求已发送");
        } else {
            Log.e(TAG, "WebSocket未连接，无法发送消息");
        }
    }

    private void hanleJoinRoom(Map map) {
        Log.i(TAG, "========== 处理加入房间响应 ==========");
        Map data = (Map) map.get("data");
        JSONArray arr;
        if (data != null) {
            arr = (JSONArray) data.get("connections");
            String js = JSONObject.toJSONString(arr, SerializerFeature.WriteClassName);
            ArrayList<String> connections = (ArrayList<String>) JSONObject.parseArray(js, String.class);
            String myId = (String) data.get("you");
            
            Log.i(TAG, "我的ID: " + myId);
            Log.i(TAG, "房间内现有连接数: " + connections.size());
            Log.d(TAG, "现有连接ID: " + connections.toString());
            
            peerConnectionManager.joinToRoom(this, connections, true, myId);
        } else {
            Log.e(TAG, "数据为空");
        }
    }

    public void sendOffer(String socketId, String sdp) {
        Log.i(TAG, "========== 发送Offer ==========");
        Log.i(TAG, "目标用户: " + socketId);
        Log.d(TAG, "SDP长度: " + (sdp != null ? sdp.length() : 0));
        
        HashMap<String, Object> childMap1 = new HashMap();
        childMap1.put("type", "offer");
        childMap1.put("sdp", sdp);

        HashMap<String, Object> childMap2 = new HashMap();
        childMap2.put("socketId", socketId);
        childMap2.put("sdp", childMap1);

        HashMap<String, Object> map = new HashMap();
        map.put("eventName", "__offer");
        map.put("data", childMap2);
        JSONObject object = new JSONObject(map);
        String jsonString = object.toString();
        
        Log.d(TAG, "发送消息: " + jsonString);
        
        if (mWebSocketClient != null && mWebSocketClient.isOpen()) {
            mWebSocketClient.send(jsonString);
            Log.i(TAG, "Offer已发送");
        } else {
            Log.e(TAG, "WebSocket未连接，无法发送Offer");
        }
    }

    public void sendAnswer(String socketId, String sdp) {
        Log.i(TAG, "========== 发送Answer ==========");
        Log.i(TAG, "目标用户: " + socketId);
        Log.d(TAG, "SDP长度: " + (sdp != null ? sdp.length() : 0));
        
        Map<String, Object> childMap1 = new HashMap();
        childMap1.put("type", "answer");
        childMap1.put("sdp", sdp);
        HashMap<String, Object> childMap2 = new HashMap();
        childMap2.put("socketId", socketId);
        childMap2.put("sdp", childMap1);
        HashMap<String, Object> map = new HashMap();
        map.put("eventName", "__answer");
        map.put("data", childMap2);
        JSONObject object = new JSONObject(map);
        String jsonString = object.toString();
        
        Log.d(TAG, "发送消息: " + jsonString);
        
        if (mWebSocketClient != null && mWebSocketClient.isOpen()) {
            mWebSocketClient.send(jsonString);
            Log.i(TAG, "Answer已发送");
        } else {
            Log.e(TAG, "WebSocket未连接，无法发送Answer");
        }
    }

    private void handleRemoteCandidate(Map map) {
        Log.i(TAG, "========== 处理ICE候选 ==========");
        Map data = (Map) map.get("data");
        String socketId;
        if (data != null) {
            socketId = (String) data.get("socketId");
            String sdpMid = (String) data.get("id");
            sdpMid = (null == sdpMid) ? "video" : sdpMid;
            int sdpMLineIndex = (int) Double.parseDouble(String.valueOf(data.get("label")));
            String candidate = (String) data.get("candidate");
            
            Log.i(TAG, "ICE候选来自: " + socketId);
            Log.d(TAG, "SDP Mid: " + sdpMid);
            Log.d(TAG, "MLine Index: " + sdpMLineIndex);
            Log.d(TAG, "Candidate: " + candidate);
            
            IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
            peerConnectionManager.onRemoteIceCandidate(socketId, iceCandidate);
        } else {
            Log.e(TAG, "数据为空");
        }
    }

    private void handleAnswer(Map map) {
        Log.i(TAG, "========== 处理Answer ==========");
        Map data = (Map) map.get("data");
        Map sdpDic;
        if (data != null) {
            sdpDic = (Map) data.get("sdp");
            String socketId = (String) data.get("socketId");
            String sdp = (String) sdpDic.get("sdp");
            
            Log.i(TAG, "Answer来自: " + socketId);
            Log.d(TAG, "SDP长度: " + (sdp != null ? sdp.length() : 0));
            
            peerConnectionManager.onReceiverAnswer(socketId, sdp);
        } else {
            Log.e(TAG, "数据为空");
        }
    }

    public void sendIceCandidate(String socketId, IceCandidate iceCandidate) {
        Log.i(TAG, "========== 发送ICE候选 ==========");
        Log.i(TAG, "目标用户: " + socketId);
        Log.d(TAG, "SDP Mid: " + iceCandidate.sdpMid);
        Log.d(TAG, "MLine Index: " + iceCandidate.sdpMLineIndex);
        Log.d(TAG, "Candidate: " + iceCandidate.sdp);
        
        HashMap<String, Object> childMap = new HashMap();
        childMap.put("id", iceCandidate.sdpMid);
        childMap.put("label", iceCandidate.sdpMLineIndex);
        childMap.put("candidate", iceCandidate.sdp);
        childMap.put("socketId", socketId);
        HashMap<String, Object> map = new HashMap();
        map.put("eventName", "__ice_candidate");
        map.put("data", childMap);
        JSONObject object = new JSONObject(map);
        String jsonString = object.toString();
        
        Log.d(TAG, "发送消息: " + jsonString);
        
        if (mWebSocketClient != null && mWebSocketClient.isOpen()) {
            mWebSocketClient.send(jsonString);
            Log.i(TAG, "ICE候选已发送");
        } else {
            Log.e(TAG, "WebSocket未连接，无法发送ICE候选");
        }
    }

    public static class TrustManagerTest implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            Log.d("TrustManager", "checkClientTrusted");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            Log.d("TrustManager", "checkServerTrusted");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}

package com.wangyao.webrtcdemo.webrtc.peerconnection;

import android.util.Log;

import com.wangyao.webrtcdemo.CallActivity;
import com.wangyao.webrtcdemo.webrtc.socket.WebSocketManager;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PeerConnectionManager {
    private static final String TAG = "PeerConnectionManager";

    private String myId;
    private boolean videoEnable;
    private ExecutorService executor;

    private PeerConnectionFactory factory;

    private CallActivity context;
    private EglBase rootEglBase;
    private MediaStream mediaStream;
    WebSocketManager webSocketManager;
    private ArrayList<String> connectionIdArray;
    private Map<String, Peer> connectionPeerDic;
    private ArrayList<PeerConnection.IceServer> ICEServers;

    enum Role {Caller, Receiver}

    private Role role;

    public PeerConnectionManager() {
        Log.d(TAG, "PeerConnectionManager 初始化");
        executor = Executors.newSingleThreadExecutor();
        connectionIdArray = new ArrayList<>();
        connectionPeerDic = new HashMap<>();
        ICEServers = new ArrayList<>();
        PeerConnection.IceServer iceServer1 = PeerConnection.IceServer.builder("turn:8.210.234.39:3478?transport=udp")
                .setUsername("ddssingsong").setPassword("123456").createIceServer();
        ICEServers.add(iceServer1);
        Log.d(TAG, "ICE服务器配置: " + iceServer1.uri);
    }

    public void initContext(CallActivity context, EglBase rootEglBase) {
        Log.i(TAG, "初始化上下文");
        this.context = context;
        this.rootEglBase = rootEglBase;
    }

    public void joinToRoom(WebSocketManager javaWebSocket, ArrayList<String> connections, boolean isVideoEnable,
                           String myId) {
        Log.i(TAG, "========== 加入房间 ==========");
        Log.i(TAG, "我的ID: " + myId);
        Log.i(TAG, "视频启用: " + isVideoEnable);
        Log.i(TAG, "房间内现有连接数: " + connections.size());
        Log.i(TAG, "现有连接ID: " + connections.toString());
        
        this.myId = myId;
        this.videoEnable = isVideoEnable;
        this.webSocketManager = javaWebSocket;
        connectionIdArray.addAll(connections);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "开始初始化WebRTC组件");
                
                if (factory == null) {
                    Log.d(TAG, "创建PeerConnectionFactory");
                    factory = creteConnectionFactory();
                    Log.i(TAG, "PeerConnectionFactory创建成功");
                }
                
                if (mediaStream == null) {
                    Log.d(TAG, "创建本地媒体流");
                    createLoaclStream();
                    Log.i(TAG, "本地媒体流创建成功");
                }
                
                Log.d(TAG, "创建PeerConnection连接");
                createPeerConnections();
                Log.i(TAG, "PeerConnection连接创建完成，连接数: " + connectionPeerDic.size());
                
                Log.d(TAG, "添加本地流到PeerConnection");
                addStreams();
                Log.i(TAG, "本地流添加完成");
                
                Log.d(TAG, "创建Offer并发送");
                createOffers();
                Log.i(TAG, "Offer创建完成");
            }
        });
    }

    private void addStreams() {
        Log.d(TAG, "addStreams - 当前连接数: " + connectionPeerDic.size());
        for (Map.Entry<String, Peer> entry : connectionPeerDic.entrySet()) {
            if (mediaStream == null) {
                createLoaclStream();
            }
            entry.getValue().pc.addStream(mediaStream);
            Log.d(TAG, "添加流到PeerConnection: " + entry.getKey());
        }
    }

    private void createOffers() {
        Log.d(TAG, "createOffers - 需要创建Offer的连接数: " + connectionPeerDic.size());
        for (Map.Entry<String, Peer> entry : connectionPeerDic.entrySet()) {
            role = Role.Caller;
            Peer mPeer = entry.getValue();
            Log.i(TAG, "为连接 " + entry.getKey() + " 创建Offer");
            mPeer.pc.createOffer(mPeer, offerOrAnswerConstraint());
        }
    }

    private MediaConstraints offerOrAnswerConstraint() {
        MediaConstraints mediaConstraints = new MediaConstraints();
        ArrayList<MediaConstraints.KeyValuePair> keyValuePairs = new ArrayList<>();
        keyValuePairs.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", String.valueOf(videoEnable)));
        mediaConstraints.mandatory.addAll(keyValuePairs);
        Log.d(TAG, "媒体约束 - 音频: true, 视频: " + videoEnable);
        return mediaConstraints;
    }

    private void createPeerConnections() {
        Log.d(TAG, "createPeerConnections - 创建 " + connectionIdArray.size() + " 个PeerConnection");
        for (String str : connectionIdArray) {
            Log.i(TAG, "创建PeerConnection: " + str);
            Peer peer = new Peer(str);
            connectionPeerDic.put(str, peer);
        }
    }

    public void onReceiverAnswer(String socketId, String sdp) {
        Log.i(TAG, "========== 接收到Answer ==========");
        Log.i(TAG, "来自连接: " + socketId);
        Log.d(TAG, "SDP长度: " + (sdp != null ? sdp.length() : 0));
        
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Peer mPeer = connectionPeerDic.get(socketId);
                if (mPeer != null) {
                    SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
                    Log.d(TAG, "设置远端SDP (Answer)");
                    mPeer.pc.setRemoteDescription(mPeer, sessionDescription);
                    Log.i(TAG, "远端SDP设置完成");
                } else {
                    Log.e(TAG, "未找到对应的PeerConnection: " + socketId);
                }
            }
        });
    }

    public void onRemoteIceCandidate(String socketId, IceCandidate iceCandidate) {
        Log.i(TAG, "========== 接收到ICE候选 ==========");
        Log.i(TAG, "来自连接: " + socketId);
        Log.d(TAG, "ICE候选: " + iceCandidate.sdpMid + ", " + iceCandidate.sdpMLineIndex);
        
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Peer peer = connectionPeerDic.get(socketId);
                if (peer != null) {
                    Log.d(TAG, "添加ICE候选到PeerConnection");
                    peer.pc.addIceCandidate(iceCandidate);
                    Log.i(TAG, "ICE候选添加完成");
                } else {
                    Log.e(TAG, "未找到对应的PeerConnection: " + socketId);
                }
            }
        });
    }

    public void onRemoteJoinToRoom(String socketId) {
        Log.i(TAG, "========== 远端用户加入房间 ==========");
        Log.i(TAG, "新用户ID: " + socketId);
        
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (mediaStream == null) {
                    Log.d(TAG, "本地流为空，创建本地流");
                    createLoaclStream();
                }
                
                Log.d(TAG, "为新用户创建PeerConnection: " + socketId);
                Peer mPeer = new Peer(socketId);
                mPeer.pc.addStream(mediaStream);
                connectionIdArray.add(socketId);
                connectionPeerDic.put(socketId, mPeer);
                
                Log.i(TAG, "新用户PeerConnection创建完成，当前连接数: " + connectionPeerDic.size());
            }
        });
    }

    public void onReceiveOffer(String socketId, String description) {
        Log.i(TAG, "========== 接收到Offer ==========");
        Log.i(TAG, "来自连接: " + socketId);
        Log.d(TAG, "SDP长度: " + (description != null ? description.length() : 0));
        
        executor.execute(new Runnable() {
            @Override
            public void run() {
                role = Role.Receiver;
                Peer mPeer = connectionPeerDic.get(socketId);
                
                if (mPeer != null) {
                    SessionDescription sdp = new SessionDescription(SessionDescription.Type.OFFER, description);
                    Log.d(TAG, "设置远端SDP (Offer)");
                    mPeer.pc.setRemoteDescription(mPeer, sdp);
                    Log.i(TAG, "远端SDP设置完成");
                } else {
                    Log.e(TAG, "未找到对应的PeerConnection: " + socketId);
                }
            }
        });
    }

    private class Peer implements SdpObserver, PeerConnection.Observer {
        private PeerConnection pc;
        private String userId;

        public Peer(String socketId) {
            this.userId = socketId;
            Log.d(TAG, "Peer初始化 - 用户ID: " + socketId);
            pc = createPeerConnection();
            Log.i(TAG, "PeerConnection创建成功: " + socketId);
        }

        @Override
        public void onCreateSuccess(SessionDescription origSdp) {
            Log.i(TAG, "========== SDP创建成功 ==========");
            Log.i(TAG, "用户: " + userId);
            Log.d(TAG, "SDP类型: " + origSdp.type);
            Log.d(TAG, "SDP长度: " + origSdp.description.length());
            Log.d(TAG, "设置本地SDP");
            pc.setLocalDescription(Peer.this, origSdp);
        }

        @Override
        public void onSetSuccess() {
            Log.i(TAG, "========== SDP设置成功 ==========");
            Log.i(TAG, "用户: " + userId);
            Log.d(TAG, "当前信令状态: " + pc.signalingState());
            Log.d(TAG, "当前ICE连接状态: " + pc.iceConnectionState());
            
            if (pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                Log.i(TAG, "状态: HAVE_LOCAL_OFFER - 准备发送Offer");
                if (role == Role.Caller) {
                    String sdp = pc.getLocalDescription().description;
                    Log.d(TAG, "发送Offer到信令服务器");
                    webSocketManager.sendOffer(userId, sdp);
                    Log.i(TAG, "Offer已发送");
                } else if (role == Role.Receiver) {
                    String sdp = pc.getLocalDescription().description;
                    Log.d(TAG, "发送Answer到信令服务器");
                    webSocketManager.sendAnswer(userId, sdp);
                    Log.i(TAG, "Answer已发送");
                }
            } else if (pc.signalingState() == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                Log.i(TAG, "状态: HAVE_REMOTE_OFFER - 准备创建Answer");
                pc.createAnswer(Peer.this, offerOrAnswerConstraint());
            } else if (pc.signalingState() == PeerConnection.SignalingState.STABLE) {
                Log.i(TAG, "状态: STABLE - 连接稳定");
                if (role == Role.Receiver) {
                    String sdp = pc.getLocalDescription().description;
                    Log.d(TAG, "发送Answer到信令服务器");
                    webSocketManager.sendAnswer(userId, sdp);
                    Log.i(TAG, "Answer已发送");
                }
            }
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, "========== SDP设置失败 ==========");
            Log.e(TAG, "用户: " + userId);
            Log.e(TAG, "错误信息: " + s);
        }

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, "========== SDP创建失败 ==========");
            Log.e(TAG, "用户: " + userId);
            Log.e(TAG, "错误信息: " + s);
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(TAG, "信令状态变化 - 用户: " + userId + ", 状态: " + signalingState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.i(TAG, "ICE连接状态变化 - 用户: " + userId + ", 状态: " + iceConnectionState);
            if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                Log.i(TAG, "========== ICE连接成功 ==========");
                Log.i(TAG, "用户: " + userId);
            } else if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                Log.e(TAG, "========== ICE连接失败 ==========");
                Log.e(TAG, "用户: " + userId);
            } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                Log.w(TAG, "ICE连接断开 - 用户: " + userId);
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
            Log.d(TAG, "ICE接收状态变化 - 用户: " + userId + ", 接收中: " + b);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(TAG, "ICE收集状态变化 - 用户: " + userId + ", 状态: " + iceGatheringState);
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.i(TAG, "========== 收到本地ICE候选 ==========");
            Log.i(TAG, "用户: " + userId);
            Log.d(TAG, "ICE候选: " + iceCandidate.sdpMid);
            Log.d(TAG, "MLineIndex: " + iceCandidate.sdpMLineIndex);
            Log.d(TAG, "Candidate: " + iceCandidate.sdp);
            webSocketManager.sendIceCandidate(userId, iceCandidate);
            Log.i(TAG, "ICE候选已发送到信令服务器");
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            Log.d(TAG, "ICE候选被移除 - 用户: " + userId + ", 数量: " + iceCandidates.length);
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.i(TAG, "========== 收到远端媒体流 ==========");
            Log.i(TAG, "来自用户: " + userId);
            Log.d(TAG, "音频轨道数: " + mediaStream.audioTracks.size());
            Log.d(TAG, "视频轨道数: " + mediaStream.videoTracks.size());
            context.onAddRemoteStream(mediaStream, userId);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.w(TAG, "远端媒体流被移除 - 用户: " + userId);
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.d(TAG, "数据通道创建 - 用户: " + userId);
        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(TAG, "需要重新协商 - 用户: " + userId);
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            Log.d(TAG, "收到轨道 - 用户: " + userId);
        }

        private PeerConnection createPeerConnection() {
            if (factory == null) {
                Log.d(TAG, "PeerConnectionFactory为空，重新创建");
                factory = creteConnectionFactory();
            }
            PeerConnection.RTCConfiguration rtcConfiguration = new PeerConnection.RTCConfiguration(ICEServers);
            Log.d(TAG, "创建PeerConnection，ICE服务器数: " + ICEServers.size());
            return factory.createPeerConnection(rtcConfiguration, this);
        }
    }

    private void createLoaclStream() {
        Log.i(TAG, "========== 创建本地媒体流 ==========");
        
        mediaStream = factory.createLocalMediaStream("ARDAMS");
        Log.d(TAG, "媒体流ID: ARDAMS");

        AudioSource audioSource = factory.createAudioSource(createAudioConstraints());
        AudioTrack audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource);
        mediaStream.addTrack(audioTrack);
        Log.i(TAG, "音频轨道创建成功");

        if (videoEnable) {
            Log.d(TAG, "视频已启用，创建视频轨道");
            VideoCapturer videoCapturer = createVideoCapture();
            if (videoCapturer == null) {
                Log.e(TAG, "视频捕获器创建失败");
                return;
            }
            
            VideoSource videoSource = factory.createVideoSource(videoCapturer.isScreencast());
            SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
            videoCapturer.startCapture(320, 240, 10);
            
            VideoTrack videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource);
            mediaStream.addTrack(videoTrack);
            Log.i(TAG, "视频轨道创建成功 - 分辨率: 320x240, 帧率: 10");
            
            if (context != null) {
                context.onSetLocalStream(mediaStream, myId);
            }
        }
        
        Log.i(TAG, "本地媒体流创建完成");
    }

    private VideoCapturer createVideoCapture() {
        Log.d(TAG, "创建视频捕获器");
        VideoCapturer videoCapturer = null;
        
        if (Camera2Enumerator.isSupported(context)) {
            Log.d(TAG, "使用Camera2 API");
            Camera2Enumerator enumerator = new Camera2Enumerator(context);
            videoCapturer = createCameraCapture(enumerator);
        } else {
            Log.d(TAG, "使用Camera1 API");
            Camera1Enumerator enumerator = new Camera1Enumerator(true);
            videoCapturer = createCameraCapture(enumerator);
        }
        
        if (videoCapturer != null) {
            Log.i(TAG, "视频捕获器创建成功");
        } else {
            Log.e(TAG, "视频捕获器创建失败");
        }
        
        return videoCapturer;
    }

    private VideoCapturer createCameraCapture(CameraEnumerator enumerator) {
        String[] deviceNames = enumerator.getDeviceNames();
        Log.d(TAG, "可用摄像头数量: " + deviceNames.length);
        
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    Log.i(TAG, "使用前置摄像头: " + deviceName);
                    return videoCapturer;
                }
            }
        }
        
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    Log.i(TAG, "使用后置摄像头: " + deviceName);
                    return videoCapturer;
                }
            }
        }
        
        Log.e(TAG, "未找到可用的摄像头");
        return null;
    }

    private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
    private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";

    private MediaConstraints createAudioConstraints() {
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "true"));
        Log.d(TAG, "音频约束配置完成 - 回音消除: true, 噪声抑制: true");
        return audioConstraints;
    }

    private PeerConnectionFactory creteConnectionFactory() {
        Log.i(TAG, "========== 创建PeerConnectionFactory ==========");
        
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).
                createInitializationOptions());
        
        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        
        PeerConnectionFactory peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
                .setVideoDecoderFactory(decoderFactory)
                .setVideoEncoderFactory(encoderFactory)
                .createPeerConnectionFactory();
        
        Log.i(TAG, "PeerConnectionFactory创建成功");
        return peerConnectionFactory;
    }
}

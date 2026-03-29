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
    private static final String TAG = "WebRTC";

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
        executor = Executors.newSingleThreadExecutor();
        connectionIdArray = new ArrayList<>();
        connectionPeerDic = new HashMap<>();
        ICEServers = new ArrayList<>();
        PeerConnection.IceServer iceServer1 = PeerConnection.IceServer.builder("turn:8.210.234.39:3478?transport=udp")
                .setUsername("ddssingsong").setPassword("123456").createIceServer();
        ICEServers.add(iceServer1);
    }

    public void initContext(CallActivity context, EglBase rootEglBase) {
        this.context = context;
        this.rootEglBase = rootEglBase;
    }

    public void joinToRoom(WebSocketManager javaWebSocket, ArrayList<String> connections, boolean isVideoEnable,
                           String myId) {
        this.myId = myId;
        this.videoEnable = isVideoEnable;
        this.webSocketManager = javaWebSocket;
        connectionIdArray.addAll(connections);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (factory == null) {
                    factory = creteConnectionFactory();
                }
                if (mediaStream == null) {
                    createLoaclStream();
                }
                createPeerConnections();
                addStreams();
                createOffers();
            }
        });
    }

    private void addStreams() {
        for (Map.Entry<String, Peer> entry : connectionPeerDic.entrySet()) {
            if (mediaStream == null) {
                createLoaclStream();
            }
            entry.getValue().pc.addStream(mediaStream);
        }
    }

    private void createOffers() {
        for (Map.Entry<String, Peer> entry : connectionPeerDic.entrySet()) {
            role = Role.Caller;
            Peer mPeer = entry.getValue();
            mPeer.pc.createOffer(mPeer, offerOrAnswerConstraint());
        }
    }

    private MediaConstraints offerOrAnswerConstraint() {
        MediaConstraints mediaConstraints = new MediaConstraints();
        ArrayList<MediaConstraints.KeyValuePair> keyValuePairs = new ArrayList<>();
        keyValuePairs.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", String.valueOf(videoEnable)));
        mediaConstraints.mandatory.addAll(keyValuePairs);
        return mediaConstraints;
    }

    private void createPeerConnections() {
        for (String str : connectionIdArray) {
            Peer peer = new Peer(str);
            connectionPeerDic.put(str, peer);
        }
    }

    public void onReceiverAnswer(String socketId, String sdp) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Peer mPeer = connectionPeerDic.get(socketId);
                if (mPeer != null) {
                    SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
                    mPeer.pc.setRemoteDescription(mPeer, sessionDescription);
                }
            }
        });
    }

    public void onRemoteIceCandidate(String socketId, IceCandidate iceCandidate) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Peer peer = connectionPeerDic.get(socketId);
                if (peer != null) {
                    peer.pc.addIceCandidate(iceCandidate);
                }
            }
        });
    }

    public void onRemoteJoinToRoom(String socketId) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (mediaStream == null) {
                    createLoaclStream();
                }
                Peer mPeer = new Peer(socketId);
                mPeer.pc.addStream(mediaStream);
                connectionIdArray.add(socketId);
                connectionPeerDic.put(socketId, mPeer);
            }
        });
    }

    public void onReceiveOffer(String socketId, String description) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                role = Role.Receiver;
                Peer mPeer = connectionPeerDic.get(socketId);
                SessionDescription sdp = new SessionDescription(SessionDescription.Type.OFFER, description);
                if (mPeer != null) {
                    mPeer.pc.setRemoteDescription(mPeer, sdp);
                }
            }
        });
    }

    private class Peer implements SdpObserver, PeerConnection.Observer {
        private PeerConnection pc;
        private String userId;

        public Peer(String socketId) {
            this.userId = socketId;
            pc = createPeerConnection();
        }

        @Override
        public void onCreateSuccess(SessionDescription origSdp) {
            Log.v(TAG, "sdp create success: " + origSdp.description);
            pc.setLocalDescription(Peer.this, origSdp);
        }

        @Override
        public void onSetSuccess() {
            if (pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                if (role == Role.Caller) {
                    webSocketManager.sendOffer(userId, pc.getLocalDescription().description);
                } else if (role == Role.Receiver) {
                    webSocketManager.sendAnswer(userId, pc.getLocalDescription().description);
                }
            } else if (pc.signalingState() == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                pc.createAnswer(Peer.this, offerOrAnswerConstraint());
            } else if (pc.signalingState() == PeerConnection.SignalingState.STABLE) {
                if (role == Role.Receiver) {
                    Log.i(TAG, "onSetSuccess: send answer");
                    webSocketManager.sendAnswer(userId, pc.getLocalDescription().description);
                }
            }
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, "onSetFailure: " + s);
        }

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, "onCreateFailure: " + s);
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(TAG, "onSignalingChange: " + signalingState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(TAG, "onIceGatheringChange: " + iceGatheringState);
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.i(TAG, "onIceCandidate: " + iceCandidate.toString());
            webSocketManager.sendIceCandidate(userId, iceCandidate);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            context.onAddRemoteStream(mediaStream, userId);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        }

        private PeerConnection createPeerConnection() {
            if (factory == null) {
                factory = creteConnectionFactory();
            }
            PeerConnection.RTCConfiguration rtcConfiguration = new PeerConnection.RTCConfiguration(ICEServers);
            return factory.createPeerConnection(rtcConfiguration, this);
        }
    }

    private void createLoaclStream() {
        mediaStream = factory.createLocalMediaStream("ARDAMS");

        AudioSource audioSource = factory.createAudioSource(createAudioConstraints());
        AudioTrack audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource);
        mediaStream.addTrack(audioTrack);

        if (videoEnable) {
            VideoCapturer videoCapturer = createVideoCapture();
            VideoSource videoSource = factory.createVideoSource(videoCapturer.isScreencast());
            SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
            videoCapturer.startCapture(320, 240, 10);
            VideoTrack videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource);
            mediaStream.addTrack(videoTrack);
            if (context != null) {
                context.onSetLocalStream(mediaStream, myId);
            }
        }
    }

    private VideoCapturer createVideoCapture() {
        VideoCapturer videoCapturer = null;
        if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator enumerator = new Camera2Enumerator(context);
            videoCapturer = createCameraCapture(enumerator);
        } else {
            Camera1Enumerator enumerator = new Camera1Enumerator(true);
            videoCapturer = createCameraCapture(enumerator);
        }
        return videoCapturer;
    }

    private VideoCapturer createCameraCapture(CameraEnumerator enumerator) {
        String[] deviceNames = enumerator.getDeviceNames();
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
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
        return audioConstraints;
    }

    private PeerConnectionFactory creteConnectionFactory() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).
                createInitializationOptions());
        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        PeerConnectionFactory peerConnectionFactory = PeerConnectionFactory.builder().setOptions(options).setAudioDeviceModule(JavaAudioDeviceModule.builder(context)
                .createAudioDeviceModule()).setVideoDecoderFactory(decoderFactory).setVideoEncoderFactory(encoderFactory).createPeerConnectionFactory();
        return peerConnectionFactory;
    }
}

package com.wangyao.webrtclib;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import com.wangyao.webrtclib.peer.PeerConnectionManager;
import com.wangyao.webrtclib.render.ProxyVideoSink;
import com.wangyao.webrtclib.signaling.WebSocketManager;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.List;

/**
 * WebRTC 模块对外门面（Facade）。UI 层只与本类交互。
 *
 * 典型调用顺序：
 *   1. {@link #init(Context, WebRTCEventListener)}        创建 EglBase / Factory / 本地流
 *   2. {@link #setupLocalVideo(boolean)}                  获取本地预览渲染器
 *   3. {@link #connect(String, String)}                   连接信令服务器并加入房间
 *   4. 回调 onRemoteStream 后 {@link #setupRemoteVideo}    获取远端渲染器
 *   5. {@link #muteAudio} / {@link #switchCamera}          通话中控制
 *   6. {@link #hangup()} / {@link #release()}              挂断并释放
 */
public class WebRTCManager implements PeerConnectionManager.PeerManagerListener {
    private static final String TAG = "WebRTCManager";

    private static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private static final String AUDIO_TRACK_ID = "ARDAMSa0";
    private static final String MEDIA_STREAM_ID = "ARDAMS";
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int VIDEO_FPS = 15;

    private static volatile WebRTCManager instance;

    private Context appContext;
    private WebRTCEventListener listener;

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private MediaStream localStream;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private SurfaceTextureHelper surfaceTextureHelper;
    /** 本地预览的 sink 代理，便于切换渲染目标。 */
    private ProxyVideoSink localSink;

    private WebSocketManager socketManager;
    private PeerConnectionManager peerManager;
    private AudioManager audioManager;

    private boolean isSwitchingCamera = false;
    private boolean released = false;

    private WebRTCManager() {
    }

    public static WebRTCManager getInstance() {
        if (instance == null) {
            synchronized (WebRTCManager.class) {
                if (instance == null) {
                    instance = new WebRTCManager();
                }
            }
        }
        return instance;
    }

    // ============================ 生命周期 ============================

    public void init(Context context, WebRTCEventListener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.released = false;
        Log.i(TAG, "init");

        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);

        eglBase = EglBase.create();
        factory = createFactory();
        localStream = createLocalStream();

        socketManager = new WebSocketManager();
        peerManager = new PeerConnectionManager(
                appContext, factory, eglBase, buildIceServers(),
                localStream, socketManager, this);
        socketManager.setCallback(peerManager);

        // 进入通话模式：扬声器默认开启（视频通话）
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
        }
    }

    public EglBase getEglBase() {
        return eglBase;
    }

    // ============================ 视频渲染 ============================

    /** 创建本地预览渲染器。overlay=true 时置于小窗（Z-order 在上）。 */
    public SurfaceViewRenderer setupLocalVideo(boolean overlay) {
        if (eglBase == null) return null;
        SurfaceViewRenderer renderer = new SurfaceViewRenderer(appContext);
        renderer.init(eglBase.getEglBaseContext(), null);
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        renderer.setMirror(true);
        renderer.setZOrderMediaOverlay(overlay);

        localSink = new ProxyVideoSink();
        localSink.setTarget(renderer);
        if (localVideoTrack != null) {
            localVideoTrack.addSink(localSink);
        }
        return renderer;
    }

    /** 创建远端渲染器。 */
    public SurfaceViewRenderer setupRemoteVideo(String userId, boolean overlay) {
        if (peerManager == null) return null;
        return peerManager.createRemoteRenderer(userId, overlay);
    }

    // ============================ 信令连接 ============================

    public void connect(String serverUrl, String roomId) {
        if (socketManager == null) {
            if (listener != null) listener.onError("请先调用 init()");
            return;
        }
        Log.i(TAG, "connect: " + serverUrl + ", room=" + roomId);
        if (listener != null) listener.onSignalConnecting();
        socketManager.connect(serverUrl, roomId);
    }

    // ============================ 通话控制 ============================

    /** 静音/取消静音本地麦克风。mute=true 表示静音。 */
    public boolean muteAudio(boolean mute) {
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!mute);
            Log.i(TAG, "muteAudio: " + mute);
            return true;
        }
        return false;
    }

    /** 开启/关闭扬声器。 */
    public void toggleSpeaker(boolean on) {
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(on);
        }
    }

    /** 切换前后摄像头。 */
    public void switchCamera() {
        if (isSwitchingCamera || !(videoCapturer instanceof CameraVideoCapturer)) {
            return;
        }
        isSwitchingCamera = true;
        ((CameraVideoCapturer) videoCapturer).switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean isFrontCamera) {
                isSwitchingCamera = false;
                Log.i(TAG, "switchCamera done, front=" + isFrontCamera);
            }

            @Override
            public void onCameraSwitchError(String errorDescription) {
                isSwitchingCamera = false;
                Log.e(TAG, "switchCamera error: " + errorDescription);
            }
        });
    }

    /** 挂断：关闭所有 peer 并断开信令（保留 factory/流，便于再次通话或由 release 清理）。 */
    public void hangup() {
        Log.i(TAG, "hangup");
        if (peerManager != null) peerManager.closeAll();
        if (socketManager != null) socketManager.disconnect();
    }

    /** 释放全部资源（通话结束销毁时调用）。 */
    public void release() {
        if (released) return;
        released = true;
        Log.i(TAG, "release");

        if (peerManager != null) peerManager.closeAll();
        if (socketManager != null) socketManager.disconnect();

        // 释放摄像头与媒体源
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            videoCapturer.dispose();
            videoCapturer = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (localSink != null) {
            localSink.setTarget(null);
            localSink = null;
        }

        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
        }

        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        listener = null;
    }

    // ============================ PeerManagerListener ============================

    @Override
    public void onSignalConnecting() {
        if (listener != null) listener.onSignalConnecting();
    }

    @Override
    public void onSignalConnected() {
        if (listener != null) listener.onSignalConnected();
    }

    @Override
    public void onSignalClosed() {
        if (listener != null) listener.onSignalClosed();
    }

    @Override
    public void onJoinedRoom(String myId) {
        if (listener != null) listener.onJoinedRoom(myId);
    }

    @Override
    public void onRemoteStreamReady(String userId) {
        if (listener != null) listener.onRemoteStream(userId);
    }

    @Override
    public void onUserLeave(String userId) {
        if (listener != null) listener.onUserLeave(userId);
    }

    // ============================ 内部构建 ============================

    private List<PeerConnection.IceServer> buildIceServers() {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        // 本地服务器场景：仅用公共 STUN 即可通过 host 候选互通；
        // 如需跨网络穿越，可在此追加 turn: 服务器。
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer());
        return servers;
    }

    private PeerConnectionFactory createFactory() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(appContext).createInitializationOptions());

        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(
                eglBase.getEglBaseContext());
        JavaAudioDeviceModule audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                .createAudioDeviceModule();
        return PeerConnectionFactory.builder()
                .setOptions(new PeerConnectionFactory.Options())
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }

    private MediaStream createLocalStream() {
        MediaStream stream = factory.createLocalMediaStream(MEDIA_STREAM_ID);

        // 音频
        audioSource = factory.createAudioSource(createAudioConstraints());
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        stream.addTrack(localAudioTrack);

        // 视频
        videoCapturer = createVideoCapturer();
        if (videoCapturer != null) {
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
            videoSource = factory.createVideoSource(videoCapturer.isScreencast());
            videoCapturer.initialize(surfaceTextureHelper, appContext, videoSource.getCapturerObserver());
            videoCapturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS);
            localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
            stream.addTrack(localVideoTrack);
        } else {
            Log.e(TAG, "createLocalStream: no camera available");
        }
        return stream;
    }

    private VideoCapturer createVideoCapturer() {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(appContext)) {
            enumerator = new Camera2Enumerator(appContext);
        } else {
            enumerator = new Camera1Enumerator(true);
        }
        // 优先前置摄像头
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                VideoCapturer c = enumerator.createCapturer(name, null);
                if (c != null) return c;
            }
        }
        for (String name : enumerator.getDeviceNames()) {
            if (!enumerator.isFrontFacing(name)) {
                VideoCapturer c = enumerator.createCapturer(name, null);
                if (c != null) return c;
            }
        }
        return null;
    }

    private MediaConstraints createAudioConstraints() {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        return c;
    }
}

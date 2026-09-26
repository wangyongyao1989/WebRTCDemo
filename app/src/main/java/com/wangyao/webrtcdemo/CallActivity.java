package com.wangyao.webrtcdemo;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.wangyao.webrtcdemo.utils.PermissionUtil;
import com.wangyao.webrtclib.WebRTCEventListener;
import com.wangyao.webrtclib.WebRTCManager;
import com.wangyao.webrtclib.WebRTCNative;

import org.webrtc.SurfaceViewRenderer;

/**
 * 视频通话界面。
 *
 * 画中画（PiP）交互（仿微信电话）：
 *   - 等待对方时：本地画面全屏。
 *   - 对方加入后：远端画面全屏，本地画面缩为右上角小窗。
 *   - 点击小窗：本地/远端画面互换（小窗变全屏，全屏变小窗）。
 *
 * 通话控制：静音麦克风、挂断、切换前后摄像头。
 */
public class CallActivity extends AppCompatActivity implements WebRTCEventListener {

    private static final String TAG = "CallActivity";
    public static final String EXTRA_SERVER_URL = "extra_server_url";
    public static final String EXTRA_ROOM_ID = "extra_room_id";

    private WebRTCManager manager;

    private FrameLayout fullscreenRenderer;
    private FrameLayout pipRenderer;
    private TextView tvStatus;
    private TextView tvNetwork;
    private Chronometer tvDuration;
    private ImageButton btnMute;
    private ImageButton btnHangup;
    private ImageButton btnSwitchCamera;

    private SurfaceViewRenderer localSurfaceView;
    private SurfaceViewRenderer remoteSurfaceView;
    /** 本地画面当前是否在小窗中。 */
    private boolean isLocalInPip = false;
    private boolean isMuted = false;
    private String remoteUserId;
    private boolean callEnded = false;

    private String serverUrl;
    private String roomId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 全屏 + 常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_call);

        serverUrl = getIntent().getStringExtra(EXTRA_SERVER_URL);
        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);

        initViews();

        if (PermissionUtil.isNeedRequestPermission(this)) {
            // 权限不足，等待 onRequestPermissionsResult 回调后再启动
            tvStatus.setText("请授予摄像头/麦克风权限");
        } else {
            startCall();
        }
    }

    private void initViews() {
        fullscreenRenderer = findViewById(R.id.fullscreen_video_view);
        pipRenderer = findViewById(R.id.pip_video_view);
        tvStatus = findViewById(R.id.tv_status);
        tvNetwork = findViewById(R.id.tv_network);
        tvDuration = findViewById(R.id.tv_duration);
        btnMute = findViewById(R.id.btn_mute);
        btnHangup = findViewById(R.id.btn_hangup);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);

        btnMute.setOnClickListener(v -> toggleMute());
        btnHangup.setOnClickListener(v -> hangup());
        btnSwitchCamera.setOnClickListener(v -> {
            if (manager != null) manager.switchCamera();
        });
        // 点击小窗切换本地/远端画面
        pipRenderer.setOnClickListener(v -> swapPip());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (PermissionUtil.isNeedRequestPermission(this)) {
            // 仍缺少权限
            Toast.makeText(this, "权限被拒绝，无法通话", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            startCall();
        }
    }

    // ============================ 启动通话 ============================

    private void startCall() {
        Log.i(TAG, "startCall: server=" + serverUrl + ", room=" + roomId);
        // 初始化 native 桥接（脚手架，失败不阻断）
        WebRTCNative.init();

        manager = WebRTCManager.getInstance();
        manager.init(this, this);

        // 本地预览：先全屏显示（等待对方加入）
        localSurfaceView = manager.setupLocalVideo(false);
        if (localSurfaceView != null) {
            fullscreenRenderer.addView(localSurfaceView);
        }
        tvStatus.setText("等待对方加入房间 " + roomId + " ...");

        // 连接信令服务器并加入房间
        manager.connect(serverUrl, roomId);
    }

    // ============================ WebRTCEventListener ============================

    @Override
    public void onSignalConnecting() {
        runOnUiThread(() -> tvStatus.setText("正在连接服务器..."));
    }

    @Override
    public void onSignalConnected() {
        runOnUiThread(() -> tvStatus.setText("已连接服务器，正在加入房间..."));
    }

    @Override
    public void onSignalClosed() {
        runOnUiThread(() -> {
            tvStatus.setText("服务器连接已断开");
            Toast.makeText(this, "服务器连接已断开", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onJoinedRoom(String myId) {
        runOnUiThread(() -> tvStatus.setText("已加入房间，等待对方加入..."));
    }

    @Override
    public void onRemoteStream(String userId) {
        runOnUiThread(() -> {
            remoteUserId = userId;
            // 远端画面到达：远端全屏，本地缩为小窗
            remoteSurfaceView = manager.setupRemoteVideo(userId, false);
            if (remoteSurfaceView == null) {
                tvStatus.setText("获取远端画面失败");
                return;
            }
            // 本地从小窗移入（若当前在全屏）
            if (localSurfaceView != null && localSurfaceView.getParent() != null) {
                ((ViewGroup) localSurfaceView.getParent()).removeView(localSurfaceView);
            }
            if (localSurfaceView != null) {
                localSurfaceView.setZOrderMediaOverlay(true);
                pipRenderer.removeAllViews();
                pipRenderer.addView(localSurfaceView);
                isLocalInPip = true;
            }
            // 远端放入全屏
            fullscreenRenderer.removeAllViews();
            fullscreenRenderer.addView(remoteSurfaceView);

            // 开启计时
            tvStatus.setVisibility(View.GONE);
            tvDuration.setVisibility(View.VISIBLE);
            tvDuration.setBase(SystemClock.elapsedRealtime());
            tvDuration.start();
        });
    }

    @Override
    public void onUserLeave(String userId) {
        runOnUiThread(() -> {
            Toast.makeText(this, "对方已离开", Toast.LENGTH_SHORT).show();
            hangup();
        });
    }

    @Override
    public void onNetworkQualityChanged(String level, String detail) {
        runOnUiThread(() -> {
            tvNetwork.setVisibility(View.VISIBLE);
            tvNetwork.setText("网络: " + level + "  " + detail);
            int color;
            switch (level) {
                case "优": color = 0xFF4ADE80; break;
                case "良": color = 0xFFFACC15; break;
                case "差": color = 0xFFFB923C; break;
                default: color = 0xFFF87171; break; // 极差
            }
            tvNetwork.setTextColor(color);
            Log.i(TAG, "网络质量: " + level + " " + detail);
        });
    }

    @Override
    public void onIceReconnecting(int attempt) {
        runOnUiThread(() -> {
            tvNetwork.setVisibility(View.VISIBLE);
            tvNetwork.setText("网络中断，重连中(第" + attempt + "次)...");
            tvNetwork.setTextColor(0xFFF87171);
            Toast.makeText(this, "网络中断，正在自动重连(第" + attempt + "次)", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, "错误: " + message, Toast.LENGTH_SHORT).show();
            tvStatus.setText("错误: " + message);
        });
    }

    // ============================ 画中画切换 ============================

    private void swapPip() {
        if (localSurfaceView == null || remoteSurfaceView == null) {
            return;
        }
        fullscreenRenderer.removeAllViews();
        pipRenderer.removeAllViews();
        if (isLocalInPip) {
            // 本地从大屏变小窗 -> 变全屏；远端变全屏 -> 变小窗
            localSurfaceView.setZOrderMediaOverlay(false);
            fullscreenRenderer.addView(localSurfaceView);
            remoteSurfaceView.setZOrderMediaOverlay(true);
            pipRenderer.addView(remoteSurfaceView);
            isLocalInPip = false;
        } else {
            localSurfaceView.setZOrderMediaOverlay(true);
            pipRenderer.addView(localSurfaceView);
            remoteSurfaceView.setZOrderMediaOverlay(false);
            fullscreenRenderer.addView(remoteSurfaceView);
            isLocalInPip = true;
        }
    }

    // ============================ 通话控制 ============================

    private void toggleMute() {
        isMuted = !isMuted;
        if (manager != null) {
            manager.muteAudio(isMuted);
        }
        btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        Toast.makeText(this, isMuted ? "已静音" : "已取消静音", Toast.LENGTH_SHORT).show();
    }

    private void hangup() {
        if (callEnded) return;
        callEnded = true;
        Log.i(TAG, "hangup");
        if (tvDuration != null) tvDuration.stop();
        if (manager != null) {
            manager.hangup();
        }
        finish();
    }

    // ============================ 生命周期销毁 ============================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tvDuration != null) tvDuration.stop();
        // 移除并释放渲染器
        if (fullscreenRenderer != null) fullscreenRenderer.removeAllViews();
        if (pipRenderer != null) pipRenderer.removeAllViews();
        if (manager != null) {
            manager.release();
        }
        WebRTCNative.release();
    }
}

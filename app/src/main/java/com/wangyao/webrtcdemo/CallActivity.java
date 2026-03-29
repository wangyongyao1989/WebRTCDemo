package com.wangyao.webrtcdemo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.wangyao.webrtcdemo.utils.PermissionUtil;
import com.wangyao.webrtcdemo.utils.Utils;
import com.wangyao.webrtcdemo.webrtc.WebRTCManager;

import org.webrtc.EglBase;
import org.webrtc.MediaStream;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CallActivity extends Activity {
    private static final String TAG = "CallActivity";
    private WebRTCManager webRTCManager;
    private EglBase rootEglBase;
    private VideoTrack localVideoTrack;
    private Map<String, SurfaceViewRenderer> videoViews = new HashMap<>();
    private List<String> persons = new ArrayList<>();
    FrameLayout wrVideoLayout;

    public static void openActivity(Activity activity) {
        Intent intent = new Intent(activity, CallActivity.class);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "========== CallActivity创建 ==========");
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);
        Log.i(TAG, "布局加载完成");
        initView();
    }

    private void initView() {
        Log.i(TAG, "========== 初始化视图 ==========");
        
        wrVideoLayout = findViewById(R.id.wr_video_view);
        wrVideoLayout.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Log.d(TAG, "视频布局容器获取成功");
        
        rootEglBase = EglBase.create();
        Log.i(TAG, "EglBase创建成功");
        
        webRTCManager = WebRTCManager.getInstance();
        Log.d(TAG, "WebRTCManager实例获取成功");

        if (!PermissionUtil.isNeedRequestPermission(this)) {
            Log.i(TAG, "权限已获取，开始加入房间");
            webRTCManager.joinRoom(this, rootEglBase);
        } else {
            Log.w(TAG, "需要请求权限");
        }
    }

    public void onAddRemoteStream(MediaStream stream, String userId) {
        Log.i(TAG, "========== 收到远端媒体流 ==========");
        Log.i(TAG, "用户ID: " + userId);
        Log.d(TAG, "音频轨道数: " + stream.audioTracks.size());
        Log.d(TAG, "视频轨道数: " + stream.videoTracks.size());
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "在UI线程添加远端视频视图");
                addView(userId, stream);
            }
        });
    }

    public void onSetLocalStream(MediaStream stream, String userId) {
        Log.i(TAG, "========== 设置本地媒体流 ==========");
        Log.i(TAG, "用户ID: " + userId);
        Log.d(TAG, "音频轨道数: " + stream.audioTracks.size());
        Log.d(TAG, "视频轨道数: " + stream.videoTracks.size());
        
        if (stream.videoTracks.size() > 0) {
            localVideoTrack = stream.videoTracks.get(0);
            Log.i(TAG, "本地视频轨道获取成功");
        }
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "在UI线程添加本地视频视图");
                addView(userId, stream);
            }
        });
    }

    private void addView(String userId, MediaStream stream) {
        Log.i(TAG, "========== 添加视频视图 ==========");
        Log.i(TAG, "用户ID: " + userId);
        
        SurfaceViewRenderer surfaceViewRenderer = new SurfaceViewRenderer(this);
        Log.d(TAG, "SurfaceViewRenderer创建成功");
        
        surfaceViewRenderer.init(rootEglBase.getEglBaseContext(), null);
        Log.d(TAG, "SurfaceViewRenderer初始化完成");
        
        surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        surfaceViewRenderer.setMirror(true);
        Log.d(TAG, "渲染器配置完成 - 缩放: ASPECT_FIT, 镜像: true");
        
        if (stream.videoTracks.size() > 0) {
            stream.videoTracks.get(0).addSink(surfaceViewRenderer);
            Log.i(TAG, "视频轨道已添加到渲染器");
        } else {
            Log.w(TAG, "无视频轨道，仅显示音频");
        }
        
        videoViews.put(userId, surfaceViewRenderer);
        persons.add(userId);
        wrVideoLayout.addView(surfaceViewRenderer);
        
        int size = videoViews.size();
        Log.i(TAG, "当前视频视图数量: " + size);
        Log.i(TAG, "当前用户列表: " + persons.toString());
        
        for (int i = 0; i < size; i++) {
            String peerId = persons.get(i);
            SurfaceViewRenderer renderer1 = videoViews.get(peerId);

            if (renderer1 != null) {
                FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                layoutParams.height = Utils.getWidth(this, size);
                layoutParams.width = Utils.getWidth(this, size);
                layoutParams.leftMargin = Utils.getX(this, size, i);
                layoutParams.topMargin = Utils.getY(this, size, i);
                renderer1.setLayoutParams(layoutParams);
                Log.d(TAG, "视图布局更新 - 用户: " + peerId + 
                          ", 宽: " + layoutParams.width + 
                          ", 高: " + layoutParams.height +
                          ", 左边距: " + layoutParams.leftMargin + 
                          ", 上边距: " + layoutParams.topMargin);
            }
        }
        
        Log.i(TAG, "视频视图添加完成");
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "========== CallActivity销毁 ==========");
        super.onDestroy();
        
        if (rootEglBase != null) {
            Log.d(TAG, "释放EglBase");
            rootEglBase.release();
        }
        
        int rendererCount = 0;
        for (SurfaceViewRenderer renderer : videoViews.values()) {
            if (renderer != null) {
                Log.d(TAG, "释放视频渲染器");
                renderer.release();
                rendererCount++;
            }
        }
        Log.i(TAG, "共释放 " + rendererCount + " 个视频渲染器");
        
        Log.i(TAG, "CallActivity销毁完成");
    }
}

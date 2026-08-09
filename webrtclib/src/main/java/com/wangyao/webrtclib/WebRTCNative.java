package com.wangyao.webrtclib;

import android.util.Log;

/**
 * WebRTC 原生桥接 Java 侧入口。
 *
 * 加载由 webrtclib/src/main/cpp 编译出的 libwebrtc_native.so。
 * 当前为「脚手架」实现，提供 native 初始化 / 版本号 / I420 帧处理占位。
 *
 * 后续自编译 WebRTC 后，可在此扩展与 native PeerConnection 对应的方法，
 * 例如 nativeCreatePeerConnectionFactory / nativeCreateOffer 等，
 * 由 WebRTCManager 在 init 时调用 nativeInit 进行底层初始化。
 */
public final class WebRTCNative {
    private static final String TAG = "WebRTCNative";
    private static volatile boolean loaded = false;
    private static volatile boolean inited = false;

    private WebRTCNative() {
    }

    /** 加载 native 库，失败返回 false（不阻断主流程）。 */
    public static boolean ensureLoaded() {
        if (loaded) return true;
        try {
            System.loadLibrary("webrtc_native");
            loaded = true;
            Log.i(TAG, "libwebrtc_native.so loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "load libwebrtc_native failed: " + e.getMessage());
            loaded = false;
        }
        return loaded;
    }

    /** 初始化 native 层。 */
    public static boolean init() {
        if (!ensureLoaded()) return false;
        if (inited) return true;
        inited = nativeInit();
        return inited;
    }

    /** 获取 native 桥接版本号。 */
    public static String getVersion() {
        if (!ensureLoaded()) return "native-unavailable";
        return nativeGetVersion();
    }

    /** 释放 native 层资源。 */
    public static void release() {
        if (inited && ensureLoaded()) {
            nativeRelease();
            inited = false;
        }
    }

    // ----------------------- native 方法声明 -----------------------

    private static native boolean nativeInit();

    private static native String nativeGetVersion();

    private static native long nativeProcessI420Frame(int width, int height,
                                                      byte[] y, byte[] u, byte[] v);

    private static native void nativeRelease();
}

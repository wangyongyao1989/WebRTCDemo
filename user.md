# WebRTC 视频通话 Demo —— 用户操作说明与实现细节

本项目以本地信令服务器（`server.py`）为桥接，实现两台 Android 设备之间的 WebRTC 视频通话，并提供仿微信电话的「本地/远端画中画切换」、静音、挂断、切换摄像头等能力。WebRTC 相关代码已模块化下沉到 `webrtclib` 模块，并附带 native C++ 桥接代码，为后续自编译 WebRTC .so 做准备。

---

## 一、快速开始（用户操作）

### 1. 启动本地信令服务器
在宿主机（电脑）上运行项目根目录的 `server.py`：

```bash
python3 server.py
# 控制台输出：WebSocket服务器已启动，监听端口3000
```

服务器监听 `0.0.0.0:3000`，负责房间管理与 WebRTC 信令（Offer/Answer/ICE）转发。

### 2. 安装并打开 App
在 Android Studio 中运行 `app` 模块，或直接安装 `app/build/outputs/apk/debug/app-debug.apk`。

### 3. 配置服务器地址
打开 App 后首页填写：
- **信令服务器地址**：
  - 模拟器访问宿主机：`ws://10.0.2.2:3000`（默认值）
  - 真机访问宿主机：改为宿主机局域网 IP，例如 `ws://192.168.1.100:3000`
- **房间号**：两台设备填**相同**的房间号即可互通（默认 `666555`）。

### 4. 建立通话
1. 在设备 A 上点击「加入房间」，进入通话界面，此时本地画面全屏显示，顶部提示「等待对方加入房间…」。
2. 在设备 B 上用**相同的房间号**加入。两端建立 P2P 连接后：
   - 远端画面全屏显示
   - 本地画面缩小为右上角小窗（画中画）
   - 顶部开始计时
3. 两台设备即可双向视频通话。

### 5. 通话中的操作
| 操作 | 说明 |
| --- | --- |
| 点击右上角小窗 | 本地/远端画面互换（画中画切换），可反复点击 |
| 静音按钮 | 关闭/开启本地麦克风，图标随之变化 |
| 翻转按钮 | 切换前/后摄像头 |
| 挂断按钮（红色） | 结束通话并退出界面，对方会收到「对方已离开」 |

> 首次进入通话界面会申请摄像头/麦克风权限，请允许。

---

## 二、项目架构与模块说明

```
WebRtc/
├── server.py                  # 本地 WebSocket 信令服务器（房间 + 信令转发）
├── app/                       # 应用层（UI）
│   └── src/main/java/com/wangyao/webrtcdemo/
│       ├── MainActivity.java  # 入口：填写服务器地址 + 房间号
│       ├── CallActivity.java  # 通话界面：画中画 + 通话控制
│       └── utils/PermissionUtil.java
└── webrtclib/                 # WebRTC 模块（所有 WebRTC 相关代码）
    └── src/main/
        ├── cpp/               # Native C++ 桥接（为自编译 WebRTC .so 做准备）
        │   ├── CMakeLists.txt
        │   ├── webrtc_native.h
        │   └── webrtc_native.cpp
        └── java/com/wangyao/webrtclib/
            ├── WebRTCManager.java          # 对外门面（Facade）
            ├── WebRTCNative.java           # native 桥接 Java 入口
            ├── WebRTCEventListener.java    # 对 UI 的事件回调
            ├── signaling/                  # 信令层
            │   ├── SignalCallback.java
            │   └── WebSocketManager.java
            ├── peer/                       # Peer 连接层
            │   ├── Peer.java
            │   └── PeerConnectionManager.java
            └── render/ProxyVideoSink.java  # 视频 Sink 代理（用于画中画切换）
```

### 分层职责
- **app（UI 层）**：只与 `WebRTCManager` 交互，不直接操作底层 WebRTC 细节；负责界面与用户交互。
- **webrtclib（WebRTC 模块）**：封装信令、PeerConnection、媒体采集、渲染、native 桥接，对外暴露简洁 API。
  - `WebRTCManager`：门面，串联初始化、本地流、连接、控制、释放。
  - `signaling.WebSocketManager`：信令收发 + 协议解析（与 `server.py` 协议一致）。
  - `peer.PeerConnectionManager` + `Peer`：管理多个 P2P 连接、SDP 协商、ICE 交换。
  - `render.ProxyVideoSink`：视频流代理，画中画切换时不需对 track 反复 add/remove sink。
  - `cpp/webrtc_native.*`：native 桥接脚手架。

---

## 三、实现细节

### 1. 信令流程（与 server.py 协议一致）

```
设备A                        server.py                      设备B
  │  __join{room}              │                              │
  │ ─────────────────────────▶ │                              │
  │  _peers{connections:[B]}   │  _new_peer{socketId:A}       │
  │ ◀───────────────────────── │ ────────────────────────────▶│ (B 加入)
  │                            │  _peers{connections:[]}      │
  │                            │ ────────────────────────────▶│
  │  __offer{sdp}              │                              │
  │ ─────────────────────────▶ │ ──(转发)──▶ _offer{sdp}      │
  │                            │                              │
  │  _answer{sdp}              │ ◀──── __answer{sdp}          │
  │ ◀──────────────────────────│ ─────────────────────────────│
  │                                                            │
  │  __ice_candidate ◀───(双向转发 ICE)────▶ _ice_candidate  │
```

- **信令模型**：「joiner 主动发起 Offer」。新加入房间的设备向房间内已有用户逐一发起 Offer，已有用户收到 `_new_peer` 后仅创建 Peer 等待 Offer，避免双方同时发 Offer 冲突。
- **SDP 状态机**：`Peer.onSetSuccess` 依据 `PeerConnection.signalingState` 驱动：
  - `HAVE_LOCAL_OFFER` → 发送 Offer（呼叫方）
  - `HAVE_REMOTE_OFFER` → 生成 Answer（接收方）
  - `STABLE` → 呼叫方 drain 候选；接收方发送 Answer 并 drain
- **ICE 候选队列**：远端 ICE 可能在远端 SDP 设置前到达，`Peer` 用 `queuedRemoteCandidates` 暂存，待 `setRemoteDescription` 完成后 `drainCandidates` 统一添加，避免候选丢失。

### 2. 画中画（PiP）切换实现

布局（`activity_call.xml`）：
- `fullscreen_video_view`：全屏 FrameLayout，默认放远端画面。
- `pip_video_view`：右上角 108×144dp 小窗 FrameLayout，默认放本地画面。

切换原理（`CallActivity.swapPip`）：
- 维护两个 `SurfaceViewRenderer`（local/remote，由 `WebRTCManager` 创建）。
- 点击小窗时，把两个 renderer 从各自父容器移除，互换位置后重新添加，并调用 `setZOrderMediaOverlay`：
  - 在小窗的 renderer 设 `setZOrderMediaOverlay(true)`（浮于全屏之上）
  - 在全屏的 renderer 设 `setZOrderMediaOverlay(false)`
- 等待对方时本地全屏；对方加入后远端全屏、本地缩小为小窗（仿微信电话）。

> `ProxyVideoSink` 把一路 VideoTrack 委托给目标 renderer，切换渲染目标时无需对 track 反复操作 sink，避免黑屏与生命周期问题。

### 3. 通话控制实现
- **静音**：`WebRTCManager.muteAudio(mute)` → `localAudioTrack.setEnabled(!mute)`，同时 UI 切换麦克风图标。
- **挂断**：`WebRTCManager.hangup()` → 关闭所有 Peer + 断开信令；`onDestroy` 再 `release()` 释放工厂/EglBase/摄像头。
- **切换摄像头**：`WebRTCManager.switchCamera()` → `CameraVideoCapturer.switchCamera(...)`。
- **扬声器**：进入通话时设为 `MODE_IN_COMMUNICATION` 并开启扬声器，释放时恢复 `MODE_NORMAL`。
- **对方离开**：`onRemovePeer`/`onIceConnectionChange(DISCONNECTED|FAILED)` 上抛 `onUserLeave`，UI 提示并挂断。

### 4. 线程模型
- `PeerConnectionManager` 使用单线程 `ExecutorService` 执行所有 `PeerConnection` 操作，保证线程安全。
- WebRTC 回调（`onAddTrack` 等）来自 WebRTC 内部线程，UI 更新通过 `runOnUiThread` 切换到主线程。

### 5. Native C++ 代码（为自编译 WebRTC .so 做准备）

`webrtclib/src/main/cpp/` 提供可编译的 native 桥接脚手架，当前产物为 `libwebrtc_native.so`：

| 文件 | 作用 |
| --- | --- |
| `CMakeLists.txt` | 构建脚本，已注释给出「链接自编译 WebRTC 静态库」的示例 |
| `webrtc_native.h` | 声明 `WebRtcNative` 类（Init/GetVersion/ProcessI420Frame/Release） |
| `webrtc_native.cpp` | JNI 导出实现 + 占位逻辑，标注后续接入点 |

**当前阶段（脚手架）**：
- `nativeInit` / `nativeGetVersion` / `nativeProcessI420Frame` / `nativeRelease` 可被 Java 侧 `WebRTCNative` 调用，验证 NDK + CMake 工具链与 JNI 通道可用。
- `CallActivity` 在 `startCall` 时调用 `WebRTCNative.init()`（失败不阻断主流程）。

**后续自编译 WebRTC 衔接方式**：
1. 按 Google WebRTC 官方流程编译 `libwebrtc.a`（或 `libjingle_peerconnection_so.so`），放入 `webrtclib/src/main/cpp/prebuilt/<abi>/`。
2. 在 `CMakeLists.txt` 启用注释段：`add_library(webrtc STATIC IMPORTED)` 并链接 `EGL/GLESv2/OpenSLES` 等。
3. 在 `webrtc_native.cpp` 的 `WebRtcNative::Init` 等方法中调用真正的 native API（`rtc::InitializeSSL()`、`PeerConnectionFactoryInterface::Create()` 等），并新增 `nativeCreatePeerConnectionFactory` 等 JNI 方法。
4. `WebRTCManager` 可在 `init` 中改走 native 路径创建 PeerConnectionFactory，逐步替换 `io.github.webrtc-sdk:android` 预编译包。

### 6. 关键依赖
| 依赖 | 用途 |
| --- | --- |
| `io.github.webrtc-sdk:android:125.6422.02` | 预编译 WebRTC（org.webrtc.*），通过 `api` 暴露给 app |
| `org.java-websocket:Java-WebSocket:1.5.3` | WebSocket 客户端（信令） |
| `com.alibaba:fastjson` | 信令 JSON 解析 |

---

## 四、构建与运行

### 环境要求
- Android Studio（含 NDK + CMake 3.22.1）
- minSdk 26 / targetSdk 34
- Python 3（运行 `server.py`，需安装 `websockets`：`pip3 install websockets`）

### 构建命令
```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 运行步骤
1. `python3 server.py` 启动信令服务器。
2. 两台设备（或模拟器 + 真机）安装 App。
3. 两端填相同房间号加入，即可建立视频通话。

---

## 五、注意事项与扩展点
- 模拟器与真机互通时，服务器地址需填宿主机的局域网 IP，且确保防火墙放行 3000 端口。
- 当前仅用公共 STUN（`stun:stun.l.google.com:19302`）；跨网络 NAT 穿透需在 `WebRTCManager.buildIceServers()` 追加 TURN 服务器。
- ICE 协商使用 `addTrack`/`onAddTrack`（Unified Plan）。注意：新版 WebRTC（本地 GetStream .so 与 Maven 125 均实测）已移除 Plan B，`PeerConnection.addStream()` 会触发 native `Check failed: !IsUnifiedPlan()` 直接 abort，禁止改回旧 API。
- 无第二台 Android 设备时，可用 `test-client/browser-peer.html`（Chrome 打开，配合 `python3 -m http.server 8080`）作为浏览器端第二通话方，信令协议与 `WebSocketManager.java` 完全一致。
- 多人房间（>2 人）已具备基础支持（每个 peer 独立 Peer），UI 当前为双人画中画，可在此基础上扩展多宫格。

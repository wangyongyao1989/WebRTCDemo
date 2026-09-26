# WebRTC 视频通话 Demo —— 用户操作说明与实现细节

本项目以本地信令服务器（`server.py`）为桥接，实现 Android 设备与浏览器（或另一台设备）之间的 WebRTC 视频通话，提供仿微信电话的「本地/远端画中画切换」、静音、挂断、切换摄像头等能力，并内置**弱网自适应**：实时检测网络质量（优/良/差/极差），自动调整码率/分辨率/帧率，极端弱网退化为纯音频保通话，链路中断自动 ICE 重连。WebRTC 相关代码已模块化下沉到 `webrtclib` 模块，并附带 native C++ 桥接代码，为后续自编译 WebRTC .so 做准备。

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

通话界面**左上角**有实时网络质量标签：`网络: 优/良/差/极差 + RTT/丢包/上下行码率`，颜色绿/黄/橙/红，弱网时自动降级并提示，详见「四、弱网自适应」。

> 首次进入通话界面会申请摄像头/麦克风权限，请允许。

---

## 二、项目架构与模块说明

```
WebRtc/
├── server.py                  # 本地 WebSocket 信令服务器（房间 + 信令转发）
├── app/                       # 应用层（UI）
│   └── src/main/java/com/wangyao/webrtcdemo/
│       ├── MainActivity.java  # 入口：填写服务器地址 + 房间号
│       ├── CallActivity.java  # 通话界面：画中画 + 通话控制 + 网络质量标签
│       └── utils/PermissionUtil.java
├── webrtclib/                 # WebRTC 模块（所有 WebRTC 相关代码）
│   └── src/main/
│       ├── cpp/               # Native C++ 桥接（为自编译 WebRTC .so 做准备）
│       │   ├── CMakeLists.txt
│       │   ├── webrtc_native.h
│       │   └── webrtc_native.cpp
│       └── java/com/wangyao/webrtclib/
│           ├── WebRTCManager.java          # 对外门面（Facade）+ 弱网策略执行
│           ├── WebRTCNative.java           # native 桥接 Java 入口
│           ├── WebRTCEventListener.java    # 对 UI 的事件回调
│           ├── signaling/                  # 信令层
│           │   ├── SignalCallback.java
│           │   └── WebSocketManager.java
│           ├── peer/                       # Peer 连接层
│           │   ├── Peer.java
│           │   ├── PeerConnectionManager.java  # ICE 状态机 + 断线重连调度
│           │   └── NetworkQualityMonitor.java  # 弱网质量监测（stats 采集/分级/迟滞）
│           └── render/ProxyVideoSink.java  # 视频 Sink 代理（用于画中画切换）
└── test-client/               # 测试工具
    ├── browser-peer.html      # 浏览器端第二通话方（真实摄像头/麦克风）
    ├── weaknet.sh             # macOS dummynet 弱网模拟（限速/时延/丢包）
    └── cdp-drive.py           # Chrome DevTools 协议驱动（自动化测试对端）
```

### 分层职责
- **app（UI 层）**：只与 `WebRTCManager` 交互，不直接操作底层 WebRTC 细节；负责界面与用户交互。
- **webrtclib（WebRTC 模块）**：封装信令、PeerConnection、媒体采集、渲染、native 桥接，对外暴露简洁 API。
  - `WebRTCManager`：门面，串联初始化、本地流、连接、控制、释放。
  - `signaling.WebSocketManager`：信令收发 + 协议解析（与 `server.py` 协议一致）。
  - `peer.PeerConnectionManager` + `Peer`：管理多个 P2P 连接、SDP 协商、ICE 交换、断线重连（restartIce）。
  - `peer.NetworkQualityMonitor`：周期采集 `getStats`，输出网络质量等级（详见「四、弱网自适应」）。
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

## 四、弱网自适应（功能与测试）

### 1. 功能表现（用户视角）
- 通话界面左上角实时显示 `网络: 等级 + RTT/丢包/下行/上行` 标签（绿优 / 黄良 / 橙差 / 红极差）。
- 网络变差时**自动**依次降级：限码率 → 降分辨率帧率 → 极差时关闭视频、保留音频（小窗变黑属正常，语音不断）。
- 网络恢复后**逐级**回升（极差→差→良→优），视频自动重新开启。
- UDP 链路彻底中断时自动 `restartIce()` 重连（最多 2 次），界面提示「网络中断，重连中(第N次)…」；仍失败才结束通话。

### 2. 实现要点（原理摘要）
| 环节 | 做法 |
| --- | --- |
| 检测 | `NetworkQualityMonitor` 每 2s `pc.getStats()`：candidate-pair 的 `currentRoundTripTime`/`availableOutgoingBitrate`、inbound-rtp 丢包增量、outbound-rtp 码率增量 |
| 分级 | RTT(100/200/400ms)、丢包(2/5/15%)、可用上行(400/250/120kbps) 三指标取最差档 → 优/良/差/极差 |
| 防抖 | 连续 2 个坏样本才降级（一步到位）；连续 4 个好样本才恢复（每次只升一级）。快降慢升 |
| 执行 | `RtpSender.setParameters(maxBitrateBps)` + `VideoSource.adaptOutputFormat(w,h,fps)` + `track.setEnabled(false)`，均无需重新协商，秒级生效 |
| 重连 | DISCONNECTED 4s 宽限→restartIce；FAILED 立即 restartIce；上限 2 次 |

完整设计文档见根目录 `弱网功能实现总结.md`（含阈值表、架构图与实测证据）。

### 3. 浏览器端真实摄像头/麦克风
`test-client/browser-peer.html` 默认优先 `getUserMedia` 取本机真实摄像头/麦克风（页面徽标显示「真实摄像头/麦克风」），失败才回退合成画面。手工使用：

```bash
python3 -m http.server 8080        # 项目根目录
# Chrome 打开 http://127.0.0.1:8080/test-client/browser-peer.html，填房间号加入
```

自动化测试时用真实 Chrome（内嵌浏览器拿不到摄像头）：

```bash
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --remote-debugging-port=9222 --user-data-dir=/tmp/chrome-webrtc-peer \
  --use-fake-ui-for-media-stream \
  http://127.0.0.1:8080/test-client/browser-peer.html
```

`--use-fake-ui-for-media-stream` 自动放行授权弹窗；`test-client/cdp-drive.py` 经 DevTools 协议驱动其入房/挂断/读状态：

```bash
NO_PROXY='*' python3 test-client/cdp-drive.py join 888123 ws://<宿主机IP>:3000
NO_PROXY='*' python3 test-client/cdp-drive.py status   # 读 ICE/码率/丢包
```

### 4. 弱网模拟环境（macOS dummynet）
`test-client/weaknet.sh` 只对宿主机↔平板之间的 **UDP 媒体流**注入带宽/时延/丢包，不影响 TCP 信令：

```bash
./test-client/weaknet.sh on 300 150 0.10   # 限速300kbps、时延150ms、丢包10%
./test-client/weaknet.sh off               # 清除注入（务必执行）
./test-client/weaknet.sh status
```

需 root（脚本内 dnctl+pfctl）。建议经授权弹窗提权执行：
`osascript -e 'do shell script "/绝对路径/test-client/weaknet.sh on 100 300 0.25" with administrator privileges'`

推荐测试参数（已实测）：轻度 `500 100 0.03`→标签降为「良」；重度 `100 300 0.25`→「极差」+纯音频；`off`→逐级恢复回「优」。

### 5. 已验证结论（2026-09-26 真机 + Chrome 双端 A/B）
- 轻度弱网：1491→381kbps，标签 优→良（黄）。
- 重度弱网：对端收流 6kbps、丢包 30.3%，标签 极差（红），本地视频自动关闭保音频。
- 撤除损伤：逐级恢复至 优，视频回归。
- 黑洞期间：RTT 3ms→148ms 实时触发降级，通话未中断。
- 浏览器刷新重入房：Offer/Answer 重新协商成功，验证重连路径可用。

---

## 五、构建与运行

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

## 六、注意事项与扩展点
- 模拟器与真机互通时，服务器地址需填宿主机的局域网 IP，且确保防火墙放行 3000 端口。宿主机的局域网 IP 可能因 DHCP 变化（App 显示「服务器连接已断开」先检查此项），变更后需同步修改 App 输入框与 `weaknet.sh` 中的 `MAC_IP`。
- 当前仅用公共 STUN（`stun:stun.l.google.com:19302`）；跨网络 NAT 穿透需在 `WebRTCManager.buildIceServers()` 追加 TURN 服务器。
- ICE 协商使用 `addTrack`/`onAddTrack`（Unified Plan）。注意：新版 WebRTC（本地 GetStream .so 与 Maven 125 均实测）已移除 Plan B，`PeerConnection.addStream()` 会触发 native `Check failed: !IsUnifiedPlan()` 直接 abort，禁止改回旧 API。
- 无第二台 Android 设备时，用 `test-client/browser-peer.html`（Chrome + `python3 -m http.server 8080`）作为浏览器端第二通话方，信令协议与 `WebSocketManager.java` 完全一致；真实摄像头/麦克风与自动化驱动方式见「四、弱网自适应」。
- 用 `weaknet.sh` 测完**必须** `off`：pf 保持开启或残留 dummynet 规则会拖慢本机网络；macOS 上 dummynet 规则写进 pf anchor 会被静默忽略（脚本已直接写主规则集）；长时间反复 on/off 后若发现注入不生效，执行 `sudo pfctl -d; sudo pfctl -F all` 重置。
- 多人房间（>2 人）已具备基础支持（每个 peer 独立 Peer），UI 当前为双人画中画，可在此基础上扩展多宫格。

# 手把手搭建 WebRTC 视频通话：Android App + Python 信令服务器 + Web 端，外加弱网自适应实战

> 本文基于一个可运行的开源结构（Android 端 `webrtclib` 模块 + Python `server.py` 信令 + 原生 JS 网页端）完整走一遍：三端怎么搭、WebRTC 连接怎么建立、以及最有工程价值的部分——**App 端弱网自适应策略**（自动降级、自动恢复、断线重连）的实现与真机实测。
>
> 项目源码仓库：**https://github.com/wangyongyao1989/WebRTCDemo.git**（`git clone https://github.com/wangyongyao1989/WebRTCDemo.git`，欢迎 Star）

先看最终效果：平板与电脑浏览器互相视频通话，平板左上角实时显示 `网络: 优/良/差/极差 + RTT/丢包/上下行码率`；用 dummynet 把 UDP 带宽压到 100kbps、丢包 25% 时，平板自动关视频保音频；网络恢复后画质逐级回升，视频自动重开；链路彻底中断时自动 ICE 重连。全程无一行"假代码"，均为实测通过。

---

## 一、整体架构

WebRTC 通话三要素：**信令（Signaling）、媒体协商（SDP）、连通性建立（ICE）**。本项目由三个部分组成：

```
┌──────────────┐   WebSocket(信令)   ┌──────────────┐
│ Android App  │◀───────────────────▶│  server.py   │
│ (webrtclib)  │                     │  :3000 房间  │
└──────┬───────┘                     └──────▲───────┘
       │                        __join/_peers/_offer... 
       │ UDP(SRTP媒体) 直连                   │
┌──────▼───────────────┐                     │
│  浏览器 WebRTC 对端   │◀────────────────────┘
│  browser-peer.html   │        WebSocket(信令)
└──────────────────────┘
```

关键点：**信令走服务器（TCP WebSocket），媒体流走 P2P（UDP SRTP），两者通道分离**。这也为后文弱网实验埋下伏笔——我们只破坏 UDP，信令仍在，App 就能完成重连协商。

App 内部做了四层封装：

```
app (UI层: MainActivity/CallActivity)
 └─ WebRTCManager            门面：初始化/本地流/策略执行
     ├─ WebSocketManager     信令层：协议收发与解析
     ├─ PeerConnectionManager  连接管理：peer 生命周期 + ICE 重连状态机
     │    ├─ Peer            单条 P2P：SDP 状态机 + ICE 候选队列
     │    └─ NetworkQualityMonitor  弱网监测：getStats 采集/分级/迟滞
     └─ ProxyVideoSink       渲染代理（画中画切换）
```

---

## 二、信令服务器搭建（Python，约 130 行）

用 `websockets` 库实现一个房间转发服务器，职责只有两个：维护 `room → [socketId]`，以及在房间内转发 SDP/ICE。

```bash
pip3 install websockets
python3 server.py     # 监听 0.0.0.0:3000
```

协议设计（发送方事件用 `__` 前缀，服务器下发用 `_` 前缀）：

| 事件 | 方向 | 含义 |
| --- | --- | --- |
| `__join {room}` | 客户端→服务器 | 入房 |
| `_peers {connections, you}` | 服务器→入房者 | 房间内已有成员 + 自己的 id |
| `_new_peer {socketId}` | 服务器→房间内其他人 | 有人加入 |
| `__offer/_offer`、`__answer/_answer` | 双向 | SDP 转发（按 socketId 点对点） |
| `__ice_candidate/_ice_candidate` | 双向 | ICE 候选转发 |
| `_remove_peer {socketId}` | 服务器→房间 | 成员离开 |

**信令模型选择「后入房者主动发起 Offer」**：新成员收到 `_peers` 后向房内每人 `createOffer`；老成员收到 `_new_peer` 只创建 Peer 等 Offer。这样天然避免双方同时发 Offer 的 GLARE 冲突，比"主叫/被叫"角色约定简单可靠。

服务器代码里有一个真实踩过的坑值得贴出来——**连接清理必须逐个容错**：

```python
finally:
    # 必须先从状态表摘除再通知；任何一次 send 失败（对端半死连接
    # 抛 ConnectionClosed）都不能中断循环，否则离房者会以僵尸身份
    # 永久残留在 rooms 里，重进房间后成员列表错乱。
    for room, room_clients in list(rooms.items()):
        if client_id in room_clients:
            room_clients.remove(client_id)
            for other_id in list(room_clients):
                try:
                    await sockets[other_id].send(json.dumps({
                        "eventName": "_remove_peer",
                        "data": {"socketId": client_id}}))
                except Exception as e:
                    print(f"通知 _remove_peer 失败({other_id}): {e}")
```

Android 端断网/挂断经常是不发 close 帧的"异常断开"（`ConnectionClosedError: no close frame received`），如果清理循环里对某个成员 send 失败就中断，僵尸成员就留下了。

---

## 三、Web 端搭建（browser-peer.html）

网页端充当"第二部手机"，方便单人调试。核心三步：`getUserMedia` 拿真实摄像头麦克风 → 每个远端用户一个 `RTCPeerConnection` → 与 App 完全相同的信令协议。

```javascript
// 真实摄像头/麦克风，失败回退到 canvas 合成流（CI 环境无摄像头时）
const stream = await navigator.mediaDevices.getUserMedia({
  audio: { echoCancellation: true },
  video: { width: 640, height: 480, facingMode: 'user' }
});

// Unified Plan：逐轨道 addTrack，别用已废弃的 addStream
const pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
stream.getTracks().forEach(t => pc.addTrack(t, stream));
pc.ontrack = e => { remoteVideo.srcObject = e.streams[0]; };
pc.onicecandidate = e => e.candidate && ws.send(JSON.stringify({
  eventName: '__ice_candidate',
  data: { socketId: peerId, candidate: e.candidate.candidate,
          id: e.candidate.sdpMid, label: e.candidate.sdpMLineIndex } }));
```

页面每 2 秒 `pc.getStats()` 轮询，把 `inbound-rtp` 的收发包增量、`candidate-pair` 的 `availableOutgoingBitrate` 显示出来——这份统计同时是后文弱网实验的"第三方裁判"。

启动方式：项目根目录 `python3 -m http.server 8080`，Chrome 打开 `http://127.0.0.1:8080/test-client/browser-peer.html`，填相同房间号即可与 App 互通。

---

## 四、Android App 搭建与连接流程

### 4.1 依赖与初始化

Google 官方早已不再发布 Android 版 WebRTC Maven 包，社区维护版 `io.github.webrtc-sdk` 是目前最省事的来源（本文用 125.6422.02，对应 Chrome 125 内核）：

```groovy
implementation 'io.github.webrtc-sdk:android:125.6422.02'
implementation 'org.java-websocket:Java-WebSocket:1.5.3'   // 信令
implementation 'com.alibaba:fastjson'                      // 协议 JSON
```

`WebRTCManager.init()` 完成工厂创建与本地流采集：

```java
eglBase = EglBase.create();

// 1. 工厂：编码器 + 音频设备模块（AEC/NS 在音频约束里开）
VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
        eglBase.getEglBaseContext(), true, true);
PeerConnectionFactory.initialize(InitializationOptions.builder(context)
        .createInitializationOptions());
factory = PeerConnectionFactory.builder()
        .setAudioDeviceModule(JavaAudioDeviceModule.builder(context)
                .createAudioDeviceModule())
        .setVideoEncoderFactory(encoderFactory)
        .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                eglBase.getEglBaseContext()))
        .createPeerConnectionFactory();

// 2. 本地流：摄像头 → VideoSource → VideoTrack
VideoCapturer capturer = createVideoCapturer();     // 优先 Camera2 + 前置
surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread",
        eglBase.getEglBaseContext());
videoSource = factory.createVideoSource(capturer.isScreencast());
capturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
capturer.startCapture(640, 480, 15);
localVideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource);
```

### 4.2 完整连接时序

从点击「加入房间」到出现远端画面，App 内部发生了这些事件：

```
CallActivity          WebSocketManager        server.py            远端Peer
    │ connect(url,room)     │                     │                    │
    ├──────────────────────▶│ __join              │                    │
    │                       ├────────────────────▶│                    │
    │                       │◀──── _peers[ids] ───┤  (老成员收到_new_peer)
    │ onPeers(ids)          │                     │                    │
    │◀──────────────────────┤                     │                    │
    │  对每个已有成员: new Peer(isOffer=true)      │                    │
    │  addTrack(local) → createOffer              │                    │
    │  onSetSuccess(HAVE_LOCAL_OFFER) ── __offer ─┼───────────────────▶│
    │                       │                     │◀── __offer ────────┤ 远端
    │◀──────────────── _offer ────────────────────┤   createAnswer     │
    │  setRemote(OFFER)→onSetSuccess(HAVE_REMOTE_OFFER)→createAnswer   │
    │  onSetSuccess(STABLE) → __answer ──────────▶│───────────────────▶│
    │                       │                     │                    │
    │  双向 onIceCandidate ⇄ __ice_candidate 互发（trickle，边收集边发） │
    │  非STABLE时到达的候选 → remoteCandidateQueue 暂存，STABLE后drain   │
    │                       │                     │                    │
    │  ICE连通→DTLS→SRTP    │                     │                    │
    │  onAddTrack → 远端流就绪 → 挂 SurfaceViewRenderer 渲染            │
```

对应到代码，`PeerConnectionManager` 收到 `_peers` 后为每个已有成员创建"发起方" Peer：

```java
@Override
public void onPeers(List<String> connections, String myId) {
    executor.execute(() -> {
        for (String id : connections) createPeer(id, true);   // isOffer=true
    });
}

private Peer createPeer(String socketId, boolean isOffer) {
    Peer peer = new Peer(factory, iceServers, socketId, isOffer, this);
    peer.addLocalStream(localStream);        // 逐轨 addTrack（见 4.3）
    peers.put(socketId, peer);
    if (isOffer) peer.createOffer(offerOrAnswerConstraints());
    return peer;
}
```

所有 PeerConnection 操作都投递到**单线程 executor**，避免多线程直接摸 native 对象。

### 4.3 SDP 状态机：不猜角色，只看状态

很多教程用"我是主叫/被叫"布尔量决定 setLocalDescription 之后干什么，重协商时必翻车。`Peer` 的实现完全依据 `signalingState`：

```java
@Override
public void onSetSuccess() {
    PeerConnection.SignalingState state = pc.signalingState();
    switch (state) {
        case HAVE_LOCAL_OFFER:              // 设完本地Offer → 发出去
            callback.onSendOffer(userId, localSdp); break;
        case HAVE_REMOTE_OFFER:             // 设完远端Offer → 生成Answer
            pc.createAnswer(this, offerOrAnswerConstraints()); break;
        case STABLE:                        // 一轮协商完成
            // 用"本地SDP类型"判断本轮角色，重协商时即使本机是初始
            // 应答方也可能主动发Offer，所以不能依赖初始 isOffer
            if (localSdp != null && localSdp.type == SessionDescription.Type.ANSWER)
                callback.onSendAnswer(userId, localSdp);
            drainCandidates();              // 补收协商期间到达的ICE
            break;
    }
}
```

ICE 候选的乱序问题（候选比 SDP 先到）用暂存队列解决：非 STABLE 时入队，STABLE 后 drain。

**这里藏着一个真实死锁事故**，值得单独讲——

最初版本 `addRemoteIceCandidate` 写成了 `synchronized`（锁 Peer 对象），锁内调用 `pc.signalingState()`。上线后偶现"挂断重进房间画面不显示"，最终 ANR 全线程栈（`adb shell dumpsys dropbox --print data_app_anr`）还原了死锁环：

```
执行线程:  持有 Peer 监视器 → 调 pc.signalingState()（同步JNI，等信令线程返回）
信令线程:  onSetSuccess → drainCandidates()（要拿同一把 Peer 监视器）→ 阻塞
```

`PeerConnection` 的 getter 类方法（`signalingState()`/`iceConnectionState()`）是**同步 JNI**：调用线程会阻塞等待信令线程处理。只要"持 Java 锁 + 等信令线程"和"信令线程回调里等同一把 Java 锁"同时成立就是死锁。修复后的写法：**独立锁只保护队列，任何 `pc.*` 调用全部放锁外**：

```java
private final Object iceLock = new Object();

public void addRemoteIceCandidate(IceCandidate candidate) {
    if (pc == null) return;
    // signalingState() 是同步 JNI，必须在锁外读取
    PeerConnection.SignalingState st = pc.signalingState();
    boolean addNow;
    synchronized (iceLock) {
        if (st == PeerConnection.SignalingState.STABLE && remoteCandidateQueue.isEmpty()) {
            addNow = true;
        } else {
            remoteCandidateQueue.add(candidate);
            addNow = false;
        }
    }
    if (addNow) pc.addIceCandidate(candidate);   // native 调用在锁外
}
```

### 4.4 另一个必踩的坑：addStream 直接崩溃

新版 WebRTC 只支持 Unified Plan，`pc.addStream(localStream)` 会命中 native `CHECK(!IsUnifiedPlan())` 直接 SIGABRT，连异常都 catch 不住。必须逐轨道添加：

```java
public void addLocalStream(MediaStream localStream) {
    List<String> streamIds = Collections.singletonList(localStream.getId());
    for (VideoTrack t : localStream.videoTracks) pc.addTrack(t, streamIds);
    for (AudioTrack t : localStream.audioTracks) pc.addTrack(t, streamIds);
}
```

---

## 五、弱网自适应：检测、分级、策略、重连

这是全文重点。弱网处理只有两个前提问题：**"网络差到什么程度"从哪来？** 和 **"降级动作"改什么？**

### 5.1 检测：直接读 WebRTC 自己的统计，不做 ping

`NetworkQualityMonitor` 每 2 秒对每个 Peer 调一次 `pc.getStats()`，取四类指标：

| 统计类型 | 字段 | 含义 |
| --- | --- | --- |
| `candidate-pair`(选中项) | `currentRoundTripTime` | 往返时延（秒） |
| `candidate-pair`(选中项) | `availableOutgoingBitrate` | **GCC 拥塞控制估算的可用上行带宽** |
| `inbound-rtp`(video) | `packetsReceived/packetsLost` 增量 | 下行瞬时丢包率 |
| `outbound-rtp`(video) | `bytesSent` 增量 | 实测上行码率 |

选 stats 而不是自己 ping 的理由：GCC 估计值就是编码器实际面对的带宽结论，零额外流量、语义完全对齐；丢包用**增量**计算，恢复期出现负增量钳 0 防误判。

### 5.2 分级：三指标取最差档

| 指标 | 优 | 良 | 差 | 极差 |
| --- | --- | --- | --- | --- |
| RTT | <100ms | <200ms | <400ms | ≥400ms |
| 丢包率 | <2% | <5% | <15% | ≥15% |
| 可用上行 | ≥400kbps | ≥250kbps | ≥120kbps | <120kbps |

单次采样按三行分别定档取 `max`（最差档）。指标无数据（-1）的维度不参与判级，避免冷启动误伤。

### 5.3 迟滞：快降慢升，防止临界震荡

裸阈值在临界值附近会让画质来回横跳。`applyHysteresis` 用非对称计数器：

```java
if (raw.ordinal() > current.ordinal()) {        // 本样本更差
    if (++worseStreak >= 2) {                   // 连续2个坏样本
        current = raw;                          // 才降级，且一步降到观测最差档
    }                                           // （弱网要快速止损）
} else if (raw.ordinal() < current.ordinal()) { // 本样本更好
    if (++betterStreak >= 4) {                  // 连续4个好样本
        current = Quality.values()[current.ordinal() - 1]; // 才回升一级
    }                                           // （恢复要保守，防假性恢复）
}
```

降级要快（2 个样本、一步到位），恢复要慢（4 个样本、逐级回升）——这是整个策略里最有工程含义的一组常数。

### 5.4 执行：三管齐下，全部免重协商

等级变化时 `WebRTCManager.applyQualityPolicy` 同时下发三个动作：

```java
switch (q) {
    case EXCELLENT: maxBps = 1_500_000; w=640; h=480; fps=15; videoOn=true;  break;
    case GOOD:      maxBps =   800_000; w=640; h=480; fps=15; videoOn=true;  break;
    case POOR:      maxBps =   300_000; w=480; h=360; fps= 8; videoOn=true;  break;
    case BAD:       maxBps =    64_000; w=480; h=360; fps= 8; videoOn=false; break; // 仅音频
}
// ① 限编码码率：改 RtpSender 参数，不触发 renegotiation，秒级生效
peerManager.setVideoMaxBitrate(maxBps);
// ② 极端时关本地视频轨：把带宽全留给音频，"至少能听到"
localVideoTrack.setEnabled(videoOn);
// ③ 降采集分辨率/帧率：从源头减少要编码的数据量
videoSource.adaptOutputFormat(w, h, fps);
```

其中 ① 的实现遍历 `pc.getSenders()`：

```java
for (RtpSender sender : pc.getSenders()) {
    if (sender.track() instanceof VideoTrack) {
        RtpParameters params = sender.getParameters();
        for (RtpParameters.Encoding enc : params.encodings) enc.maxBitrateBps = maxBps;
        sender.setParameters(params);
    }
}
```

三层递进的逻辑：**编码器目标码率**决定发多少、**采集参数**决定编多少、**轨道开关**决定还发不发视频。

### 5.5 断线重连：ICE 状态机

降级救得了"慢"，救不了"断"。`PeerConnectionManager.handleIceState` 处理链路失效：

```java
switch (state) {
    case CONNECTED: case COMPLETED:
        iceRestartAttempts.remove(userId);      // 通了就清零计数
        cancelPendingIceCheck(userId);
        startMonitor(userId);                   // 并确保质量监测在跑
        break;
    case DISCONNECTED:
        scheduleIceCheck(userId, 4000);         // 4s宽限：WebRTC自己可能恢复
        break;                                  // 超时未恢复才人工介入
    case FAILED:
        attemptIceRestart(userId);              // 立即重启
        break;
}

private void attemptIceRestart(String userId) {
    int n = iceRestartAttempts.merge(userId, 1, Integer::sum);
    if (n > MAX_ICE_RESTART) { onUserLeave(userId); return; } // 最多2次，优雅放弃
    peer.restartIceAndRenegotiate();  // pc.restartIce() + createOffer 重协商
    listener.onIceReconnecting(userId, n);      // UI: "网络中断，重连中(第N次)…"
    scheduleIceCheck(userId, 10_000);           // 10s后复查，没通再试
}
```

`restartIce()` 会更换 ICE 的 ufrag/pwd 并重新收集候选，走一次轻量重协商——因为信令走 TCP 不受 UDP 中断影响，这条路在媒体全断时依然通畅。

### 5.6 UI 呈现

`CallActivity` 左上角一个 TextView，按等级染色（绿/黄/橙/红），文本直接展示判级依据，方便肉眼核对：

```
网络: 优  RTT=2ms 丢包=0.0% 下行=300kbps 上行=779kbps 可用上行=3915kbps
```

---

## 六、真机实测：dummynet 造弱网 + 多轮 A/B

### 6.1 环境

macOS 自带 dummynet + pf，可以只对「Mac↔平板」之间的 **UDP** 注入带宽/时延/丢包，完全不碰 TCP 信令，模拟出非常真实的弱网：

```bash
# test-client/weaknet.sh
dnctl pipe 1 config bw 100Kbit/s delay 300 plr 0.25   # 出向管道
dnctl pipe 2 config bw 100Kbit/s delay 300 plr 0.25   # 入向管道
pfctl -f - <<EOF
dummynet out proto udp from 192.168.1.4 to 192.168.1.3 pipe 1
dummynet in  proto udp from 192.168.1.3 to 192.168.1.4 pipe 2
EOF
pfctl -e
```

两个平台冷知识：① macOS 上 **dummynet 规则写在 pf anchor 里会被静默忽略**，必须直接写主规则集；② 用完必须 `weaknet.sh off`（清规则+删管道+`pfctl -d`），否则本机网络持续受损。

### 6.2 A/B 结果

对同一通「平板 ↔ Chrome」实时通话施加不同损伤，同时采集平板标签与浏览器 `getStats`：

| 轮次 | 注入 | 浏览器实测 | 平板表现 | 结论 |
| --- | --- | --- | --- | --- |
| 基线 | 无 | 1491kbps | 优（绿） | — |
| 轻度 | 500kbps/100ms/3% | 1491→381kbps | 优→**良**（黄），丢包1.3% | 降级 ✓ |
| 重度 | 100kbps/300ms/25% | 收流仅 6kbps | 良→**极差**（红），丢包30.3%，**小窗黑屏=视频已关、纯音频** | 兜底 ✓ |
| 恢复 | 撤除 | 码率回升 | 极差→差→良→优 **逐级**回升，视频自动重开 | 迟滞 ✓ |
| 黑洞 | plr=1.0 | ice 存活 | RTT 3ms→148ms 即时降档，通话不断 | 快速响应 ✓ |

---

## 七、总结

回顾整个链路，值得带走的东西：

1. **信令可以极简**：130 行的房间转发器足够跑通生产级协议形态，关键是「joiner 发起 Offer」避免协商冲突。
2. **SDP 状态机不要依赖角色假设**：以 `signalingState` + 本地 SDP 类型驱动，重协商天然正确。
3. **弱网检测用 `getStats` 而不是自造探测**：GCC 的 `availableOutgoingBitrate` 就是编码器视角的真相。
4. **降级三件套全部免重协商**：`setParameters(maxBitrateBps)` + `adaptOutputFormat` + `track.setEnabled(false)`，秒级生效、对端无感。
5. **迟滞是非对称的**：快降（2 样本一步到位）慢升（4 样本逐级），临界网络才不会抽搐。
6. **两条血泪线程规则**：绝不在持 Java 锁时调用 `PeerConnection` 的同步 JNI 方法；`addStream` 在 Unified Plan 下是 native 崩溃不是异常。
7. **测试环境本身就是产出**：dummynet 只损 UDP 不损信令，才能同时验证"降级"和"重连"两条路径。

完整代码结构：`server.py`（信令）+ `webrtclib`（WebRTC 四层封装）+ `app`（双人通话 UI）+ `test-client/`（网页对端、weaknet.sh、CDP 驱动脚本）。全部源码已开源：**https://github.com/wangyongyao1989/WebRTCDemo.git**。欢迎评论区交流 TURN 部署与多人房间扩展。

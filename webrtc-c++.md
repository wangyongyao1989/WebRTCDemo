# WebRTC Native (C++/JNI) 引入与预编译产物下载实现总结

本文档详细记录了将 WebRTC 从 Maven 预编译包切换为「本地 .so + 本地 Java 源码 + C++ 头文件 + JNI 调用」的完整过程，包括编码细节、数据来源、架构变更、编译验证及下载脚本说明。

---

## 一、目标与背景

### 原始状态
- `webrtclib/build.gradle.kts` 通过 `api("io.github.webrtc-sdk:android:125.6422.02")` 引入 WebRTC
- `org.webrtc.*` 全部来自 Maven 仓库
- `webrtclib/src/main/cpp/` 仅有脚手架 native 代码（`webrtc_native.cpp`），与 WebRTC 无实际关联

### 变更目标
1. **注释掉 Maven 依赖**：保留代码但禁用 `api("io.github.webrtc-sdk:android:125.6422.02")`
2. **从网络获取稳定的预编译 WebRTC 产物**（替代手动交叉编译）：
   - .so 库（4 个 ABI）
   - C++ 头文件
   - WebRTC Java SDK 源码
3. **JNI 调用原逻辑**：`org.webrtc.*` Java 层本身就是 JNI 包装器，调用 `libjingle_peerconnection_so.so` 中的 native 方法
4. **保留自定义 native 桥接**：`libwebrtc_native.so` 作为后续扩展的 C++ 入口

---

## 二、数据来源与选型

由于没有 Ubuntu/Linux 环境进行交叉编译，从网络上选择了两个稳定可靠的数据源：

| 产物 | 数据源 | 版本 | 说明 |
|------|--------|------|------|
| .so 库（4 ABI） | [GetStream/webrtc-android](https://github.com/GetStream/webrtc-android) | 1.3.10（最新） | 活跃维护，.so 与 Java 源码在同一仓库，版本严格匹配 |
| Java SDK 源码 | [GetStream/webrtc-android](https://github.com/GetStream/webrtc-android) | 1.3.10（最新） | 189 个 .java 文件，包含 `audio/`、`voiceengine/` 子包 |
| C++ 头文件 | [shiguredo-webrtc-build](https://github.com/shiguredo-webrtc-build/webrtc-build) | M142.7444.2.1 | 日本 Shiguredo 团队维护，高质量稳定构建，52K+ 头文件 |

### 为什么选 GetStream？

1. **.so 与 Java 源码版本严格匹配**：同一仓库的 `stream-webrtc-android/libs/` 目录存放 .so，`src/main/java/org/webrtc/` 存放 Java 源码，两者来自同一编译版本，避免 JNI 方法签名不匹配
2. **活跃维护**：GetStream 是知名 Android WebRTC 维护者，定期跟随 WebRTC 官方稳定分支更新
3. **4 个 ABI 全覆盖**：arm64-v8a、armeabi-v7a、x86、x86_64
4. **无修改的 WebRTC 源码**：Java 源码保持 `org.webrtc` 原始包名，与官方 API 一致

### 为什么选 Shiguredo 头文件？

1. **高质量构建**：Shiguredo 是 WebRTC 社区公认的稳定构建提供者
2. **完整 SDK tarball**：`webrtc.android_sdk.tar.gz` 包含完整的 C++ 头文件树（52,064 个 .h 文件）
3. **版本标注清晰**：使用 Chromium 分支号（如 M142.7444），便于追踪

---

## 三、编码过程与实现细节

### 步骤 1：注释 Maven 依赖，配置 jniLibs

**文件**：`webrtclib/build.gradle.kts`

```kotlin
dependencies {
    // ===================================================================
    // [已注释] 原先通过 Maven 引入的预编译 WebRTC 包。
    // 现已改为「本地 .so + 本地 org.webrtc Java 源码」的方式，
    // .so 位于 src/main/jniLibs/<abi>/libjingle_peerconnection_so.so，
    // Java 源码位于 src/main/java/org/webrtc/。
    // 如需恢复 Maven 方式，取消下面两行注释即可（并删除本地 jniLibs/java 源码）。
    // -------------------------------------------------------------------
    // api("io.github.webrtc-sdk:android:125.6422.02")
    // ===================================================================

    api("org.java-websocket:Java-WebSocket:1.5.3")
    api("com.alibaba:fastjson:1.1.72.android")
    // ...
}
```

新增 `sourceSets` 配置：
```kotlin
sourceSets {
    getByName("main") {
        jniLibs.srcDirs("src/main/jniLibs")
    }
}
```

### 步骤 2：下载并替换 .so 文件（4 个 ABI）

从 GetStream GitHub 仓库下载 repo zip，提取 4 个 ABI 的 .so 文件：

```
webrtclib/src/main/jniLibs/
├── arm64-v8a/
│   └── libjingle_peerconnection_so.so    (11 MB)
├── armeabi-v7a/
│   └── libjingle_peerconnection_so.so    (6.3 MB)
├── x86/
│   └── libjingle_peerconnection_so.so    (12 MB)
└── x86_64/
    └── libjingle_peerconnection_so.so    (12 MB)
```

**版本验证**：通过 MD5 校验确认 APK 中的 .so 与源文件一致：
```
MD5 (lib/arm64-v8a/libjingle_peerconnection_so.so) = bb71985285937057f8f344dae7daf6e5  (APK)
MD5 (lib/arm64-v8a/libjingle_peerconnection_so.so) = bb71985285937057f8f344dae7daf6e5  (源文件)
```

### 步骤 3：下载并替换 Java SDK 源码（189 个文件）

从 GetStream 仓库的 `stream-webrtc-android/src/main/java/org/webrtc/` 提取：

```
webrtclib/src/main/java/org/webrtc/
├── audio/                          # 音频设备模块
├── voiceengine/                    # 语音引擎
├── PeerConnection.java             # P2P 连接
├── PeerConnectionFactory.java      # 工厂类
├── VideoTrack.java / AudioTrack.java
├── SurfaceViewRenderer.java        # 视频渲染
├── Camera2Capturer.java            # 摄像头采集
├── ...                             # 共 189 个 Java 文件
```

**关键**：.so 和 Java 源码来自同一仓库的同一版本，保证 JNI `native` 方法签名严格匹配。

### 步骤 4：下载 C++ 头文件（1050 个关键头文件）

从 Shiguredo 的 `webrtc.android_sdk.tar.gz`（M142）提取关键公开 API 头文件：

```
webrtclib/src/main/cpp/include/
├── api/                # 公开 API（PeerConnectionInterface, VideoTrackInterface 等）
├── sdk/                # Android SDK native 层
├── rtc_base/           # 基础库（Thread, Ssl, Network）
├── pc/                 # PeerConnection 实现
├── common_video/       # 视频通用工具
├── media/              # 媒体引擎
├── call/               # 呼叫管理
└── system_wrappers/    # 系统封装
```

仅拷贝了 8 个核心目录的 1050 个头文件（非全部 52K 文件），避免项目臃肿。这些头文件供后续 C++ native 开发使用，当前阶段不参与编译。

### 步骤 5：JNI 调用链路（原逻辑如何走通）

替换后，**原有的 `WebRTCManager`、`Peer`、`PeerConnectionManager` 等业务代码无需任何修改**，因为 `org.webrtc.*` 的 API 完全一致——只是来源从 Maven JAR 变成了本地源码。

完整 JNI 调用链路：

```
应用层 (CallActivity / WebRTCManager)
    │  调用 org.webrtc.PeerConnectionFactory.builder()...
    ▼
org.webrtc.* Java 包装层 (本地源码, 189 文件, GetStream 1.3.10)
    │  通过 native 方法声明调用 .so
    ▼
libjingle_peerconnection_so.so (C++ native 实现, GetStream 1.3.10 编译)
    │  WebRTC 核心: PeerConnection, ICE, RTP, 编解码...
    ▼
Android 系统 (Camera2, OpenSLES, EGL, 网络)
```

以创建 PeerConnection 为例：

```
1. WebRTCManager.createFactory()
   → PeerConnectionFactory.builder().createPeerConnectionFactory()

2. PeerConnectionFactory (Java, 本地源码)
   → nativeCreatePeerConnectionFactory(...)  [JNI 调用]

3. libjingle_peerconnection_so.so (C++)
   → webrtc::PeerConnectionFactory::Create()
   → 创建 worker thread / signaling thread
   → 返回 jlong (native 指针) 给 Java 层

4. Peer (Java, 本地源码)
   → factory.createPeerConnection(iceServers, observer)
   → nativeCreatePeerConnection(factoryPtr, ...)
   → .so 中创建 webrtc::PeerConnection
```

### 步骤 6：自定义 native 桥接 (libwebrtc_native.so)

除 WebRTC 官方 .so 外，项目还保留了自定义的 `libwebrtc_native.so`，位于 `webrtclib/src/main/cpp/`：

```
webrtclib/src/main/cpp/
├── CMakeLists.txt       # 构建脚本（含自编译 WebRTC 链接示例）
├── webrtc_native.h      # C++ 类声明
├── webrtc_native.cpp    # JNI 导出实现
└── include/             # ← 新增：WebRTC C++ 头文件 (1050 个)
    ├── api/
    ├── sdk/
    ├── rtc_base/
    ├── pc/
    └── ...
```

**后续扩展方向**：在 `CMakeLists.txt` 中取消注释，引入 `include/` 目录的头文件，链接 WebRTC 静态库，然后在 `webrtc_native.cpp` 中直接调用 WebRTC C++ API（如 `webrtc::PeerConnectionInterface`），实现纯 native 通话逻辑。

### 步骤 7：APK 内 .so 验证

构建后验证 APK 包含 8 个 .so 文件（2 库 × 4 ABI），且 MD5 与源文件一致：

```
lib/arm64-v8a/libjingle_peerconnection_so.so    11 MB   ← WebRTC 核心 (GetStream 1.3.10)
lib/arm64-v8a/libwebrtc_native.so              383 KB   ← 自定义桥接
lib/armeabi-v7a/libjingle_peerconnection_so.so  6.3 MB
lib/armeabi-v7a/libwebrtc_native.so            287 KB
lib/x86/libjingle_peerconnection_so.so          12 MB
lib/x86/libwebrtc_native.so                    359 KB
lib/x86_64/libjingle_peerconnection_so.so       12 MB
lib/x86_64/libwebrtc_native.so                 365 KB
```

---

## 四、最终项目结构

```
webrtclib/
├── build.gradle.kts                         # Maven 依赖已注释，配置 jniLibs
├── src/main/
│   ├── AndroidManifest.xml                  # 权限声明
│   ├── jniLibs/                             # WebRTC 预编译 .so (GetStream 1.3.10)
│   │   ├── arm64-v8a/libjingle_peerconnection_so.so
│   │   ├── armeabi-v7a/libjingle_peerconnection_so.so
│   │   ├── x86/libjingle_peerconnection_so.so
│   │   └── x86_64/libjingle_peerconnection_so.so
│   ├── cpp/                                 # Native 桥接 + C++ 头文件
│   │   ├── CMakeLists.txt
│   │   ├── webrtc_native.h
│   │   ├── webrtc_native.cpp
│   │   └── include/                         # ← WebRTC C++ 头文件 (Shiguredo M142, 1050 个)
│   │       ├── api/
│   │       ├── sdk/
│   │       ├── rtc_base/
│   │       ├── pc/
│   │       └── ...
│   └── java/
│       ├── com/wangyao/webrtclib/           # 模块业务代码
│       │   ├── WebRTCManager.java
│       │   ├── WebRTCNative.java
│       │   ├── WebRTCEventListener.java
│       │   ├── signaling/
│       │   ├── peer/
│       │   └── render/
│       └── org/webrtc/                      # ← WebRTC Java SDK 源码 (GetStream 1.3.10, 189 文件)
│           ├── audio/
│           ├── voiceengine/
│           ├── PeerConnection.java
│           ├── PeerConnectionFactory.java
│           ├── SurfaceViewRenderer.java
│           └── ...
```

---

## 五、自动化下载脚本

项目根目录提供 `download_prebuilt.sh`，用于在 **macOS** 上一键下载并替换全部预编译产物，无需 Linux/Ubuntu 环境。

### 使用方法

```bash
chmod +x download_prebuilt.sh

# 下载最新版
./download_prebuilt.sh

# 下载指定 GetStream 版本
./download_prebuilt.sh 1.3.8
```

### 脚本执行流程

```
[1/3] 下载 GetStream/webrtc-android（.so + Java 源码）
  │    curl 下载 GitHub repo zip (20MB)
  │    提取 4 个 ABI 的 .so → webrtclib/src/main/jniLibs/
  │    提取 189 个 Java 源码 → webrtclib/src/main/java/org/webrtc/
  │
[2/3] 下载 Shiguredo C++ 头文件（M142 SDK tarball）
  │    curl 下载 webrtc.android_sdk.tar.gz (95MB)
  │    提取 8 个核心目录的 1050 个 .h → webrtclib/src/main/cpp/include/
  │
[3/3] 验证产物完整性
      检查 .so / Java / 头文件数量与大小
```

### 与 build_webrtc.sh 的关系

| 脚本 | 适用场景 | 环境要求 | 产出 |
|------|----------|----------|------|
| `download_prebuilt.sh` | **无 Linux 环境**，快速获取稳定预编译版 | macOS / Linux + curl | .so + Java + 头文件 |
| `build_webrtc.sh` | 需要自编译最新 WebRTC 源码 | Ubuntu Linux + 100GB 磁盘 | 同上，但从源码编译 |

---

## 六、关键设计决策

### 1. 为什么 .so 和 Java 源码必须来自同一版本？

WebRTC 的 Java SDK 本质是 JNI 包装层，每个 `native` 方法与特定版本的 .so **严格对应**。如果 .so 版本与 Java 源码版本不一致，会导致：
- JNI 方法签名不匹配 → `UnsatisfiedLinkError` 崩溃
- 新增的 native 方法在旧 .so 中不存在 → 运行时错误
- 数据结构偏移变化 → 内存损坏

因此，选择 GetStream 仓库（.so 和 Java 在同一仓库同一版本）是最可靠的方案。

### 2. C++ 头文件版本与 .so 版本不同是否有问题？

当前阶段**不影响**，因为头文件仅用于未来的 C++ native 开发，不参与当前编译。后续如果要在 `webrtc_native.cpp` 中直接调用 WebRTC C++ API，需要确保头文件版本与链接的 .so/.a 版本一致。届时可从 GetStream 仓库获取匹配版本的源码并提取头文件，或使用 `build_webrtc.sh` 自编译。

### 3. Maven 与本地 .so 如何切换？

| 方式 | build.gradle.kts | .so 来源 | Java 来源 |
|------|------------------|----------|-----------|
| Maven（已注释） | 取消注释 `api("io.github.webrtc-sdk:android:...")` | Maven 仓库 | Maven JAR |
| 本地 .so（当前） | 保持注释 | `jniLibs/<abi>/` | `java/org/webrtc/` |

### 4. 版本信息

| 产物 | 来源 | 版本 |
|------|------|------|
| .so + Java 源码 | GetStream/webrtc-android | 1.3.10（对应 WebRTC M13x 稳定分支） |
| C++ 头文件 | Shiguredo webrtc-build | M142.7444.2.1 |
| 自定义 native 桥接 | 本项目编译 | 1.0.0-scaffold |

---

## 七、验证结果

```
./gradlew :app:assembleDebug  →  BUILD SUCCESSFUL (31s)

APK 内 .so 文件（8 个 = 2 库 × 4 ABI）：
  ✓ libjingle_peerconnection_so.so  (WebRTC 核心, GetStream 1.3.10, 4 ABI)
  ✓ libwebrtc_native.so             (自定义桥接, 4 ABI)

Java 编译：
  ✓ webrtclib: 189 个 org.webrtc 源码 + 模块业务代码
  ✓ app: CallActivity / MainActivity 通过 org.webrtc API 调用

MD5 校验：
  ✓ APK 内 .so MD5 与 jniLibs 源文件 MD5 一致
```

Maven 依赖已完全移除，项目通过「网络下载的预编译 .so + 本地 Java 源码 + C++ 头文件 + JNI 调用」运行全部 WebRTC 逻辑，无需 Linux 交叉编译环境。

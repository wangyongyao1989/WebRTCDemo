# WebRTC 本地服务器使用说明

基于 [ddssingsong/webrtc_server_node](https://github.com/ddssingsong/webrtc_server_node) 项目搭建，
参考 [CSDN 博客教程](https://blog.csdn.net/u011077027/article/details/86225524)，适配 macOS 本地环境。

## 架构概览

本项目包含两个核心组件：

| 组件 | 说明 | 端口 |
|------|------|------|
| **SkyRTC 信令服务器** | Node.js + WebSocket，负责房间管理和信令交换 | 3000 |
| **coturn STUN/TURN** | 穿透和转发服务器，负责 NAT 穿透 | 3478 |

```
浏览器A ──┐                        ┌── 浏览器B
          ├── SkyRTC 信令 (3000) ──┤
          └── coturn STUN/TURN (3478) ──┘
                    │
              P2P 音视频流 (直连或经TURN中继)
```

## 快速开始

### 1. 启动服务

```bash
cd webrtc_server_node
bash start.sh
```

启动后会同时运行 coturn (STUN/TURN) 和 Node 信令服务器。

### 2. 浏览器测试视频通话

打开浏览器，访问：

```
http://localhost:3000/#room1
```

- URL 中 `#` 后面是**房间名**（如 `room1`）
- 相同房间名的用户可以互相通话
- **本机测试**：打开两个浏览器标签页，都访问上面的地址
- 浏览器会请求摄像头/麦克风权限，点击允许

### 3. 局域网测试

同一局域网内的其他设备访问：

```
http://192.168.43.104:3000/#room1
```

> **注意**：非 localhost 访问时，浏览器要求 HTTPS 才能使用摄像头/麦克风。
> 局域网设备测试需要配置 HTTPS（如 nginx 反向代理 + 自签名证书），
> 或在 Chrome 中添加启动参数：
> `--unsafely-treat-insecure-origin-as-secure=http://192.168.43.104:3000`

### 4. 停止服务

```bash
bash stop.sh
```

或按 `Ctrl+C` 停止前台运行的服务。

## 功能说明

| 功能 | 说明 |
|------|------|
| 视频通话 | 房间内所有用户互看视频 |
| 文字聊天 | 页面底部输入框发送消息 |
| 文件传输 | 选择文件后点击"发送文件"，对方确认后传输 |

## 配置说明

### coturn 配置

配置文件：`coturn/turnserver.local.conf`

关键配置项：

```ini
listening-ip=192.168.43.104    # 监听 IP（改为你的本机 IP）
external-ip=192.168.43.104     # 外网 IP（本地测试填局域网 IP）
user=ddssingsong:123456        # TURN 用户名和密码
realm=test                      # Realm 域名
```

如果本机 IP 变化，需要修改以上配置。

### ICE 服务器配置

配置文件：`public/dist/js/SkyRTC-client.js`

```javascript
const iceServer = {
    "iceServers": [
        { "url": "stun:stun.l.google.com:19302" },     // Google 公共 STUN
        { "url": "stun:192.168.43.104:3478" },          // 本地 coturn STUN
        { "url": "turn:192.168.43.104:3478",             // 本地 coturn TURN
          "username": "ddssingsong",
          "credential": "123456" }
    ]
};
```

如果本机 IP 变化，需要同步修改此文件中的 IP 地址。

### WebSocket 连接配置

配置文件：`public/dist/js/conn.js`

当前配置为本地无 nginx 的 `ws:` 连接方式。如需配置 nginx HTTPS/WSS 代理，
将最后一行改为：

```javascript
rtc.connect("wss:" + window.location.href.substring(window.location.protocol.length).split('#')[0]+"/wss", window.location.hash.slice(1));
```

## 文件结构

```
webrtc_server_node/
├── server.js                      # Node 信令服务器入口
├── package.json                   # npm 依赖配置
├── index.html                     # 前端页面
├── start.sh                       # 一键启动脚本
├── stop.sh                        # 一键停止脚本
├── bin/
│   └── turnserver                 # coturn 二进制（已编译）
├── coturn/
│   ├── turnserver.local.conf      # coturn 本地配置
│   └── turnserver.conf            # coturn 完整配置模板
└── public/
    └── dist/
        ├── css/index.css
        └── js/
            ├── SkyRTC.js          # 服务端 WebSocket 信令逻辑
            ├── SkyRTC-client.js   # 客户端 WebRTC + ICE 配置
            └── conn.js            # 客户端 UI 交互 + WS 连接
```

## 验证测试

### 测试信令服务器

信令服务器通过 WebSocket 在 3000 端口提供信令服务。
两个客户端加入同一房间后会互相发现，交换 SDP Offer/Answer 和 ICE Candidate。

### 测试 STUN/TURN

访问 WebRTC 官方 ICE 测试页面：

```
https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/
```

在 STUN/TURN 配置中添加：

```
stun:192.168.43.104:3478
turn:192.168.43.104:3478 (用户名: ddssingsong, 密码: 123456)
```

点击 Gather candidates，如果能看到 relay 类型的 candidate，说明 TURN 工作正常。

## 常见问题

### Q: 浏览器无法访问摄像头？

A: getUserMedia 要求安全上下文（HTTPS 或 localhost）。
- `http://localhost:3000` 可以正常使用（localhost 是安全上下文）
- `http://192.168.43.104:3000` 需要配置 HTTPS 或使用 Chrome 参数

### Q: 两个标签页看不到对方视频？

A: 请检查：
1. 两个标签页的房间名是否相同（URL 中 `#` 后面的部分）
2. 浏览器控制台是否有 WebSocket 连接错误
3. coturn 是否正在运行（`bash stop.sh && bash start.sh` 重启）

### Q: coturn 启动失败？

A: 如果 IP 地址变了，需要更新以下文件中的 IP：
1. `coturn/turnserver.local.conf` 中的 `listening-ip`、`relay-ip`、`external-ip`
2. `public/dist/js/SkyRTC-client.js` 中的 ICE 服务器地址

查看当前 IP：`ipconfig getifaddr en0`

### Q: 如何在公网使用？

A: 公网部署需要：
1. 将 `external-ip` 改为公网 IP
2. 配置 nginx 提供 HTTPS 和 WSS 代理
3. 开放防火墙端口：3000（信令）、3478（STUN/TURN）、49152-65535（TURN 中继）

## 技术细节

### macOS 适配说明

原始博客针对 Linux 环境，本搭建做了以下 macOS 适配：

1. **coturn 编译**：由于 Homebrew 权限问题，从源码编译 OpenSSL 3.0.13 + libevent + coturn 4.6.2，
   安装到项目 `bin/` 目录，静态链接 OpenSSL 和 libevent，仅依赖系统库
2. **Express 兼容**：修复 `server.js` 中 `app.use()` 的 null 参数问题（新版 Express 不兼容）
3. **WebSocket 协议**：`conn.js` 使用 `ws:` 而非 `wss:`（本地无 nginx 代理）
4. **Node.js 路径**：使用系统 Node.js v24（`/usr/local/bin/node`）

### TURN 凭证

- 用户名：`ddssingsong`
- 密码：`123456`
- Realm：`test`

这些凭证在 `coturn/turnserver.local.conf` 和 `public/dist/js/SkyRTC-client.js` 中保持一致。

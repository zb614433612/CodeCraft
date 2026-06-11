> 🌐 English Version：[🇬🇧 P2P_SYSTEM_EN](./P2P_SYSTEM_EN.md)
# P2P 远程协作系统深描

> 版本：v1.0.5 | 更新：2026-05-28 | 受众：开发者 / AI 协作伙伴
> 本文档解剖 P2P 子系统的完整架构——从二进制协议到 Agent 远程调用的全链路。

---

## 一、一句话定义

P2P 子系统让两台运行 CodeCraft 的电脑通过点对点加密通道互相调用对方的 AI Agent，实现"你的 AI 可以调用我的 AI"的分布式协作。

---

## 二、系统架构拓扑

```
┌─────────────────── CodeCraft 实例 A ───────────────────────┐
│                                                            │
│ ┌──────────┐   ┌──────────────────┐   ┌──────────────┐    │
│ │P2pController│←→│ P2pAgentService │←→│DeepSeekService│    │
│ │(REST API) │   │(授权/调用逻辑)  │   │ (AI 引擎)    │    │
│ └──────────┘   └───────┬──────────┘   └──────────────┘    │
│                        │                                   │
│ ┌──────────────────────┴───────────────────────────────┐   │
│ │             P2pConnectionManager                      │   │
│ │ ┌──────────┐ ┌──────────┐ ┌──────────────────┐       │   │
│ │ │P2pServer │ │P2pClient │ │ ConnectionPool   │       │   │
│ │ │(Netty)   │ │(Netty)   │ │ (Channel管理)    │       │   │
│ │ └────┬─────┘ └────┬─────┘ └──────────────────┘       │   │
│ │      │            │                                  │   │
│ │ ┌────┴────────────┴──────────────────────────────┐   │   │
│ │ │             MessageRouter                       │   │   │
│ │ │ ┌──────────┐┌────────────┐┌───────────────┐    │   │   │
│ │ │ │Handshake ││ChatMessage ││AgentInvoke    │    │   │   │
│ │ │ │Handler   ││Handler     ││Handler        │    │   │   │
│ │ │ └──────────┘└────────────┘└───────────────┘    │   │   │
│ │ └───────────────────────────────────────────────┘   │   │
│ └─────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────┘
                         │
        ═════════════════╪═════════════════
        ║ TLS 加密通道   ║
        ═════════════════╪═════════════════
                         │
┌───────────────────────┴───────────────────────────────┐
│             同样的镜像结构                              │
│                  CodeCraft 实例 B                      │
└───────────────────────────────────────────────────────┘
```

---

## 三、协议栈

### 3.1 消息帧格式（MessageFrame）

```
┌────────┬─────────┬──────────┬──────────┬──────────────────┐
│ Magic  │ Version │  Type    │ Length   │    Payload       │
│2 Byte  │ 1 Byte  │ 1 Byte   │ 4 Byte   │    N Byte        │
│0xCCCC  │ 0x01    │ (枚举值) │ (大端)   │  (UTF-8 JSON)    │
└────────┴─────────┴──────────┴──────────┴──────────────────┘
```

- **Magic**: `0xCCCC`，用于快速识别 CodeCraft P2P 消息
- **Version**: `0x01`，协议版本
- **Type**: 消息类型（见下表）
- **Length**: Payload 字节数（大端序）
- **Payload**: UTF-8 编码的 JSON 字符串

### 3.2 消息类型枚举（12 种）

| 代码 | 枚举名 | 方向 | 说明 |
|:----:|--------|:----:|------|
| 0x00 | HEARTBEAT | ↔ | 心跳保活，每 30s 发送 |
| 0x01 | HANDSHAKE | ↔ | 握手（HELLO + HELLO_ACK），交换设备名和功能信息 |
| 0x02 | FILE_TRANSFER | ↔ | 文件传输 |
| 0x03 | CHAT_MESSAGE | ↔ | 即时聊天消息 |
| 0x04 | CODE_SYNC | ↔ | 代码片段同步 |
| 0x05 | REMOTE_SHELL | ↔ | 远程命令执行 |
| 0x06 | SNAPSHOT_SHARE | ↔ | 快照分享 |
| 0x07 | DISCONNECT_NOTIFY | ↔ | 优雅断开通知，告知对方不要重连 |
| 0x08 | AGENT_AUTH_GRANT | ↔ | 授权 Agent 给对方使用 |
| 0x09 | AGENT_AUTH_CANCEL | ↔ | 取消 Agent 授权 |
| 0x0A | AGENT_INVOKE | ↔ | 调用对方 Agent |
| 0x0B | AGENT_RESPONSE | ↔ | Agent 返回执行结果 |

---

## 四、连接生命周期

### 4.1 启动流程

```
P2pConnectionManager.start()
    ↓
    ├─ ① TlsHelper 生成/加载自签名证书（RSA 2048 + SAN 扩展）
    │
    ├─ ② P2pServer 启动（Netty ServerBootstrap）
    │   ├─ bossGroup(1) + workerGroup(4)
    │   ├─ SslContext（TLS 1.2, 双向认证可选）
    │   ├─ MessageFrameDecoder + MessageFrameEncoder
    │   ├─ 消息处理器链（MessageRouter）
    │   └─ 绑定端口（默认 19527）
    │
    └─ ③ Ipv6AddressCollector 收集本机 IPv6 地址
        └─ 供二维码信令使用（局域网直连）
```

### 4.2 握手流程

```
发起方 A                              接收方 B
    │                                   │
    │── HANDSHAKE (HELLO) ──────────────→│
    │   {name, addresses, features}      │
    │                                   │── 存储 PeerInfo
    │                                   │   (peerId, name, address)
    │                                   │
    │←─ HANDSHAKE (HELLO_ACK) ──────────│
    │   {name, addresses, features}     │
    │                                   │
    │── 存储 PeerInfo                    │
    │                                   │
    │═══ 连接建立，开始心跳 ═══════════════│
```

### 4.3 心跳与重连

```
心跳机制：
  └─ 每 30 秒发送 HEARTBEAT
  └─ 60 秒未收到心跳 → 判定对方离线
  └─ 触发自动重连（指数退避：1s → 2s → 4s → ... 最大 60s）

优雅断开：
  └─ 主动断开前发送 DISCONNECT_NOTIFY
  └─ 接收方取消自动重连计划
```

### 4.4 连接池管理

```
ConnectionPool
  ├─ ConcurrentHashMap<peerId, Channel>
  ├─ 同一 peer 最多一个活跃 Channel
  └─ 连接建立/断开时通知 MessageRouter
```

---

## 五、安全体系

### 5.1 TLS 证书（TlsHelper）

```
流程：
  1. 首次启动 → 检查 data/p2p/certs/p2p-keystore.jks
  2. 不存在 → 用 BouncyCastle 生成自签名证书
     ├─ RSA 2048 位密钥
     ├─ CN = "CodeCraft P2P"
     ├─ SAN = 本机所有 IPv4/IPv6 地址 + localhost
     └─ 有效期 365 天
  3. 加载到 Netty SslContext
  4. 配置 TLS 1.2, 支持双向认证（可选）
```

### 5.2 消息加密（CryptoHelper）

```
AES-256-GCM 加密（用于非 TLS 场景的 Payload 加密）：
  ├─ 密钥派生：PBKDF2WithHmacSHA256
  ├─ IV：随机生成 12 字节
  └─ 认证标签：128 位
```

---

## 六、信令交换：二维码配对

### 6.1 数据结构（SignalingData）

```json
{
  "peerId": "abc123...",
  "name": "我的电脑",
  "addresses": ["192.168.1.5", "fe80::1"],
  "port": 19527,
  "fingerprint": "SHA256:..."
}
```

### 6.2 配对流程

```
设备 A（生成二维码）                    设备 B（扫码）
    │                                      │
    │① 构建 SignalingData                  │
    │② QrCodeSignaling.generate()         │
    │   生成 Base64 PNG 二维码              │
    │③ 前端显示二维码                      │
    │                                      │
    │                         ← 扫码 → 解析 SignalingData
    │                          提取连接信息
    │                          P2pClient.connect(address, port)
    │                          TLS 握手
    │                          HANDSHAKE 交换
    │                                      │
    │═══════ 配对完成，开始通信 ════════════════│
```

### 6.3 连接字符串（ConnectionStringHelper）

作为二维码的备用方案，支持手动输入连接字符串：
```
codecraft-p2p://192.168.1.5:19527?fp=SHA256:xxx&name=MyPC
```

---

## 七、Agent 远程调用全链路

这是 P2P 子系统最核心的业务场景。

### 7.1 授权流程

```
用户 A（授权方）                      用户 B（被授权方）
    │                                      │
    │ ① P2P 面板勾选 Agent，点击授权       │
    │ ② P2pAgentService.grantAuthorization()│
    │   ├─ 冲突检测（同一Agent不可重复授权） │
    │   ├─ 写本地DB: direction=sent        │
    │   └─ 发送 AGENT_AUTH_GRANT ──────────→│
    │                                      │
    │                         ← handleIncomingGrant()
    │                            ├─ 写本地DB: direction=received
    │                            ├─ 存储 Agent 元数据（名称/描述/头像）
    │                            └─ 聊天记录通知
    │                                      │
    │══════ 双方各存一条授权记录 ═══════════════│
```

### 7.2 调用流程

```
调用方 A                               被调用方 B
    │                                      │
    │ ① 用户发送消息给远程 Agent            │
    │ ② P2pAgentService.invokeAgent()      │
    │   ├─ 构建 requestId (UUID)            │
    │   ├─ 构建 AGENT_INVOKE 消息           │
    │   ├─ 通过 TLS 通道发送 ──────────────→│
    │   └─ 记录到聊天                       │
    │                                      │
    │                         ← handleIncomingInvoke()
    │                            ├─ ① 校验本地授权(sent)
    │                            │    └─ 非active → 拒绝
    │                            ├─ getOrCreateConversation()
    │                            │    └─ 上下文继承映射
    │                            ├─ executeAiSync()
    │                            │    └─ 调用 DeepSeekService
    │                            │       └─ Agent 执行工具循环
    │                            ├─ 发送 AGENT_RESPONSE ────→│
    │                            └─ 记录到聊天                │
    │                                      │
    │ ③ handleIncomingResponse()          │
    │   ├─ 完成 pendingInvocations Future   │
    │   └─ 前端展示 Agent 回复              │
    │                                      │
```

### 7.3 关键安全校验

```
handleIncomingInvoke() 的授权校验逻辑：
  ① 查本地 p2p_agent_authorization 表
     └─ WHERE peer_id=? AND agent_config_id=?
        AND direction='sent' AND status='active'

  ② 如果记录不存在 → 返回 AGENT_RESPONSE(status=rejected)
     └─ 本地方即使取消授权后通知未送达，对方也无法绕过

  ③ 授权有效 → 执行 AI 并返回结果

这是"零信任"设计：不信任网络通道，始终以本地授权记录为准
```

### 7.4 上下文继承

```
p2p_agent_conversation 表实现 peerId + agentId → conversationId 映射

首次调用：
  └─ 创建新 Conversation → 存储映射

后续调用：
  └─ 复用已有 Conversation → AI 可访问之前的对话上下文

这实现了"同一个远程 Agent 的多次调用共享上下文"
```

---

## 八、消息路由（MessageRouter）

```
MessageRouter
    ↓
    ├─ 注册处理器（按 MessageType）
    │   ├─ HANDSHAKE    → HandshakeHandler
    │   ├─ CHAT_MESSAGE → ChatMessageHandler
    │   ├─ DISCONNECT_NOTIFY → DisconnectNotifyHandler
    │   ├─ AGENT_AUTH_GRANT   → AgentAuthGrantHandler
    │   ├─ AGENT_AUTH_CANCEL  → AgentAuthCancelHandler
    │   ├─ AGENT_INVOKE       → AgentInvokeHandler
    │   └─ AGENT_RESPONSE     → AgentResponseHandler
    │
    └─ 收到消息 → 解析 MessageFrame.type → 路由到对应 Handler
```

---

## 九、包结构速查

```
p2p/
├── agent/                          # Agent 远程调用业务逻辑
│   ├── P2pAgentService             # ★核心：授权/调用/响应 (33.4KB)
│   ├── AgentAuthGrantHandler       # 处理对方发来的授权
│   ├── AgentAuthCancelHandler      # 处理对方发来的取消授权
│   ├── AgentInvokeHandler          # 处理对方发来的调用请求
│   └── AgentResponseHandler        # 处理对方发来的调用响应
├── connection/                     # 网络连接管理
│   ├── P2pConnectionManager        # ★核心：Server/Client 生命周期 (12.6KB)
│   ├── P2pServer                   # Netty TCP Server
│   ├── P2pClient                   # Netty TCP Client
│   ├── ConnectionPool              # Channel 连接池
│   └── PeerInfo                    # 节点信息 POJO
├── protocol/                       # 二进制协议
│   ├── MessageFrame                # ★消息帧编解码
│   ├── MessageType                 # 消息类型枚举 (12种)
│   ├── P2pConstants                # 常量定义（Magic/Version）
│   └── codec/                      # Netty 编解码器
├── security/                       # 安全
│   ├── TlsHelper                   # 自签名证书生成 + SslContext
│   └── CryptoHelper                # AES-256-GCM 加密
├── signaling/                      # 信令交换
│   ├── QrCodeSignaling             # 二维码生成/解析 (ZXing)
│   ├── ConnectionStringHelper      # 连接字符串编解码
│   └── SignalingData               # 信令数据结构
├── message/                        # 消息路由
│   ├── MessageRouter               # 消息分发器
│   ├── HandshakeHandler            # 握手处理
│   ├── ChatMessageHandler          # 聊天消息处理
│   ├── DisconnectNotifyHandler     # 断开通知处理
│   └── MessageHandler              # 处理器接口
├── service/P2pChatService          # 聊天消息持久化
├── controller/P2pController        # REST API (19.8KB)
└── config/P2pConfig                 # 配置（端口/证书路径等）
```

---

## 十、关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 网络框架 | Netty | 高性能异步 IO，支持 TLS 开箱即用 |
| 序列化 | JSON（非 Protobuf） | P2P 消息量小，JSON 调试友好 |
| 信令方式 | 二维码 + 连接字符串双模式 | 扫码配对方便，手动输入兜底 |
| 安全模型 | TLS 通道 + 本地授权校验（零信任） | 双重保障，不依赖网络层安全 |
| 授权方向 | sent/received 双记录 | 每方独立管理授权，取消时本地立即生效 |
| 上下文继承 | p2p_agent_conversation 映射表 | 多次调用同一 Agent 保持对话连续 |
| 重连策略 | 指数退避（1s→60s） | 避免网络波动时频繁重连 |
| IPv6 优先 | Ipv6AddressCollector | 局域网直连场景 IPv6 更稳定 |

---

> 📌 **文档维护约定**: P2P 协议新增 MessageType 或授权逻辑变更时，请同步更新本文档。

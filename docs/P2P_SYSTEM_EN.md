> 🌐 中文版：[🇨🇳 P2P_SYSTEM](./P2P_SYSTEM.md)
# P2P Remote Collaboration System Deep Dive

> Version: v1.0.5 | Updated: 2026-05-28 | Audience: Developers / AI Collaborators
> This document dissects the complete architecture of the P2P subsystem — from binary protocol to the full chain of Agent remote invocation.

---

## 1. One-Sentence Definition

The P2P subsystem enables two computers running CodeCraft to invoke each other's AI Agents through a peer-to-peer encrypted channel, realizing distributed collaboration where "your AI can call my AI."

---

## 2. System Architecture Topology

```
┌─────────────────── CodeCraft Instance A ───────────────────────┐
│                                                                │
│ ┌──────────┐   ┌──────────────────┐   ┌──────────────┐        │
│ │P2pController│←→│ P2pAgentService │←→│DeepSeekService│        │
│ │(REST API) │   │(Auth/Invoke Logic)│  │ (AI Engine)  │        │
│ └──────────┘   └───────┬──────────┘   └──────────────┘        │
│                        │                                       │
│ ┌──────────────────────┴───────────────────────────────┐       │
│ │             P2pConnectionManager                      │       │
│ │ ┌──────────┐ ┌──────────┐ ┌──────────────────┐       │       │
│ │ │P2pServer │ │P2pClient │ │ ConnectionPool   │       │       │
│ │ │(Netty)   │ │(Netty)   │ │ (Channel Mgmt)   │       │       │
│ │ └────┬─────┘ └────┬─────┘ └──────────────────┘       │       │
│ │      │            │                                   │       │
│ │ ┌────┴────────────┴──────────────────────────────┐    │       │
│ │ │             MessageRouter                       │    │       │
│ │ │ ┌──────────┐┌────────────┐┌───────────────┐    │    │       │
│ │ │ │Handshake ││ChatMessage ││AgentInvoke    │    │    │       │
│ │ │ │Handler   ││Handler     ││Handler        │    │    │       │
│ │ │ └──────────┘└────────────┘└───────────────┘    │    │       │
│ │ └───────────────────────────────────────────────┘    │       │
│ └─────────────────────────────────────────────────────┘       │
└───────────────────────────────────────────────────────────────┘
                         │
        ═════════════════╪═════════════════
        ║ TLS Encrypted  ║
        ═════════════════╪═════════════════
                         │
┌───────────────────────┴───────────────────────────────┐
│             Same Mirror Structure                      │
│                  CodeCraft Instance B                  │
└───────────────────────────────────────────────────────┘
```

---

## 3. Protocol Stack

### 3.1 Message Frame Format

```
┌────────┬─────────┬──────────┬──────────┬──────────────────┐
│ Magic  │ Version │  Type    │ Length   │    Payload       │
│2 Byte  │ 1 Byte  │ 1 Byte   │ 4 Byte   │    N Byte        │
│0xCCCC  │ 0x01    │ (enum)   │ (big-end)│  (UTF-8 JSON)    │
└────────┴─────────┴──────────┴──────────┴──────────────────┘
```

- **Magic**: `0xCCCC`, for quick CodeCraft P2P message identification
- **Version**: `0x01`, protocol version
- **Type**: Message type (see table below)
- **Length**: Payload byte count (big-endian)
- **Payload**: UTF-8 encoded JSON string

### 3.2 Message Type Enumeration (12 Types)

| Code | Enum Name | Direction | Description |
|:----:|-----------|:--------:|-------------|
| 0x00 | HEARTBEAT | ↔ | Heartbeat keepalive, every 30s |
| 0x01 | HANDSHAKE | ↔ | Handshake (HELLO + HELLO_ACK), exchange device name and features |
| 0x02 | FILE_TRANSFER | ↔ | File transfer |
| 0x03 | CHAT_MESSAGE | ↔ | Instant chat message |
| 0x04 | CODE_SYNC | ↔ | Code snippet sync |
| 0x05 | REMOTE_SHELL | ↔ | Remote command execution |
| 0x06 | SNAPSHOT_SHARE | ↔ | Snapshot sharing |
| 0x07 | DISCONNECT_NOTIFY | ↔ | Graceful disconnect notification |
| 0x08 | AGENT_AUTH_GRANT | ↔ | Grant Agent authorization to peer |
| 0x09 | AGENT_AUTH_CANCEL | ↔ | Cancel Agent authorization |
| 0x0A | AGENT_INVOKE | ↔ | Invoke peer's Agent |
| 0x0B | AGENT_RESPONSE | ↔ | Agent returns execution result |

---

## 4. Connection Lifecycle

### 4.1 Startup Flow

```
P2pConnectionManager.start()
    ↓
    ├─ ① TlsHelper generates/loads self-signed certificate (RSA 2048 + SAN extension)
    │
    ├─ ② P2pServer startup (Netty ServerBootstrap)
    │   ├─ bossGroup(1) + workerGroup(4)
    │   ├─ SslContext (TLS 1.2, optional mutual auth)
    │   ├─ MessageFrameDecoder + MessageFrameEncoder
    │   ├─ Message handler chain (MessageRouter)
    │   └─ Bind port (default 19527)
    │
    └─ ③ Ipv6AddressCollector collects local IPv6 addresses
        └─ Used for QR code signaling (LAN direct connection)
```

### 4.2 Handshake Flow

```
Initiator A                          Receiver B
    │                                   │
    │── HANDSHAKE (HELLO) ──────────────→│
    │   {name, addresses, features}      │
    │                                   │── Store PeerInfo
    │                                   │   (peerId, name, address)
    │                                   │
    │←─ HANDSHAKE (HELLO_ACK) ──────────│
    │   {name, addresses, features}     │
    │                                   │
    │═══ Connection established ═════════│
```

### 4.3 Heartbeat & Reconnect

```
Heartbeat:
  └─ Send HEARTBEAT every 30 seconds
  └─ No heartbeat for 60 seconds → peer considered offline
  └─ Trigger auto-reconnect (exponential backoff: 1s → 2s → 4s → ... max 60s)

Graceful Disconnect:
  └─ Send DISCONNECT_NOTIFY before active disconnect
  └─ Receiver cancels auto-reconnect schedule
```

---

## 5. Security System

### 5.1 TLS Certificates (TlsHelper)

```
Flow:
  1. First launch → check data/p2p/certs/p2p-keystore.jks
  2. Non-existent → generate self-signed cert with BouncyCastle
     ├─ RSA 2048-bit key
     ├─ CN = "CodeCraft P2P"
     ├─ SAN = all local IPv4/IPv6 addresses + localhost
     └─ Validity 365 days
  3. Load into Netty SslContext
  4. Configure TLS 1.2, optional mutual auth
```

### 5.2 Message Encryption (CryptoHelper)

```
AES-256-GCM encryption (for non-TLS payload encryption):
  ├─ Key derivation: PBKDF2WithHmacSHA256
  ├─ IV: Random 12 bytes
  └─ Auth tag: 128-bit
```

---

## 6. Signaling: QR Code Pairing

### 6.1 Data Structure (SignalingData)

```json
{
  "peerId": "abc123...",
  "name": "My Computer",
  "addresses": ["192.168.1.5", "fe80::1"],
  "port": 19527,
  "fingerprint": "SHA256:..."
}
```

### 6.2 Pairing Flow

```
Device A (Generate QR)               Device B (Scan)
    │                                      │
    │① Build SignalingData                 │
    │② QrCodeSignaling.generate()          │
    │    Generate Base64 PNG QR code        │
    │③ Frontend displays QR code           │
    │                                      │
    │                          ← Scan → Parse SignalingData
    │                           Extract connection info
    │                           P2pClient.connect(address, port)
    │                           TLS handshake
    │                           HANDSHAKE exchange
    │                                      │
    │══════ Pairing complete ═══════════════│
```

---

## 7. Agent Remote Invocation: Full Chain

### 7.1 Authorization Flow

```
User A (Grantor)                     User B (Grantee)
    │                                      │
    │ ① Check Agent in P2P panel, grant   │
    │ ② P2pAgentService.grantAuthorization()│
    │   ├─ Conflict check (same Agent can't be doubly granted)│
    │   ├─ Write local DB: direction=sent │
    │   └─ Send AGENT_AUTH_GRANT ──────────→│
    │                                      │
    │                          ← handleIncomingGrant()
    │                             ├─ Write local DB: direction=received
    │                             ├─ Store Agent metadata
    │                             └─ Chat record notification
    │                                      │
    │══════ Both sides store auth record ════════│
```

### 7.2 Invocation Flow

```
Caller A                              Callee B
    │                                      │
    │ ① User sends message to remote Agent │
    │ ② P2pAgentService.invokeAgent()      │
    │   ├─ Build requestId (UUID)          │
    │   ├─ Build AGENT_INVOKE message      │
    │   └─ Send via TLS channel ───────────→│
    │                                      │
    │                          ← handleIncomingInvoke()
    │                             ├─ Verify local auth (sent)
    │                             │   └─ Non-active → reject
    │                             ├─ getOrCreateConversation()
    │                             ├─ executeAiSync()
    │                             │   └─ Call DeepSeekService
    │                             │       └─ Agent executes tool loop
    │                             ├─ Send AGENT_RESPONSE ────→│
    │                             └─ Record to chat           │
    │                                      │
    │ ③ handleIncomingResponse()           │
    │   ├─ Complete pendingInvocation Future│
    │   └─ Frontend displays Agent reply   │
    │                                      │
```

### 7.3 Key Security Check

```
handleIncomingInvoke() authorization verification:
  ① Query local p2p_agent_authorization table
     └─ WHERE peer_id=? AND agent_config_id=?
        AND direction='sent' AND status='active'

  ② If no record → return AGENT_RESPONSE(status=rejected)
     └─ Even if cancel notification hasn't arrived, can't bypass

  ③ Auth valid → execute AI and return result

This is "Zero Trust" design: don't trust network channel,
always use local auth records as the source of truth
```

### 7.4 Context Inheritance

```
p2p_agent_conversation table: peerId + agentId → conversationId mapping

First invocation:
  └─ Create new Conversation → store mapping

Subsequent invocations:
  └─ Reuse existing Conversation → AI can access previous dialogue context

This enables "shared context across multiple invocations of the same remote Agent"
```

---

## 8. Package Structure Quick Reference

```
p2p/
├── agent/                          # Agent remote invocation business logic
│   ├── P2pAgentService             # ★Core: auth/invoke/response (33.4KB)
│   ├── AgentAuthGrantHandler       # Handle incoming auth grant
│   ├── AgentAuthCancelHandler      # Handle incoming auth cancel
│   ├── AgentInvokeHandler          # Handle incoming invoke request
│   └── AgentResponseHandler        # Handle incoming invoke response
├── connection/                     # Network connection management
│   ├── P2pConnectionManager        # ★Core: Server/Client lifecycle (12.6KB)
│   ├── P2pServer                   # Netty TCP Server
│   ├── P2pClient                   # Netty TCP Client
│   ├── ConnectionPool              # Channel connection pool
│   └── PeerInfo                    # Peer info POJO
├── protocol/                       # Binary protocol
│   ├── MessageFrame                # ★Message frame codec
│   ├── MessageType                 # Message type enum (12 types)
│   ├── P2pConstants                # Constants (Magic/Version)
│   └── codec/                      # Netty codecs
├── security/                       # Security
│   ├── TlsHelper                   # Self-signed cert generation + SslContext
│   └── CryptoHelper                # AES-256-GCM encryption
├── signaling/                      # Signaling exchange
│   ├── QrCodeSignaling             # QR code generation/parsing (ZXing)
│   ├── ConnectionStringHelper      # Connection string codec
│   └── SignalingData               # Signaling data structure
├── message/                        # Message routing
│   ├── MessageRouter               # Message dispatcher
│   ├── HandshakeHandler            # Handshake handler
│   ├── ChatMessageHandler          # Chat message handler
│   └── DisconnectNotifyHandler     # Disconnect notification handler
├── service/P2pChatService          # Chat message persistence
├── controller/P2pController        # REST API (19.8KB)
└── config/P2pConfig                 # Configuration (port/cert path etc.)
```

---

## 9. Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Network Framework | Netty | High-performance async IO, TLS support out of the box |
| Serialization | JSON (not Protobuf) | P2P messages are small, JSON is debugging-friendly |
| Signaling | QR Code + Connection String dual mode | Scan pairing is convenient, manual entry as fallback |
| Security Model | TLS channel + local auth verification (Zero Trust) | Dual protection, doesn't rely on network-layer security |
| Auth Direction | sent/received dual records | Each side manages auth independently, cancel takes effect locally instantly |
| Context Inheritance | p2p_agent_conversation mapping table | Multiple calls to same Agent maintain conversation continuity |
| Reconnect Strategy | Exponential backoff (1s→60s) | Avoid frequent reconnection during network fluctuations |
| IPv6 Priority | Ipv6AddressCollector | LAN direct connection more stable with IPv6 |

---

> 📌 **Doc Maintenance Convention**: When P2P protocol adds new MessageTypes or auth logic changes, please sync this document.

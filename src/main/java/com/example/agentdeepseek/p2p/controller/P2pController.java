package com.example.agentdeepseek.p2p.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.mapper.P2pKnownPeerMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.agentdeepseek.model.entity.P2pChatMessage;
import com.example.agentdeepseek.model.entity.P2pKnownPeer;
import com.example.agentdeepseek.p2p.agent.P2pAgentService;
import com.example.agentdeepseek.p2p.connection.P2pConnectionManager;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import com.example.agentdeepseek.p2p.service.P2pChatService;
import com.example.agentdeepseek.p2p.signaling.ConnectionStringHelper;
import com.example.agentdeepseek.p2p.signaling.QrCodeSignaling;
import com.example.agentdeepseek.p2p.signaling.SignalingData;
import com.example.agentdeepseek.service.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * P2P REST Controller
 */
@RestController
@RequestMapping("/api/p2p")
public class P2pController {

    private static final Logger log = LoggerFactory.getLogger(P2pController.class);

    private final P2pConnectionManager connectionManager;

    @Autowired
    private ConfigService configService;

    @Autowired
    private P2pChatService chatService;

    @Autowired
    private P2pKnownPeerMapper knownPeerMapper;

    @Autowired
    private P2pAgentService agentService;

    public P2pController(P2pConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 本机名称 ====================

    @GetMapping("/name")
    public ApiResponse<Map<String, Object>> getName() {
        String name = getMyDisplayName();
        connectionManager.setMyName(name);
        return ApiResponse.success(Map.of("name", name));
    }

    @PostMapping("/name")
    public ApiResponse<Map<String, Object>> setName(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "");
        connectionManager.setMyName(name);
        return ApiResponse.success(Map.of("name", name, "status", "ok"));
    }

    // ==================== 状态查询 ====================

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("running", connectionManager.isRunning());
        status.put("peerId", connectionManager.getAddressCollector().getPeerId());
        status.put("port", connectionManager.getConfig().getPort());
        status.put("peerCount", connectionManager.getConnectionPool().getPeerCount());
        status.put("certFingerprint", connectionManager.getTlsHelper().getCertFingerprint());
        status.put("myName", connectionManager.getMyName());
        return ApiResponse.success(status);
    }

    @GetMapping("/address")
    public ApiResponse<Map<String, Object>> getAddress() {
        Map<String, Object> result = new HashMap<>();
        result.put("addresses", connectionManager.getAddressCollector().getAddressStrings());
        result.put("bestAddress", connectionManager.getAddressCollector().getBestAddressString().orElse(null));
        result.put("hasIpv6", connectionManager.getAddressCollector().hasIpv6Connectivity());
        return ApiResponse.success(result);
    }

    // ==================== 信令 ====================

    @PostMapping("/qrcode")
    public ApiResponse<Map<String, Object>> generateQrCode() {
        SignalingData data = buildMySignalingData();
        String connectionString = ConnectionStringHelper.generate(data);

        Map<String, Object> result = new HashMap<>();
        result.put("connectionString", connectionString);
        result.put("compactString", ConnectionStringHelper.generateCompact(data));

        try {
            String qrBase64 = QrCodeSignaling.generateQrCodeBase64(data);
            result.put("qrCodeBase64", qrBase64);
        } catch (Exception e) {
            log.error("[P2P] QR code generation failed", e);
            result.put("qrCodeBase64", "");
            result.put("qrError", "QR code generation failed: " + e.getMessage());
        }

        return ApiResponse.success(result);
    }

    @PostMapping("/qrcode/parse")
    public ApiResponse<Map<String, Object>> parseQrCode(@RequestBody Map<String, String> body) {
        String base64Image = body.get("image");
        if (base64Image == null) {
            return ApiResponse.error(400, "Missing 'image' field");
        }
        try {
            SignalingData data = QrCodeSignaling.parseQrCode(base64Image);
            Map<String, Object> result = new HashMap<>();
            result.put("peerId", data.getPeerId());
            result.put("address", data.getAddress());
            result.put("fingerprint", data.getCertFingerprint());
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(400, "Failed to parse QR code: " + e.getMessage());
        }
    }

    // ==================== 连接管理 ====================

    /**
     * 连接对端（阻塞等待连接结果，超时 10 秒）
     */
    @PostMapping("/connect")
    public ApiResponse<Map<String, Object>> connect(@RequestBody Map<String, String> body) {
        String connectionString = body.get("connectionString");
        if (connectionString == null || connectionString.isEmpty()) {
            return ApiResponse.error(400, "Missing 'connectionString'");
        }

        try {
            SignalingData data = ConnectionStringHelper.parse(connectionString);
            // 使用连接串中携带的对方名称（握手后还会更新为对方设置的名称）
            String peerName = data.getName() != null ? data.getName() : "";
            CompletableFuture<Channel> future = connectionManager.connect(
                    data.getPeerId(), data.getAddress(), data.getCertFingerprint(), peerName);

            Channel channel = future.get(10, TimeUnit.SECONDS);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "connected");
            result.put("peerId", data.getPeerId());
            result.put("address", data.getAddress());
            result.put("channelActive", channel.isActive());
            return ApiResponse.success(result);
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("[P2P] Connect timeout");
            return ApiResponse.error(408, "连接超时，请检查对方地址和网络");
        } catch (Exception e) {
            log.error("[P2P] Connect failed", e);
            return ApiResponse.error(400, "连接失败: " + e.getMessage());
        }
    }

    @PostMapping("/disconnect/{peerId}")
    public ApiResponse<Map<String, Object>> disconnect(@PathVariable String peerId) {
        connectionManager.clearMessages(peerId);
        connectionManager.disconnect(peerId);
        return ApiResponse.success(Map.of("status", "disconnected", "peerId", peerId));
    }

    @GetMapping("/peers")
    public ApiResponse<List<Map<String, Object>>> getPeers() {
        List<Map<String, Object>> peers = connectionManager.getConnectionPool().getAllPeers().stream()
                .map(peer -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("peerId", peer.getPeerId());
                    map.put("address", peer.getAddress());
                    map.put("name", peer.getName());
                    map.put("displayName", getDisplayName(peer.getPeerId(), peer.getName()));
                    map.put("remark", getRemark(peer.getPeerId()));
                    map.put("connectedAt", peer.getConnectedAt());
                    map.put("lastHeartbeat", peer.getLastHeartbeat());
                    map.put("online", System.currentTimeMillis() - peer.getLastHeartbeat()
                            < connectionManager.getConfig().getHeartbeatTimeout() * 1000L);
                    return map;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(peers);
    }

    // ==================== 消息 ====================

    @PostMapping("/send/{peerId}")
    public ApiResponse<Map<String, Object>> sendMessage(@PathVariable String peerId,
                                                         @RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        if (content.isEmpty()) {
            return ApiResponse.error(400, "Missing 'content'");
        }

        // 消息带名称（统一用 AI 配置中的名称）
        String name = getMyDisplayName();
        connectionManager.setMyName(name);
        String payload = "{\"name\":\"" + (name != null && !name.isEmpty() ? name : "未知")
                + "\",\"content\":\"" + escapeJson(content) + "\"}";
        MessageFrame frame = new MessageFrame(MessageType.CHAT_MESSAGE, payload);
        try {
            connectionManager.send(peerId, frame).get(5, TimeUnit.SECONDS);
            // 存入数据库
            chatService.saveMessage(peerId, name, content, "sent");
            return ApiResponse.success(Map.of("status", "sent"));
        } catch (Exception e) {
            return ApiResponse.error(400, "Send failed: " + e.getMessage());
        }
    }

    /**
     * 拉取指定 Peer 发来的新消息（轮询用）
     */
    @GetMapping("/messages/{peerId}")
    public ApiResponse<List<Map<String, Object>>> pollMessages(@PathVariable String peerId) {
        List<MessageFrame> frames = connectionManager.pollMessages(peerId);
        List<Map<String, Object>> messages = frames.stream().map(frame -> {
            Map<String, Object> msg = new HashMap<>();
            try {
                // ★ 用 ObjectMapper 完整解析 JSON（替换原来的 extractJsonValue 字符串查找方式）
                String payload = frame.getPayloadAsString();
                JsonNode node = MAPPER.readTree(payload);
                String name = node.has("name") ? node.get("name").asText() : null;
                String content = node.has("content") ? node.get("content").asText() : null;
                msg.put("name", name != null ? getDisplayName(peerId, name) : "未知");
                msg.put("content", content != null ? content : payload);
                // 扩展字段
                String msgType = node.has("messageType") ? node.get("messageType").asText() : "chat";
                msg.put("messageType", msgType);
                if (node.has("agentConfigId") && !node.get("agentConfigId").isNull()) {
                    msg.put("agentConfigId", node.get("agentConfigId").asLong());
                }
                if (node.has("agentName") && !node.get("agentName").isNull()) {
                    msg.put("agentName", node.get("agentName").asText());
                }
                // 方向字段（让前端知道是"我"发出的还是对方发出的）
                if (node.has("direction") && !node.get("direction").isNull()) {
                    msg.put("direction", node.get("direction").asText());
                }
            } catch (Exception e) {
                log.warn("[P2P] Failed to parse payload for peer {}: {}", peerId, e.getMessage());
                msg.put("content", frame.getPayloadAsString());
                msg.put("messageType", "chat");
            }
            return msg;
        }).collect(Collectors.toList());
        return ApiResponse.success(messages);
    }

    /**
     * 获取历史消息
     */
    @GetMapping("/messages/{peerId}/history")
    public ApiResponse<List<Map<String, Object>>> getHistory(@PathVariable String peerId,
                                                              @RequestParam(defaultValue = "0") int offset,
                                                              @RequestParam(defaultValue = "50") int limit) {
        List<P2pChatMessage> list = chatService.getHistory(peerId, offset, limit);
        List<Map<String, Object>> result = list.stream().map(m -> {
            Map<String, Object> msg = new HashMap<>();
            msg.put("name", getDisplayName(peerId, m.getSenderName()));
            msg.put("content", m.getContent());
            msg.put("direction", m.getDirection());
            msg.put("messageType", m.getMessageType() != null ? m.getMessageType() : "chat");
            msg.put("agentConfigId", m.getAgentConfigId());
            msg.put("agentName", m.getAgentName());
            msg.put("time", m.getCreatedAt() != null ? m.getCreatedAt().toString() : "");
            return msg;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 删除与指定节点的聊天记录
     */
    @DeleteMapping("/messages/{peerId}")
    public ApiResponse<Map<String, Object>> deleteMessages(@PathVariable String peerId) {
        chatService.deleteByPeerId(peerId);
        return ApiResponse.success(Map.of("status", "deleted", "peerId", peerId));
    }

    /** 设置节点备注 */
    @PutMapping("/peer/{peerId}/remark")
    public ApiResponse<Map<String, Object>> setRemark(@PathVariable String peerId,
                                                       @RequestBody Map<String, String> body) {
        String remark = body.getOrDefault("remark", "");
        knownPeerMapper.updateRemark(peerId, remark);
        return ApiResponse.success(Map.of("status", "ok", "peerId", peerId, "remark", remark));
    }

    // ==================== Agent 授权与调用 ====================

    /**
     * 授权 Agent 给对方
     */
    @PostMapping("/agent-auth/grant/{peerId}")
    public ApiResponse<Map<String, Object>> grantAgentAuth(@PathVariable String peerId,
                                                            @RequestBody Map<String, Object> body,
                                                            @RequestAttribute("userId") Long userId) {
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.getOrDefault("agentConfigIds", List.of());
        List<Long> agentIds = rawIds.stream().map(Long::valueOf).toList();
        if (agentIds.isEmpty()) {
            return ApiResponse.error(400, "Missing 'agentConfigIds'");
        }
        String conflictUser = agentService.grantAuthorization(peerId, agentIds, userId);
        if (conflictUser != null) {
            return ApiResponse.error(409, "授权失败：用户「" + conflictUser + "」已对此节点授权过该 Agent");
        }
        return ApiResponse.success(Map.of("status", "ok", "peerId", peerId, "agentCount", agentIds.size()));
    }

    /**
     * 取消授权
     */
    @PostMapping("/agent-auth/cancel/{peerId}")
    public ApiResponse<Map<String, Object>> cancelAgentAuth(@PathVariable String peerId,
                                                             @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.getOrDefault("agentConfigIds", List.of());
        List<Long> agentIds = rawIds.stream().map(Long::valueOf).toList();
        if (agentIds.isEmpty()) {
            return ApiResponse.error(400, "Missing 'agentConfigIds'");
        }
        agentService.cancelAuthorization(peerId, agentIds);
        return ApiResponse.success(Map.of("status", "ok", "peerId", peerId));
    }

    /**
     * 获取我授权给某节点的 Agent 列表
     */
    @GetMapping("/agent-auth/sent/{peerId}")
    public ApiResponse<List<Map<String, Object>>> getMyAuthToPeer(@PathVariable String peerId) {
        var agents = agentService.getMyAuthToPeer(peerId);
        var result = agents.stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("avatar", a.getAvatar());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 获取某节点授权给我的 Agent 列表（我可以调用的）
     */
    @GetMapping("/agent-auth/received/{peerId}")
    public ApiResponse<List<Map<String, Object>>> getPeerAuthToMe(@PathVariable String peerId) {
        var agents = agentService.getPeerAuthToMe(peerId);
        var result = agents.stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("avatar", a.getAvatar());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 调用对方的 Agent
     */
    @PostMapping("/agent/invoke/{peerId}")
    public ApiResponse<Map<String, Object>> invokeAgent(@PathVariable String peerId,
                                                         @RequestBody Map<String, Object> body,
                                                         @RequestAttribute("userId") Long userId) {
        Long agentConfigId = body.get("agentConfigId") instanceof Number n ? n.longValue() : null;
        String message = (String) body.get("message");
        if (agentConfigId == null || message == null || message.isEmpty()) {
            return ApiResponse.error(400, "Missing 'agentConfigId' or 'message'");
        }
        String requestId = agentService.invokeAgent(peerId, agentConfigId, message, userId);
        return ApiResponse.success(Map.of("status", "sent", "requestId", requestId));
    }

    // ==================== 辅助方法 ====================

    private SignalingData buildMySignalingData() {
        String bestAddress = connectionManager.getAddressCollector().getBestAddressString()
                .orElse("[::1]:" + connectionManager.getConfig().getPort());
        return new SignalingData(
                connectionManager.getAddressCollector().getPeerId(),
                bestAddress,
                connectionManager.getTlsHelper().getCertFingerprint(),
                connectionManager.getMyName()
        );
    }

    /** 获取显示名称：备注 > AI配置名称 > 握手名称 > "未知" */
    private String getDisplayName(String peerId, String fallbackName) {
        try {
            P2pKnownPeer known = knownPeerMapper.findByPeerId(peerId);
            if (known != null) {
                if (known.getRemark() != null && !known.getRemark().isEmpty()) return known.getRemark();
                if (known.getName() != null && !known.getName().isEmpty()) return known.getName();
            }
        } catch (Exception ignored) {}
        return fallbackName != null && !fallbackName.isEmpty() ? fallbackName : "未知";
    }

    private String getRemark(String peerId) {
        try {
            P2pKnownPeer known = knownPeerMapper.findByPeerId(peerId);
            if (known != null && known.getRemark() != null) return known.getRemark();
        } catch (Exception ignored) {}
        return "";
    }

    /** 从 AI 性格配置读取本机名称 */
    private String getMyDisplayName() {
        try {
            String json = configService.getValue("character_profile");
            if (json != null && !json.isEmpty() && !"{}".equals(json.trim())) {
                var node = MAPPER.readTree(json);
                if (node.has("name") && !node.get("name").asText().isEmpty()) {
                    return node.get("name").asText();
                }
            }
        } catch (Exception e) {
            log.debug("[P2P] Failed to read character_profile name", e);
        }
        return "未知用户";
    }
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** 简单 JSON 值提取 */
    private static String extractJsonValue(String json, String key) {
        if (json == null) return null;
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) return null;
        start += searchKey.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end)
                .replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }
}

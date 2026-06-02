package com.example.agentdeepseek.p2p.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.mapper.P2pKnownPeerMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.agentdeepseek.model.entity.P2pChatMessage;
import com.example.agentdeepseek.model.entity.P2pKnownPeer;
import com.example.agentdeepseek.p2p.agent.P2pAgentService;
import com.example.agentdeepseek.p2p.connection.P2pConnectionManager;
import com.example.agentdeepseek.p2p.connection.PeerInfo;
import com.example.agentdeepseek.p2p.protocol.MessageFrame;
import com.example.agentdeepseek.p2p.protocol.MessageType;
import com.example.agentdeepseek.p2p.protocol.P2pConstants;
import com.example.agentdeepseek.p2p.service.P2pChatService;
import com.example.agentdeepseek.p2p.service.FileTransferManager;
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

import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Autowired
    private FileTransferManager transferManager;

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
        // 断开连接但保留消息队列 + 已知节点记录，方便离线查看聊天记录
        connectionManager.disconnect(peerId);
        return ApiResponse.success(Map.of("status", "disconnected", "peerId", peerId));
    }

    /**
     * 彻底删除节点（清除聊天记录 + 节点信息）
     */
    @DeleteMapping("/peer/{peerId}")
    public ApiResponse<Map<String, Object>> deletePeer(@PathVariable String peerId) {
        // 1. 断开连接（如果在线）
        connectionManager.disconnect(peerId);
        // 2. 清除消息队列
        connectionManager.clearMessages(peerId);
        // 3. 删除聊天记录
        chatService.deleteByPeerId(peerId);
        // 4. 删除已知节点记录
        knownPeerMapper.deleteByPeerId(peerId);
        // 5. 清理该节点的接收文件
        try {
            Path peerDir = FileTransferManager.resolveReceivedPath(peerId, "");
            if (Files.exists(peerDir)) {
                Files.walk(peerDir)
                     .sorted(java.util.Comparator.reverseOrder())
                     .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                log.info("[P2P] Deleted received files for peer: {}", peerId);
            }
        } catch (Exception e) {
            log.warn("[P2P] Failed to clean received files for peer {}: {}", peerId, e.getMessage());
        }
        log.info("[P2P] Peer deleted completely: {}", peerId);
        return ApiResponse.success(Map.of("status", "deleted", "peerId", peerId));
    }

    /**
     * 重连离线节点（使用数据库保存的连接信息）
     */
    @PostMapping("/reconnect/{peerId}")
    public ApiResponse<Map<String, Object>> reconnect(@PathVariable String peerId) {
        P2pKnownPeer known = knownPeerMapper.findByPeerId(peerId);
        if (known == null) {
            return ApiResponse.error(404, "节点信息不存在，请重新添加");
        }
        if (known.getAddress() == null || known.getAddress().isEmpty()) {
            return ApiResponse.error(400, "节点地址信息缺失，无法重连");
        }
        if (known.getCertFingerprint() == null || known.getCertFingerprint().isEmpty()) {
            return ApiResponse.error(400, "节点证书信息缺失，无法重连");
        }

        try {
            String peerName = known.getName() != null ? known.getName() : "";
            CompletableFuture<Channel> future = connectionManager.connect(
                    known.getPeerId(), known.getAddress(), known.getCertFingerprint(), peerName);
            Channel channel = future.get(10, TimeUnit.SECONDS);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "connected");
            result.put("peerId", known.getPeerId());
            result.put("address", known.getAddress());
            result.put("channelActive", channel.isActive());
            return ApiResponse.success(result);
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("[P2P] Reconnect timeout for peer {}", peerId);
            return ApiResponse.error(408, "重连超时：对方可能已离线或地址已变更");
        } catch (Exception e) {
            log.error("[P2P] Reconnect failed for peer {}", peerId, e);
            String msg = e.getMessage();
            if (msg != null && msg.contains("certificate")) {
                return ApiResponse.error(400, "重连失败：对方证书已变更，请重新扫码添加");
            }
            if (msg != null && (msg.contains("Connection refused") || msg.contains("connect"))) {
                return ApiResponse.error(400, "重连失败：无法连接到对方，请检查对方是否在线或地址是否变更");
            }
            return ApiResponse.error(400, "重连失败：" + (msg != null ? msg : "未知错误"));
        }
    }

    @GetMapping("/peers")
    public ApiResponse<List<Map<String, Object>>> getPeers() {
        // 1. 收集在线节点（来自 ConnectionPool）
        Set<String> onlinePeerIds = new HashSet<>();
        List<Map<String, Object>> peers = new ArrayList<>();
        for (PeerInfo peer : connectionManager.getConnectionPool().getAllPeers()) {
            onlinePeerIds.add(peer.getPeerId());
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
            peers.add(map);
        }

        // 2. 合并数据库中的已知节点（离线节点）
        try {
            List<P2pKnownPeer> knownPeers = knownPeerMapper.findAll();
            for (P2pKnownPeer kp : knownPeers) {
                if (!onlinePeerIds.contains(kp.getPeerId())) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("peerId", kp.getPeerId());
                    map.put("address", kp.getAddress() != null ? kp.getAddress() : "");
                    map.put("name", kp.getName() != null ? kp.getName() : "");
                    // 离线节点直接用 DB 中的 remark/name 计算，避免 N+1 查询
                    String dn = (kp.getRemark() != null && !kp.getRemark().isEmpty()) ? kp.getRemark()
                            : (kp.getName() != null && !kp.getName().isEmpty()) ? kp.getName()
                            : "未知";
                    map.put("displayName", dn);
                    map.put("remark", kp.getRemark() != null ? kp.getRemark() : "");
                    map.put("connectedAt", kp.getLastConnectedAt() != null
                            ? kp.getLastConnectedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : 0L);
                    map.put("lastHeartbeat", 0L);
                    map.put("online", false);
                    peers.add(map);
                }
            }
        } catch (Exception e) {
            log.warn("[P2P] Failed to load known peers from DB", e);
        }

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

    // ==================== 文件传输 ====================

    /**
     * 发送文件（图片或普通文件）
     */
    @PostMapping("/send-file/{peerId}")
    public ApiResponse<Map<String, Object>> sendFile(@PathVariable String peerId,
                                                      @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ApiResponse.error(400, "文件为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isEmpty()) {
            originalName = "unknown";
        }
        long fileSize = file.getSize();

        // 判断文件类型
        String mimeType = file.getContentType();
        if (mimeType == null) mimeType = "application/octet-stream";
        boolean isImage = mimeType.startsWith("image/");
        String category;

        if (isImage && fileSize <= P2pConstants.MAX_IMAGE_SIZE) {
            category = "image";
        } else if (isImage && fileSize > P2pConstants.MAX_IMAGE_SIZE) {
            category = "file"; // 超大图片降级为文件传输
        } else {
            category = "file";
        }

        // 大小限制
        if (fileSize > P2pConstants.MAX_FILE_SIZE) {
            return ApiResponse.error(400, "文件过大，最大支持 2GB");
        }

        String transferId = UUID.randomUUID().toString();
        int chunkSize = P2pConstants.FILE_CHUNK_SIZE;
        int totalChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);

        // 发送 META 帧
        try {
            String metaJson = String.format(
                    "{\"subType\":\"META\",\"transferId\":\"%s\",\"fileName\":\"%s\",\"fileSize\":%d," +
                            "\"mimeType\":\"%s\",\"category\":\"%s\",\"totalChunks\":%d,\"chunkSize\":%d}",
                    transferId, escapeJson(originalName), fileSize, mimeType, category, totalChunks, chunkSize);
            MessageFrame metaFrame = new MessageFrame(MessageType.FILE_TRANSFER, metaJson);
            connectionManager.send(peerId, metaFrame).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return ApiResponse.error(400, "发送文件元信息失败: " + e.getMessage());
        }

        // 逐块发送 CHUNK（流式读取，避免大文件 OOM）
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[chunkSize];
            for (int i = 0; i < totalChunks; i++) {
                int len = Math.min(chunkSize, (int) (fileSize - (long) i * chunkSize));
                int totalRead = 0;
                while (totalRead < len) {
                    int n = in.read(buf, totalRead, len - totalRead);
                    if (n < 0) {
                        // 未预期的 EOF：文件可能被截断
                        throw new IOException("Unexpected EOF at chunk " + i + ", expected " + len + " bytes, got " + totalRead);
                    }
                    totalRead += n;
                }
                byte[] chunkData;
                if (totalRead == chunkSize) {
                    chunkData = buf;
                    buf = new byte[chunkSize]; // 避免覆盖已发送的数据
                } else {
                    chunkData = new byte[totalRead];
                    System.arraycopy(buf, 0, chunkData, 0, totalRead);
                }
                byte[] chunkPayload = FileTransferManager.encodeChunkPayload(transferId, i, chunkData);
                MessageFrame chunkFrame = new MessageFrame(MessageType.FILE_TRANSFER, chunkPayload);
                // 每块最多等待 60 秒
                connectionManager.send(peerId, chunkFrame).get(60, TimeUnit.SECONDS);
            }

            // 发送 COMPLETE
            String completeJson = String.format(
                    "{\"subType\":\"COMPLETE\",\"transferId\":\"%s\"}", transferId);
            MessageFrame completeFrame = new MessageFrame(MessageType.FILE_TRANSFER, completeJson);
            connectionManager.send(peerId, completeFrame).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 发送失败通知
            try {
                String errorJson = String.format(
                        "{\"subType\":\"ERROR\",\"transferId\":\"%s\",\"message\":\"发送方传输失败: %s\"}",
                        transferId, escapeJson(e.getMessage() != null ? e.getMessage() : "未知错误"));
                connectionManager.send(peerId,
                        new MessageFrame(MessageType.FILE_TRANSFER, errorJson));
            } catch (Exception ignored) {}
            return ApiResponse.error(400, "文件传输失败: " + e.getMessage());
        }

        // 存入本地聊天记录（发送方）
        String content = "[" + (isImage ? "图片" : "文件") + "] " + originalName;
        String name = getMyDisplayName();
        chatService.saveFileMessage(peerId, name, content, "sent",
                "file_transfer", originalName, fileSize, mimeType,
                category, transferId, "completed", null); // 发送方本地无文件路径

        Map<String, Object> result = new HashMap<>();
        result.put("status", "sent");
        result.put("transferId", transferId);
        result.put("category", category);
        return ApiResponse.success(result);
    }

    /**
     * 获取接收的文件（图片预览用）
     */
    @GetMapping("/file/{peerId}/{transferId}")
    public ResponseEntity<byte[]> getFile(@PathVariable String peerId,
                                           @PathVariable String transferId) {
        String filePath = null;
        String mimeType = "application/octet-stream";

        // 优先从内存中的 TransferSession 查找
        FileTransferManager.TransferSession session = transferManager.getSession(transferId);
        if (session != null && session.finalFilePath != null) {
            filePath = session.finalFilePath;
            if (session.mimeType != null) mimeType = session.mimeType;
        }

        // 内存中未找到，回退到数据库查找（历史消息）
        if (filePath == null) {
            P2pChatMessage dbMsg = chatService.findByTransferId(transferId);
            if (dbMsg != null && dbMsg.getLocalPath() != null) {
                filePath = dbMsg.getLocalPath();
                if (dbMsg.getMimeType() != null) mimeType = dbMsg.getMimeType();
            }
        }

        if (filePath == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            File file = new File(filePath);
            if (!file.exists()) return ResponseEntity.notFound().build();
            byte[] data = Files.readAllBytes(file.toPath());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, mimeType)
                    .body(data);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取缩略图
     */
    @GetMapping("/file/{peerId}/{transferId}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(@PathVariable String peerId,
                                                @PathVariable String transferId) {
        String thumbPath = null;

        // 优先从内存中查找
        FileTransferManager.TransferSession session = transferManager.getSession(transferId);
        if (session != null && session.thumbnailPath != null) {
            thumbPath = session.thumbnailPath;
        }

        // 回退到数据库：缩略图路径 = local_path + ".thumb.jpg"（由 generateThumbnail 的命名规则决定）
        if (thumbPath == null) {
            P2pChatMessage dbMsg = chatService.findByTransferId(transferId);
            if (dbMsg != null && dbMsg.getLocalPath() != null) {
                // 缩略图与文件同目录，命名规则：{transferId}_{fileName}.thumb.jpg
                // 由于没有单独存缩略图路径到DB，这里通过 local_path 推断
                String localPath = dbMsg.getLocalPath();
                int dotIdx = localPath.lastIndexOf('.');
                if (dotIdx > 0) {
                    thumbPath = localPath.substring(0, dotIdx) + ".thumb.jpg";
                } else {
                    thumbPath = localPath + ".thumb.jpg";
                }
            }
        }

        if (thumbPath == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            File file = new File(thumbPath);
            if (!file.exists()) return ResponseEntity.notFound().build();
            byte[] data = Files.readAllBytes(file.toPath());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                    .body(data);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 打开文件所在目录
     */
    @PostMapping("/file/{peerId}/{transferId}/open-dir")
    public ApiResponse<Map<String, Object>> openFileDir(@PathVariable String peerId,
                                                         @PathVariable String transferId) {
        String filePath = null;

        // 优先从内存中的 TransferSession 查找
        FileTransferManager.TransferSession session = transferManager.getSession(transferId);
        if (session != null && session.finalFilePath != null) {
            filePath = session.finalFilePath;
        }

        // 回退到数据库查找（历史消息）
        if (filePath == null) {
            P2pChatMessage dbMsg = chatService.findByTransferId(transferId);
            if (dbMsg != null && dbMsg.getLocalPath() != null) {
                filePath = dbMsg.getLocalPath();
            }
        }

        if (filePath == null) {
            return ApiResponse.error(404, "文件不存在");
        }

        try {
            File file = new File(filePath);
            if (!file.exists()) {
                // 尝试找同目录下任意文件
                File dir = file.getParentFile();
                if (dir != null && dir.exists()) {
                    openInFileManager(dir.getAbsolutePath());
                    return ApiResponse.success(Map.of("status", "opened", "path", dir.getAbsolutePath()));
                }
                return ApiResponse.error(404, "目录不存在");
            }
            // 打开文件所在目录并选中文件
            openInFileManager(file.getAbsolutePath());
            return ApiResponse.success(Map.of("status", "opened", "path", file.getAbsolutePath()));
        } catch (Exception e) {
            String errMsg = e.getMessage();
            if (errMsg == null || errMsg.isEmpty()) errMsg = e.getClass().getSimpleName();
            return ApiResponse.error(500, "打开目录失败: " + errMsg);
        }
    }

    /**
     * 查询传输进度
     */
    @GetMapping("/file/{peerId}/{transferId}/progress")
    public ApiResponse<Map<String, Object>> getTransferProgress(@PathVariable String peerId,
                                                                 @PathVariable String transferId) {
        Map<String, Object> progress = transferManager.getProgress(transferId);
        return ApiResponse.success(progress);
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
                // 文件传输字段
                if ("file_transfer".equals(msgType)) {
                    if (node.has("fileName")) msg.put("fileName", node.get("fileName").asText());
                    if (node.has("fileSize")) msg.put("fileSize", node.get("fileSize").asLong());
                    if (node.has("mimeType")) msg.put("mimeType", node.get("mimeType").asText());
                    if (node.has("fileCategory")) msg.put("fileCategory", node.get("fileCategory").asText());
                    if (node.has("transferId")) msg.put("transferId", node.get("transferId").asText());
                    if (node.has("fileStatus")) msg.put("fileStatus", node.get("fileStatus").asText());
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
            // 文件传输字段
            if ("file_transfer".equals(m.getMessageType())) {
                msg.put("fileName", m.getFileName());
                msg.put("fileSize", m.getFileSize());
                msg.put("mimeType", m.getMimeType());
                msg.put("fileCategory", m.getFileCategory());
                msg.put("transferId", m.getTransferId());
                msg.put("fileStatus", m.getFileStatus());
            }
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

    /**
     * 用系统文件管理器打开指定路径（纯 ProcessBuilder，无 Desktop 依赖，避免 HeadlessException）
     */
    private static void openInFileManager(String path) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            new ProcessBuilder("explorer", "/select,", path).start();
        } else if (os.contains("mac")) {
            new ProcessBuilder("open", "-R", path).start();
        } else {
            new ProcessBuilder("xdg-open", new File(path).getParent()).start();
        }
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
